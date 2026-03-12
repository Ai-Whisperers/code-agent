package com.eneve.agent.agent;

import java.util.Collections;
import java.util.List;

import com.eneve.agent.bitbucket.BitbucketCloudService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
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

            // 2. Resolve prompt — use JIRA description if prompt is empty
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

            // 3. JIRA: comment started
            safeJira(() -> jiraService.commentStarted(request.jiraKey(), request.branchName()));

            // 4. Load Cursor rules and build system prompt
            String systemPrompt = buildSystemPrompt(request, effectivePrompt, workspace);

            // 4. Run agentic loop
            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace);
            } catch (Exception e) {
                fail(job, "Agent loop error: " + e.getMessage());
                return;
            }

            // 5. Run mvn test
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

            // 3. Fetch the target branch and compute diff
            String diff;
            try {
                workspace.fetchBranch(targetBranch, jobTimeoutMinutes);
                diff = workspace.getDiff(targetBranch);
            } catch (Exception e) {
                failReview(job, "Failed to compute diff: " + e.getMessage());
                return;
            }

            if (diff == null || diff.isBlank()) {
                failReview(job, "PR has no diff against " + targetBranch + ". Nothing to review.");
                return;
            }

            // Truncate very large diffs to stay within context limits
            int maxDiffChars = 80_000;
            boolean diffTruncated = false;
            if (diff.length() > maxDiffChars) {
                diff = diff.substring(0, maxDiffChars);
                diffTruncated = true;
            }

            // 4. JIRA: comment started (if JIRA key provided)
            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentStarted(request.jiraKey(),
                        "PR review for #" + request.prId()));
            }

            // 5. Build review system prompt
            String systemPrompt = buildReviewSystemPrompt(request, prTitle, targetBranch,
                    diff, diffTruncated, workspace);

            // 6. Run agentic loop (read-only tools)
            String reviewSummary;
            try {
                reviewSummary = toolUseLoop.run(systemPrompt, workspace,
                        ToolDefinitions.readOnly(),
                        "Please review the pull request diff provided in the system prompt. "
                                + "Use the read_file and list_files tools to examine surrounding context "
                                + "when needed. Provide your complete review.");
            } catch (Exception e) {
                failReview(job, "Agent review loop error: " + e.getMessage());
                return;
            }

            // 7. Post review as Bitbucket PR comment
            try {
                bitbucketService.addPrComment(coords.workspace(), coords.repoSlug(),
                        request.prId(), reviewSummary);
            } catch (Exception e) {
                LOG.warnf("Failed to post review comment to PR #%s: %s", request.prId(), e.getMessage());
            }

            // 8. Update job record
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(reviewSummary);
            job.setPrUrl(prInfo.getOrDefault("prUrl", ""));

            // 9. JIRA comment (optional)
            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                safeJira(() -> jiraService.commentSuccess(request.jiraKey(),
                        "PR #" + request.prId(), "Code review completed."));
            }

            // 10. Notify
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

    private String buildReviewSystemPrompt(ReviewPrRequest request, String prTitle,
                                           String targetBranch, String diff,
                                           boolean diffTruncated,
                                           WorkspaceContext workspace) {
        String rulesRepoUrl = (request.rulesRepoUrl() != null && !request.rulesRepoUrl().isBlank())
                ? request.rulesRepoUrl() : defaultRulesRepoUrl;
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

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
                For each finding, provide:
                - **File and line reference** (from the diff)
                - **Severity**: CRITICAL, HIGH, MEDIUM, LOW, or INFO
                - **Category**: which of the 6 categories above
                - **Description**: clear explanation of the issue
                - **Suggestion**: how to fix or improve it

                At the end, provide an **Overall Assessment** with:
                - A summary verdict: APPROVE, REQUEST_CHANGES, or COMMENT
                - A brief overall summary of the PR quality

                ## Instructions
                - You can use `read_file` and `list_files` to examine files in the repository for context beyond what the diff shows.
                - Focus your review ONLY on the changed code in the diff. Do not review unchanged code.
                - Be constructive and specific. Provide actionable feedback.
                - If the code looks good, say so — don't invent issues.
                - Do NOT modify any files. This is a read-only review.

                ## Diff
                %s
                ```diff
                %s
                ```
                """.formatted(
                prTitle != null ? prTitle : "(untitled)",
                targetBranch,
                diffTruncated ? "**Note**: The diff was truncated due to size. Focus on the portions shown.\n" : "",
                diffTruncated ? "(truncated — showing first ~80,000 characters)\n" : "",
                diff
        );

        String guardrailText = """
                You MUST follow these rules without exception:
                - This is a READ-ONLY review. Do NOT create, modify, or delete any files.
                - Only run read-only commands: git diff, git status, git log, ls, find, cat, grep
                - Never read or write files outside the repository root.
                """;

        return rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, reviewInstructions);
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
        RunFixRequest request = job.getRequest();
        RepoCoordinates coords = RepoCoordinates.parse(request.repoUrl());

        try {
            bitbucketService.mergePullRequest(coords.workspace(), coords.repoSlug(), job.getPrId());
            job.setStatus(JobStatus.SUCCESS);
            safeJira(() -> jiraService.commentMerged(request.jiraKey()));
            safeJira(() -> jiraService.transitionToDone(request.jiraKey()));
            LOG.infof("Job %s approved and merged", job.getJobId());
        } catch (Exception e) {
            LOG.errorf("Failed to merge PR for job %s: %s", job.getJobId(), e.getMessage());
            throw new RuntimeException("Merge failed: " + e.getMessage(), e);
        }
    }

    public void reject(JobRecord job, String reason) {
        RunFixRequest request = job.getRequest();
        RepoCoordinates coords = RepoCoordinates.parse(request.repoUrl());

        try {
            bitbucketService.declinePullRequest(coords.workspace(), coords.repoSlug(), job.getPrId());
        } catch (Exception e) {
            LOG.warnf("Failed to decline PR for job %s: %s", job.getJobId(), e.getMessage());
        }

        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage("Rejected: " + (reason != null ? reason : "No reason provided"));
        safeJira(() -> jiraService.commentRejected(request.jiraKey(), reason));
        safeJira(() -> jiraService.transitionToRejected(request.jiraKey()));
        LOG.infof("Job %s rejected", job.getJobId());
    }

    private String buildSystemPrompt(RunFixRequest request, String effectivePrompt,
                                     WorkspaceContext workspace) {
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
}
