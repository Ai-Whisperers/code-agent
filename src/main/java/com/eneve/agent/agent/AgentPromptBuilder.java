package com.eneve.agent.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.ThreadComment;
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
    @Inject CommentFeedbackStore feedbackStore;
    @Inject RepoSettingsStore repoSettingsStore;

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

        int maxDiffChars = 80_000;
        List<ParsedDiffFile> displayFiles = DiffFormatter.truncateAtFileBoundary(parsedFiles, maxDiffChars);
        boolean diffTruncated = displayFiles.size() < parsedFiles.size();

        String annotatedDiff = DiffFormatter.toAnnotated(displayFiles);
        Map<String, TreeSet<Integer>> commentableLines = DiffFormatter.buildCommentableLines(displayFiles);

        String reviewInstructions;
        if (settings != null && settings.reviewPrompt() != null && !settings.reviewPrompt().isBlank()) {
            LOG.infof("Using custom review prompt for %s/%s", bbWorkspace, repoSlug);
            reviewInstructions = applyPlaceholders(settings.reviewPrompt(),
                    prTitle, targetBranch, previousCommentsSection, memorySection,
                    falsePositiveSection, impactSection, diffTruncated, annotatedDiff);
        } else {
            reviewInstructions = buildDefaultReviewInstructions(
                    prTitle, targetBranch, previousCommentsSection, memorySection,
                    falsePositiveSection, impactSection, diffTruncated, annotatedDiff);
        }

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

    public String buildGenerateTestsPrompt(GenerateTestsRequest request, WorkspaceContext workspace) {
        return buildGenerateTestsPrompt(request, workspace, null);
    }

    public String buildGenerateTestsPrompt(GenerateTestsRequest request, WorkspaceContext workspace,
                                           CoverageReporter.CoverageSnapshot baseline) {
        String rulesRepoUrl = resolveRulesRepoUrl(request.rulesRepoUrl());
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        String targetFilesSection;
        if (request.targetFiles() != null && !request.targetFiles().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Focus ONLY on generating tests for these specific source files/packages:\n");
            for (String f : request.targetFiles()) {
                sb.append("  - ").append(f).append("\n");
            }
            targetFilesSection = sb.toString();
        } else if (baseline != null) {
            targetFilesSection = """
                    Scan `src/main/java` to find classes that currently have low or no test coverage. \
                    Use the coverage baseline below to identify the highest-impact targets. \
                    Prioritize service classes, utility classes, and classes with non-trivial business logic. \
                    Skip generated code (e.g. Panache entities, mappers).
                    """;
        } else {
            targetFilesSection = """
                    Scan `src/main/java` to find classes that currently have no corresponding test file \
                    in `src/test/java`. Prioritize service classes, utility classes, and classes with \
                    non-trivial business logic. Skip generated code (e.g. Panache entities, mappers).
                    """;
        }

        String coverageSection = baseline != null
                ? "\n" + baseline.formatForPrompt() + "\n"
                : "";

        String generateTestsInstructions = """
                You are generating unit tests for a Java project.
                Your ONLY goal is to write new or improved test files. Do NOT modify any production source code.
                %s
                ## Discovery (do this FIRST)
                1. Read `pom.xml` or `build.gradle` to identify the test framework and mocking library in use \
                (e.g. JUnit 5, Mockito, AssertJ, Quarkus test extensions, Spring Boot Test).
                2. List and read a sample of existing test files under `src/test/java` to understand the \
                project's testing conventions, base classes, and annotation patterns.
                3. Note the package structure so you place new test files in the correct mirrored package.

                ## Scope
                %s
                ## Test Writing Rules
                - Place each test file at the mirrored path in `src/test/java/...`.
                - Use the same test framework and assertion library already used in the project.
                - Prefer plain `@ExtendWith(MockitoExtension.class)` unit tests unless the class requires \
                a full container (`@QuarkusTest`).
                - For each class under test, cover:
                  - Happy path (typical valid inputs)
                  - Edge cases (null inputs, empty collections, boundary values)
                  - Error paths (exceptions, invalid state)
                - Use descriptive `@DisplayName` or method name patterns already present in the project.
                - Mock all external dependencies (repositories, services, HTTP clients) with Mockito.
                - Do NOT add `@Disabled` tests or tests that always pass trivially.
                - If a test file already exists for a class, ADD missing test cases rather than replacing the file.

                ## After Writing Tests
                - Run `mvn test` (or `gradle test` if there is no `pom.xml`) to verify the tests compile and pass.
                - If tests fail, read the error output carefully, fix the failures, and re-run.
                - Repeat until all tests pass.
                - Provide a summary listing which test files were created or modified and the number of test \
                cases added for each.
                """.formatted(coverageSection, targetFilesSection);

        String guardrailText = buildTestGenerationGuardrailText();

        return rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, generateTestsInstructions);
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
                - You can use `read_file`, `list_files`, and `fetch_url` to examine the code or look up official documentation for additional context.
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

    public String buildGenerateDocsPrompt(GenerateDocsRequest request, WorkspaceContext workspace,
                                          RepoSettings settings, boolean confluenceActive,
                                          String confluenceSpaceKey) {
        String rulesRepoUrl = resolveRulesRepoUrl(null);
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();

        List<String> sharedRules = rulesLoader.loadFromRulesRepo(rulesRepoUrl, ruleNames);
        List<String> repoRules = rulesLoader.loadFromTargetRepo(workspace.getRoot());

        String confluenceSection = "";
        if (confluenceActive) {
            confluenceSection = """

                    ## Confluence Publishing
                    After writing each documentation file, publish it to Confluence using the `publish_confluence` tool.
                    - **IMPORTANT**: Publish the index page (`docs/README.md`) FIRST. It becomes the parent page \
                    under which all other documentation pages are created as children.
                    - Use the document title as the Confluence page title (e.g. "Architecture Overview", "API Documentation").
                    - Pass the full Markdown content as `markdown_content` — it will be converted automatically.
                    - The space key and parent page are pre-configured; you do not need to supply them.
                    - If a page with the same title already exists, it will be updated.
                    """;
        }

        String docsInstructions = """
                You are generating comprehensive documentation for a software project.
                Your goal is to explore the entire codebase and produce high-quality Markdown documentation
                in the `docs/` folder at the repository root.

                ## Discovery (do this FIRST)
                1. Use `list_files` to understand the overall project structure (src layout, config files, build system).
                2. Read `pom.xml` or `build.gradle` to identify the tech stack, frameworks, and dependencies.
                3. Read `application.properties` / `application.yml` or `.env` for configuration reference.
                4. Read key source files: controllers/endpoints, services, models/entities, database migrations.
                5. Use `search_code` and `query_code_graph` to understand class hierarchies and call chains.
                6. Use `semantic_search` to find related patterns if vector indexing is available.

                ## Documentation to Generate

                Create the following files using `write_file`. Each file should be well-structured Markdown
                with Mermaid diagrams where they add value.

                ### 1. `docs/README.md` — Index
                - Brief project summary (1-2 paragraphs).
                - Table of contents linking to all other doc files.
                - Quick start command (how to build and run).

                ### 2. `docs/architecture.md` — Architecture Overview
                - High-level system design: what the project does, how components interact.
                - Tech stack summary (frameworks, databases, external services).
                - Use `flowchart` Mermaid diagrams for component maps.
                - Use `classDiagram` for key class relationships.
                - Cover deployment topology if evident from config.

                ### 3. `docs/api.md` — API Documentation
                - List all REST endpoints grouped by controller/tag.
                - For each endpoint: HTTP method, path, description, request body schema, response schema, error codes.
                - Read the controller classes and OpenAPI annotations to extract this.
                - Include authentication requirements if present.

                ### 4. `docs/data-model.md` — Data Model
                - Document all database tables, their columns, types, and constraints.
                - Derive from Flyway migration files (`src/main/resources/db/migration/`).
                - Use `erDiagram` Mermaid diagrams for entity relationships.
                - Note important indexes and unique constraints.

                ### 5. `docs/getting-started.md` — Developer Onboarding
                - Prerequisites (JDK version, Docker, database, etc.).
                - Step-by-step environment setup.
                - How to build, run, and test the project.
                - Project structure walkthrough (what each top-level directory contains).
                - Key configuration that must be set before first run.

                ### 6. `docs/flows.md` — Key Business Flows
                - Document the most important workflows as `sequenceDiagram` Mermaid diagrams.
                - Cover: the main job lifecycle, webhook processing, external integrations.
                - Each flow should have a brief text description followed by the diagram.

                ### 7. `docs/configuration.md` — Configuration Reference
                - List ALL configuration properties and environment variables.
                - Group by feature area (e.g. "Database", "Authentication", "External Services").
                - For each: property name, env var name, description, default value, whether required.
                - Read `application.properties` and `.env` files as sources.
                %s
                ## Writing Guidelines
                - **Depth**: Moderate — cover packages and key classes, skip private internals.
                - **Audience**: Mixed — both internal developers and external API consumers.
                - Use clear language and avoid jargon without explanation.
                - Every Mermaid diagram must be in a fenced code block with the `mermaid` language tag.
                - Keep diagrams focused — no more than ~15 nodes per diagram. Split large diagrams.
                - Use tables for structured data (endpoints, config properties, DB columns).
                - Cross-reference between docs using relative links (e.g. `[see API docs](api.md)`).
                - Do NOT reference any specific git hosting platform (e.g. GitHub, GitLab, Bitbucket) unless the \
                codebase explicitly integrates with one. This project supports multiple git platforms. \
                Use generic terms like "git repository", "pull request / merge request", or "remote" instead.

                ## Process
                1. Explore the codebase thoroughly before writing any documentation.
                2. Write each doc file using `write_file`.
                3. After writing all files, provide a summary of what was generated.
                """.formatted(confluenceSection);

        String guardrailText = """
                You MUST follow these rules without exception:
                - Do NOT modify files under these paths: %s
                - Only write files under the `docs/` directory. Do NOT modify source code.
                - Only run allowed commands: %s
                - Never read or write files outside the repository root.
                """.formatted(
                String.join(", ", guardrails.getBlockedPaths()),
                String.join(", ", guardrails.getAllowedCommands())
        );

        return rulesLoader.buildSystemPrompt(sharedRules, repoRules,
                request.extraRules(), guardrailText, docsInstructions);
    }

    // ─── Private helpers ────────────────────────────────────────────────

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

    private String buildDefaultReviewInstructions(String prTitle, String targetBranch,
                                                  String previousCommentsSection,
                                                  String memorySection,
                                                  String falsePositiveSection,
                                                  String impactSection,
                                                  boolean diffTruncated,
                                                  String annotatedDiff) {
        return """
                You are performing an automated code review of a pull request.
                Your goal is to review the changes for quality, correctness, and adherence to best practices.

                ## PR Information
                - **Title**: %s
                - **Target branch**: %s
                %s
                %s
                %s
                %s
                ## Context Gathering (do this FIRST)
                Before writing any findings, explore the repository for context relevant to the changed code:
                - Use `search_code` to find callers, implementations, or usages of changed classes, methods, or interfaces.
                - Use `query_code_graph` to find callers, implementations, or dependents of a specific symbol.
                - Use `read_file` to examine interfaces, base classes, utility files, or configuration referenced in the diff.
                - Use `list_files` to understand the module and package structure around changed files.
                - Look at test files for changed modules to understand expected behaviour and existing coverage.
                - Use `fetch_url` to look up official framework or library documentation when the changed code uses specific APIs, annotations, or patterns you want to verify (e.g. Quarkus, Spring, React, or any third-party library docs). Prefer official documentation sites.
                - Only gather context that is directly relevant to the changed code — do not explore unrelated areas.

                This context will help you avoid false positives and produce more precise, actionable findings.

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
                - Use `search_code`, `query_code_graph`, `read_file`, `list_files`, and `fetch_url` to gather context before finalising findings (see Context Gathering above).
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
                falsePositiveSection,
                impactSection != null ? impactSection : "",
                previousCommentsSection,
                diffTruncated ? "(truncated — some files omitted)\n" : "",
                annotatedDiff
        );
    }

    private String resolveRulesRepoUrl(String requestUrl) {
        return (requestUrl != null && !requestUrl.isBlank()) ? requestUrl : defaultRulesRepoUrl;
    }

    /**
     * Guardrails for unit test generation: no file/line count cap (the agent may need to
     * create many test files), but production code is fully off-limits.
     */
    private String buildTestGenerationGuardrailText() {
        return """
                You MUST follow these rules without exception:
                - Do NOT modify files under these paths: %s
                - Do NOT modify ANY file under src/main/java — only write or update files under src/test/java.
                - Only run allowed commands: %s
                - After making changes, run: mvn test (or gradle test if build.gradle is present)
                - If tests fail, report the failure and do NOT proceed
                - Stop as soon as all requested tests have been written and pass.
                - Never read or write files outside the repository root.
                """.formatted(
                String.join(", ", guardrails.getBlockedPaths()),
                String.join(", ", guardrails.getAllowedCommands())
        );
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

        // Collect unique patterns, preserving insertion order
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
