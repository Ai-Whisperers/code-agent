package com.eneve.agent.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.eneve.agent.diff.DiffParser;
import com.eneve.agent.diff.ParsedDiffFile;
import com.eneve.agent.diff.ReviewPromptResult;

import com.eneve.agent.rules.CursorRulesLoader;
import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scm.ThreadComment;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.linter.LinterFinding;
import com.eneve.agent.linter.LinterResult;
import com.eneve.agent.linter.LinterService;
import com.eneve.agent.linter.StaticAnalysisDiffReport;
import com.eneve.agent.agent.CoverageReporter.CoverageSnapshot;
import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.model.FixPrRequest;
import com.eneve.agent.model.GenerateDocsRequest;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.HookJobRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.MetricsJobRequest;
import com.eneve.agent.model.ReplyCommentRequest;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.model.RunResult;
import com.eneve.agent.model.SyncConfluenceRequest;
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

    /** Dedicated pool for parallel review phases (code graph, finding resolution, PR summary). */
    private static final ExecutorService REVIEW_PARALLEL_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "review-parallel-phase");
        t.setDaemon(true);
        return t;
    });

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject CursorRulesLoader rulesLoader;
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
    @Inject MermaidPngRenderer mermaidPngRenderer;
    @Inject CoverageReporter coverageReporter;
    @Inject ConfluenceService confluenceService;
    @Inject DocsEmbeddingService docsEmbeddingService;
    @Inject CodeMetricsCalculator codeMetricsCalculator;
    @Inject CodeMetricsStore codeMetricsStore;
    @Inject PromptTemplateService promptTemplates;

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

    @ConfigProperty(name = "rules.repo.url", defaultValue = "")
    String defaultRulesRepoUrl;

    @ConfigProperty(name = "run-fix.job-timeout-minutes", defaultValue = "30")
    long jobTimeoutMinutes;

    @ConfigProperty(name = "run-fix.max-build-retries", defaultValue = "2")
    int maxBuildRetries;

    @ConfigProperty(name = "run-fix.self-review.enabled", defaultValue = "true")
    boolean selfReviewEnabled;

    @ConfigProperty(name = "run-fix.self-review.max-iterations", defaultValue = "15")
    int selfReviewMaxIterations;

    @ConfigProperty(name = "run-fix.self-review.max-diff-chars", defaultValue = "30000")
    int selfReviewMaxDiffChars;

    @ConfigProperty(name = "generate-tests.max-loop-iterations", defaultValue = "500")
    int generateTestsMaxIterations;

    @ConfigProperty(name = "generate-tests.job-timeout-minutes", defaultValue = "60")
    long generateTestsTimeoutMinutes;

    @ConfigProperty(name = "generate-docs.max-loop-iterations", defaultValue = "200")
    int generateDocsMaxIterations;

    @ConfigProperty(name = "metrics.job-timeout-minutes", defaultValue = "30")
    long metricsTimeoutMinutes;

    @ConfigProperty(name = "metrics.max-methods-per-fix", defaultValue = "10")
    int metricsMaxMethodsPerFix;

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

            // Quality-improvement FIX jobs carry a planId; use the focused CC-reduction prompt.
            if (request.planId() != null && !request.planId().isBlank()) {
                String qualityPrompt = buildQualityFixPrompt(request.planId());
                if (qualityPrompt != null) {
                    String summary;
                    try {
                        summary = toolUseLoop.run(qualityPrompt, workspace,
                                job.getJobId(), job.getJobType().name());
                    } catch (Exception e) {
                        fail(job, "Agent loop error: " + e.getMessage());
                        return;
                    }
                    finishFixJob(job, request, workspace, summary);
                    return;
                }
                LOG.warnf("No CC snapshot for plan %s, falling back to generic fix prompt", request.planId());
            }

            // Linter baseline scan and prompt resolution (JIRA fetch) are independent — run in parallel
            CompletableFuture<List<LinterResult>> linterFuture = CompletableFuture.supplyAsync(
                    () -> runBaselineLinterScan(workspace), REVIEW_PARALLEL_POOL);
            CompletableFuture<String> promptFuture = CompletableFuture.supplyAsync(
                    () -> resolvePrompt(request), REVIEW_PARALLEL_POOL);

            CompletableFuture.allOf(linterFuture, promptFuture).join();

            List<LinterResult> linterBaseline = linterFuture.join();
            String baselineSummary = linterBaseline.isEmpty() ? "" : linterService.formatSummary(linterBaseline);

            String effectivePrompt = promptFuture.join();
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

            runSelfReview(workspace, job, effectivePrompt);

            LinterFixResult linterFixResult = runLinterFixLoop(workspace, linterBaseline, job);
            if (!linterFixResult.canContinue()) {
                return;
            }

            if (!runBuildWithRetry(workspace, job)) {
                fail(job, "Build validation failed after " + maxBuildRetries + " retry attempt(s)");
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

            // Build the static analysis diff report now that changes are committed
            // (HEAD~1 is the original branch tip, HEAD is the agent's commit).
            StaticAnalysisDiffReport linterDiffReport = buildLinterDiffReport(
                    workspace, linterBaseline, linterFixResult.finalResults());

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
                String linterSummaryLine = linterDiffReport != null
                        ? "\n\n" + buildLinterDiffSummaryLine(linterDiffReport)
                        : "";
                String description = "**Automated PR created by Code Agent Runner**\n\n"
                        + "JIRA: " + request.jiraKey() + "\n\n" + summary + linterSummaryLine;
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

            // Post the full static analysis diff report as a standalone PR comment
            if (linterDiffReport != null && linterService.getConfig().isReportOnPr()) {
                final String capturedPrId = prId;
                final StaticAnalysisDiffReport capturedReport = linterDiffReport;
                CompletableFuture.runAsync(() -> safeComment(() ->
                        platformService.addPrComment(
                                coords.organization(), coords.project(), coords.repository(),
                                capturedPrId, capturedReport.formatMarkdown())),
                        REVIEW_PARALLEL_POOL);
            }

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

            // ── Parallel phase: finding resolution + code graph + PR summary ──
            // Parse the diff once; used in multiple parallel branches.
            final List<ParsedDiffFile> parsedDiff = DiffParser.parse(diff);
            final List<String> changedFiles = parsedDiff.stream().map(ParsedDiffFile::path).toList();

            // Effectively-final copies for lambda captures (some variables are assigned in try-catch
            // blocks above, making them not automatically effectively-final in Java).
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
                }, REVIEW_PARALLEL_POOL);
            } else {
                findingsFuture = CompletableFuture.completedFuture(0);
            }

            // Future 2: Code graph indexing + impact section + PR summary
            // PR summary runs after code graph so it can use the impact section for diagram context.
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

                // PR summary follows immediately so it can use the impact section for diagrams.
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
                                    summaryResult, jobIdFinal);
                        }
                    } catch (Exception e) {
                        LOG.warnf("PR summary generation failed (non-fatal): %s", e.getMessage());
                    }
                }
                return impact;
            }, REVIEW_PARALLEL_POOL);

            // Wait for both parallel futures before building the main review prompt.
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

            // Phase 1: launch PR comments and shared rules loading in parallel — no dependency on PR info
            CompletableFuture<List<String>> commentsFuture = CompletableFuture.supplyAsync(
                    () -> platformService.getPullRequestComments(
                            coords.organization(), coords.project(), coords.repository(), request.prId()),
                    REVIEW_PARALLEL_POOL);

            String resolvedRulesRepoUrl = (request.rulesRepoUrl() != null && !request.rulesRepoUrl().isBlank())
                    ? request.rulesRepoUrl() : defaultRulesRepoUrl;
            List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();
            CompletableFuture<List<String>> sharedRulesFuture = CompletableFuture.supplyAsync(
                    () -> rulesLoader.loadFromRulesRepo(resolvedRulesRepoUrl, ruleNames),
                    REVIEW_PARALLEL_POOL);

            // PR info is needed for sourceBranch before we can clone
            Map<String, String> prInfo;
            try {
                prInfo = platformService.getPullRequestInfo(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                commentsFuture.cancel(true);
                sharedRulesFuture.cancel(true);
                failFixPr(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String targetBranch = prInfo.get("destinationBranch");
            String prTitle = prInfo.get("title");
            job.setPrId(request.prId());

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            LOG.infof("Fix-PR: cloning %s/%s branch %s for PR #%s",
                    coords.organization(), coords.repository(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                commentsFuture.cancel(true);
                sharedRulesFuture.cancel(true);
                failFixPr(job, "Clone failed: " + e.getMessage());
                return;
            }

            configureGitIfNeeded(workspace);

            // Phase 2: fetchBranch+diff and target-repo rules loading overlap with each other
            CompletableFuture<String> diffFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    workspace.fetchBranch(targetBranch, jobTimeoutMinutes);
                    return workspace.getDiff(targetBranch);
                } catch (Exception e) {
                    LOG.warnf("Failed to compute diff for context (non-fatal): %s", e.getMessage());
                    return "";
                }
            }, REVIEW_PARALLEL_POOL);

            CompletableFuture<List<String>> repoRulesFuture = CompletableFuture.supplyAsync(
                    () -> rulesLoader.loadFromTargetRepo(workspace.getRoot()),
                    REVIEW_PARALLEL_POOL);

            // Resolve comments — needed before we can determine whether to proceed
            List<String> reviewComments;
            try {
                reviewComments = commentsFuture.join();
            } catch (Exception e) {
                failFixPr(job, "Failed to fetch PR comments: " + e.getMessage());
                return;
            }

            if (reviewComments.isEmpty()) {
                failFixPr(job, "No review comments found on PR #" + request.prId() + ". Nothing to fix.");
                return;
            }

            // Collect all parallel results before building the prompt
            String diff = diffFuture.join();
            int maxDiffChars = 40_000;
            if (diff.length() > maxDiffChars) {
                diff = diff.substring(0, maxDiffChars);
            }

            List<String> sharedRules = sharedRulesFuture.join();
            List<String> repoRules = repoRulesFuture.join();

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentStarted(request.jiraKey(),
                        "Auto-fixing review comments on PR #" + request.prId()));
            }

            String systemPrompt = promptBuilder.buildFixPrPrompt(
                    request, prTitle, sourceBranch, targetBranch, diff, reviewComments,
                    sharedRules, repoRules);

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                failFixPr(job, "Agent loop error: " + e.getMessage());
                return;
            }

            runSelfReview(workspace, job, "Fix review comments on PR #" + request.prId());

            if (!runBuildWithRetry(workspace, job)) {
                failFixPr(job, "Build validation failed after " + maxBuildRetries + " retry attempt(s)");
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

            // Guardrails check before push to avoid pushing a branch that violates limits
            DiffStats stats = countChanges(workspace);
            String violation = checkGuardrails(stats);
            if (violation != null) {
                failFixPr(job, violation);
                return;
            }
            job.setFilesChanged(stats.filesChanged);
            job.setLinesChanged(stats.linesChanged);

            try {
                workspace.push(fixBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failFixPr(job, "Push failed: " + e.getMessage());
                return;
            }

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

            RunResult result = buildFixPrResult(job, true);

            // Fire-and-forget: notifications are best-effort and do not block job completion
            final String capturedPrUrl = prUrl;
            final String capturedSummary = summary;
            CompletableFuture.runAsync(() -> safeComment(() -> platformService.addPrComment(
                    coords.organization(), coords.project(), coords.repository(), request.prId(),
                    "Code Agent has created a fix PR for the review comments: " + capturedPrUrl)),
                    REVIEW_PARALLEL_POOL);

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                CompletableFuture.runAsync(() -> {
                    safeJira(() -> jiraService.commentSuccess(request.jiraKey(), capturedPrUrl, capturedSummary));
                    safeJira(() -> jiraService.addWorklog(request.jiraKey(), null));
                }, REVIEW_PARALLEL_POOL);
            }

            CompletableFuture.runAsync(() -> teamsNotifier.sendNotification(result), REVIEW_PARALLEL_POOL);
            CompletableFuture.runAsync(
                    () -> n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result),
                    REVIEW_PARALLEL_POOL);

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

            if (!runBuildWithRetry(workspace, job)) {
                failFixComment(job, request, "Build validation failed after " + maxBuildRetries + " retry attempt(s)");
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
            teamsNotifier.sendNotification(buildFixCommentResult(job, true));
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
                        testBranch, generateTestsTimeoutMinutes);
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
                        generateTestsMaxIterations, job.getJobId(), job.getJobType().name());
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
                // No JaCoCo: fall back to plain build validation with retry
                if (!runBuildWithRetry(workspace, job)) {
                    failGenerateTests(job, "Build validation failed after " + maxBuildRetries
                            + " retry attempt(s) (generated tests did not pass)");
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
                workspace.push(testBranch, generateTestsTimeoutMinutes);
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

    /**
     * Builds the system prompt for a quality-improvement FIX job by looking up the latest
     * CC snapshot for the plan and delegating to the focused metrics-fix prompt builder.
     * Returns {@code null} if no snapshot exists yet (caller should fall back).
     */
    private String buildQualityFixPrompt(String planId) {
        List<CodeMetricsCalculator.CodeMetricsSnapshot> snapshots = codeMetricsStore.findByPlan(planId);
        if (snapshots.isEmpty()) {
            LOG.warnf("Quality-fix job for plan %s: no CC snapshot found", planId);
            return null;
        }
        CodeMetricsCalculator.CodeMetricsSnapshot latest = snapshots.get(snapshots.size() - 1);
        return promptBuilder.buildMetricsFixPrompt(latest, metricsMaxMethodsPerFix);
    }

    /**
     * Handles the post-agent steps for a quality-improvement FIX job:
     * build validation, commit, push, guardrail check, and PR creation.
     */
    private void finishFixJob(JobRecord job, RunFixRequest request, WorkspaceContext workspace, String summary) {
        RepoCoordinates coords = RepoCoordinates.parse(request.repoUrl());
        runSelfReview(workspace, job, "Refactor methods to reduce cyclomatic complexity");
        if (!runBuildWithRetry(workspace, job)) {
            fail(job, "Build validation failed after " + maxBuildRetries + " retry attempt(s)");
            return;
        }

        boolean hasChanges;
        try {
            hasChanges = workspace.commitAll("refactor: reduce cyclomatic complexity\n\n" + summary);
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
            String title = "refactor: reduce cyclomatic complexity";
            String description = "**Automated quality improvement by Code Agent**\n\n" + summary;
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

        RunResult result = buildResult(job, true);
        teamsNotifier.sendNotification(result);
        n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);

        LOG.infof("Quality-fix job %s completed successfully. PR: %s", job.getJobId(), prUrl);
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
     * Builds a {@link StaticAnalysisDiffReport} from the baseline and final linter scans.
     * Collects changed file names from the most recent commit to scope the report.
     * Returns {@code null} if linting is disabled or no scan results are available.
     */
    private StaticAnalysisDiffReport buildLinterDiffReport(
            WorkspaceContext workspace,
            List<LinterResult> baseline,
            List<LinterResult> finalResults) {

        if (!linterService.getConfig().isEnabled()) {
            return null;
        }
        if (baseline.isEmpty() && finalResults.isEmpty()) {
            return null;
        }

        Set<String> changedFiles = Collections.emptySet();
        try {
            changedFiles = workspace.getChangedFileNames();
        } catch (Exception e) {
            LOG.warnf("Could not retrieve changed file names for linter diff scoping: %s", e.getMessage());
        }

        StaticAnalysisDiffReport report = linterService.buildDiffReport(baseline, finalResults, changedFiles);
        LOG.infof("Static analysis diff: verdict=%s, newIssues=%d, resolvedIssues=%d",
                report.verdict(), report.newIssues().size(), report.resolvedIssues().size());
        return report;
    }

    /**
     * Produces a one-line Markdown summary of the static analysis verdict for the PR description.
     */
    private static String buildLinterDiffSummaryLine(StaticAnalysisDiffReport report) {
        int newCount = report.newIssues().size();
        int resolvedCount = report.resolvedIssues().size();
        return switch (report.verdict()) {
            case PASS     -> "**Static Analysis:** PASS — no new issues introduced.";
            case IMPROVED -> "**Static Analysis:** IMPROVED — " + resolvedCount
                    + " issue(s) resolved, " + newCount + " new issue(s).";
            case DEGRADED -> "**Static Analysis:** DEGRADED — " + newCount
                    + " new issue(s) introduced, " + resolvedCount + " resolved.";
        };
    }

    /**
     * Carries the outcome of the linter fix loop:
     * whether the job may continue and the final scan results needed to build the diff report.
     */
    private record LinterFixResult(boolean canContinue, List<LinterResult> finalResults) {}

    /**
     * Runs the post-change linter delta scan and fix loop.
     * Returns a {@link LinterFixResult} whose {@code canContinue} is {@code false}
     * when the job should be aborted (fail() has already been called).
     * The {@code finalResults} field always contains the last completed linter scan.
     */
    private LinterFixResult runLinterFixLoop(WorkspaceContext workspace, List<LinterResult> baseline, JobRecord job) {
        if (!linterService.getConfig().isEnabled()) {
            return new LinterFixResult(true, Collections.emptyList());
        }
        int maxLintFixes = linterService.getConfig().getMaxFixIterations();
        List<LinterResult> lastResults = Collections.emptyList();
        for (int lintIter = 0; lintIter < maxLintFixes; lintIter++) {
            LOG.infof("Linter delta scan iteration %d/%d", lintIter + 1, maxLintFixes);
            List<LinterResult> current = linterService.runAll(workspace.getRoot());
            lastResults = current;
            List<LinterFinding> newIssues = linterService.findNewIssues(baseline, current);

            if (newIssues.isEmpty()) {
                LOG.info("No new linter issues introduced — linter gate passed");
                return new LinterFixResult(true, lastResults);
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
                    return new LinterFixResult(true, lastResults);
                }
            } else {
                String issueList = linterService.buildFixPrompt(newIssues);
                LOG.warnf("Linter fix iterations exhausted with %d remaining issues", newIssues.size());
                if (linterService.getConfig().isFailOnNewIssues()) {
                    fail(job, "New linter/SAST issues introduced and could not be auto-fixed:\n" + issueList);
                    return new LinterFixResult(false, lastResults);
                }
                LOG.warn("fail-on-new-issues is false — continuing despite new linter issues");
            }
        }
        return new LinterFixResult(true, lastResults);
    }

    /**
     * Runs a focused self-review pass: the agent reads its own diff against the original task,
     * checks for completeness, debug artefacts, unused imports, and edge cases, then fixes
     * anything it finds before the linter and build gates run.
     * <p>
     * This step is entirely non-fatal — any error causes a warning log and the job continues.
     */
    private void runSelfReview(WorkspaceContext workspace, JobRecord job, String originalTask) {
        if (!selfReviewEnabled) {
            return;
        }

        String diff;
        try {
            diff = workspace.getWorkingDiff();
        } catch (Exception e) {
            LOG.warnf("Self-review: failed to get working diff (non-fatal): %s", e.getMessage());
            return;
        }

        if (diff == null || diff.isBlank()) {
            LOG.info("Self-review: no changes detected, skipping");
            return;
        }

        if (diff.length() > selfReviewMaxDiffChars) {
            diff = diff.substring(0, selfReviewMaxDiffChars)
                    + "\n\n... [diff truncated at " + selfReviewMaxDiffChars + " chars] ...";
        }

        String filesChanged = diff.lines()
                .filter(l -> l.startsWith("diff --git "))
                .map(l -> {
                    // "diff --git a/path/to/File.java b/path/to/File.java" -> "path/to/File.java"
                    String[] parts = l.split(" ");
                    return parts.length >= 4 ? parts[3].replaceFirst("^b/", "") : l;
                })
                .collect(Collectors.joining(", "));

        String reviewPrompt = promptTemplates.resolve("self-review", Map.of(
                "ORIGINAL_TASK", originalTask != null ? originalTask : "(not available)",
                "DIFF", diff,
                "FILES_CHANGED", filesChanged));

        LOG.infof("Running self-review pass over %d changed file(s)...",
                diff.lines().filter(l -> l.startsWith("diff --git ")).count());
        try {
            toolUseLoop.run(reviewPrompt, workspace, selfReviewMaxIterations,
                    job.getJobId(), job.getJobType().name());
        } catch (Exception e) {
            LOG.warnf("Self-review loop error (non-fatal): %s", e.getMessage());
        }
        LOG.info("Self-review pass complete");
    }

    /**
     * Runs build validation with automatic retries on failure.
     * On each failure the build error output is fed back to the agent so it can
     * fix its own mistakes before the next attempt.
     *
     * @return {@code true} if validation eventually passes,
     *         {@code false} if all attempts are exhausted (caller must call fail())
     */
    private boolean runBuildWithRetry(WorkspaceContext workspace, JobRecord job) {
        String jobId = job.getJobId();
        String jobType = job.getJobType().name();
        int attempts = 0;
        while (true) {
            try {
                buildValidator.validate(workspace);
                if (attempts > 0) {
                    LOG.infof("Build validation passed on retry attempt %d", attempts);
                }
                return true;
            } catch (Exception e) {
                attempts++;
                String buildError = e.getMessage() != null ? e.getMessage() : "Unknown build error";
                if (attempts > maxBuildRetries) {
                    LOG.warnf("Build validation failed after %d attempt(s), giving up: %s",
                            attempts, buildError.length() > 200 ? buildError.substring(0, 200) + "..." : buildError);
                    return false;
                }

                LOG.infof("Build validation failed (attempt %d/%d), feeding error back to agent: %s",
                        attempts, maxBuildRetries,
                        buildError.length() > 200 ? buildError.substring(0, 200) + "..." : buildError);

                String retryPrompt = promptTemplates.resolve("build-retry", Map.of(
                        "BUILD_OUTPUT", buildError,
                        "ATTEMPT", String.valueOf(attempts),
                        "MAX_ATTEMPTS", String.valueOf(maxBuildRetries)));

                try {
                    toolUseLoop.run(retryPrompt, workspace, 30, jobId, jobType);
                } catch (Exception agentEx) {
                    LOG.warnf("Agent fix loop error during build retry (attempt %d): %s", attempts, agentEx.getMessage());
                    return false;
                }
            }
        }
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

    // ─── Generate Docs ───────────────────────────────────────────────────

    public void executeGenerateDocs(JobRecord job) {
        GenerateDocsRequest request = job.getGenerateDocsRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("GenerateDocs job %s starting for %s (commitDirect=%s, branch=%s, target=%s)",
                job.getJobId(), request.repoUrl(), request.isCommitDirect(),
                request.branchName(), request.targetBranchOrDefault());

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failGenerateDocs(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        String ws = coords.organization();
        String repoSlug = coords.repository();

        RepoSettings settings = repoSettingsStore.find(ws, repoSlug)
                .orElse(RepoSettings.defaults(ws, repoSlug));

        if (!settings.docsEnabled()) {
            failGenerateDocs(job, "Documentation generation is disabled for " + ws + "/" + repoSlug);
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            String targetBranch = request.targetBranchOrDefault();

            if (request.isCommitDirect()) {
                try {
                    workspace.cloneRepo(authUrl, targetBranch, jobTimeoutMinutes);
                } catch (Exception e) {
                    failGenerateDocs(job, "Clone failed: " + e.getMessage());
                    return;
                }
            } else {
                try {
                    workspace.cloneAndCreateBranch(authUrl, targetBranch,
                            request.branchName(), jobTimeoutMinutes);
                } catch (Exception e) {
                    failGenerateDocs(job, "Clone/branch failed: " + e.getMessage());
                    return;
                }
            }

            configureGitIfNeeded(workspace);

            workspace.putMetadata("workspace", ws);
            workspace.putMetadata("repoSlug", repoSlug);

            String systemPrompt = promptBuilder.buildGenerateDocsPrompt(request, workspace, settings);

            var tools = ToolDefinitions.docsGeneration();

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace, tools,
                        "Please generate comprehensive documentation for this repository. "
                                + "Start by exploring the project structure, then create all doc files.",
                        generateDocsMaxIterations, job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                failGenerateDocs(job, "Agent loop error: " + e.getMessage());
                return;
            }

            try {
                docsEmbeddingService.indexDocs(workspace, ws, repoSlug);
            } catch (Exception e) {
                LOG.warnf("Doc embedding failed (non-fatal): %s", e.getMessage());
            }

            String pushBranch = request.isCommitDirect() ? targetBranch : request.branchName();
            String commitMsg = "docs: generate project documentation";

            boolean hasChanges;
            try {
                hasChanges = workspace.commitAll(commitMsg);
            } catch (Exception e) {
                failGenerateDocs(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary("Documentation generation completed with no new files.");
                jobStore.archive(job);
                LOG.infof("GenerateDocs job %s completed: no changes made", job.getJobId());
                return;
            }

            if (request.isCommitDirect()) {
                try {
                    workspace.pullRebase(targetBranch, jobTimeoutMinutes);
                } catch (Exception e) {
                    LOG.warnf("Pull --rebase before push failed (non-fatal): %s", e.getMessage());
                }
            }

            try {
                workspace.push(pushBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failGenerateDocs(job, "Push failed: " + e.getMessage());
                return;
            }

            if (request.isCommitDirect()) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary(summary);
                jobStore.archive(job);
                LOG.infof("GenerateDocs job %s completed: committed to %s", job.getJobId(), pushBranch);
            } else {
                try {
                    String title = "docs: generate project documentation";
                    String description = "**Automated documentation generated by code-agent**\n\n" + summary;
                    String[] prResult = platformService.createPullRequest(
                            coords.organization(), coords.project(), coords.repository(),
                            request.branchName(), targetBranch,
                            title, description);
                    job.setStatus(JobStatus.AWAITING_APPROVAL);
                    job.setSummary(summary);
                    job.setPrUrl(prResult[0]);
                    job.setPrId(prResult[1]);
                    jobStore.update(job);
                    LOG.infof("GenerateDocs job %s completed: PR %s created", job.getJobId(), prResult[0]);
                } catch (Exception e) {
                    failGenerateDocs(job, "Create PR failed: " + e.getMessage());
                }
            }

            String webhookUrl = resolveWebhookUrl(request.n8nWebhookUrl());
            RunResult result = buildGenerateDocsResult(job, true);
            teamsNotifier.sendNotification(result);
            n8nNotifier.sendResult(webhookUrl, result);

        } catch (Exception e) {
            failGenerateDocs(job, "Unexpected error in doc generation: " + e.getMessage());
        }
    }

    // ─── Execute Sync Confluence ─────────────────────────────────────────

    public void executeSyncConfluence(JobRecord job) {
        SyncConfluenceRequest request = job.getSyncConfluenceRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("SyncConfluence job %s starting for %s (branch=%s, docsPath=%s)",
                job.getJobId(), request.repoUrl(), request.branchOrDefault(), request.docsPathOrDefault());

        if (!confluenceService.isEnabled()) {
            failSyncConfluence(job, "Confluence is not configured (set CONFLUENCE_BASE_URL, CONFLUENCE_USER, CONFLUENCE_API_TOKEN)");
            return;
        }

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failSyncConfluence(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        String ws = coords.organization();
        String repoSlug = coords.repository();

        RepoSettings settings = repoSettingsStore.find(ws, repoSlug)
                .orElse(RepoSettings.defaults(ws, repoSlug));

        String spaceKey = (request.confluenceSpaceKey() != null && !request.confluenceSpaceKey().isBlank())
                ? request.confluenceSpaceKey()
                : settings.confluenceSpaceKey();

        if (spaceKey == null || spaceKey.isBlank()) {
            failSyncConfluence(job, "No Confluence space key available. Provide it in the request or configure it in repo settings.");
            return;
        }

        String parentPageId = (request.confluenceParentPageId() != null && !request.confluenceParentPageId().isBlank())
                ? request.confluenceParentPageId()
                : settings.confluenceParentPageId();

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            try {
                workspace.cloneRepo(authUrl, request.branchOrDefault(), jobTimeoutMinutes);
            } catch (Exception e) {
                failSyncConfluence(job, "Clone failed: " + e.getMessage());
                return;
            }

            java.nio.file.Path docsDir = workspace.getRoot().resolve(request.docsPathOrDefault());
            if (!java.nio.file.Files.isDirectory(docsDir)) {
                failSyncConfluence(job, "Docs folder not found: " + request.docsPathOrDefault());
                return;
            }

            java.util.List<java.nio.file.Path> mdFiles;
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(docsDir)) {
                mdFiles = stream
                        .filter(p -> java.nio.file.Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                        .sorted(java.util.Comparator.comparing(p -> {
                            // README.md sorts first so it becomes the root page
                            return p.getFileName().toString().equalsIgnoreCase("README.md") ? "0" : p.getFileName().toString();
                        }))
                        .collect(java.util.stream.Collectors.toList());
            } catch (Exception e) {
                failSyncConfluence(job, "Failed to list docs files: " + e.getMessage());
                return;
            }

            if (mdFiles.isEmpty()) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary("No Markdown files found in " + request.docsPathOrDefault() + "; nothing to sync.");
                jobStore.archive(job);
                return;
            }

            int synced = 0;
            String docsRootPageId = null;

            for (java.nio.file.Path mdFile : mdFiles) {
                String fileName = mdFile.getFileName().toString();
                String title = fileName.replaceAll("\\.md$", "").replace("-", " ").replace("_", " ");
                if (fileName.equalsIgnoreCase("README.md")) {
                    title = repoSlug + " Documentation";
                }

                String markdownContent;
                try {
                    markdownContent = java.nio.file.Files.readString(mdFile);
                } catch (Exception e) {
                    LOG.warnf("SyncConfluence job %s: could not read %s — skipping: %s",
                            job.getJobId(), fileName, e.getMessage());
                    continue;
                }

                String effectiveParent = (docsRootPageId != null) ? docsRootPageId : parentPageId;

                try {
                    com.eneve.agent.confluence.ConfluenceService.PageResult result =
                            confluenceService.createOrUpdatePage(spaceKey, effectiveParent, title, markdownContent);
                    if (result != null) {
                        if (docsRootPageId == null) {
                            docsRootPageId = result.pageId();
                        }
                        synced++;
                        LOG.debugf("SyncConfluence job %s: published '%s' → %s", job.getJobId(), title, result.pageUrl());
                    } else {
                        LOG.warnf("SyncConfluence job %s: failed to publish '%s'", job.getJobId(), title);
                    }
                } catch (Exception e) {
                    LOG.warnf("SyncConfluence job %s: error publishing '%s': %s", job.getJobId(), title, e.getMessage());
                }
            }

            String summary = "Synced " + synced + " of " + mdFiles.size() + " Markdown files to Confluence space " + spaceKey + ".";
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);
            LOG.infof("SyncConfluence job %s completed: %s", job.getJobId(), summary);

        } catch (Exception e) {
            failSyncConfluence(job, "Unexpected error in Confluence sync: " + e.getMessage());
        }
    }

    // ─── Execute Metrics ─────────────────────────────────────────────────

    public void executeMetrics(JobRecord job) {
        MetricsJobRequest request = job.getMetricsRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failMetrics(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        LOG.infof("Metrics job %s: analysing %s/%s (branch: %s, threshold: %d)",
                job.getJobId(), coords.organization(), coords.repository(),
                request.branch(), request.effectiveThreshold());

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            try {
                // Depth-1 is sufficient: metrics only reads the current file tree
                workspace.cloneRepoShallow(authUrl, request.branch(), metricsTimeoutMinutes);
            } catch (Exception e) {
                failMetrics(job, "Clone failed: " + e.getMessage());
                return;
            }

            String wsName = request.workspace() != null ? request.workspace() : coords.organization();
            String repoSlug = request.repoSlug() != null ? request.repoSlug() : coords.repository();

            CodeMetricsCalculator.CodeMetricsSnapshot snapshot;
            try {
                snapshot = codeMetricsCalculator.calculate(
                        workspace.getRoot(), wsName, repoSlug, request.branch(),
                        request.effectiveThreshold());
            } catch (Exception e) {
                failMetrics(job, "Metrics calculation failed: " + e.getMessage());
                return;
            }

            codeMetricsStore.save(snapshot, request.planId());

            String summary = snapshot.formatMarkdownComparison(null);
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);

            LOG.infof("Metrics job %s complete: %d methods, %d above threshold (CC>%d), avg=%.2f",
                    job.getJobId(), snapshot.totalMethods(), snapshot.methodsAboveThreshold(),
                    snapshot.threshold(), snapshot.avgComplexity());

        } catch (Exception e) {
            failMetrics(job, "Unexpected error in metrics job: " + e.getMessage());
        }
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
            failHook(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);

            if (request.commitDirect()) {
                try {
                    workspace.cloneRepo(authUrl, request.targetBranch(), jobTimeoutMinutes);
                } catch (Exception e) {
                    failHook(job, "Clone failed: " + e.getMessage());
                    return;
                }
            } else {
                try {
                    workspace.cloneAndCreateBranch(authUrl, request.targetBranch(),
                            request.branchName(), jobTimeoutMinutes);
                } catch (Exception e) {
                    failHook(job, "Clone/branch failed: " + e.getMessage());
                    return;
                }
            }

            configureGitIfNeeded(workspace);

            RunFixRequest fixRequest = new RunFixRequest(
                    request.repoUrl(), request.branchName(), null,
                    request.prompt(), request.targetBranch(), null, null,
                    request.ruleNames(), request.extraRules(), null);

            String systemPrompt = promptBuilder.buildRunFixPrompt(fixRequest, request.prompt(), workspace, "");

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                failHook(job, "Agent loop error: " + e.getMessage());
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
                failHook(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary("Hook '" + request.hookName() + "' completed with no changes needed.");
                jobStore.archive(job);
                LOG.infof("Hook job %s completed: no changes made", job.getJobId());
                teamsNotifier.sendNotification(buildHookResult(job, true));
                return;
            }

            try {
                workspace.push(pushBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failHook(job, "Push failed: " + e.getMessage());
                return;
            }

            if (request.commitDirect()) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary(summary);
                jobStore.archive(job);
                LOG.infof("Hook job %s completed: committed directly to %s", job.getJobId(), pushBranch);
                teamsNotifier.sendNotification(buildHookResult(job, true));
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
                    teamsNotifier.sendNotification(buildHookResult(job, true));
                } catch (Exception e) {
                    failHook(job, "Create PR failed: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            failHook(job, "Unexpected error in hook execution: " + e.getMessage());
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

    private void failGenerateDocs(JobRecord job, String message) {
        LOG.errorf("GenerateDocs job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        RunResult result = buildGenerateDocsResult(job, false);
        teamsNotifier.sendNotification(result);

        GenerateDocsRequest request = job.getGenerateDocsRequest();
        if (request != null) {
            n8nNotifier.sendResult(resolveWebhookUrl(request.n8nWebhookUrl()), result);
        }
    }

    private void failSyncConfluence(JobRecord job, String message) {
        LOG.errorf("SyncConfluence job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
    }

    private void failHook(JobRecord job, String message) {
        LOG.errorf("Hook job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        RunResult result = buildHookResult(job, false);
        teamsNotifier.sendNotification(result);
    }

    private void failMetrics(JobRecord job, String message) {
        LOG.errorf("Metrics job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);

        teamsNotifier.sendNotification(buildMetricsResult(job, false));
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

        teamsNotifier.sendNotification(buildFixCommentResult(job, false));

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
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                req.jiraKey(), req.repoUrl(), req.branchName(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    private RunResult buildReviewResult(JobRecord job, boolean success) {
        ReviewPrRequest req = job.getReviewRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                req.jiraKey() != null ? req.jiraKey() : "",
                req.repoUrl(),
                "PR-" + req.prId(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                0, 0);
    }

    private RunResult buildGenerateTestsResult(JobRecord job, boolean success) {
        GenerateTestsRequest req = job.getGenerateTestsRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                req.jiraKey() != null ? req.jiraKey() : "",
                req.repoUrl(),
                req.branchName(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    private RunResult buildGenerateDocsResult(JobRecord job, boolean success) {
        GenerateDocsRequest req = job.getGenerateDocsRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                "",
                req != null ? req.repoUrl() : "",
                req != null ? req.branchName() : "",
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    private RunResult buildFixPrResult(JobRecord job, boolean success) {
        FixPrRequest req = job.getFixPrRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                req.jiraKey() != null ? req.jiraKey() : "",
                req.repoUrl(),
                "fix-pr-" + req.prId(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    private RunResult buildHookResult(JobRecord job, boolean success) {
        HookJobRequest req = job.getHookRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                "",
                req.repoUrl(),
                req.branchName(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    private RunResult buildMetricsResult(JobRecord job, boolean success) {
        MetricsJobRequest req = job.getMetricsRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                "",
                req != null ? req.repoUrl() : "",
                req != null ? req.branch() : "",
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                0, 0);
    }

    private RunResult buildFixCommentResult(JobRecord job, boolean success) {
        ReplyCommentRequest req = job.getReplyRequest();
        return new RunResult(
                job.getJobId(), job.getJobType().name(), success ? "SUCCESS" : "FAILED",
                "",
                req != null ? req.repoUrl() : "",
                req != null ? "PR-" + req.prId() : "",
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged());
    }

    // ─── Utilities ──────────────────────────────────────────────────────

    /**
     * Renders any pending diagram PNGs, uploads them via the platform's file-hosting API,
     * substitutes the real URLs into the summary markdown, then posts or updates the
     * PR summary comment. Falls back to mermaid.ink URLs if rendering or upload fails.
     */
    private void postOrUpdatePrSummary(RepoCoordinates coords, String prId,
                                       List<AgentComment> existingComments,
                                       PrSummaryGenerator.SummaryResult summaryResult,
                                       String jobId) {
        String org = coords.organization();
        String project = coords.project();
        String repo = coords.repository();

        String body = resolveDiagramPlaceholders(summaryResult, org, repo);

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

    /**
     * For each {@link PrSummaryGenerator.PendingDiagram} in the result, renders the Mermaid
     * source to PNG and uploads it via {@link com.eneve.agent.scm.GitPlatformService#uploadDownload}.
     * On success, replaces the placeholder URL with the real download URL.
     * On failure (mmdc not installed, upload error), falls back to a mermaid.ink URL so the
     * comment still contains a working image link.
     */
    private String resolveDiagramPlaceholders(PrSummaryGenerator.SummaryResult summaryResult,
                                              String org, String repo) {
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
                String encoded = java.util.Base64.getEncoder()
                        .encodeToString(diagram.mermaidSource().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                resolvedUrl = "https://mermaid.ink/img/base64:" + encoded;
            }

            body = body.replace(diagram.placeholder(), resolvedUrl);
        }
        return body;
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
