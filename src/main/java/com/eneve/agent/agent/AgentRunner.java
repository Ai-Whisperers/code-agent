package com.eneve.agent.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.eneve.agent.diff.DiffParser;
import com.eneve.agent.diff.ParsedDiffFile;
import com.eneve.agent.diff.ReviewPromptResult;

import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scm.ThreadComment;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.linter.LinterFinding;
import com.eneve.agent.linter.LinterResult;
import com.eneve.agent.linter.LinterService;
import com.eneve.agent.agent.CoverageReporter.CoverageSnapshot;
import com.eneve.agent.model.FixPrRequest;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.HookJobRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ReplyCommentRequest;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.model.RunResult;
import com.eneve.agent.notifications.N8nWebhookNotifier;
import com.eneve.agent.notifications.TeamsNotifier;
import com.eneve.agent.tools.GuardrailConfig;
import com.eneve.agent.workspace.WorkspaceContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Orchestrates the full agent job lifecycle:
 * clone -> load rules -> agentic loop -> mvn test -> commit/push -> create PR -> update JIRA -> notify
 */
@ApplicationScoped
public class AgentRunner {

    private static final Logger LOG = Logger.getLogger(AgentRunner.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject ReviewCommentProcessor reviewProcessor;
    @Inject BuildValidator buildValidator;
    @Inject GuardrailConfig guardrails;
    @Inject JiraService jiraService;
    @Inject GitPlatformService platformService;
    @Inject TeamsNotifier teamsNotifier;
    @Inject N8nWebhookNotifier n8nNotifier;
    @Inject CommentStore commentStore;
    @Inject LinterService linterService;
    @Inject LearningExtractor learningExtractor;
    @Inject JobStore jobStore;
    @Inject FindingResolver findingResolver;
    @Inject CodeGraphStore codeGraphStore;
    @Inject CodeGraphIndexer codeGraphIndexer;
    @Inject EmbeddingIndexer embeddingIndexer;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject CodeGraphQueryService codeGraphQueryService;
    @Inject PrSummaryGenerator prSummaryGenerator;
    @Inject CoverageReporter coverageReporter;

    @ConfigProperty(name = "git.username")
    String gitUser;

    @ConfigProperty(name = "git.password")
    String gitPassword;

    @ConfigProperty(name = "git.author.name", defaultValue = "code-agent")
    String gitAuthorName;

    @ConfigProperty(name = "git.author.email", defaultValue = "")
    String gitAuthorEmail;

    @ConfigProperty(name = "n8n.webhook.url", defaultValue = "")
    String defaultN8nWebhookUrl;

    @ConfigProperty(name = "run-fix.job-timeout-minutes", defaultValue = "30")
    long jobTimeoutMinutes;

    @ConfigProperty(name = "review.pr-summary.enabled", defaultValue = "true")
    boolean prSummaryEnabled;

    @ConfigProperty(name = "review.sequence-diagrams.enabled", defaultValue = "true")
    boolean sequenceDiagramsEnabled;

    // ─── Run-Fix (implement a JIRA ticket) ──────────────────────────────

    public void execute(JobRecord job) {
        RunFixRequest request = job.getRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            fail(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            LOG.infof("Cloning %s/%s (branch: %s)", coords.organization(), coords.repository(), request.branchName());
            try {
                workspace.cloneRepo(authUrl, request.branchName(), jobTimeoutMinutes);
            } catch (Exception e) {
                LOG.infof("Branch '%s' not found, trying clone from '%s' and create branch",
                        request.branchName(), request.targetBranchOrDefault());
                try {
                    workspace.cloneAndCreateBranch(authUrl, request.targetBranchOrDefault(),
                            request.branchName(), jobTimeoutMinutes);
                } catch (Exception e2) {
                    fail(job, "Clone failed: " + e2.getMessage());
                    return;
                }
            }

            configureGitIfNeeded(workspace);

            List<LinterResult> linterBaseline = runBaselineLinterScan(workspace);
            String baselineSummary = linterBaseline.isEmpty() ? "" : linterService.formatSummary(linterBaseline);

            String effectivePrompt = resolvePrompt(request);
            if (effectivePrompt == null) {
                fail(job, "No prompt provided and could not fetch JIRA issue description for " + request.jiraKey());
                return;
            }

            safeJira(() -> jiraService.commentStarted(request.jiraKey(), request.branchName()));

            String systemPrompt = promptBuilder.buildRunFixPrompt(request, effectivePrompt, workspace, baselineSummary);

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                fail(job, "Agent loop error: " + e.getMessage());
                return;
            }

            if (!runLinterFixLoop(workspace, linterBaseline, job)) {
                return;
            }

            try {
                buildValidator.validate(workspace);
            } catch (Exception e) {
                fail(job, "Build validation failed: " + e.getMessage());
                return;
            }

            boolean hasChanges;
            try {
                hasChanges = workspace.commitAll("fix(" + request.jiraKey() + "): automated fix\n\n" + summary);
            } catch (Exception e) {
                fail(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                fail(job, "Agent completed but made no file changes. Claude summary: " + summary);
                return;
            }

            try {
                workspace.push(request.branchName(), jobTimeoutMinutes);
            } catch (Exception e) {
                fail(job, "Push failed: " + e.getMessage());
                return;
            }

            DiffStats stats = countChanges(workspace);
            String violation = checkGuardrails(stats);
            if (violation != null) {
                fail(job, violation);
                return;
            }
            job.setFilesChanged(stats.filesChanged);
            job.setLinesChanged(stats.linesChanged);

            String prUrl;
            String prId;
            try {
                String title = request.jiraKey() + ": Automated fix";
                String description = "**Automated PR created by Code Agent Runner**\n\n"
                        + "JIRA: " + request.jiraKey() + "\n\n" + summary;
                String[] prResult = platformService.createPullRequest(
                        coords.organization(), coords.project(), coords.repository(),
                        request.branchName(), request.targetBranchOrDefault(),
                        title, description);
                prUrl = prResult[0];
                prId = prResult[1];
            } catch (Exception e) {
                fail(job, "Create PR failed: " + e.getMessage());
                return;
            }

            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary(summary);
            job.setPrUrl(prUrl);
            job.setPrId(prId);
            jobStore.update(job);

            safeJira(() -> jiraService.commentSuccess(request.jiraKey(), prUrl, summary));
            safeJira(() -> jiraService.transitionToInReview(request.jiraKey()));
            safeJira(() -> jiraService.addWorklog(request.jiraKey(), null));

            RunResult result = buildResult(job, true);
            teamsNotifier.sendNotification(result);
            n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);

            LOG.infof("Job %s completed successfully. PR: %s", job.getJobId(), prUrl);

        } catch (Exception e) {
            fail(job, "Unexpected error: " + e.getMessage());
        }
    }

