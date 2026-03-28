package com.eneve.agent.agent;

import com.eneve.agent.agent.store.MemoryStore;
import com.eneve.agent.agent.store.CommentFeedbackStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.model.MemoryEntry;
import com.eneve.agent.agent.model.CommentFeedbackEntry;
import com.eneve.agent.agent.model.CommentContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.ThreadComment;
import com.eneve.agent.diff.DiffFilter;
import com.eneve.agent.diff.DiffFormatter;
import com.eneve.agent.diff.DiffParser;
import com.eneve.agent.diff.ParsedDiffFile;
import com.eneve.agent.diff.ReviewPromptResult;
import com.eneve.agent.model.FixPrRequest;
import com.eneve.agent.model.GenerateDocsRequest;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.rules.CursorRulesLoader;
import com.eneve.agent.tools.GuardrailConfig;
import com.eneve.agent.workspace.WorkspaceContext;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AgentPromptBuilder {

    private static final Logger LOG = Logger.getLogger(AgentPromptBuilder.class);
    private static final int MAX_MEMORY_CHARS = 2000;

    @Inject CursorRulesLoader rulesLoader;
    @Inject GuardrailConfig guardrails;
    @Inject MemoryStore memoryStore;
    @Inject CommentFeedbackStore feedbackStore;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject PromptTemplateService promptTemplates;
    @Inject SettingsService settings;

    public String buildRunFixPrompt(RunFixRequest request, String effectivePrompt,
                                    WorkspaceContext workspace, String baselineLinterSummary) {
        String rulesRepoUrl = resolveRulesRepoUrl(request.rulesRepoUrl());
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        String guardrailText = resolveWritableGuardrails(
                "Stop as soon as the task is complete. Do not refactor unrelated code.", workspace.getRoot());

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

    public ReviewPromptResult buildReviewPrompt(ReviewPrRequest request, String prTitle,
                                                String targetBranch, String diff,
                                                List<AgentComment> existingComments,
                                                WorkspaceContext workspace,
                                                String bbWorkspace, String repoSlug) {
        return buildReviewPrompt(request, prTitle, targetBranch, diff, existingComments,
                workspace, bbWorkspace, repoSlug, "");
    }

    public ReviewPromptResult buildReviewPrompt(ReviewPrRequest request, String prTitle,
                                                String targetBranch, String diff,
                                                List<AgentComment> existingComments,
                                                WorkspaceContext workspace,
                                                String bbWorkspace, String repoSlug,
                                                String impactSection) {

        RepoSettings settings = loadRepoSettings(bbWorkspace, repoSlug);

        String rulesRepoUrl = resolveRulesRepoUrl(request.rulesRepoUrl());
        List<String> ruleNames = resolveRuleNames(request.ruleNames(), settings);

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        String previousCommentsSection = buildPreviousCommentsSection(existingComments);
        String memorySection = buildMemorySection(bbWorkspace, repoSlug);
        String falsePositiveSection = buildFalsePositiveSection(bbWorkspace, repoSlug);

        List<ParsedDiffFile> parsedFiles = DiffParser.parse(diff);

        List<ParsedDiffFile> reviewableFiles = DiffFilter.filterReviewable(parsedFiles);
        if (reviewableFiles.size() < parsedFiles.size()) {
            LOG.infof("Diff filter: excluded %d non-reviewable file(s) from %d total (lock files, " +
                            "coverage reports, build artifacts). Reviewable: %d",
                    parsedFiles.size() - reviewableFiles.size(), parsedFiles.size(), reviewableFiles.size());
        }

        int maxDiffChars = Integer.parseInt(this.settings.get("review.max-diff-chars", "60000"));
        List<ParsedDiffFile> displayFiles = DiffFormatter.truncateAtFileBoundary(reviewableFiles, maxDiffChars);
        boolean diffTruncated = displayFiles.size() < reviewableFiles.size();

        String annotatedDiff = DiffFormatter.toAnnotated(displayFiles);
        Map<String, TreeSet<Integer>> commentableLines = DiffFormatter.buildCommentableLines(displayFiles);

        String reviewInstructions;
        if (settings != null && settings.reviewPrompt() != null && !settings.reviewPrompt().isBlank()) {
            LOG.infof("Using custom review prompt for %s/%s", bbWorkspace, repoSlug);
            reviewInstructions = applyPlaceholders(settings.reviewPrompt(),
                    prTitle, targetBranch, previousCommentsSection, memorySection,
                    falsePositiveSection, impactSection, diffTruncated, annotatedDiff);
        } else {
            reviewInstructions = promptTemplates.resolve("review", Map.of(
                    "PR_TITLE", prTitle != null ? prTitle : "(untitled)",
                    "TARGET_BRANCH", targetBranch != null ? targetBranch : "",
                    "DIFF_NOTE", diffTruncated ? "**Note**: The diff was truncated due to size. Focus on the portions shown.\n" : "",
                    "MEMORY_SECTION", memorySection,
                    "FALSE_POSITIVE_SECTION", falsePositiveSection,
                    "IMPACT_SECTION", impactSection != null ? impactSection : "",
                    "PREVIOUS_COMMENTS", previousCommentsSection,
                    "DIFF_TRUNCATION_NOTE", diffTruncated ? "(truncated — some files omitted)\n" : "",
                    "DIFF", annotatedDiff
            ));
        }

        String guardrailText = promptTemplates.resolve("guardrails.readonly", Map.of());

        String prompt = rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, reviewInstructions);
        return new ReviewPromptResult(prompt, commentableLines, diffTruncated);
    }

    public String buildFixPrPrompt(FixPrRequest request, String prTitle,
                                   String sourceBranch, String targetBranch,
                                   String diff, List<String> reviewComments,
                                   WorkspaceContext workspace) {
        String rulesRepoUrl = resolveRulesRepoUrl(request.rulesRepoUrl());
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();
        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());
        return buildFixPrPrompt(request, prTitle, sourceBranch, targetBranch, diff, reviewComments,
                sharedRules, repoRules, workspace.getRoot());
    }

    /**
     * Builds the fix-PR system prompt using pre-loaded rules. Callers that load rules in parallel
     * (e.g. while the repo is being cloned) should use this overload to avoid redundant loading.
     */
    public String buildFixPrPrompt(FixPrRequest request, String prTitle,
                                   String sourceBranch, String targetBranch,
                                   String diff, List<String> reviewComments,
                                   List<String> sharedRules, List<String> repoRules) {
        return buildFixPrPrompt(request, prTitle, sourceBranch, targetBranch, diff, reviewComments,
                sharedRules, repoRules, null);
    }

    /**
     * Core fix-PR prompt builder. Accepts an optional {@code workspaceRoot} for
     * language-aware test command resolution.
     */
    public String buildFixPrPrompt(FixPrRequest request, String prTitle,
                                   String sourceBranch, String targetBranch,
                                   String diff, List<String> reviewComments,
                                   List<String> sharedRules, List<String> repoRules,
                                   Path workspaceRoot) {
        StringBuilder commentsSection = new StringBuilder();
        for (int i = 0; i < reviewComments.size(); i++) {
            commentsSection.append(i + 1).append(". ").append(reviewComments.get(i)).append("\n");
        }

        String fixInstructions = promptTemplates.resolve("fix-pr", Map.of(
                "PR_TITLE", prTitle != null ? prTitle : "(untitled)",
                "SOURCE_BRANCH", sourceBranch,
                "TARGET_BRANCH", targetBranch,
                "COMMENTS", commentsSection.toString(),
                "DIFF", diff.isEmpty() ? "(diff not available)" : diff,
                "BUILD_AND_TEST_COMMAND", resolveTestCommand(workspaceRoot)
        ));

        String guardrailText = resolveWritableGuardrails(
                "Stop as soon as all review comments are addressed. Do not refactor unrelated code.",
                workspaceRoot);

        return rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, fixInstructions);
    }

    public String buildGenerateTestsPrompt(GenerateTestsRequest request, WorkspaceContext workspace) {
        return buildGenerateTestsPrompt(request, workspace, null);
    }

    public String buildGenerateTestsPrompt(GenerateTestsRequest request, WorkspaceContext workspace,
                                           CoverageReporter.CoverageSnapshot baseline) {
        String rulesRepoUrl = resolveRulesRepoUrl(request.rulesRepoUrl());
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        Path workspaceRoot = workspace.getRoot();
        String testCommand = resolveTestCommand(workspaceRoot);
        String sourceDir = resolveSourceDir(workspaceRoot);
        String testDir = resolveTestDir(workspaceRoot);

        String targetFilesSection;
        if (request.targetFiles() != null && !request.targetFiles().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Focus ONLY on generating tests for these specific source files/packages:\n");
            for (String f : request.targetFiles()) {
                sb.append("  - ").append(f).append("\n");
            }
            targetFilesSection = sb.toString();
        } else if (baseline != null) {
            targetFilesSection = "Scan `" + sourceDir + "` to find source files that currently have low or no test coverage. " +
                    "Use the coverage baseline below to identify the highest-impact targets. " +
                    "Prioritize service classes, utility classes, and files with non-trivial business logic. " +
                    "Skip generated/compiled code.\n";
        } else {
            targetFilesSection = "Scan `" + sourceDir + "` to find source files that currently have no corresponding test file " +
                    "in `" + testDir + "`. Prioritize service classes, utility classes, and files with " +
                    "non-trivial business logic. Skip generated/compiled code.\n";
        }

        String coverageSection = baseline != null
                ? "\n" + baseline.formatForPrompt() + "\n"
                : "";

        String generateTestsInstructions = promptTemplates.resolve("generate-tests", Map.of(
                "COVERAGE_SECTION", coverageSection,
                "TARGET_FILES_SECTION", targetFilesSection,
                "BUILD_AND_TEST_COMMAND", testCommand
        ));

        String guardrailText = promptTemplates.resolve("guardrails.tests", Map.of(
                "BLOCKED_PATHS", String.join(", ", guardrails.getBlockedPaths()),
                "ALLOWED_COMMANDS", String.join(", ", guardrails.getAllowedCommands()),
                "BUILD_AND_TEST_COMMAND", testCommand,
                "SOURCE_DIR", sourceDir,
                "TEST_DIR", testDir
        ));

        return rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, generateTestsInstructions);
    }

    public String buildReplyPrompt(CommentContext ctx, List<ThreadComment> thread,
                                   String latestHumanMessage) {
        String threadSection = formatThreadSection(thread);

        return promptTemplates.resolve("reply", Map.of(
                "FILE", ctx.filePath() != null ? ctx.filePath() : "(general)",
                "LINE", String.valueOf(ctx.line()),
                "SEVERITY", ctx.severity() != null ? ctx.severity() : "INFO",
                "CATEGORY", ctx.category() != null ? ctx.category() : "General",
                "FINDING", ctx.findingText(),
                "THREAD", threadSection.isBlank() ? "(no previous thread messages)" : threadSection,
                "LATEST_MESSAGE", latestHumanMessage
        ));
    }

    public String buildFixCommentPrompt(CommentContext ctx, List<ThreadComment> thread,
                                        String humanMessage) {
        return buildFixCommentPrompt(ctx, thread, humanMessage, null);
    }

    public String buildFixCommentPrompt(CommentContext ctx, List<ThreadComment> thread,
                                        String humanMessage, Path workspaceRoot) {
        String threadSection = formatThreadSection(thread);

        return promptTemplates.resolve("fix-comment", Map.of(
                "FILE", ctx.filePath() != null ? ctx.filePath() : "(general)",
                "LINE", String.valueOf(ctx.line()),
                "SEVERITY", ctx.severity() != null ? ctx.severity() : "INFO",
                "CATEGORY", ctx.category() != null ? ctx.category() : "General",
                "FINDING", ctx.findingText(),
                "HUMAN_MESSAGE", humanMessage != null ? humanMessage : "(no additional instructions)",
                "THREAD", threadSection.isBlank() ? "(no previous thread)" : threadSection,
                "BUILD_AND_TEST_COMMAND", resolveTestCommand(workspaceRoot)
        ));
    }

    public String buildGenerateDocsPrompt(GenerateDocsRequest request, WorkspaceContext workspace,
                                          RepoSettings settings) {
        String rulesRepoUrl = resolveRulesRepoUrl(null);
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        String docsInstructions = promptTemplates.resolve("generate-docs", Map.of());

        String guardrailText = promptTemplates.resolve("guardrails.docs", Map.of(
                "BLOCKED_PATHS", String.join(", ", guardrails.getBlockedPaths()),
                "ALLOWED_COMMANDS", String.join(", ", guardrails.getAllowedCommands())
        ));

        return rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, docsInstructions);
    }

    /**
     * Builds the system prompt for a quality-improvement FIX job driven by cyclomatic
     * complexity metrics. The agent's goal is to refactor methods above the CC threshold
     * without changing observable behaviour, then commit the result.
     *
     * @param snapshot   the CC snapshot from the preceding METRICS step
     * @param maxMethods how many of the worst-offending methods to include in the prompt
     */
    public String buildMetricsFixPrompt(
            CodeMetricsCalculator.CodeMetricsSnapshot snapshot,
            int maxMethods) {

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(null, Collections.emptyList());

        String metricsSection = snapshot.formatForPrompt(maxMethods);

        String instructions = promptTemplates.resolve("metrics-fix", Map.of(
                "METRICS_SECTION", metricsSection,
                "THRESHOLD", String.valueOf(snapshot.threshold()),
                "BUILD_AND_TEST_COMMAND", resolveTestCommand(null)
        ));

        String guardrailText = resolveWritableGuardrails(
                "Stop as soon as all listed high-CC methods have been refactored and tests pass.", null);

        return rulesLoader.buildSystemPrompt(sharedRules, Collections.emptyList(),
                null, guardrailText, instructions);
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private String resolveWritableGuardrails(String stopCondition, Path workspaceRoot) {
        return promptTemplates.resolve("guardrails.writable", Map.of(
                "BLOCKED_PATHS", String.join(", ", guardrails.getBlockedPaths()),
                "ALLOWED_COMMANDS", String.join(", ", guardrails.getAllowedCommands()),
                "MAX_FILES", String.valueOf(guardrails.getMaxFilesChanged()),
                "MAX_LINES", String.valueOf(guardrails.getMaxLinesChanged()),
                "STOP_CONDITION", stopCondition,
                "BUILD_AND_TEST_COMMAND", resolveTestCommand(workspaceRoot)
        ));
    }

    /**
     * Resolves the appropriate test command for the given workspace root.
     * Falls back to a human-readable instruction when workspace is null or detection fails.
     */
    static String resolveTestCommand(Path workspaceRoot) {
        if (workspaceRoot == null) {
            return "run the appropriate test command for this project's build system";
        }
        if (Files.exists(workspaceRoot.resolve("mvnw"))) {
            return "./mvnw test";
        }
        if (Files.exists(workspaceRoot.resolve("pom.xml"))) {
            return "mvn test";
        }
        if (Files.exists(workspaceRoot.resolve("build.gradle"))
                || Files.exists(workspaceRoot.resolve("build.gradle.kts"))) {
            return "gradle test";
        }
        if (Files.exists(workspaceRoot.resolve("package.json"))) {
            return "npm test";
        }
        if (hasDotnetProject(workspaceRoot)) {
            return "dotnet test";
        }
        if (Files.exists(workspaceRoot.resolve("composer.json"))) {
            // Check for Laravel Artisan first, fall back to direct PHPUnit
            if (Files.exists(workspaceRoot.resolve("artisan"))) {
                return "php artisan test";
            }
            return "vendor/bin/phpunit";
        }
        return "run the appropriate test command for this project's build system";
    }

    /**
     * Resolves the primary source directory for test generation scoping.
     */
    static String resolveSourceDir(Path workspaceRoot) {
        if (workspaceRoot == null) return "src";
        if (Files.exists(workspaceRoot.resolve("src/main/java"))) return "src/main/java";
        if (Files.exists(workspaceRoot.resolve("src/main"))) return "src/main";
        if (Files.exists(workspaceRoot.resolve("src"))) return "src";
        if (Files.exists(workspaceRoot.resolve("app"))) return "app";   // Laravel convention
        if (Files.exists(workspaceRoot.resolve("lib"))) return "lib";
        return "src";
    }

    /**
     * Resolves the primary test directory for test generation scoping.
     */
    static String resolveTestDir(Path workspaceRoot) {
        if (workspaceRoot == null) return "tests";
        if (Files.exists(workspaceRoot.resolve("src/test/java"))) return "src/test/java";
        if (Files.exists(workspaceRoot.resolve("src/test"))) return "src/test";
        if (Files.exists(workspaceRoot.resolve("tests"))) return "tests";
        if (Files.exists(workspaceRoot.resolve("test"))) return "test";
        if (Files.exists(workspaceRoot.resolve("__tests__"))) return "__tests__";
        if (Files.exists(workspaceRoot.resolve("spec"))) return "spec";
        return "tests";
    }

    private static boolean hasDotnetProject(Path workspaceRoot) {
        try (var stream = Files.list(workspaceRoot)) {
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".sln") || name.endsWith(".csproj")
                        || name.endsWith(".fsproj") || name.endsWith(".vbproj");
            });
        } catch (Exception e) {
            return false;
        }
    }

    private RepoSettings loadRepoSettings(String workspace, String repoSlug) {
        try {
            return repoSettingsStore.find(workspace, repoSlug).orElse(null);
        } catch (Exception e) {
            LOG.warnf("Failed to load repo settings for %s/%s (non-fatal): %s",
                    workspace, repoSlug, e.getMessage());
            return null;
        }
    }

    /**
     * If the request specifies rule names, use those. Otherwise fall back
     * to per-repo settings, then to an empty list.
     */
    private List<String> resolveRuleNames(List<String> requestRuleNames, RepoSettings settings) {
        if (requestRuleNames != null && !requestRuleNames.isEmpty()) {
            return requestRuleNames;
        }
        if (settings != null && settings.ruleNames() != null && !settings.ruleNames().isEmpty()) {
            return settings.ruleNames();
        }
        return Collections.emptyList();
    }

    private String applyPlaceholders(String template, String prTitle, String targetBranch,
                                     String previousComments, String memorySection,
                                     String falsePositiveSection, String impactSection,
                                     boolean diffTruncated, String annotatedDiff) {
        String diffNote = diffTruncated
                ? "**Note**: The diff was truncated due to size. Focus on the portions shown.\n"
                : "";
        return template
                .replace("{{PR_TITLE}}", prTitle != null ? prTitle : "(untitled)")
                .replace("{{TARGET_BRANCH}}", targetBranch != null ? targetBranch : "")
                .replace("{{PREVIOUS_COMMENTS}}", previousComments != null ? previousComments : "")
                .replace("{{MEMORY_SECTION}}", memorySection != null ? memorySection : "")
                .replace("{{FALSE_POSITIVE_SECTION}}", falsePositiveSection != null ? falsePositiveSection : "")
                .replace("{{IMPACT_SECTION}}", impactSection != null ? impactSection : "")
                .replace("{{DIFF_NOTE}}", diffNote)
                .replace("{{DIFF}}", annotatedDiff != null ? annotatedDiff : "");
    }

    private String resolveRulesRepoUrl(String requestUrl) {
        return (requestUrl != null && !requestUrl.isBlank()) ? requestUrl : settings.get("rules.repo.url", "");
    }

    private static String formatThreadSection(List<ThreadComment> thread) {
        StringBuilder sb = new StringBuilder();
        for (ThreadComment tc : thread) {
            String role = tc.isAgent() ? "You (AI Reviewer)" : tc.author();
            sb.append("**").append(role).append("**: ").append(tc.content()).append("\n\n");
        }
        return sb.toString();
    }

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
     * Builds a prompt section with team preferences learned from past review
     * interactions. Returns an empty string if no memories exist for the repo.
     * Truncates at {@link #MAX_MEMORY_CHARS} to stay within token budget.
     */
    private String buildMemorySection(String workspace, String repoSlug) {
        List<MemoryEntry> memories;
        try {
            memories = memoryStore.findForRepo(workspace, repoSlug);
        } catch (Exception e) {
            LOG.warnf("Failed to load review memories for %s/%s (non-fatal): %s",
                    workspace, repoSlug, e.getMessage());
            return "";
        }

        if (memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Team Preferences (learned from past reviews)\n");
        sb.append("The following preferences were learned from previous code review interactions ");
        sb.append("with this team. Respect these unless the code explicitly violates best practices:\n\n");

        int headerLen = sb.length();
        for (MemoryEntry m : memories) {
            String bullet = "- " + m.memoryText() + "\n";
            if (sb.length() + bullet.length() > MAX_MEMORY_CHARS) {
                int remaining = memories.size() - memories.indexOf(m);
                sb.append("- ... and ").append(remaining).append(" more preferences (truncated)\n");
                break;
            }
            sb.append(bullet);
        }
        sb.append("\n");

        if (sb.length() <= headerLen + 1) {
            return "";
        }

        LOG.debugf("Injected %d memories (%d chars) into review prompt for %s/%s",
                memories.size(), sb.length(), workspace, repoSlug);
        return sb.toString();
    }

    /**
     * Builds a prompt section listing finding patterns the team has marked as false positives.
     * Injected into the review prompt so the agent avoids repeating noise.
     * Capped at ~1500 chars to stay within token budget.
     */
    private String buildFalsePositiveSection(String workspace, String repoSlug) {
        List<CommentFeedbackEntry> fps;
        try {
            fps = feedbackStore.findFalsePositives(workspace, repoSlug);
        } catch (Exception e) {
            LOG.warnf("Failed to load false positives for %s/%s (non-fatal): %s",
                    workspace, repoSlug, e.getMessage());
            return "";
        }

        if (fps.isEmpty()) {
            return "";
        }

        java.util.LinkedHashSet<String> patterns = new java.util.LinkedHashSet<>();
        for (CommentFeedbackEntry fp : fps) {
            if (fp.pattern() != null && !fp.pattern().isBlank()) {
                patterns.add(fp.pattern());
            }
        }
        if (patterns.isEmpty()) {
            return "";
        }

        final int maxChars = 1500;
        StringBuilder sb = new StringBuilder();
        sb.append("## Known False Positives (flagged by this team)\n");
        sb.append("The following finding patterns have been marked as false positives by this team. ");
        sb.append("Do NOT report findings matching these patterns unless there is a clear, genuine issue:\n\n");

        int headerLen = sb.length();
        for (String pattern : patterns) {
            String bullet = "- " + pattern + "\n";
            if (sb.length() + bullet.length() > maxChars) {
                sb.append("- ... (additional patterns truncated)\n");
                break;
            }
            sb.append(bullet);
        }
        sb.append("\n");

        if (sb.length() <= headerLen + 1) {
            return "";
        }

        LOG.debugf("Injected %d FP patterns into review prompt for %s/%s", patterns.size(), workspace, repoSlug);
        return sb.toString();
    }
}
