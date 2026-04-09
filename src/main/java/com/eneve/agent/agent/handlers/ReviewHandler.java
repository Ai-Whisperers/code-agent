package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.*;
import com.eneve.agent.agent.model.OpenFinding;
import com.eneve.agent.agent.service.CodeGraphQueryService;
import com.eneve.agent.agent.store.CodeGraphStore;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.diff.DiffParser;
import com.eneve.agent.diff.ParsedDiffFile;
import com.eneve.agent.diff.ReviewPromptResult;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.*;
import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ReviewHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ReviewHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject com.eneve.agent.agent.lobster.LobsterClient lobsterClient;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject ReviewCommentProcessor reviewProcessor;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject CommentStore commentStore;
    @Inject FindingResolver findingResolver;
    @Inject CodeGraphStore codeGraphStore;
    @Inject CodeGraphIndexer codeGraphIndexer;
    @Inject CodeGraphQueryService codeGraphQueryService;
    @Inject EmbeddingIndexer embeddingIndexer;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject PrSummaryGenerator prSummaryGenerator;
    @Inject MermaidPngRenderer mermaidPngRenderer;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject JiraService jiraService;
    @Inject SettingsService settings;
    @Inject com.eneve.agent.notifications.ReviewEmailNotifier emailNotifier;

    @Override
    public JobType jobType() {
        return JobType.REVIEW;
    }

    @Override
    public void handle(JobRecord job) {
        long jobTimeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        boolean prSummaryEnabled = Boolean.parseBoolean(settings.get("review.pr-summary.enabled", "true"));
        boolean sequenceDiagramsEnabled = Boolean.parseBoolean(settings.get("review.sequence-diagrams.enabled", "true"));
        ReviewPrRequest request = (ReviewPrRequest) job.getPayload();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failReview(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        final GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            Map<String, String> prInfo;
            try {
                prInfo = platformService.getPullRequestInfo(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                lifecycle.failReview(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String targetBranch = request.targetBranch() != null && !request.targetBranch().isBlank()
                    ? request.targetBranch() : prInfo.get("destinationBranch");
            String prTitle = prInfo.get("title");
            job.setPrId(request.prId());
            job.setWorkspace(coords.organization());
            job.setRepoSlug(coords.repository());

            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());
            LOG.infof("Review: cloning %s/%s branch %s for PR #%s",
                    coords.organization(), coords.repository(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failReview(job, "Clone failed: " + e.getMessage());
                return;
            }

            List<AgentComment> existingAgentComments;
            try {
                existingAgentComments = platformService.getAgentPrComments(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
                LOG.infof("Review: found %d existing agent comments on PR #%s",
                        existingAgentComments.size(), request.prId());
            } catch (Exception e) {
                LOG.warnf("Failed to fetch existing agent comments (non-fatal): %s", e.getMessage());
                existingAgentComments = Collections.emptyList();
            }

            String lastReviewedSha = ReviewCommentProcessor.extractLastReviewedSha(existingAgentComments);
            if (request.headCommitSha() != null && shaAlreadyReviewed(request.headCommitSha(), lastReviewedSha)) {
                LOG.infof("Review: PR #%s already reviewed at commit %s — skipping",
                        request.prId(), lastReviewedSha);
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary("Already reviewed at " + lastReviewedSha + " — no new commits.");
                jobStore.update(job);
                return;
            }

            String diff;
            try {
                workspace.fetchBranch(targetBranch, jobTimeoutMinutes);

                if (lastReviewedSha != null && workspace.objectExists(lastReviewedSha)) {
                    LOG.infof("Review: incremental diff from previously reviewed commit %s", lastReviewedSha);
                    diff = workspace.getDiffFromCommit(lastReviewedSha);
                    if (diff == null || diff.isBlank()) {
                        LOG.infof("Review: PR #%s has no new changes since %s — skipping",
                                request.prId(), lastReviewedSha);
                        job.setStatus(JobStatus.SUCCESS);
                        job.setSummary("No new changes since last review at " + lastReviewedSha + ".");
                        jobStore.update(job);
                        return;
                    }
                } else {
                    // Prefer the SCM platform API diff — it reflects the exact PR state
                    // (handles rebases, force-pushes, and platform-specific merge strategies).
                    // Fall back to local git diff if the API is unavailable.
                    String apiDiff = null;
                    try {
                        apiDiff = platformService.getPullRequestDiff(
                                coords.organization(), coords.project(), coords.repository(), request.prId());
                    } catch (Exception e) {
                        LOG.warnf("Review: SCM API diff unavailable for PR #%s, will fall back to git diff: %s",
                                request.prId(), e.getMessage());
                    }

                    if (apiDiff != null && !apiDiff.isBlank()) {
                        LOG.infof("Review: using SCM API diff for PR #%s (%d bytes)",
                                request.prId(), apiDiff.length());
                        diff = apiDiff;
                    } else {
                        LOG.infof("Review: falling back to git diff for PR #%s", request.prId());
                        diff = workspace.getDiff(targetBranch);
                    }
                }
            } catch (Exception e) {
                lifecycle.failReview(job, "Failed to compute diff: " + e.getMessage());
                return;
            }

            if (diff == null || diff.isBlank()) {
                lifecycle.failReview(job,
                        "PR has no diff against " + targetBranch + ". Nothing to review.");
                return;
            }

            String headSha;
            try {
                headSha = workspace.getHeadSha();
            } catch (Exception e) {
                LOG.warnf("Failed to get HEAD SHA (non-fatal): %s", e.getMessage());
                headSha = null;
            }

            final List<ParsedDiffFile> parsedDiff = DiffParser.parse(diff);
            final List<String> changedFiles = parsedDiff.stream().map(ParsedDiffFile::path).toList();

            final List<AgentComment> existingComments = existingAgentComments;
            final String targetBranchFinal = targetBranch;
            final String prTitleFinal = prTitle;
            final String jobIdFinal = job.getJobId();

            workspace.putMetadata("workspace", coords.organization());
            workspace.putMetadata("repoSlug", coords.repository());

            // Future 1: Finding resolution (only when a previous reviewed SHA exists)
            CompletableFuture<Integer> findingsFuture;
            if (lastReviewedSha != null) {
                findingsFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        List<OpenFinding> openFindings = commentStore.findOpenInlineComments(
                                request.prId(), coords.organization(), coords.repository());
                        if (openFindings.isEmpty()) return 0;

                        List<Long> resolvedIds = findingResolver.resolveAddressedFindings(
                                openFindings, parsedDiff, workspace, jobIdFinal);
                        int count = 0;
                        String botActor = settings.get("review.bot-display-name", "Review Agent");
                        for (long resolvedId : resolvedIds) {
                            try {
                                platformService.replyToComment(
                                        coords.organization(), coords.project(), coords.repository(),
                                        request.prId(), resolvedId,
                                        "This issue appears to have been addressed in the latest commits.");
                                platformService.resolveComment(
                                        coords.organization(), coords.project(), coords.repository(),
                                        request.prId(), resolvedId);
                            } catch (Exception e) {
                                LOG.warnf("Failed to resolve comment %d on platform (non-fatal): %s",
                                        resolvedId, e.getMessage());
                            }
                            commentStore.markResolved(resolvedId, botActor);
                            count++;
                        }
                        if (count > 0) {
                            LOG.infof("Auto-resolved %d previously flagged finding(s) on PR #%s",
                                    count, request.prId());
                        }
                        return count;
                    } catch (Exception e) {
                        LOG.warnf("Finding resolution pass failed (non-fatal): %s", e.getMessage());
                        return 0;
                    }
                }, AgentPools.PARALLEL);
            } else {
                findingsFuture = CompletableFuture.completedFuture(0);
            }

            // Future 2: Code graph indexing + impact section + PR summary
            CompletableFuture<String> codeGraphFuture = CompletableFuture.supplyAsync(() -> {
                String impact = "";
                try {
                    if (codeGraphStore.hasGraph(coords.organization(), coords.repository())) {
                        codeGraphIndexer.indexIncremental(workspace,
                                coords.organization(), coords.repository(), changedFiles);
                        if (repoSettingsStore.isVectorEnabled(coords.organization(), coords.repository())) {
                            embeddingIndexer.indexIncremental(workspace,
                                    coords.organization(), coords.repository(), changedFiles);
                        }
                    } else {
                        codeGraphIndexer.indexFull(workspace, coords.organization(), coords.repository());
                        if (repoSettingsStore.isVectorEnabled(coords.organization(), coords.repository())) {
                            embeddingIndexer.indexFull(workspace,
                                    coords.organization(), coords.repository());
                        }
                    }
                    impact = codeGraphQueryService.buildImpactSection(
                            coords.organization(), coords.repository(), changedFiles);
                } catch (Exception e) {
                    LOG.warnf("Code graph indexing/query failed (non-fatal): %s", e.getMessage());
                }

                if (prSummaryEnabled) {
                    try {
                        String diagramContext = null;
                        if (sequenceDiagramsEnabled && !impact.isBlank()) {
                            diagramContext = codeGraphQueryService.buildDiagramContext(
                                    coords.organization(), coords.repository(), changedFiles);
                        }
                        PrSummaryGenerator.SummaryResult summaryResult = prSummaryGenerator.generate(
                                prTitleFinal, targetBranchFinal, parsedDiff, jobIdFinal,
                                diagramContext, request.prId());
                        if (summaryResult != null) {
                            postOrUpdatePrSummary(coords, request.prId(), existingComments,
                                    summaryResult, jobIdFinal, platformService);
                        }
                    } catch (Exception e) {
                        LOG.warnf("PR summary generation failed (non-fatal): %s", e.getMessage());
                    }
                }
                return impact;
            }, AgentPools.PARALLEL);

            int resolvedCount = findingsFuture
                    .orTimeout(5, TimeUnit.MINUTES)
                    .exceptionally(e -> {
                        LOG.warnf("Finding resolution phase timed out or failed: %s", e.getMessage());
                        return 0;
                    })
                    .join();

            String impactSection = codeGraphFuture
                    .orTimeout(5, TimeUnit.MINUTES)
                    .exceptionally(e -> {
                        LOG.warnf("Code graph + PR summary phase timed out or failed: %s", e.getMessage());
                        return "";
                    })
                    .join();

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                lifecycle.safeJira(() -> jiraService.commentStarted(request.jiraKey(),
                        "Code review", "PR review for #" + request.prId()));
            }

            ReviewPromptResult promptResult = promptBuilder.buildReviewPrompt(
                    request, prTitle, targetBranch, diff, existingAgentComments, workspace,
                    coords.organization(), coords.repository(), impactSection);

            // AIW: Phase 1 Lobster integration — optional deterministic
            // workflow path that skips the expensive multi-iteration
            // ClaudeToolUseLoop. Feature-flagged for instant rollback.
            //
            // When `review.deep-loop.enabled=false`, we skip the tool-use
            // loop entirely. The PR summary comment still posts (it's
            // generated by the parallel futures above via
            // PrSummaryGenerator), so humans still see a review — just
            // without the agent's deep inline line-level comments. Saves
            // ~5-10 LLM calls per review at the cost of losing inline
            // comment granularity.
            //
            // When `lobster.enabled=true` AND `review.deep-loop.enabled=false`,
            // we also invoke the Lobster review-pr workflow to prove the
            // integration works and log its structured output. The result
            // isn't currently used for anything — this is instrumentation
            // for when we later wire it into PrSummaryGenerator or a
            // fix.issue workflow.
            boolean deepLoopEnabled = Boolean.parseBoolean(
                    settings.get("review.deep-loop.enabled", "true"));

            String reviewOutput;
            if (deepLoopEnabled) {
                try {
                    reviewOutput = toolUseLoop.run(promptResult.prompt(), workspace,
                            ToolDefinitions.readOnly(),
                            "Please review the pull request diff provided in the system prompt. "
                                    + "Use the read_file and list_files tools to examine surrounding context "
                                    + "when needed. Provide your complete review as the specified JSON structure.",
                            job.getJobId(), job.getJobType().name(),
                            job.getParentJobId(), job.getDepth());
                } catch (Exception e) {
                    lifecycle.failReview(job, "Agent review loop error: " + e.getMessage());
                    return;
                }
            } else {
                LOG.infof("Review: deep-loop disabled for job %s — using PR summary only",
                          job.getJobId());
                // Empty review output means no inline comments will be posted.
                // ReviewCommentProcessor handles this case gracefully.
                reviewOutput = "{\"inlineComments\":[],\"summary\":\"\"}";

                // If Lobster is enabled, also invoke the deterministic workflow
                // so we can measure end-to-end parity with the Java clone/diff
                // path. Result is logged but not used yet.
                if (lobsterClient.isEnabled()) {
                    try {
                        String gitToken = settings.getSecret("github.token");
                        com.eneve.agent.agent.lobster.LobsterResult lobsterResult =
                                lobsterClient.runReviewPr(
                                        request.repoUrl(),
                                        sourceBranch,
                                        targetBranch,
                                        gitToken != null ? gitToken : "",
                                        request.prId());
                        if (lobsterResult.isSuccess()) {
                            LOG.infof("Lobster review.pr workflow returned: language=%s archetype=%s lintRan=%s durationMs=%d",
                                    lobsterResult.output().path("language").asText("?"),
                                    lobsterResult.output().path("archetype").asText("?"),
                                    lobsterResult.output().path("lintResult").path("ran").asBoolean(false),
                                    lobsterResult.durationMs());
                        } else if (!lobsterResult.isDisabled()) {
                            LOG.warnf("Lobster review.pr workflow failed: %s", lobsterResult.errorMessage());
                        }
                    } catch (Exception e) {
                        // Lobster is instrumentation-only right now; never fail the review because of it.
                        LOG.warnf("Lobster review.pr workflow threw: %s", e.getMessage());
                    }
                }
            }

            String reviewSummary = reviewProcessor.postReviewComments(reviewOutput, coords, request.prId(),
                    existingAgentComments, headSha, job.getJobId(), promptResult.commentableLines(),
                    resolvedCount);

            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(reviewSummary);
            job.setPrUrl(prInfo.getOrDefault("prUrl", ""));
            jobStore.archive(job);
            lifecycle.auditLog("JOBS", "REVIEW_COMPLETED", "job", job.getJobId(),
                    java.util.Map.of("prId", request.prId()));

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                lifecycle.safeJira(() -> jiraService.commentSuccess(request.jiraKey(),
                        "Code review", "PR #" + request.prId(), "Code review completed."));
            }

            RunResult result = lifecycle.buildReviewResult(job, true);
            lifecycle.notifyResult(result, request.n8nWebhookUrl());
            emailNotifier.sendReviewDigest(request, job, coords);

            LOG.infof("Review job %s completed for PR #%s", job.getJobId(), request.prId());

        } catch (Exception e) {
            lifecycle.failReview(job, "Unexpected error: " + e.getMessage());
        }
    }

    private void postOrUpdatePrSummary(RepoCoordinates coords, String prId,
                                       List<AgentComment> existingComments,
                                       PrSummaryGenerator.SummaryResult summaryResult,
                                       String jobId, GitPlatformService platformService) {
        String org = coords.organization();
        String project = coords.project();
        String repo = coords.repository();

        String body = resolveDiagramPlaceholders(summaryResult, org, repo, platformService);

        Optional<Long> existingId = commentStore.findPrSummaryCommentId(prId, org, repo);
        if (existingId.isPresent()) {
            long commentId = existingId.get();
            try {
                platformService.updatePrComment(org, project, repo, prId, commentId, body);
                commentStore.savePrSummaryComment(commentId, prId, org, project, repo, jobId);
                LOG.infof("Updated PR summary comment %d on PR #%s", commentId, prId);
                return;
            } catch (Exception e) {
                LOG.warnf("Failed to update PR summary comment %d (may have been deleted): %s",
                        commentId, e.getMessage());
            }
        }

        lifecycle.safeComment(() -> {
            long newId = platformService.addPrComment(org, project, repo, prId, body);
            if (newId > 0) {
                commentStore.savePrSummaryComment(newId, prId, org, project, repo, jobId);
                LOG.infof("Created PR summary comment %d on PR #%s", newId, prId);
            }
        });
    }

    private String resolveDiagramPlaceholders(PrSummaryGenerator.SummaryResult summaryResult,
                                              String org, String repo, GitPlatformService platformService) {
        if (summaryResult.pendingDiagrams().isEmpty()) {
            return summaryResult.body();
        }

        String body = summaryResult.body();
        for (PrSummaryGenerator.PendingDiagram diagram : summaryResult.pendingDiagrams()) {
            String resolvedUrl = null;
            try {
                byte[] png = mermaidPngRenderer.renderToPng(diagram.mermaidSource());
                resolvedUrl = platformService.uploadDownload(org, repo, diagram.filename(), png, "image/png");
            } catch (Exception e) {
                LOG.warnf("Failed to render/upload diagram '%s', falling back to mermaid.ink: %s",
                        diagram.filename(), e.getMessage());
            }

            if (resolvedUrl == null) {
                String encoded = Base64.getEncoder()
                        .encodeToString(diagram.mermaidSource().getBytes(StandardCharsets.UTF_8));
                resolvedUrl = "https://mermaid.ink/img/base64:" + encoded;
            }

            body = body.replace(diagram.placeholder(), resolvedUrl);
        }
        return body;
    }

    private static boolean shaAlreadyReviewed(String webhookSha, String storedSha) {
        if (webhookSha == null || storedSha == null) return false;
        if (webhookSha.equals(storedSha)) return true;
        if (webhookSha.length() < storedSha.length()) return storedSha.startsWith(webhookSha);
        return webhookSha.startsWith(storedSha);
    }
}