    // ─── Review PR ──────────────────────────────────────────────────────

    public void executeReview(JobRecord job) {
        ReviewPrRequest request = job.getReviewRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failReview(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            Map<String, String> prInfo;
            try {
                prInfo = platformService.getPullRequestInfo(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                failReview(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String targetBranch = request.targetBranch() != null && !request.targetBranch().isBlank()
                    ? request.targetBranch() : prInfo.get("destinationBranch");
            String prTitle = prInfo.get("title");
            job.setPrId(request.prId());

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            LOG.infof("Review: cloning %s/%s branch %s for PR #%s",
                    coords.organization(), coords.repository(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failReview(job, "Clone failed: " + e.getMessage());
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

            // If the webhook told us the current HEAD SHA, check whether we already reviewed it.
            // This avoids re-running the LLM when a non-commit MR update fires (title, labels, etc.).
            // Bitbucket sends 12-char short SHAs; stored SHAs are always the full 40-char rev-parse output.
            // Use a prefix match so both short and full SHAs compare correctly.
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
                        // No new commits since the last review — nothing to do.
                        LOG.infof("Review: PR #%s has no new changes since %s — skipping",
                                request.prId(), lastReviewedSha);
                        job.setStatus(JobStatus.SUCCESS);
                        job.setSummary("No new changes since last review at " + lastReviewedSha + ".");
                        jobStore.update(job);
                        return;
                    }
                } else {
                    diff = workspace.getDiff(targetBranch);
                }
            } catch (Exception e) {
                failReview(job, "Failed to compute diff: " + e.getMessage());
                return;
            }

            if (diff == null || diff.isBlank()) {
                failReview(job, "PR has no diff against " + targetBranch + ". Nothing to review.");
                return;
            }

            String headSha;
            try {
                headSha = workspace.getHeadSha();
            } catch (Exception e) {
                LOG.warnf("Failed to get HEAD SHA (non-fatal): %s", e.getMessage());
                headSha = null;
            }

            // ── Resolution pass: auto-resolve findings addressed by new commits ──
            int resolvedCount = 0;
            if (lastReviewedSha != null) {
                try {
                    List<OpenFinding> openFindings = commentStore.findOpenInlineComments(
                            request.prId(), coords.organization(), coords.repository());
                    if (!openFindings.isEmpty()) {
                        List<ParsedDiffFile> incrementalParsed = DiffParser.parse(diff);
                        List<Long> resolvedIds = findingResolver.resolveAddressedFindings(
                                openFindings, incrementalParsed, workspace, job.getJobId());
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
                            commentStore.markResolved(resolvedId);
                        }
                        resolvedCount = resolvedIds.size();
                        if (resolvedCount > 0) {
                            LOG.infof("Auto-resolved %d previously flagged finding(s) on PR #%s",
                                    resolvedCount, request.prId());
                        }
                    }
                } catch (Exception e) {
                    LOG.warnf("Finding resolution pass failed (non-fatal): %s", e.getMessage());
                }
            }

            // ── Code graph: index and query impact surface ──
            String impactSection = "";
            try {
                List<String> changedFiles = DiffParser.parse(diff).stream()
                        .map(ParsedDiffFile::path).toList();
                if (codeGraphStore.hasGraph(coords.organization(), coords.repository())) {
                    codeGraphIndexer.indexIncremental(workspace,
                            coords.organization(), coords.repository(), changedFiles);
                    if (repoSettingsStore.isVectorEnabled(coords.organization(), coords.repository())) {
                        embeddingIndexer.indexIncremental(workspace,
                                coords.organization(), coords.repository(), changedFiles);
                    }
                } else {
                    codeGraphIndexer.indexFull(workspace,
                            coords.organization(), coords.repository());
                    if (repoSettingsStore.isVectorEnabled(coords.organization(), coords.repository())) {
                        embeddingIndexer.indexFull(workspace,
                                coords.organization(), coords.repository());
                    }
                }
                impactSection = codeGraphQueryService.buildImpactSection(
                        coords.organization(), coords.repository(), changedFiles);
            } catch (Exception e) {
                LOG.warnf("Code graph indexing/query failed (non-fatal): %s", e.getMessage());
            }

            workspace.putMetadata("workspace", coords.organization());
            workspace.putMetadata("repoSlug", coords.repository());

            // ── PR Summary / Walkthrough (posted early so devs see it while review runs) ──
            if (prSummaryEnabled) {
                try {
                    List<ParsedDiffFile> summaryFiles = DiffParser.parse(diff);
                    List<String> changedFilePaths = summaryFiles.stream()
                            .map(ParsedDiffFile::path).toList();
                    String diagramContext = null;
                    if (sequenceDiagramsEnabled && !impactSection.isBlank()) {
                        diagramContext = codeGraphQueryService.buildDiagramContext(
                                coords.organization(), coords.repository(), changedFilePaths);
                    }
                    String summaryBody = prSummaryGenerator.generate(
                            prTitle, targetBranch, summaryFiles, job.getJobId(), diagramContext);
                    if (summaryBody != null) {
                        postOrUpdatePrSummary(coords, request.prId(), existingAgentComments,
                                summaryBody, job.getJobId());
                    }
                } catch (Exception e) {
                    LOG.warnf("PR summary generation failed (non-fatal): %s", e.getMessage());
                }
            }

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentStarted(request.jiraKey(),
                        "PR review for #" + request.prId()));
            }

            ReviewPromptResult promptResult = promptBuilder.buildReviewPrompt(
                    request, prTitle, targetBranch, diff, existingAgentComments, workspace,
                    coords.organization(), coords.repository(), impactSection);

            String reviewOutput;
            try {
                reviewOutput = toolUseLoop.run(promptResult.prompt(), workspace,
                        ToolDefinitions.readOnly(),
                        "Please review the pull request diff provided in the system prompt. "
                                + "Use the read_file and list_files tools to examine surrounding context "
                                + "when needed. Provide your complete review as the specified JSON structure.",
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                failReview(job, "Agent review loop error: " + e.getMessage());
                return;
            }

            String reviewSummary = reviewProcessor.postReviewComments(reviewOutput, coords, request.prId(),
                    existingAgentComments, headSha, job.getJobId(), promptResult.commentableLines(),
                    resolvedCount);

            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(reviewSummary);
            job.setPrUrl(prInfo.getOrDefault("prUrl", ""));
            jobStore.archive(job);

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentSuccess(request.jiraKey(),
                        "PR #" + request.prId(), "Code review completed."));
            }

