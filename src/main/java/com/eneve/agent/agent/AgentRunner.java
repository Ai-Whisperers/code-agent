package com.eneve.agent.agent;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.diff.DiffFormatter;
import com.eneve.agent.diff.DiffParser;
import com.eneve.agent.diff.ParsedDiffFile;
import com.eneve.agent.diff.ReviewPromptResult;

import com.eneve.agent.bitbucket.AgentComment;
import com.eneve.agent.bitbucket.BitbucketCloudService;
import com.eneve.agent.linter.LinterFinding;
import com.eneve.agent.linter.LinterResult;
import com.eneve.agent.linter.LinterService;
import com.eneve.agent.bitbucket.ThreadComment;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.FixPrRequest;
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
import com.eneve.agent.rules.CursorRulesLoader;
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
    @Inject CursorRulesLoader rulesLoader;
    @Inject GuardrailConfig guardrails;
    @Inject JiraService jiraService;
    @Inject BitbucketCloudService bitbucketService;
    @Inject TeamsNotifier teamsNotifier;
    @Inject N8nWebhookNotifier n8nNotifier;
    @Inject CommentStore commentStore;
    @Inject LinterService linterService;

    @ConfigProperty(name = "bitbucket.user")
    String bbUser;

    @ConfigProperty(name = "bitbucket.app.password")
    String bbAppPassword;

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

    public void execute(JobRecord job) {
        RunFixRequest request = job.getRequest();
        job.setStatus(JobStatus.RUNNING);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            fail(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            // 1. Clone the repo
            String authUrl = coords.httpsCloneUrl(bbUser, bbAppPassword);
            String maskedUrl = "https://" + bbUser + ":****@bitbucket.org/"
                    + coords.workspace() + "/" + coords.repoSlug() + ".git";
            LOG.infof("Cloning %s (branch: %s)", maskedUrl, request.branchName());
            try {
                workspace.cloneRepo(authUrl, request.branchName(), jobTimeoutMinutes);
            } catch (Exception e) {
                LOG.infof("Branch '%s' not found on remote, trying clone from '%s' and create branch",
                        request.branchName(), request.targetBranchOrDefault());
                try {
                    workspace.cloneAndCreateBranch(authUrl, request.targetBranchOrDefault(),
                            request.branchName(), jobTimeoutMinutes);
                } catch (Exception e2) {
                    fail(job, "Clone failed: " + e2.getMessage());
                    return;
                }
            }

            // 1b. Configure git author for commits
            if (!gitAuthorEmail.isBlank()) {
                workspace.configureAuthor(gitAuthorName, gitAuthorEmail);
            }

            // 2. Run baseline linter scan (before any changes)
            List<LinterResult> linterBaseline = Collections.emptyList();
            String baselineSummary = "";
            if (linterService.getConfig().isEnabled()) {
                LOG.info("Running baseline linter scan...");
                linterBaseline = linterService.runAll(workspace.getRoot());
                baselineSummary = linterService.formatSummary(linterBaseline);
                LOG.infof("Baseline linter scan complete: %s", baselineSummary);
            }

            // 3. Resolve prompt — use JIRA description if prompt is empty
            String effectivePrompt = request.prompt();
            if (effectivePrompt == null || effectivePrompt.isBlank()) {
                LOG.infof("No prompt provided, fetching JIRA issue %s for task description", request.jiraKey());
                try {
                    effectivePrompt = jiraService.fetchIssuePrompt(request.jiraKey());
                } catch (Exception e) {
                    LOG.warnf("Failed to fetch JIRA issue: %s", e.getMessage());
                }
                if (effectivePrompt == null || effectivePrompt.isBlank()) {
                    fail(job, "No prompt provided and could not fetch JIRA issue description for " + request.jiraKey());
                    return;
                }
                LOG.infof("Using JIRA description as prompt: %s",
                        effectivePrompt.length() > 200 ? effectivePrompt.substring(0, 200) + "..." : effectivePrompt);
            }

            // 4. JIRA: comment started
            safeJira(() -> jiraService.commentStarted(request.jiraKey(), request.branchName()));

            // 5. Load Cursor rules and build system prompt
            String systemPrompt = buildSystemPrompt(request, effectivePrompt, workspace, baselineSummary);

            // 6. Run agentic loop
            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                fail(job, "Agent loop error: " + e.getMessage());
                return;
            }

            // 7. Run post-change linter scan and fix loop
            if (linterService.getConfig().isEnabled()) {
                int maxLintFixes = linterService.getConfig().getMaxFixIterations();
                for (int lintIter = 0; lintIter < maxLintFixes; lintIter++) {
                    LOG.infof("Linter delta scan iteration %d/%d", lintIter + 1, maxLintFixes);
                    List<LinterResult> current = linterService.runAll(workspace.getRoot());
                    List<LinterFinding> newIssues = linterService.findNewIssues(linterBaseline, current);

                    if (newIssues.isEmpty()) {
                        LOG.info("No new linter issues introduced — linter gate passed");
                        break;
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
                            break;
                        }
                    } else {
                        String issueList = linterService.buildFixPrompt(newIssues);
                        LOG.warnf("Linter fix iterations exhausted with %d remaining issues", newIssues.size());
                        if (linterService.getConfig().isFailOnNewIssues()) {
                            fail(job, "New linter/SAST issues introduced and could not be auto-fixed:\n" + issueList);
                            return;
                        }
                        LOG.warn("fail-on-new-issues is false — continuing despite new linter issues");
                    }
                }
            }

            // 8. Run mvn test
            try {
                runBuildValidation(workspace);
            } catch (Exception e) {
                fail(job, "Build validation failed: " + e.getMessage());
                return;
            }

            // 6. Commit and push
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

            // 7. Enforce diff guardrails
            int filesChanged;
            int linesChanged;
            try {
                filesChanged = workspace.countFilesChanged();
                linesChanged = workspace.countLinesChanged();
            } catch (Exception e) {
                filesChanged = 0;
                linesChanged = 0;
            }

            if (filesChanged > guardrails.getMaxFilesChanged()) {
                fail(job, "Too many files changed: " + filesChanged + " (max: " + guardrails.getMaxFilesChanged() + ")");
                return;
            }
            if (linesChanged > guardrails.getMaxLinesChanged()) {
                fail(job, "Too many lines changed: " + linesChanged + " (max: " + guardrails.getMaxLinesChanged() + ")");
                return;
            }

            job.setFilesChanged(filesChanged);
            job.setLinesChanged(linesChanged);

            // 8. Create pull request
            String prUrl;
            String prId;
            try {
                String title = request.jiraKey() + ": Automated fix";
                String description = "**Automated PR created by Code Agent Runner**\n\n"
                        + "JIRA: " + request.jiraKey() + "\n\n" + summary;
                String[] prResult = bitbucketService.createPullRequest(
                        coords.workspace(), coords.repoSlug(),
                        request.branchName(), request.targetBranchOrDefault(),
                        title, description
                );
                prUrl = prResult[0];
                prId = prResult[1];
            } catch (Exception e) {
                fail(job, "Create PR failed: " + e.getMessage());
                return;
            }

            // 9. Update job record
            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary(summary);
            job.setPrUrl(prUrl);
            job.setPrId(prId);

            // 10. JIRA: comment success, transition, worklog
            safeJira(() -> jiraService.commentSuccess(request.jiraKey(), prUrl, summary));
            safeJira(() -> jiraService.transitionToInReview(request.jiraKey()));
            safeJira(() -> jiraService.addWorklog(request.jiraKey(), null));

            // 11. Notify
            RunResult result = buildResult(job, true);
            teamsNotifier.sendNotification(result);
            String webhookUrl = resolveN8nUrl(request);
            n8nNotifier.sendResult(webhookUrl, result);

            LOG.infof("Job %s completed successfully. PR: %s", job.getJobId(), prUrl);

        } catch (Exception e) {
            fail(job, "Unexpected error: " + e.getMessage());
        }
    }

    public void executeReview(JobRecord job) {
        ReviewPrRequest request = job.getReviewRequest();
        job.setStatus(JobStatus.RUNNING);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failReview(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            // 1. Fetch PR metadata from Bitbucket to resolve branches
            java.util.Map<String, String> prInfo;
            try {
                prInfo = bitbucketService.getPullRequestInfo(
                        coords.workspace(), coords.repoSlug(), request.prId());
            } catch (Exception e) {
                failReview(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String targetBranch = request.targetBranch() != null && !request.targetBranch().isBlank()
                    ? request.targetBranch() : prInfo.get("destinationBranch");
            String prTitle = prInfo.get("title");
            job.setPrId(request.prId());

            // 2. Clone the source branch
            String authUrl = coords.httpsCloneUrl(bbUser, bbAppPassword);
            LOG.infof("Review: cloning %s/%s branch %s for PR #%s",
                    coords.workspace(), coords.repoSlug(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failReview(job, "Clone failed: " + e.getMessage());
                return;
            }

            // 3. Fetch existing agent comments for incremental review
            List<AgentComment> existingAgentComments;
            try {
                existingAgentComments = bitbucketService.getAgentPrComments(
                        coords.workspace(), coords.repoSlug(), request.prId());
                LOG.infof("Review: found %d existing agent comments on PR #%s",
                        existingAgentComments.size(), request.prId());
            } catch (Exception e) {
                LOG.warnf("Failed to fetch existing agent comments (non-fatal): %s", e.getMessage());
                existingAgentComments = Collections.emptyList();
            }

            // 4. Fetch the target branch and compute diff (incremental if possible)
            String diff;
            try {
                workspace.fetchBranch(targetBranch, jobTimeoutMinutes);

                String lastReviewedSha = extractLastReviewedSha(existingAgentComments);
                if (lastReviewedSha != null && workspace.objectExists(lastReviewedSha)) {
                    LOG.infof("Review: incremental diff from previously reviewed commit %s", lastReviewedSha);
                    diff = workspace.getDiffFromCommit(lastReviewedSha);
                    if (diff == null || diff.isBlank()) {
                        LOG.info("Review: incremental diff is empty, falling back to full diff");
                        diff = workspace.getDiff(targetBranch);
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

            // 5. JIRA: comment started (if JIRA key provided)
            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentStarted(request.jiraKey(),
                        "PR review for #" + request.prId()));
            }

            // 6. Build review system prompt (parse diff, annotate with line numbers, truncate at file boundaries)
            ReviewPromptResult promptResult = buildReviewSystemPrompt(request, prTitle, targetBranch,
                    diff, existingAgentComments, workspace);

            // 7. Run agentic loop (read-only tools)
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

            // 8. Parse structured review and post inline + summary comments (with dedup + line validation)
            String reviewSummary = postReviewComments(reviewOutput, coords, request.prId(),
                    existingAgentComments, headSha, job.getJobId(), promptResult.commentableLines());

            // 9. Update job record
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(reviewSummary);
            job.setPrUrl(prInfo.getOrDefault("prUrl", ""));

            // 10. JIRA comment (optional)
            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentSuccess(request.jiraKey(),
                        "PR #" + request.prId(), "Code review completed."));
            }

            // 11. Notify
            RunResult result = buildReviewResult(job, true);
            teamsNotifier.sendNotification(result);
            String webhookUrl = (request.n8nWebhookUrl() != null && !request.n8nWebhookUrl().isBlank())
                    ? request.n8nWebhookUrl() : defaultN8nWebhookUrl;
            n8nNotifier.sendResult(webhookUrl, result);

            LOG.infof("Review job %s completed for PR #%s", job.getJobId(), request.prId());

        } catch (Exception e) {
            failReview(job, "Unexpected error: " + e.getMessage());
        }
    }

    public void executeFixPr(JobRecord job) {
        FixPrRequest request = job.getFixPrRequest();
        job.setStatus(JobStatus.RUNNING);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failFixPr(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            // 1. Fetch PR metadata from Bitbucket
            java.util.Map<String, String> prInfo;
            try {
                prInfo = bitbucketService.getPullRequestInfo(
                        coords.workspace(), coords.repoSlug(), request.prId());
            } catch (Exception e) {
                failFixPr(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String targetBranch = prInfo.get("destinationBranch");
            String prTitle = prInfo.get("title");
            job.setPrId(request.prId());

            // 2. Fetch review comments from the PR
            List<String> reviewComments;
            try {
                reviewComments = bitbucketService.getPullRequestComments(
                        coords.workspace(), coords.repoSlug(), request.prId());
            } catch (Exception e) {
                failFixPr(job, "Failed to fetch PR comments: " + e.getMessage());
                return;
            }

            if (reviewComments.isEmpty()) {
                failFixPr(job, "No review comments found on PR #" + request.prId() + ". Nothing to fix.");
                return;
            }

            // 3. Clone the source branch
            String authUrl = coords.httpsCloneUrl(bbUser, bbAppPassword);
            LOG.infof("Fix-PR: cloning %s/%s branch %s for PR #%s",
                    coords.workspace(), coords.repoSlug(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failFixPr(job, "Clone failed: " + e.getMessage());
                return;
            }

            // 3b. Configure git author
            if (!gitAuthorEmail.isBlank()) {
                workspace.configureAuthor(gitAuthorName, gitAuthorEmail);
            }

            // 4. Fetch target branch and compute diff for context
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

            // 5. JIRA: comment started (if key provided)
            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentStarted(request.jiraKey(),
                        "Auto-fixing review comments on PR #" + request.prId()));
            }

            // 6. Build system prompt from review comments + diff
            String systemPrompt = buildFixPrSystemPrompt(request, prTitle, sourceBranch,
                    targetBranch, diff, reviewComments, workspace);

            // 7. Run agentic loop with full tools (read + write)
            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                failFixPr(job, "Agent loop error: " + e.getMessage());
                return;
            }

            // 8. Run build validation
            try {
                runBuildValidation(workspace);
            } catch (Exception e) {
                failFixPr(job, "Build validation failed: " + e.getMessage());
                return;
            }

            // 9. Create new branch, commit, and push
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

            // 10. Enforce diff guardrails
            int filesChanged;
            int linesChanged;
            try {
                filesChanged = workspace.countFilesChanged();
                linesChanged = workspace.countLinesChanged();
            } catch (Exception e) {
                filesChanged = 0;
                linesChanged = 0;
            }

            if (filesChanged > guardrails.getMaxFilesChanged()) {
                failFixPr(job, "Too many files changed: " + filesChanged
                        + " (max: " + guardrails.getMaxFilesChanged() + ")");
                return;
            }
            if (linesChanged > guardrails.getMaxLinesChanged()) {
                failFixPr(job, "Too many lines changed: " + linesChanged
                        + " (max: " + guardrails.getMaxLinesChanged() + ")");
                return;
            }

            job.setFilesChanged(filesChanged);
            job.setLinesChanged(linesChanged);

            // 11. Create a new PR targeting the original PR's source branch
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
                String[] prResult = bitbucketService.createPullRequest(
                        coords.workspace(), coords.repoSlug(),
                        fixBranch, sourceBranch,
                        title, description
                );
                prUrl = prResult[0];
                newPrId = prResult[1];
            } catch (Exception e) {
                failFixPr(job, "Create PR failed: " + e.getMessage());
                return;
            }

            // 12. Update job record
            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary(summary);
            job.setPrUrl(prUrl);
            job.setPrId(newPrId);

            // 13. Comment on the original PR linking to the fix PR
            safeComment(() -> bitbucketService.addPrComment(
                    coords.workspace(), coords.repoSlug(), request.prId(),
                    "Code Agent has created a fix PR for the review comments: " + prUrl));

            // 14. JIRA (optional)
            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentSuccess(request.jiraKey(), prUrl, summary));
                safeJira(() -> jiraService.addWorklog(request.jiraKey(), null));
            }

            // 15. Notify
            RunResult result = buildFixPrResult(job, true);
            teamsNotifier.sendNotification(result);
            String webhookUrl = (request.n8nWebhookUrl() != null && !request.n8nWebhookUrl().isBlank())
                    ? request.n8nWebhookUrl() : defaultN8nWebhookUrl;
            n8nNotifier.sendResult(webhookUrl, result);

            LOG.infof("Fix-PR job %s completed. Fix PR: %s", job.getJobId(), prUrl);

        } catch (Exception e) {
            failFixPr(job, "Unexpected error: " + e.getMessage());
        }
    }

    private String buildFixPrSystemPrompt(FixPrRequest request, String prTitle,
                                          String sourceBranch, String targetBranch,
                                          String diff, List<String> reviewComments,
                                          WorkspaceContext workspace) {
        String rulesRepoUrl = (request.rulesRepoUrl() != null && !request.rulesRepoUrl().isBlank())
                ? request.rulesRepoUrl() : defaultRulesRepoUrl;
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        StringBuilder commentsSection = new StringBuilder();
        for (int i = 0; i < reviewComments.size(); i++) {
            commentsSection.append(i + 1).append(". ").append(reviewComments.get(i)).append("\n");
        }

        String fixInstructions = """
                You are fixing review comments on a pull request.
                Your goal is to address each review comment by making the appropriate code changes.

                ## PR Information
                - **Title**: %s
                - **Source branch**: %s
                - **Target branch**: %s

                ## Review Comments to Address
                %s

                ## Current PR Diff (for context)
                ```diff
                %s
                ```

                ## Instructions
                - Read and understand each review comment carefully.
                - Comments prefixed with [file:line] indicate the exact location of the issue.
                - Use `read_file` to examine the current code around each comment.
                - Use `write_file` to make the necessary fixes.
                - Address ALL review comments, not just some of them.
                - Only change code that is relevant to the review comments. Do not refactor unrelated code.
                - After making changes, run: mvn test (or gradle test if build.gradle is present)
                - If tests fail, fix the test failures before completing.
                - Provide a summary of all changes you made.
                """.formatted(
                prTitle != null ? prTitle : "(untitled)",
                sourceBranch,
                targetBranch,
                commentsSection.toString(),
                diff.isEmpty() ? "(diff not available)" : diff
        );

        String guardrailText = """
                You MUST follow these rules without exception:
                - Do NOT modify files under these paths: %s
                - Only run allowed commands: %s
                - Do NOT modify more than %d files or %d lines
                - After making changes, run: mvn test (or gradle test if build.gradle is present)
                - If tests fail, report the failure and do NOT proceed
                - Stop as soon as all review comments are addressed. Do not refactor unrelated code.
                - Never read or write files outside the repository root.
                """.formatted(
                String.join(", ", guardrails.getBlockedPaths()),
                String.join(", ", guardrails.getAllowedCommands()),
                guardrails.getMaxFilesChanged(),
                guardrails.getMaxLinesChanged()
        );

        return rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, fixInstructions);
    }

    private void failFixPr(JobRecord job, String message) {
        LOG.errorf("Fix-PR job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);

        FixPrRequest request = job.getFixPrRequest();
        if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
            safeJira(() -> jiraService.commentFailure(request.jiraKey(), message));
        }

        RunResult result = buildFixPrResult(job, false);
        teamsNotifier.sendNotification(result);
        String webhookUrl = (request.n8nWebhookUrl() != null && !request.n8nWebhookUrl().isBlank())
                ? request.n8nWebhookUrl() : defaultN8nWebhookUrl;
        n8nNotifier.sendResult(webhookUrl, result);
    }

    private RunResult buildFixPrResult(JobRecord job, boolean success) {
        FixPrRequest req = job.getFixPrRequest();
        return new RunResult(
                job.getJobId(), success,
                req.jiraKey() != null ? req.jiraKey() : "",
                "fix-pr-" + req.prId(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged()
        );
    }

    // ─── Conversational Reply ─────────────────────────────────────────────

    /**
     * Handle a REPLY job: respond in-thread to a developer's reply on one of our review comments.
     */
    public void executeReply(JobRecord job) {
        ReplyCommentRequest request = job.getReplyRequest();
        job.setStatus(JobStatus.RUNNING);
        job.setPrId(request.prId());

        Optional<CommentContext> ctxOpt = commentStore.find(request.parentCommentId());
        if (ctxOpt.isEmpty()) {
            failReply(job, "Original comment context not found for comment #" + request.parentCommentId());
            return;
        }
        CommentContext ctx = ctxOpt.get();

        String repoUrl = "https://bitbucket.org/" + request.workspace() + "/" + request.repoSlug() + ".git";
        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(repoUrl);
        } catch (IllegalArgumentException e) {
            failReply(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            // 1. Resolve source branch from PR metadata
            java.util.Map<String, String> prInfo;
            try {
                prInfo = bitbucketService.getPullRequestInfo(
                        coords.workspace(), coords.repoSlug(), request.prId());
            } catch (Exception e) {
                failReply(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");

            // 2. Clone the source branch for file context
            String authUrl = coords.httpsCloneUrl(bbUser, bbAppPassword);
            LOG.infof("Reply: cloning %s/%s branch %s for comment thread on PR #%s",
                    coords.workspace(), coords.repoSlug(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failReply(job, "Clone failed: " + e.getMessage());
                return;
            }

            // 3. Fetch the full comment thread
            List<ThreadComment> thread;
            try {
                thread = bitbucketService.getCommentThread(
                        request.workspace(), request.repoSlug(), request.prId(),
                        request.parentCommentId());
            } catch (Exception e) {
                LOG.warnf("Failed to fetch comment thread (non-fatal): %s", e.getMessage());
                thread = Collections.emptyList();
            }

            // 4. Build conversational prompt
            String systemPrompt = buildReplySystemPrompt(ctx, thread, request.humanMessage());

            // 5. Run agentic loop with read-only tools
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

            // 6. Post the reply in-thread on Bitbucket
            try {
                long replyCommentId = bitbucketService.replyToComment(
                        request.workspace(), request.repoSlug(), request.prId(),
                        request.parentCommentId(), replyText);

                // Store the reply comment so nested follow-ups also work
                if (replyCommentId > 0) {
                    commentStore.save(replyCommentId, new CommentContext(
                            request.prId(), request.workspace(), request.repoSlug(),
                            ctx.filePath(), ctx.line(), ctx.category(), ctx.severity(),
                            ctx.findingText(), ctx.reviewJobId()));
                }
            } catch (Exception e) {
                failReply(job, "Failed to post reply: " + e.getMessage());
                return;
            }

            job.setStatus(JobStatus.SUCCESS);
            job.setSummary("Replied to comment thread on PR #" + request.prId());
            LOG.infof("Reply job %s completed for comment #%d on PR #%s",
                    job.getJobId(), request.parentCommentId(), request.prId());

        } catch (Exception e) {
            failReply(job, "Unexpected error: " + e.getMessage());
        }
    }

    private String buildReplySystemPrompt(CommentContext ctx, List<ThreadComment> thread,
                                          String latestHumanMessage) {
        StringBuilder threadSection = new StringBuilder();
        for (ThreadComment tc : thread) {
            String role = tc.isAgent() ? "You (AI Reviewer)" : tc.author();
            threadSection.append("**").append(role).append("**: ").append(tc.content()).append("\n\n");
        }

        return """
                You are an AI code reviewer engaged in a conversation on a Bitbucket pull request.
                A developer has replied to one of your review comments and you need to respond.

                ## Original Finding
                - **File**: %s (line %d)
                - **Severity**: %s | **Category**: %s
                - **Your original comment**: %s

                ## Conversation Thread
                %s

                ## Latest Message from Developer
                %s

                ## Instructions
                - Respond helpfully and concisely to the developer's message.
                - If they ask for clarification, explain your reasoning in more detail with code references.
                - If they disagree, consider their argument carefully. Acknowledge if they make a valid point.
                - If they provide additional context that changes your assessment, say so explicitly.
                - You can use `read_file` and `list_files` to examine the code for additional context.
                - Keep your response focused and conversational — this is a thread reply, not a full review.
                - Do NOT output JSON. Write your response in natural language (markdown is fine).
                - Your final message will be posted directly as a Bitbucket comment, so make it clean.
                """.formatted(
                ctx.filePath() != null ? ctx.filePath() : "(general)",
                ctx.line(),
                ctx.severity() != null ? ctx.severity() : "INFO",
                ctx.category() != null ? ctx.category() : "General",
                ctx.findingText(),
                threadSection.toString().isBlank() ? "(no previous thread messages)" : threadSection,
                latestHumanMessage
        );
    }

    private void failReply(JobRecord job, String message) {
        LOG.errorf("Reply job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
    }

    // ─── Fix from Comment ───────────────────────────────────────────────

    /**
     * Handle a FIX_COMMENT job: implement the suggested fix from a review comment,
     * commit directly to the PR's source branch, and reply in-thread with the commit SHA.
     */
    public void executeFixComment(JobRecord job) {
        ReplyCommentRequest request = job.getReplyRequest();
        job.setStatus(JobStatus.RUNNING);
        job.setPrId(request.prId());

        Optional<CommentContext> ctxOpt = commentStore.find(request.parentCommentId());
        if (ctxOpt.isEmpty()) {
            failFixComment(job, request, "Original comment context not found for comment #"
                    + request.parentCommentId());
            return;
        }
        CommentContext ctx = ctxOpt.get();

        String repoUrl = "https://bitbucket.org/" + request.workspace() + "/" + request.repoSlug() + ".git";
        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(repoUrl);
        } catch (IllegalArgumentException e) {
            failFixComment(job, request, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            // 1. Fetch PR metadata
            java.util.Map<String, String> prInfo;
            try {
                prInfo = bitbucketService.getPullRequestInfo(
                        coords.workspace(), coords.repoSlug(), request.prId());
            } catch (Exception e) {
                failFixComment(job, request, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");

            // 2. Clone the source branch
            String authUrl = coords.httpsCloneUrl(bbUser, bbAppPassword);
            LOG.infof("FixComment: cloning %s/%s branch %s for comment fix on PR #%s",
                    coords.workspace(), coords.repoSlug(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failFixComment(job, request, "Clone failed: " + e.getMessage());
                return;
            }

            // 2b. Configure git author
            if (!gitAuthorEmail.isBlank()) {
                workspace.configureAuthor(gitAuthorName, gitAuthorEmail);
            }

            // 3. Fetch the comment thread for context
            List<ThreadComment> thread;
            try {
                thread = bitbucketService.getCommentThread(
                        request.workspace(), request.repoSlug(), request.prId(),
                        request.parentCommentId());
            } catch (Exception e) {
                LOG.warnf("Failed to fetch comment thread (non-fatal): %s", e.getMessage());
                thread = Collections.emptyList();
            }

            // 4. Build fix prompt
            String systemPrompt = buildFixCommentSystemPrompt(ctx, thread, request.humanMessage());

            // 5. Run agentic loop with full tools (read + write)
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

            // 6. Run build validation
            try {
                runBuildValidation(workspace);
            } catch (Exception e) {
                failFixComment(job, request, "Build validation failed: " + e.getMessage());
                return;
            }

            // 7. Commit and push to source branch
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

            // 8. Enforce guardrails
            int filesChanged;
            int linesChanged;
            try {
                filesChanged = workspace.countFilesChanged();
                linesChanged = workspace.countLinesChanged();
            } catch (Exception e) {
                filesChanged = 0;
                linesChanged = 0;
            }

            if (filesChanged > guardrails.getMaxFilesChanged()) {
                failFixComment(job, request, "Too many files changed: " + filesChanged
                        + " (max: " + guardrails.getMaxFilesChanged() + ")");
                return;
            }
            if (linesChanged > guardrails.getMaxLinesChanged()) {
                failFixComment(job, request, "Too many lines changed: " + linesChanged
                        + " (max: " + guardrails.getMaxLinesChanged() + ")");
                return;
            }

            job.setFilesChanged(filesChanged);
            job.setLinesChanged(linesChanged);

            try {
                workspace.push(sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                failFixComment(job, request, "Push failed: " + e.getMessage());
                return;
            }

            // 9. Reply in-thread confirming the fix
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
                long replyCommentId = bitbucketService.replyToComment(
                        request.workspace(), request.repoSlug(), request.prId(),
                        request.parentCommentId(), replyText);
                if (replyCommentId > 0) {
                    commentStore.save(replyCommentId, new CommentContext(
                            request.prId(), request.workspace(), request.repoSlug(),
                            ctx.filePath(), ctx.line(), ctx.category(), ctx.severity(),
                            ctx.findingText(), ctx.reviewJobId()));
                }
            } catch (Exception e) {
                LOG.warnf("Failed to post fix confirmation reply (non-fatal): %s", e.getMessage());
            }

            // 10. Update job record
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            LOG.infof("FixComment job %s completed for comment #%d on PR #%s",
                    job.getJobId(), request.parentCommentId(), request.prId());

        } catch (Exception e) {
            failFixComment(job, request, "Unexpected error: " + e.getMessage());
        }
    }

    private String buildFixCommentSystemPrompt(CommentContext ctx, List<ThreadComment> thread,
                                               String humanMessage) {
        StringBuilder threadSection = new StringBuilder();
        for (ThreadComment tc : thread) {
            String role = tc.isAgent() ? "You (AI Reviewer)" : tc.author();
            threadSection.append("**").append(role).append("**: ").append(tc.content()).append("\n\n");
        }

        return """
                You are an AI code reviewer implementing a fix for a specific finding on a pull request.
                A developer has asked you to apply the suggested change.

                ## Finding to Fix
                - **File**: %s (line %d)
                - **Severity**: %s | **Category**: %s
                - **Your original review comment**: %s

                ## Developer's Request
                %s

                ## Conversation Thread
                %s

                ## Instructions
                - Fix ONLY the issue described in the finding above.
                - Use `read_file` to examine the current code around the issue.
                - Use `write_file` to apply the fix.
                - Do NOT change unrelated code. Keep the fix as minimal and targeted as possible.
                - After fixing, run: mvn test (or gradle test if build.gradle is present)
                - If tests fail, fix the test failures before completing.
                - Provide a brief summary of what you changed.
                """.formatted(
                ctx.filePath() != null ? ctx.filePath() : "(general)",
                ctx.line(),
                ctx.severity() != null ? ctx.severity() : "INFO",
                ctx.category() != null ? ctx.category() : "General",
                ctx.findingText(),
                humanMessage != null ? humanMessage : "(no additional instructions)",
                threadSection.toString().isBlank() ? "(no previous thread)" : threadSection
        );
    }

    private void failFixComment(JobRecord job, ReplyCommentRequest request, String message) {
        LOG.errorf("FixComment job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);

        // Reply in-thread to inform the developer the fix failed
        try {
            bitbucketService.replyToComment(
                    request.workspace(), request.repoSlug(), request.prId(),
                    request.parentCommentId(),
                    "Failed to apply fix: " + message);
        } catch (Exception e) {
            LOG.warnf("Failed to post fix failure reply (non-fatal): %s", e.getMessage());
        }
    }

    // ─── Review Prompt ──────────────────────────────────────────────────

    private ReviewPromptResult buildReviewSystemPrompt(ReviewPrRequest request, String prTitle,
                                                       String targetBranch, String diff,
                                                       List<AgentComment> existingComments,
                                                       WorkspaceContext workspace) {
        String rulesRepoUrl = (request.rulesRepoUrl() != null && !request.rulesRepoUrl().isBlank())
                ? request.rulesRepoUrl() : defaultRulesRepoUrl;
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        String previousCommentsSection = buildPreviousCommentsSection(existingComments);

        // Parse the raw unified diff into a structured model with resolved line numbers
        List<ParsedDiffFile> parsedFiles = DiffParser.parse(diff);

        // Truncate at file boundaries instead of mid-hunk
        int maxDiffChars = 80_000;
        List<ParsedDiffFile> displayFiles = DiffFormatter.truncateAtFileBoundary(parsedFiles, maxDiffChars);
        boolean diffTruncated = displayFiles.size() < parsedFiles.size();

        // Build annotated diff with explicit new-side line numbers on every line
        String annotatedDiff = DiffFormatter.toAnnotated(displayFiles);

        // Build commentable-lines index from the full parsed set for validation
        Map<String, TreeSet<Integer>> commentableLines = DiffFormatter.buildCommentableLines(displayFiles);

        String reviewInstructions = """
                You are performing an automated code review of a pull request.
                Your goal is to review the changes for quality, correctness, and adherence to best practices.

                ## PR Information
                - **Title**: %s
                - **Target branch**: %s
                %s

                ## Review Categories
                Analyze the diff and provide findings organized in these categories:

                ### 1. Security
                - SQL injection, XSS, CSRF vulnerabilities
                - Secrets or credentials in code
                - Authentication/authorization bypasses
                - Insecure deserialization or input handling
                - Dependency vulnerabilities

                ### 2. Design Principles
                - SOLID principles adherence
                - Separation of concerns
                - Appropriate abstractions and encapsulation
                - Coupling and cohesion
                - API design consistency

                ### 3. Code Quality
                - Naming conventions and clarity
                - Cyclomatic complexity
                - Code duplication
                - Error handling and edge cases
                - Readability and maintainability
                - Magic numbers or hardcoded values

                ### 4. Testing
                - Are the changes covered by unit tests?
                - Are edge cases and error paths tested?
                - Are test assertions meaningful?
                - Are there missing test scenarios?

                ### 5. Performance
                - N+1 queries or inefficient data access
                - Unnecessary object allocations
                - Blocking calls in async contexts
                - Resource leaks (connections, streams)

                ### 6. Best Practices
                - Framework-specific patterns and conventions
                - Logging (appropriate levels, no sensitive data)
                - Input validation
                - Documentation for public APIs
                - Backward compatibility

                ## Output Format
                You MUST output your review as a JSON object inside a ```json code fence.
                Each finding will be posted as an inline comment on the exact file and line in Bitbucket.

                ```json
                {
                  "findings": [
                    {
                      "file": "src/main/java/com/example/Foo.java",
                      "line": 42,
                      "severity": "HIGH",
                      "category": "Security",
                      "description": "User input is concatenated directly into SQL query",
                      "suggestion": "Use a parameterized PreparedStatement instead"
                    }
                  ],
                  "verdict": "REQUEST_CHANGES",
                  "summary": "Brief overall assessment of the PR quality."
                }
                ```

                Field definitions:
                - **file**: exact relative path from the repo root (must match a file shown in the diff header)
                - **line**: the line number shown to the LEFT of each line in the annotated diff below. Use EXACTLY the number displayed — do not compute or guess line numbers.
                - **severity**: one of CRITICAL, HIGH, MEDIUM, LOW, INFO
                - **category**: one of Security, Design, Code Quality, Testing, Performance, Best Practices
                - **description**: clear explanation of the issue
                - **suggestion**: how to fix or improve it
                - **verdict**: one of APPROVE, REQUEST_CHANGES, COMMENT
                - **summary**: 2-4 sentence overall assessment
                %s
                ## Instructions
                - You can use `read_file` and `list_files` to examine files in the repository for context beyond what the diff shows.
                - Focus your review ONLY on the changed code in the diff. Do not review unchanged code.
                - Be constructive and specific. Provide actionable feedback.
                - Each line in the diff below is annotated with its actual line number in the new version of the file. Lines marked with `+` are added lines. Lines marked with `-` are removed lines (no line number). Use the displayed line number exactly as your `line` value.
                - If the code looks good with no issues, return an empty findings array and APPROVE verdict.
                - Do NOT modify any files. This is a read-only review.
                - Your final message MUST contain ONLY the JSON block — no extra text before or after.

                ## Diff
                %s
                ```
                %s
                ```
                """.formatted(
                prTitle != null ? prTitle : "(untitled)",
                targetBranch,
                diffTruncated ? "**Note**: The diff was truncated due to size. Focus on the portions shown.\n" : "",
                previousCommentsSection,
                diffTruncated ? "(truncated — some files omitted)\n" : "",
                annotatedDiff
        );

        String guardrailText = """
                You MUST follow these rules without exception:
                - This is a READ-ONLY review. Do NOT create, modify, or delete any files.
                - Only run read-only commands: git diff, git status, git log, ls, find, cat, grep
                - Never read or write files outside the repository root.
                """;

        String prompt = rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, reviewInstructions);
        return new ReviewPromptResult(prompt, commentableLines, diffTruncated);
    }

    private static final Pattern REVIEWED_UP_TO_PATTERN =
            Pattern.compile("<!-- agent-reviewed-up-to:([0-9a-f]{7,40}) -->");

    /**
     * Scan existing agent comments for the last-reviewed commit SHA marker.
     * Returns the SHA if found, or null.
     */
    private static String extractLastReviewedSha(List<AgentComment> existingComments) {
        for (AgentComment comment : existingComments) {
            if (comment.filePath().isEmpty() && comment.line() == 0) {
                Matcher m = REVIEWED_UP_TO_PATTERN.matcher(comment.content());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    /**
     * Build a prompt section listing inline comments the agent already posted,
     * so the LLM can avoid repeating them.
     */
    private static String buildPreviousCommentsSection(List<AgentComment> existingComments) {
        List<AgentComment> inlineComments = existingComments.stream()
                .filter(c -> !c.filePath().isEmpty() && c.line() > 0)
                .toList();

        if (inlineComments.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n## Previous Review Comments (already posted)\n");
        sb.append("Do NOT repeat these findings. Only report NEW issues in the new changes.\n\n");
        for (AgentComment c : inlineComments) {
            String summary = c.content().length() > 150
                    ? c.content().substring(0, 150) + "..."
                    : c.content();
            summary = summary.replace("\n", " ");
            sb.append("- [%s:%d] %s\n".formatted(c.filePath(), c.line(), summary));
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Parse the structured JSON review from Claude and post inline comments + overall summary.
     * Deduplicates against existing agent comments and embeds a reviewed-up-to SHA marker.
     * Falls back to posting the entire output as a general comment if JSON parsing fails.
     * Returns the overall summary text for the job record.
     */
    private String postReviewComments(String reviewOutput, RepoCoordinates coords, String prId,
                                      List<AgentComment> existingComments, String headSha,
                                      String reviewJobId,
                                      Map<String, TreeSet<Integer>> commentableLines) {
        String ws = coords.workspace();
        String slug = coords.repoSlug();

        String json = extractJsonBlock(reviewOutput);
        if (json == null) {
            LOG.warn("Review output is not structured JSON, posting as single general comment");
            safeComment(() -> bitbucketService.addPrComment(ws, slug, prId, reviewOutput));
            return reviewOutput;
        }

        // Build dedup set from existing inline comments: "file:line"
        Set<String> alreadyCommented = new HashSet<>();
        for (AgentComment existing : existingComments) {
            if (!existing.filePath().isEmpty() && existing.line() > 0) {
                alreadyCommented.add(existing.filePath() + ":" + existing.line());
            }
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

            com.fasterxml.jackson.databind.JsonNode findings = root.path("findings");
            int inlineCount = 0;
            int skippedCount = 0;
            int snappedCount = 0;
            if (findings.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode finding : findings) {
                    String file = finding.path("file").asText("");
                    int line = finding.path("line").asInt(0);
                    String severity = finding.path("severity").asText("INFO");
                    String category = finding.path("category").asText("");
                    String description = finding.path("description").asText("");
                    String suggestion = finding.path("suggestion").asText("");

                    StringBuilder comment = new StringBuilder();
                    comment.append("**[").append(severity).append("]** ");
                    if (!category.isEmpty()) {
                        comment.append("_").append(category).append("_ — ");
                    }
                    comment.append(description);
                    if (!suggestion.isEmpty()) {
                        comment.append("\n\n**Suggestion:** ").append(suggestion);
                    }

                    if (!file.isEmpty() && line > 0) {
                        // Validate and snap line number against the parsed diff index
                        String normalizedFile = normalizeDiffPath(file);
                        TreeSet<Integer> validLines = commentableLines.get(normalizedFile);
                        if (validLines == null) {
                            validLines = commentableLines.get(file);
                        }
                        if (validLines != null && !validLines.isEmpty()) {
                            int snapped = DiffFormatter.snapToNearest(validLines, line);
                            if (snapped != line) {
                                LOG.infof("Snapping line %d -> %d for %s (nearest commentable line)",
                                        line, snapped, file);
                                line = snapped;
                                snappedCount++;
                            }
                            file = normalizedFile != null
                                    && commentableLines.containsKey(normalizedFile)
                                    ? normalizedFile : file;
                        }

                        String dedupKey = file + ":" + line;
                        if (alreadyCommented.contains(dedupKey)) {
                            LOG.infof("Skipping duplicate comment at %s (already posted)", dedupKey);
                            skippedCount++;
                            continue;
                        }
                        try {
                            long commentId = bitbucketService.addInlinePrComment(ws, slug, prId,
                                    file, line, comment.toString());
                            inlineCount++;
                            if (commentId > 0) {
                                commentStore.save(commentId, new CommentContext(
                                        prId, ws, slug, file, line,
                                        category, severity, description, reviewJobId));
                            }
                        } catch (Exception e) {
                            LOG.warnf("Failed to post inline comment at %s:%d, falling back to general: %s",
                                    file, line, e.getMessage());
                            final String fallbackFile = file;
                            final int fallbackLine = line;
                            safeComment(() -> bitbucketService.addPrComment(ws, slug, prId,
                                    "**" + fallbackFile + ":" + fallbackLine + "** — " + comment));
                        }
                    } else {
                        safeComment(() -> bitbucketService.addPrComment(ws, slug, prId, comment.toString()));
                    }
                }
            }

            String verdict = root.path("verdict").asText("");
            String summary = root.path("summary").asText("");
            StringBuilder overallComment = new StringBuilder();
            overallComment.append("## Code Review Summary\n\n");
            if (!verdict.isEmpty()) {
                overallComment.append("**Verdict: ").append(verdict).append("**\n\n");
            }
            if (!summary.isEmpty()) {
                overallComment.append(summary);
            }
            overallComment.append("\n\n---\n_").append(inlineCount)
                    .append(" inline comment(s) posted on specific lines.");
            if (skippedCount > 0) {
                overallComment.append(" ").append(skippedCount)
                        .append(" duplicate(s) skipped from previous review.");
            }
            overallComment.append("_");

            if (headSha != null && !headSha.isBlank()) {
                overallComment.append("\n<!-- agent-reviewed-up-to:").append(headSha).append(" -->");
            }

            safeComment(() -> bitbucketService.addPrComment(ws, slug, prId, overallComment.toString()));

            LOG.infof("Posted %d inline comments + summary to PR #%s (%d duplicates skipped, %d lines snapped)",
                    inlineCount, prId, skippedCount, snappedCount);
            return overallComment.toString();

        } catch (Exception e) {
            LOG.warnf("Failed to parse review JSON, posting as general comment: %s", e.getMessage());
            safeComment(() -> bitbucketService.addPrComment(ws, slug, prId, reviewOutput));
            return reviewOutput;
        }
    }

    /**
     * Normalize a file path that the LLM may have prefixed with "a/", "b/", or "/".
     */
    private static String normalizeDiffPath(String path) {
        if (path == null) return null;
        if (path.startsWith("b/")) return path.substring(2);
        if (path.startsWith("a/")) return path.substring(2);
        if (path.startsWith("/")) return path.substring(1);
        return path;
    }

    /**
     * Extract a JSON block from Claude's output.
     * Looks for ```json ... ``` fences first, then tries the raw string.
     */
    private static String extractJsonBlock(String text) {
        if (text == null || text.isBlank()) return null;

        int jsonStart = text.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = text.indexOf('\n', jsonStart);
            int jsonEnd = text.indexOf("```", contentStart + 1);
            if (contentStart >= 0 && jsonEnd > contentStart) {
                return text.substring(contentStart + 1, jsonEnd).trim();
            }
        }

        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        return null;
    }

    private void safeComment(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warnf("Failed to post Bitbucket comment (non-fatal): %s", e.getMessage());
        }
    }

    private void failReview(JobRecord job, String message) {
        LOG.errorf("Review job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);

        ReviewPrRequest request = job.getReviewRequest();
        if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
            safeJira(() -> jiraService.commentFailure(request.jiraKey(), message));
        }

        RunResult result = buildReviewResult(job, false);
        teamsNotifier.sendNotification(result);
        String webhookUrl = (request.n8nWebhookUrl() != null && !request.n8nWebhookUrl().isBlank())
                ? request.n8nWebhookUrl() : defaultN8nWebhookUrl;
        n8nNotifier.sendResult(webhookUrl, result);
    }

    private RunResult buildReviewResult(JobRecord job, boolean success) {
        ReviewPrRequest req = job.getReviewRequest();
        return new RunResult(
                job.getJobId(), success,
                req.jiraKey() != null ? req.jiraKey() : "",
                "PR-" + req.prId(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                0, 0
        );
    }

    public void approve(JobRecord job) {
        String repoUrl = resolveRepoUrl(job);
        String jiraKey = resolveJiraKey(job);
        RepoCoordinates coords = RepoCoordinates.parse(repoUrl);

        try {
            bitbucketService.mergePullRequest(coords.workspace(), coords.repoSlug(), job.getPrId());
            job.setStatus(JobStatus.SUCCESS);
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
            bitbucketService.declinePullRequest(coords.workspace(), coords.repoSlug(), job.getPrId());
        } catch (Exception e) {
            LOG.warnf("Failed to decline PR for job %s: %s", job.getJobId(), e.getMessage());
        }

        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage("Rejected: " + (reason != null ? reason : "No reason provided"));
        if (jiraKey != null && !jiraKey.isBlank()) {
            safeJira(() -> jiraService.commentRejected(jiraKey, reason));
            safeJira(() -> jiraService.transitionToRejected(jiraKey));
        }
        LOG.infof("Job %s rejected", job.getJobId());
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

    private String buildSystemPrompt(RunFixRequest request, String effectivePrompt,
                                     WorkspaceContext workspace, String baselineLinterSummary) {
        String rulesRepoUrl = (request.rulesRepoUrl() != null && !request.rulesRepoUrl().isBlank())
                ? request.rulesRepoUrl() : defaultRulesRepoUrl;
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        String guardrailText = """
                You MUST follow these rules without exception:
                - Do NOT modify files under these paths: %s
                - Only run allowed commands: %s
                - Do NOT modify more than %d files or %d lines
                - After making changes, run: mvn test (or gradle test if build.gradle is present)
                - If tests fail, report the failure and do NOT proceed
                - Stop as soon as the task is complete. Do not refactor unrelated code.
                - Never read or write files outside the repository root.
                """.formatted(
                String.join(", ", guardrails.getBlockedPaths()),
                String.join(", ", guardrails.getAllowedCommands()),
                guardrails.getMaxFilesChanged(),
                guardrails.getMaxLinesChanged()
        );

        if (baselineLinterSummary != null && !baselineLinterSummary.isBlank()) {
            guardrailText += """

                    === EXISTING STATIC ANALYSIS ISSUES (do NOT make these worse) ===
                    %s
                    Do not introduce new linter/SAST violations. A post-change scan will verify this.
                    """.formatted(baselineLinterSummary);
        }

        return rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, effectivePrompt);
    }

    private void runBuildValidation(WorkspaceContext workspace) throws Exception {
        java.nio.file.Path gradleFile = workspace.getRoot().resolve("build.gradle");
        String command = java.nio.file.Files.exists(gradleFile) ? "gradle test" : "mvn test";

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
                .directory(workspace.getRoot().toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(jobTimeoutMinutes, java.util.concurrent.TimeUnit.MINUTES);

        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("Build validation timed out after " + jobTimeoutMinutes + " minutes");
        }
        if (proc.exitValue() != 0) {
            String tail = output.length() > 2000 ? output.substring(output.length() - 2000) : output;
            throw new RuntimeException("Build validation failed (exit " + proc.exitValue() + "):\n" + tail);
        }
        LOG.info("Build validation passed");
    }

    private void fail(JobRecord job, String message) {
        LOG.errorf("Job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);

        RunFixRequest request = job.getRequest();
        safeJira(() -> jiraService.commentFailure(request.jiraKey(), message));

        RunResult result = buildResult(job, false);
        teamsNotifier.sendNotification(result);
        String webhookUrl = resolveN8nUrl(request);
        n8nNotifier.sendResult(webhookUrl, result);
    }

    private RunResult buildResult(JobRecord job, boolean success) {
        RunFixRequest req = job.getRequest();
        return new RunResult(
                job.getJobId(), success, req.jiraKey(), req.branchName(),
                job.getPrUrl(), job.getSummary(), job.getErrorMessage(),
                job.getFilesChanged(), job.getLinesChanged()
        );
    }

    private String resolveN8nUrl(RunFixRequest request) {
        if (request.n8nWebhookUrl() != null && !request.n8nWebhookUrl().isBlank()) {
            return request.n8nWebhookUrl();
        }
        return defaultN8nWebhookUrl;
    }

    private void safeJira(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warnf("JIRA operation failed (non-fatal): %s", e.getMessage());
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
