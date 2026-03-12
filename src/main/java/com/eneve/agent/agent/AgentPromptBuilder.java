package com.eneve.agent.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.eneve.agent.bitbucket.AgentComment;
import com.eneve.agent.bitbucket.ThreadComment;
import com.eneve.agent.diff.DiffFormatter;
import com.eneve.agent.diff.DiffParser;
import com.eneve.agent.diff.ParsedDiffFile;
import com.eneve.agent.diff.ReviewPromptResult;
import com.eneve.agent.model.FixPrRequest;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.rules.CursorRulesLoader;
import com.eneve.agent.tools.GuardrailConfig;
import com.eneve.agent.workspace.WorkspaceContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    @ConfigProperty(name = "rules.repo.url", defaultValue = "")
    String defaultRulesRepoUrl;

    public String buildRunFixPrompt(RunFixRequest request, String effectivePrompt,
                                    WorkspaceContext workspace, String baselineLinterSummary) {
        String rulesRepoUrl = resolveRulesRepoUrl(request.rulesRepoUrl());
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        String guardrailText = buildWritableGuardrailText(
                "Stop as soon as the task is complete. Do not refactor unrelated code.");

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
        String rulesRepoUrl = resolveRulesRepoUrl(request.rulesRepoUrl());
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        String previousCommentsSection = buildPreviousCommentsSection(existingComments);
        String memorySection = buildMemorySection(bbWorkspace, repoSlug);

        List<ParsedDiffFile> parsedFiles = DiffParser.parse(diff);

        int maxDiffChars = 80_000;
        List<ParsedDiffFile> displayFiles = DiffFormatter.truncateAtFileBoundary(parsedFiles, maxDiffChars);
        boolean diffTruncated = displayFiles.size() < parsedFiles.size();

        String annotatedDiff = DiffFormatter.toAnnotated(displayFiles);
        Map<String, TreeSet<Integer>> commentableLines = DiffFormatter.buildCommentableLines(displayFiles);

        String reviewInstructions = """
                You are performing an automated code review of a pull request.
                Your goal is to review the changes for quality, correctness, and adherence to best practices.

                ## PR Information
                - **Title**: %s
                - **Target branch**: %s
                %s
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
                memorySection,
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

    public String buildFixPrPrompt(FixPrRequest request, String prTitle,
                                   String sourceBranch, String targetBranch,
                                   String diff, List<String> reviewComments,
                                   WorkspaceContext workspace) {
        String rulesRepoUrl = resolveRulesRepoUrl(request.rulesRepoUrl());
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

        String guardrailText = buildWritableGuardrailText(
                "Stop as soon as all review comments are addressed. Do not refactor unrelated code.");

        return rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, fixInstructions);
    }

    public String buildReplyPrompt(CommentContext ctx, List<ThreadComment> thread,
                                   String latestHumanMessage) {
        String threadSection = formatThreadSection(thread);

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
                threadSection.isBlank() ? "(no previous thread messages)" : threadSection,
                latestHumanMessage
        );
    }

    public String buildFixCommentPrompt(CommentContext ctx, List<ThreadComment> thread,
                                        String humanMessage) {
        String threadSection = formatThreadSection(thread);

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
                threadSection.isBlank() ? "(no previous thread)" : threadSection
        );
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private String resolveRulesRepoUrl(String requestUrl) {
        return (requestUrl != null && !requestUrl.isBlank()) ? requestUrl : defaultRulesRepoUrl;
    }

    private String buildWritableGuardrailText(String stopCondition) {
        return """
                You MUST follow these rules without exception:
                - Do NOT modify files under these paths: %s
                - Only run allowed commands: %s
                - Do NOT modify more than %d files or %d lines
                - After making changes, run: mvn test (or gradle test if build.gradle is present)
                - If tests fail, report the failure and do NOT proceed
                - %s
                - Never read or write files outside the repository root.
                """.formatted(
                String.join(", ", guardrails.getBlockedPaths()),
                String.join(", ", guardrails.getAllowedCommands()),
                guardrails.getMaxFilesChanged(),
                guardrails.getMaxLinesChanged(),
                stopCondition
        );
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
}