            RunResult result = buildReviewResult(job, true);
            teamsNotifier.sendNotification(result);
            n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);

            LOG.infof("Review job %s completed for PR #%s", job.getJobId(), request.prId());

        } catch (Exception e) {
            failReview(job, "Unexpected error: " + e.getMessage());
        }
    }

    // ─── Fix PR (address review comments) ───────────────────────────────

    public void executeFixPr(JobRecord job) {
        FixPrRequest request = job.getFixPrRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failFixPr(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            Map<String, String> prInfo;
            try {
                prInfo = platformService.getPullRequestInfo(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                failFixPr(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String targetBranch = prInfo.get("destinationBranch");
            String prTitle = prInfo.get("title");
            job.setPrId(request.prId());

            List<String> reviewComments;
            try {
                reviewComments = platformService.getPullRequestComments(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                failFixPr(job, "Failed to fetch PR comments: " + e.getMessage());
                return;
            }

            if (reviewComments.isEmpty()) {
                failFixPr(job, "No review comments found on PR #" + request.prId() + ". Nothing to fix.");
                return;
            }

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            LOG.infof("Fix-PR: cloning %s/%s branch %s for PR #%s",
                    coords.organization(), coords.repository(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failFixPr(job, "Clone failed: " + e.getMessage());
                return;
            }

            configureGitIfNeeded(workspace);

            String diff = "";
            try {
                workspace.fetchBranch(targetBranch, jobTimeoutMinutes);
                diff = workspace.getDiff(targetBranch);
            } catch (Exception e) {
                LOG.warnf("Failed to compute diff for context (non-fatal): %s", e.getMessage());
            }

            int maxDiffChars = 40_000;
            if (diff.length() > maxDiffChars) {
                diff = diff.substring(0, maxDiffChars);
            }

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentStarted(request.jiraKey(),
                        "Auto-fixing review comments on PR #" + request.prId()));
            }

            String systemPrompt = promptBuilder.buildFixPrPrompt(
                    request, prTitle, sourceBranch, targetBranch, diff, reviewComments, workspace);

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                failFixPr(job, "Agent loop error: " + e.getMessage());
                return;
            }

            try {
                buildValidator.validate(workspace);
            } catch (Exception e) {
                failFixPr(job, "Build validation failed: " + e.getMessage());
                return;
            }

            String fixBranch = "agent/fix-pr-" + request.prId() + "-"
                    + slugify(prTitle != null ? prTitle : "review-fixes");
            boolean hasChanges;
            try {
                workspace.createBranch(fixBranch);
                hasChanges = workspace.commitAll(
                        "fix(PR-" + request.prId() + "): address review comments\n\n" + summary);
            } catch (Exception e) {
                failFixPr(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                failFixPr(job, "Agent completed but made no file changes. Claude summary: " + summary);
                return;
            }

            try {
                workspace.push(fixBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failFixPr(job, "Push failed: " + e.getMessage());
                return;
            }

            DiffStats stats = countChanges(workspace);
            String violation = checkGuardrails(stats);
            if (violation != null) {
                failFixPr(job, violation);
                return;
            }
            job.setFilesChanged(stats.filesChanged);
            job.setLinesChanged(stats.linesChanged);

            String prUrl;
            String newPrId;
            try {
                String title = "Fix review comments on PR #" + request.prId();
                String description = "**Automated fix for review comments on PR #" + request.prId() + "**\n\n"
                        + "Original PR: *" + (prTitle != null ? prTitle : "") + "*\n\n"
                        + "## Review comments addressed\n"
                        + String.join("\n", reviewComments.stream()
                                .map(c -> "- " + c)
                                .limit(20)
                                .toList())
                        + "\n\n## Agent summary\n" + summary;
                String[] prResult = platformService.createPullRequest(
                        coords.organization(), coords.project(), coords.repository(),
                        fixBranch, sourceBranch,
                        title, description);
                prUrl = prResult[0];
                newPrId = prResult[1];
            } catch (Exception e) {
                failFixPr(job, "Create PR failed: " + e.getMessage());
                return;
            }

            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary(summary);
            job.setPrUrl(prUrl);
            job.setPrId(newPrId);
            jobStore.update(job);

            safeComment(() -> platformService.addPrComment(
                    coords.organization(), coords.project(), coords.repository(), request.prId(),
                    "Code Agent has created a fix PR for the review comments: " + prUrl));

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentSuccess(request.jiraKey(), prUrl, summary));
                safeJira(() -> jiraService.addWorklog(request.jiraKey(), null));
            }

            RunResult result = buildFixPrResult(job, true);
            teamsNotifier.sendNotification(result);
            n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);

            LOG.infof("Fix-PR job %s completed. Fix PR: %s", job.getJobId(), prUrl);

        } catch (Exception e) {
            failFixPr(job, "Unexpected error: " + e.getMessage());
        }
    }

    // ─── Conversational Reply ───────────────────────────────────────────

    public void executeReply(JobRecord job) {
        ReplyCommentRequest request = job.getReplyRequest();
        job.setStatus(JobStatus.RUNNING);
        job.setPrId(request.prId());
        jobStore.update(job);

        Optional<CommentContext> ctxOpt = commentStore.find(request.parentCommentId());
        if (ctxOpt.isEmpty()) {
            failReply(job, "Original comment context not found for comment #" + request.parentCommentId());
            return;
        }
        CommentContext ctx = ctxOpt.get();

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failReply(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            Map<String, String> prInfo;
            try {
                prInfo = platformService.getPullRequestInfo(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                failReply(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            LOG.infof("Reply: cloning %s/%s branch %s for comment thread on PR #%s",
                    coords.organization(), coords.repository(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failReply(job, "Clone failed: " + e.getMessage());
                return;
            }

            List<ThreadComment> thread;
            try {
                thread = platformService.getCommentThread(
                        coords.organization(), coords.project(), coords.repository(),
                        request.prId(), request.parentCommentId());
            } catch (Exception e) {
                LOG.warnf("Failed to fetch comment thread (non-fatal): %s", e.getMessage());
                thread = Collections.emptyList();
            }

            String systemPrompt = promptBuilder.buildReplyPrompt(ctx, thread, request.humanMessage());

            String replyText;
            try {
                replyText = toolUseLoop.run(systemPrompt, workspace,
                        ToolDefinitions.readOnly(),
                        "A developer has replied to your review comment. "
                                + "Please read the conversation and respond helpfully.",
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                failReply(job, "Agent reply loop error: " + e.getMessage());
                return;
            }

            try {
                long replyCommentId = platformService.replyToComment(
                        coords.organization(), coords.project(), coords.repository(),
                        request.prId(), request.parentCommentId(), replyText);

                if (replyCommentId > 0) {
                    commentStore.save(replyCommentId, new CommentContext(
                            request.prId(), coords.organization(), coords.project(),
                            coords.repository(),
                            ctx.filePath(), ctx.line(), ctx.category(), ctx.severity(),
                            ctx.findingText(), ctx.reviewJobId()));
                }
            } catch (Exception e) {
                failReply(job, "Failed to post reply: " + e.getMessage());
                return;
            }

            try {
                String developerUsername = thread.stream()
                        .filter(tc -> !tc.isAgent())
                        .reduce((first, second) -> second)
                        .map(ThreadComment::author)
                        .orElse(null);
                learningExtractor.extractAndStore(thread, ctx,
                        coords.organization(), coords.repository(), developerUsername);
            } catch (Exception e) {
                LOG.warnf("Learning extraction failed (non-fatal): %s", e.getMessage());
            }

            job.setStatus(JobStatus.SUCCESS);
            job.setSummary("Replied to comment thread on PR #" + request.prId());
            jobStore.archive(job);
            LOG.infof("Reply job %s completed for comment #%d on PR #%s",
                    job.getJobId(), request.parentCommentId(), request.prId());

        } catch (Exception e) {
            failReply(job, "Unexpected error: " + e.getMessage());
        }
    }

    // ─── Fix from Comment ───────────────────────────────────────────────

    public void executeFixComment(JobRecord job) {
        ReplyCommentRequest request = job.getReplyRequest();
        job.setStatus(JobStatus.RUNNING);
        job.setPrId(request.prId());
        jobStore.update(job);

        Optional<CommentContext> ctxOpt = commentStore.find(request.parentCommentId());
        if (ctxOpt.isEmpty()) {
            failFixComment(job, request, "Original comment context not found for comment #"
                    + request.parentCommentId());
            return;
        }
        CommentContext ctx = ctxOpt.get();

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failFixComment(job, request, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            Map<String, String> prInfo;
            try {
                prInfo = platformService.getPullRequestInfo(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                failFixComment(job, request, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            LOG.infof("FixComment: cloning %s/%s branch %s for comment fix on PR #%s",
                    coords.organization(), coords.repository(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failFixComment(job, request, "Clone failed: " + e.getMessage());
                return;
            }

            configureGitIfNeeded(workspace);

            List<ThreadComment> thread;
            try {
                thread = platformService.getCommentThread(
                        coords.organization(), coords.project(), coords.repository(),
                        request.prId(), request.parentCommentId());
            } catch (Exception e) {
                LOG.warnf("Failed to fetch comment thread (non-fatal): %s", e.getMessage());
                thread = Collections.emptyList();
            }

            String systemPrompt = promptBuilder.buildFixCommentPrompt(ctx, thread, request.humanMessage());

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        ToolDefinitions.all(),
                        "A developer has requested that you implement the fix from your review comment. "
                                + "Read the relevant code, apply the fix, and run tests.",
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                failFixComment(job, request, "Agent fix loop error: " + e.getMessage());
                return;
            }

            try {
                buildValidator.validate(workspace);
            } catch (Exception e) {
                failFixComment(job, request, "Build validation failed: " + e.getMessage());
                return;
            }

            boolean hasChanges;
            try {
                String findingSummary = ctx.findingText();
                if (findingSummary != null && findingSummary.length() > 60) {
                    findingSummary = findingSummary.substring(0, 57) + "...";
                }
                String commitMsg = "fix: " + (ctx.filePath() != null ? ctx.filePath() : "review")
                        + (ctx.line() > 0 ? ":" + ctx.line() : "")
                        + " — " + (findingSummary != null ? findingSummary : "review comment fix");
                hasChanges = workspace.commitAll(commitMsg);
            } catch (Exception e) {
                failFixComment(job, request, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                failFixComment(job, request,
                        "Agent completed but made no file changes. Claude summary: " + summary);
                return;
            }

            DiffStats stats = countChanges(workspace);
            String violation = checkGuardrails(stats);
            if (violation != null) {
                failFixComment(job, request, violation);
                return;
            }
            job.setFilesChanged(stats.filesChanged);
            job.setLinesChanged(stats.linesChanged);

            try {
                workspace.push(sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failFixComment(job, request, "Push failed: " + e.getMessage());
                return;
            }

            String commitSha = null;
            try {
                commitSha = workspace.getHeadSha();
            } catch (Exception e) {
                LOG.warnf("Failed to get HEAD SHA (non-fatal): %s", e.getMessage());
            }

            String replyText = "Applied fix"
                    + (commitSha != null ? " in commit `" + commitSha.substring(0, Math.min(8, commitSha.length())) + "`" : "")
                    + ".\n\n" + summary;
            try {
                long replyCommentId = platformService.replyToComment(
                        coords.organization(), coords.project(), coords.repository(),
                        request.prId(), request.parentCommentId(), replyText);
                if (replyCommentId > 0) {
                    commentStore.save(replyCommentId, new CommentContext(
                            request.prId(), coords.organization(), coords.project(),
                            coords.repository(),
                            ctx.filePath(), ctx.line(), ctx.category(), ctx.severity(),
                            ctx.findingText(), ctx.reviewJobId()));
                }
            } catch (Exception e) {
                LOG.warnf("Failed to post fix confirmation reply (non-fatal): %s", e.getMessage());
            }

            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);
            LOG.infof("FixComment job %s completed for comment #%d on PR #%s",
                    job.getJobId(), request.parentCommentId(), request.prId());

        } catch (Exception e) {
            failFixComment(job, request, "Unexpected error: " + e.getMessage());
        }
    }

    // ─── Generate Unit Tests ─────────────────────────────────────────────

    public void executeGenerateTests(JobRecord job) {
        GenerateTestsRequest request = job.getGenerateTestsRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failGenerateTests(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            String testBranch = request.branchName();
            LOG.infof("GenerateTests: cloning %s/%s (branch: %s)", coords.organization(), coords.repository(), testBranch);
            try {
                workspace.cloneAndCreateBranch(authUrl, request.targetBranchOrDefault(),
                        testBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failGenerateTests(job, "Clone/branch failed: " + e.getMessage());
                return;
            }

            configureGitIfNeeded(workspace);

            // ── Baseline coverage (before agent writes any tests) ──
            CoverageSnapshot baselineCoverage = null;
            if (coverageReporter.isJacocoPresent(workspace)) {
                try {
                    LOG.info("GenerateTests: measuring baseline coverage...");
                    baselineCoverage = coverageReporter.measureCoverage(workspace);
                    if (baselineCoverage != null) {
                        LOG.infof("GenerateTests: baseline — lines %.1f%%, branches %.1f%%",
                                baselineCoverage.lineRate(), baselineCoverage.branchRate());
                    }
                } catch (Exception e) {
                    LOG.warnf("Baseline coverage measurement failed (non-fatal): %s", e.getMessage());
                }
            }

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentStarted(request.jiraKey(), testBranch));
            }

            String systemPrompt = promptBuilder.buildGenerateTestsPrompt(request, workspace, baselineCoverage);

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                failGenerateTests(job, "Agent loop error: " + e.getMessage());
                return;
            }

            // ── Validate build + measure post-generation coverage ──
            CoverageSnapshot afterCoverage = null;
            if (coverageReporter.isJacocoPresent(workspace)) {
                // Running JaCoCo also validates the tests (throws if they fail)
                try {
                    LOG.info("GenerateTests: measuring post-generation coverage...");
                    afterCoverage = coverageReporter.measureCoverage(workspace);
                    if (afterCoverage != null) {
                        LOG.infof("GenerateTests: after — lines %.1f%%, branches %.1f%%",
                                afterCoverage.lineRate(), afterCoverage.branchRate());
                    }
                } catch (Exception e) {
                    failGenerateTests(job, "Build validation failed (generated tests did not pass): " + e.getMessage());
                    return;
                }
            } else {
                // No JaCoCo: fall back to plain build validation
                try {
                    buildValidator.validate(workspace);
                } catch (Exception e) {
                    failGenerateTests(job, "Build validation failed (generated tests did not pass): " + e.getMessage());
                    return;
                }
            }

            // ── Build coverage section for commit msg and PR description ──
            String coverageSummary = "";
            if (afterCoverage != null) {
                coverageSummary = "\n\n" + afterCoverage.formatMarkdownComparison(baselineCoverage);
            }

            boolean hasChanges;
            try {
                String commitMsg = "test: generate unit tests"
                        + (request.jiraKey() != null && !request.jiraKey().isBlank()
                                ? " for " + request.jiraKey() : "")
                        + "\n\n" + summary;
                hasChanges = workspace.commitAll(commitMsg);
            } catch (Exception e) {
                failGenerateTests(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                failGenerateTests(job, "Agent completed but made no file changes. Claude summary: " + summary);
                return;
            }

            try {
                workspace.push(testBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failGenerateTests(job, "Push failed: " + e.getMessage());
                return;
            }

            DiffStats stats = countChanges(workspace);
            job.setFilesChanged(stats.filesChanged);
            job.setLinesChanged(stats.linesChanged);

            String prUrl;
            String prId;
            try {
                String title = "test: generate unit tests"
                        + (request.jiraKey() != null && !request.jiraKey().isBlank()
                                ? " (" + request.jiraKey() + ")" : "");
                String description = "**Automated unit test generation by Code Agent**\n\n"
                        + (request.jiraKey() != null && !request.jiraKey().isBlank()
                                ? "JIRA: " + request.jiraKey() + "\n\n" : "")
                        + summary
                        + coverageSummary;
                String[] prResult = platformService.createPullRequest(
                        coords.organization(), coords.project(), coords.repository(),
                        testBranch, request.targetBranchOrDefault(),
                        title, description);
                prUrl = prResult[0];
                prId = prResult[1];
            } catch (Exception e) {
                failGenerateTests(job, "Create PR failed: " + e.getMessage());
                return;
            }

            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary(summary);
            job.setPrUrl(prUrl);
            job.setPrId(prId);
            jobStore.update(job);

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentSuccess(request.jiraKey(), prUrl, summary));
            }

            RunResult result = buildGenerateTestsResult(job, true);
            teamsNotifier.sendNotification(result);
            n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);

            LOG.infof("GenerateTests job %s completed. PR: %s", job.getJobId(), prUrl);

        } catch (Exception e) {
            failGenerateTests(job, "Unexpected error: " + e.getMessage());
        }
    }

    // ─── Approve / Reject ───────────────────────────────────────────────

    public void approve(JobRecord job) {
        String repoUrl = resolveRepoUrl(job);
        String jiraKey = resolveJiraKey(job);
        RepoCoordinates coords = RepoCoordinates.parse(repoUrl);

        try {
            platformService.mergePullRequest(coords.organization(), coords.project(), coords.repository(), job.getPrId());
            job.setStatus(JobStatus.SUCCESS);
            jobStore.archive(job);
            if (jiraKey != null && !jiraKey.isBlank()) {
                safeJira(() -> jiraService.commentMerged(jiraKey));
                safeJira(() -> jiraService.transitionToDone(jiraKey));
            }
            LOG.infof("Job %s approved and merged", job.getJobId());
        } catch (Exception e) {
            LOG.errorf("Failed to merge PR for job %s: %s", job.getJobId(), e.getMessage());
            throw new RuntimeException("Merge failed: " + e.getMessage(), e);
        }
    }

    public void reject(JobRecord job, String reason) {
        String repoUrl = resolveRepoUrl(job);
        String jiraKey = resolveJiraKey(job);
        RepoCoordinates coords = RepoCoordinates.parse(repoUrl);

        try {
            platformService.declinePullRequest(coords.organization(), coords.project(), coords.repository(), job.getPrId());
        } catch (Exception e) {
            LOG.warnf("Failed to decline PR for job %s: %s", job.getJobId(), e.getMessage());
        }

        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage("Rejected: " + (reason != null ? reason : "No reason provided"));
        jobStore.archive(job);
        if (jiraKey != null && !jiraKey.isBlank()) {
            safeJira(() -> jiraService.commentRejected(jiraKey, reason));
            safeJira(() -> jiraService.transitionToRejected(jiraKey));
        }
        LOG.infof("Job %s rejected", job.getJobId());
    }

    // ─── Shared helpers ─────────────────────────────────────────────────

    private record DiffStats(int filesChanged, int linesChanged) {}

    private DiffStats countChanges(WorkspaceContext workspace) {
        try {
            return new DiffStats(workspace.countFilesChanged(), workspace.countLinesChanged());
        } catch (Exception e) {
            return new DiffStats(0, 0);
        }
    }

    private String checkGuardrails(DiffStats stats) {
        if (stats.filesChanged > guardrails.getMaxFilesChanged()) {
            return "Too many files changed: " + stats.filesChanged
                    + " (max: " + guardrails.getMaxFilesChanged() + ")";
        }
        if (stats.linesChanged > guardrails.getMaxLinesChanged()) {
            return "Too many lines changed: " + stats.linesChanged
                    + " (max: " + guardrails.getMaxLinesChanged() + ")";
        }
        return null;
    }

    private void configureGitIfNeeded(WorkspaceContext workspace) throws Exception {
        if (!gitAuthorEmail.isBlank()) {
            workspace.configureAuthor(gitAuthorName, gitAuthorEmail);
        }
    }

    private String resolvePrompt(RunFixRequest request) {
        String prompt = request.prompt();
        if (prompt != null && !prompt.isBlank()) {
            return prompt;
        }
        LOG.infof("No prompt provided, fetching JIRA issue %s for task description", request.jiraKey());
        try {
            prompt = jiraService.fetchIssuePrompt(request.jiraKey());
        } catch (Exception e) {
            LOG.warnf("Failed to fetch JIRA issue: %s", e.getMessage());
        }
        if (prompt != null && !prompt.isBlank()) {
            LOG.infof("Using JIRA description as prompt: %s",
                    prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);
            return prompt;
        }
        return null;
    }

    private List<LinterResult> runBaselineLinterScan(WorkspaceContext workspace) {
        if (!linterService.getConfig().isEnabled()) {
            return Collections.emptyList();
        }
        LOG.info("Running baseline linter scan...");
        List<LinterResult> baseline = linterService.runAll(workspace.getRoot());
        LOG.infof("Baseline linter scan complete: %s", linterService.formatSummary(baseline));
        return baseline;
    }

    /**
     * Runs the post-change linter delta scan and fix loop.
     * Returns false if the job should be aborted (fail already called).
     */
    private boolean runLinterFixLoop(WorkspaceContext workspace, List<LinterResult> baseline, JobRecord job) {
        if (!linterService.getConfig().isEnabled()) {
            return true;
        }
        int maxLintFixes = linterService.getConfig().getMaxFixIterations();
        for (int lintIter = 0; lintIter < maxLintFixes; lintIter++) {
            LOG.infof("Linter delta scan iteration %d/%d", lintIter + 1, maxLintFixes);
            List<LinterResult> current = linterService.runAll(workspace.getRoot());
            List<LinterFinding> newIssues = linterService.findNewIssues(baseline, current);

            if (newIssues.isEmpty()) {
                LOG.info("No new linter issues introduced — linter gate passed");
                return true;
            }

            LOG.infof("Found %d new linter issues, asking Claude to fix (iteration %d/%d)",
                    newIssues.size(), lintIter + 1, maxLintFixes);

            if (lintIter < maxLintFixes - 1) {
                String fixPrompt = linterService.buildFixPrompt(newIssues);
                try {
                    toolUseLoop.run(fixPrompt, workspace,
                            job.getJobId(), job.getJobType().name());
                } catch (Exception e) {
                    LOG.warnf("Linter fix loop error (non-fatal): %s", e.getMessage());
                    return true;
                }
            } else {
                String issueList = linterService.buildFixPrompt(newIssues);
                LOG.warnf("Linter fix iterations exhausted with %d remaining issues", newIssues.size());
                if (linterService.getConfig().isFailOnNewIssues()) {
                    fail(job, "New linter/SAST issues introduced and could not be auto-fixed:\n" + issueList);
                    return false;
                }
                LOG.warn("fail-on-new-issues is false — continuing despite new linter issues");
            }
        }
        return true;
    }

    private String resolveRepoUrl(JobRecord job) {
        if (job.getJobType() == JobType.FIX_PR) {
            return job.getFixPrRequest().repoUrl();
        }
        return job.getRequest().repoUrl();
    }

    private String resolveJiraKey(JobRecord job) {
        if (job.getJobType() == JobType.FIX_PR) {
            return job.getFixPrRequest().jiraKey();
        }
        return job.getRequest().jiraKey();
    }

    private String resolveWebhookUrl(String requestUrl) {
        return (requestUrl != null && !requestUrl.isBlank()) ? requestUrl : defaultN8nWebhookUrl;
    }

    // ─── Execute Hook ────────────────────────────────────────────────────

    public void executeHook(JobRecord job) {
        HookJobRequest request = job.getHookRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("Hook job %s starting: hook='%s' repo=%s/%s target=%s commitDirect=%s",
                job.getJobId(), request.hookName(), request.workspace(), request.repoSlug(),
                request.targetBranch(), request.commitDirect());

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            fail(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);

            if (request.commitDirect()) {
                try {
                    workspace.cloneRepo(authUrl, request.targetBranch(), jobTimeoutMinutes);
                } catch (Exception e) {
                    fail(job, "Clone failed: " + e.getMessage());
                    return;
                }
            } else {
                try {
                    workspace.cloneAndCreateBranch(authUrl, request.targetBranch(),
                            request.branchName(), jobTimeoutMinutes);
                } catch (Exception e) {
                    fail(job, "Clone/branch failed: " + e.getMessage());
                    return;
                }
            }

            configureGitIfNeeded(workspace);

            RunFixRequest fixRequest = new RunFixRequest(
                    request.repoUrl(), request.branchName(), null,
                    request.prompt(), request.targetBranch(), null, null,
                    request.ruleNames(), request.extraRules());

            String systemPrompt = promptBuilder.buildRunFixPrompt(fixRequest, request.prompt(), workspace, "");

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                fail(job, "Agent loop error: " + e.getMessage());
                return;
            }

            String pushBranch = request.commitDirect() ? request.targetBranch() : request.branchName();
            String commitMsg = "chore(hook-" + request.hookName() + "): " + summary;
            if (commitMsg.length() > 200) {
                commitMsg = commitMsg.substring(0, 197) + "...";
            }

            boolean hasChanges;
            try {
                hasChanges = workspace.commitAll(commitMsg);
            } catch (Exception e) {
                fail(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary("Hook '" + request.hookName() + "' completed with no changes needed.");
                jobStore.archive(job);
                LOG.infof("Hook job %s completed: no changes made", job.getJobId());
                return;
            }

            try {
                workspace.push(pushBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                fail(job, "Push failed: " + e.getMessage());
                return;
            }

            if (request.commitDirect()) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary(summary);
                jobStore.archive(job);
                LOG.infof("Hook job %s completed: committed directly to %s", job.getJobId(), pushBranch);
            } else {
                try {
                    String title = "chore: " + request.hookName();
                    String description = "**Automated PR created by hook: " + request.hookName() + "**\n\n" + summary;
                    String[] prResult = platformService.createPullRequest(
                            coords.organization(), coords.project(), coords.repository(),
                            request.branchName(), request.targetBranch(),
                            title, description);
                    job.setStatus(JobStatus.AWAITING_APPROVAL);
                    job.setSummary(summary);
                    job.setPrUrl(prResult[0]);
                    job.setPrId(prResult[1]);
                    jobStore.update(job);
                    LOG.infof("Hook job %s completed: PR %s created", job.getJobId(), prResult[0]);
                } catch (Exception e) {
                    fail(job, "Create PR failed: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            fail(job, "Unexpected error in hook execution: " + e.getMessage());
        }
    }

    // ─── Failure handlers ───────────────────────────────────────────────

    private void fail(JobRecord job, String message) {
        LOG.errorf("Job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        RunFixRequest request = job.getRequest();
        safeJira(() -> jiraService.commentFailure(request.jiraKey(), message));

        RunResult result = buildResult(job, false);
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
    }

    /**
     * Returns true when the webhook-supplied SHA (which may be a short prefix, e.g. Bitbucket's
     * 12-char hash) matches the full SHA stored in the reviewed-up-to comment marker.
     */
    private static boolean shaAlreadyReviewed(String webhookSha, String storedSha) {
        if (webhookSha == null || storedSha == null) return false;
        if (webhookSha.equals(storedSha)) return true;
        // Short SHA from webhook is a prefix of the stored full SHA
        if (webhookSha.length() < storedSha.length()) return storedSha.startsWith(webhookSha);
        // Stored SHA is somehow shorter (unusual but safe to handle)
        return webhookSha.startsWith(storedSha);
    }

    private void failReview(JobRecord job, String message) {
        LOG.errorf("Review job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        ReviewPrRequest request = job.getReviewRequest();
        if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
            safeJira(() -> jiraService.commentFailure(request.jiraKey(), message));
        }

        RunResult result = buildReviewResult(job, false);
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
    }

    private void failFixPr(JobRecord job, String message) {
        LOG.errorf("Fix-PR job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        FixPrRequest request = job.getFixPrRequest();
        if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
            safeJira(() -> jiraService.commentFailure(request.jiraKey(), message));
        }

        RunResult result = buildFixPrResult(job, false);
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
    }

    private void failGenerateTests(JobRecord job, String message) {
        LOG.errorf("GenerateTests job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        GenerateTestsRequest request = job.getGenerateTestsRequest();
        if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
            safeJira(() -> jiraService.commentFailure(request.jiraKey(), message));
        }

        RunResult result = buildGenerateTestsResult(job, false);
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
    }

    private void failReply(JobRecord job, String message) {
        LOG.errorf("Reply job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
    }

    private void failFixComment(JobRecord job, ReplyCommentRequest request, String message) {
        LOG.errorf("FixComment job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        try {
            RepoCoordinates c = RepoCoordinates.parse(request.repoUrl());
            platformService.replyToComment(
                    c.organization(), c.project(), c.repository(), request.prId(),
                    request.parentCommentId(),
                    "Failed to apply fix: " + message);
        } catch (Exception e) {
            LOG.warnf("Failed to post fix failure reply (non-fatal): %s", e.getMessage());
        }
    }

    // ─── Result builders ────────────────────────────────────────────────

    private RunResult buildResult(JobRecord job, boolean success) {
        RunFixRequest req = job.getRequest();
        return new RunResult(
                job.getJobId(), success, req.jiraKey(), req.branchName(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    private RunResult buildReviewResult(JobRecord job, boolean success) {
        ReviewPrRequest req = job.getReviewRequest();
        return new RunResult(
                job.getJobId(), success,
                req.jiraKey() != null ? req.jiraKey() : "",
                "PR-" + req.prId(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                0, 0);
    }

    private RunResult buildGenerateTestsResult(JobRecord job, boolean success) {
        GenerateTestsRequest req = job.getGenerateTestsRequest();
        return new RunResult(
                job.getJobId(), success,
                req.jiraKey() != null ? req.jiraKey() : "",
                req.branchName(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    private RunResult buildFixPrResult(JobRecord job, boolean success) {
        FixPrRequest req = job.getFixPrRequest();
        return new RunResult(
                job.getJobId(), success,
                req.jiraKey() != null ? req.jiraKey() : "",
                "fix-pr-" + req.prId(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    // ─── Utilities ──────────────────────────────────────────────────────

    /**
     * Post a new PR walkthrough comment or update the existing one in-place.
     * Looks for a previously posted comment tracked under the {@code __pr_summary__}
     * marker in CommentStore. Falls back to creating a new comment on update failure.
     */
    private void postOrUpdatePrSummary(RepoCoordinates coords, String prId,
                                       List<AgentComment> existingComments,
                                       String body, String jobId) {
        String org = coords.organization();
        String project = coords.project();
        String repo = coords.repository();

        var existingId = commentStore.findPrSummaryCommentId(prId, org, repo);
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

        safeComment(() -> {
            long newId = platformService.addPrComment(org, project, repo, prId, body);
            if (newId > 0) {
                commentStore.savePrSummaryComment(newId, prId, org, project, repo, jobId);
                LOG.infof("Created PR summary comment %d on PR #%s", newId, prId);
            }
        });
    }

    private void safeJira(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warnf("JIRA operation failed (non-fatal): %s", e.getMessage());
        }
    }

    private void safeComment(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warnf("Failed to post PR comment (non-fatal): %s", e.getMessage());
        }
    }

    private static String slugify(String text) {
        if (text == null || text.isBlank()) return "fix";
        String slug = text.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-$", "");
        }
        return slug;
    }
}
