package com.eneve.agent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;

/**
 * Defines the tool schemas exposed to Claude during the agentic loop.
 */
public final class ToolDefinitions {

    private ToolDefinitions() { }

    /**
     * Read-only tool set for the roadmap AI-improvement loop.
     * Lets Claude research the linked product's knowledge base, code semantics,
     * code graph, and external documentation before writing the improved issue.
     */
    public static List<ToolUnion> roadmapImprove() {
        return List.of(
                ToolUnion.ofTool(searchKnowledgeBase()),
                ToolUnion.ofTool(semanticSearch()),
                ToolUnion.ofTool(queryCodeGraph()),
                ToolUnion.ofTool(fetchUrl())
        );
    }

    public static List<ToolUnion> all() {
        return List.of(
                ToolUnion.ofTool(readFile()),
                ToolUnion.ofTool(writeFile()),
                ToolUnion.ofTool(runCommand()),
                ToolUnion.ofTool(listFiles()),
                ToolUnion.ofTool(fetchUrl()),
                ToolUnion.ofTool(searchKnowledgeBase()),
                ToolUnion.ofTool(lookupCustomerContext())
        );
    }

    public static List<ToolUnion> readOnly() {
        return List.of(
                ToolUnion.ofTool(readFile()),
                ToolUnion.ofTool(searchCode()),
                ToolUnion.ofTool(queryCodeGraph()),
                ToolUnion.ofTool(semanticSearch()),
                ToolUnion.ofTool(runCommand()),
                ToolUnion.ofTool(listFiles()),
                ToolUnion.ofTool(fetchUrl()),
                ToolUnion.ofTool(searchKnowledgeBase()),
                ToolUnion.ofTool(lookupCustomerContext()),
                ToolUnion.ofTool(setProductContext())
        );
    }

    public static List<ToolUnion> docsGeneration() {
        return List.of(
                ToolUnion.ofTool(readFile()),
                ToolUnion.ofTool(writeFile()),
                ToolUnion.ofTool(listFiles()),
                ToolUnion.ofTool(searchCode()),
                ToolUnion.ofTool(queryCodeGraph()),
                ToolUnion.ofTool(semanticSearch()),
                ToolUnion.ofTool(runCommand()),
                ToolUnion.ofTool(fetchUrl()),
                ToolUnion.ofTool(searchKnowledgeBase()),
                ToolUnion.ofTool(lookupCustomerContext()),
                ToolUnion.ofTool(setProductContext())
        );
    }

    public static List<ToolUnion> planExecution() {
        return List.of(
                ToolUnion.ofTool(planRead()),
                ToolUnion.ofTool(planUpdate()),
                ToolUnion.ofTool(searchKnowledgeBase()),
                ToolUnion.ofTool(lookupCustomerContext()),
                ToolUnion.ofTool(setProductContext()),
                ToolUnion.ofTool(semanticSearch()),
                ToolUnion.ofTool(searchCode()),
                ToolUnion.ofTool(queryCodeGraph()),
                ToolUnion.ofTool(fetchUrl()),
                // Jira MCP tools
                ToolUnion.ofTool(jiraSearchIssues()),
                ToolUnion.ofTool(jiraGetIssue()),
                ToolUnion.ofTool(jiraGetComments()),
                ToolUnion.ofTool(jiraCreateIssue()),
                ToolUnion.ofTool(jiraUpdateIssue()),
                ToolUnion.ofTool(jiraAddComment()),
                ToolUnion.ofTool(jiraTransitionIssue()),
                ToolUnion.ofTool(jiraGetWorklogs()),
                ToolUnion.ofTool(jiraAddWorklog()),
                // Confluence MCP tools
                ToolUnion.ofTool(confluenceSearch()),
                ToolUnion.ofTool(confluenceGetPage()),
                ToolUnion.ofTool(confluenceCreatePage()),
                ToolUnion.ofTool(confluenceUpdatePage()),
                // Agent action tools
                ToolUnion.ofTool(agentRunFix()),
                ToolUnion.ofTool(agentGetJobStatus()),
                ToolUnion.ofTool(agentSubmitReviewJob())
        );
    }

    public static List<ToolUnion> chat() {
        return chat(true);
    }

    /**
     * Returns the tool set for the chat loop, filtered by the caller's permissions.
     *
     * @param canExecuteJobs {@code true} for DEVELOPER / ADMINISTRATOR roles;
     *                       {@code false} for USER / STAFF (read-only + Jira/Confluence reads only)
     */
    public static List<ToolUnion> chat(boolean canExecuteJobs) {
        return chat(canExecuteJobs, true);
    }

    /**
     * Returns the tool set for the chat loop.
     *
     * @param canExecuteJobs  {@code true} for DEVELOPER / ADMINISTRATOR roles
     * @param includeAwsTools {@code true} to include AWS tools; pass {@code false} when no
     *                        customer context is resolved yet to avoid paying for their large
     *                        tool schemas on every iteration
     */
    public static List<ToolUnion> chat(boolean canExecuteJobs, boolean includeAwsTools) {
        List<ToolUnion> tools = new ArrayList<>(List.of(
                // ── Read-only / analysis tools (all roles) ────────────────
                ToolUnion.ofTool(searchKnowledgeBase()),
                ToolUnion.ofTool(lookupCustomerContext()),
                ToolUnion.ofTool(setProductContext()),
                ToolUnion.ofTool(semanticSearch()),
                ToolUnion.ofTool(searchCode()),
                ToolUnion.ofTool(queryCodeGraph()),
                ToolUnion.ofTool(fetchUrl()),
                // Jira read tools (all roles)
                ToolUnion.ofTool(jiraSearchIssues()),
                ToolUnion.ofTool(jiraGetIssue()),
                ToolUnion.ofTool(jiraGetComments()),
                ToolUnion.ofTool(jiraGetWorklogs()),
                // Confluence read tools (all roles)
                ToolUnion.ofTool(confluenceSearch()),
                ToolUnion.ofTool(confluenceGetPage())
        ));

        if (includeAwsTools) {
            tools.addAll(List.of(
                    ToolUnion.ofTool(awsCloudWatchLogs()),
                    ToolUnion.ofTool(awsEcs()),
                    ToolUnion.ofTool(awsCloudWatchMetrics()),
                    ToolUnion.ofTool(awsRds())
            ));
        }

        if (canExecuteJobs) {
            // ── Write / action tools (DEVELOPER / ADMINISTRATOR only) ────
            tools.addAll(List.of(
                    // Jira write tools
                    ToolUnion.ofTool(jiraCreateIssue()),
                    ToolUnion.ofTool(jiraUpdateIssue()),
                    ToolUnion.ofTool(jiraAddComment()),
                    ToolUnion.ofTool(jiraTransitionIssue()),
                    ToolUnion.ofTool(jiraAddWorklog()),
                    // Confluence write tools
                    ToolUnion.ofTool(confluenceCreatePage()),
                    ToolUnion.ofTool(confluenceUpdatePage()),
                    // Agent action tools
                    ToolUnion.ofTool(agentRunFix()),
                    ToolUnion.ofTool(agentGetJobStatus()),
                    ToolUnion.ofTool(agentSubmitReviewJob())
            ));
        }

        return Collections.unmodifiableList(tools);
    }

    // ─── Plan MCP tool schemas ────────────────────────────────────────────────────

    private static Tool planRead() {
        return Tool.builder()
                .name("plan_read")
                .description("Read the current markdown content of the active execution plan. "
                        + "Use this to check which tasks are pending, in-progress, or completed before starting work.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .build())
                .build();
    }

    private static Tool planUpdate() {
        return Tool.builder()
                .name("plan_update")
                .description("Update the markdown content of the active execution plan. "
                        + "Use this after completing each task to tick it off (change '- [ ]' to '- [x]') "
                        + "and add a brief result note. Always read the plan first with plan_read before updating.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("markdownContent", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The full updated markdown content of the plan"
                                )))
                                .build())
                        .addRequired("markdownContent")
                        .build())
                .build();
    }

    private static Tool readFile() {
        return Tool.builder()
                .name("read_file")
                .description("Read the contents of a file from the repository. Returns the full text content.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Relative path to the file from the repository root"
                                )))
                                .build())
                        .addRequired("path")
                        .build())
                .build();
    }

    private static Tool writeFile() {
        return Tool.builder()
                .name("write_file")
                .description("Write or overwrite a file in the repository. Creates parent directories if needed.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Relative path to the file from the repository root"
                                )))
                                .putAdditionalProperty("content", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The full content to write to the file"
                                )))
                                .build())
                        .addRequired("path")
                        .addRequired("content")
                        .build())
                .build();
    }

    private static Tool runCommand() {
        return Tool.builder()
                .name("run_command")
                .description("Run a shell command in the repository root. Only allowed commands: ./mvnw, mvn, gradle, git diff, git status, ls, find, cat. Prefer ./mvnw over mvn when a Maven wrapper is present. Returns exit code and output.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("command", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The shell command to execute"
                                )))
                                .build())
                        .addRequired("command")
                        .build())
                .build();
    }

    private static Tool searchCode() {
        return Tool.builder()
                .name("search_code")
                .description("Search for a pattern in the repository using grep. "
                        + "Supports single repository search, specific repository targeting with repoSlug, "
                        + "or searching across all cloned repositories in a multi-repo workspace. "
                        + "Use this to find callers, usages, or related code beyond what the diff shows.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("pattern", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The grep pattern to search for (supports basic regex)"
                                )))
                                .putAdditionalProperty("path", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Relative directory path to scope the search (default: repository root)"
                                )))
                                .putAdditionalProperty("include", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Glob pattern to restrict file types, e.g. '*.java' or '*.ts'"
                                )))
                                .putAdditionalProperty("repoSlug", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Repository slug to search in (optional). If not specified, uses active product context or searches all cloned repos."
                                )))
                                .putAdditionalProperty("repo", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Full repository URL to clone and search (optional). Use repoSlug instead for known repositories."
                                )))
                                .build())
                        .addRequired("pattern")
                        .build())
                .build();
    }

    private static Tool queryCodeGraph() {
        return Tool.builder()
                .name("query_code_graph")
                .description("Query the code graph to find callers, implementations, or dependents of a specific symbol. "
                        + "Use this to understand the impact surface of changed code. "
                        + "Use scope='workspace' to find cross-repo usages across all indexed repositories in the workspace.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("symbol", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The symbol to query (e.g. 'MyClass.myMethod' or 'MyInterface')"
                                )))
                                .putAdditionalProperty("relation", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The relation to query: 'callers' (who calls this), 'implementations' (who implements/extends this), or 'dependents' (all edges pointing at this symbol)"
                                )))
                                .putAdditionalProperty("repoSlug", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Repository slug to scope the query to (e.g. 'code-agent'). Required when the product has multiple repositories. Check the system prompt for available repository slugs."
                                )))
                                .putAdditionalProperty("scope", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Optional: 'repo' (default, current repository only) or 'workspace' (all indexed repos in the workspace). Use 'workspace' when you suspect a symbol is shared across multiple repositories."
                                )))
                                .build())
                        .addRequired("symbol")
                        .addRequired("relation")
                        .build())
                .build();
    }

    private static Tool semanticSearch() {
        return Tool.builder()
                .name("semantic_search")
                .description("Search for code across all indexed repositories by meaning. "
                        + "Use this to find library implementations, shared utilities, base classes, "
                        + "or similar patterns in other repos beyond the one currently being reviewed. "
                        + "Unlike search_code (grep), this understands intent — e.g. searching "
                        + "'payment refund logic' finds relevant code even if those exact words don't appear.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("query", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Natural language description of the code you want to find"
                                )))
                                .putAdditionalProperty("repo", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Optional: restrict search to a specific repository slug. Omit to search across all indexed repos."
                                )))
                                .putAdditionalProperty("top_k", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Number of results to return (default: 10, max: 25)"
                                )))
                                .build())
                        .addRequired("query")
                        .build())
                .build();
    }

    private static Tool listFiles() {
        return Tool.builder()
                .name("list_files")
                .description("List files and directories in the given directory. "
                        + "Supports up to 5 levels deep (default 3).")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("directory", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Relative path to the directory (default: repository root)"
                                )))
                                .putAdditionalProperty("depth", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "How many levels deep to traverse (1-5, default 3)"
                                )))
                                .build())
                        .build())
                .build();
    }

    private static Tool fetchUrl() {
        return Tool.builder()
                .name("fetch_url")
                .description("Fetch a web page (documentation, API reference, framework guide) and return its text content. "
                        + "Use this to look up official docs when reviewing code that uses specific frameworks, libraries, or APIs. "
                        + "Only HTTPS URLs are supported.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("url", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The HTTPS URL to fetch (e.g. official docs page, API reference, framework guide)"
                                )))
                                .build())
                        .addRequired("url")
                        .build())
                .build();
    }

    private static Tool searchKnowledgeBase() {
        return Tool.builder()
                .name("search_knowledge_base")
                .description("Search previously indexed Jira tickets, Confluence documentation pages, "
                        + "Jira file attachments, web documentation, and admin-uploaded static files "
                        + "(.txt, .md, .pdf) by meaning. "
                        + "Use this BEFORE answering questions about past issues, known bugs, "
                        + "architecture decisions, runbooks, team knowledge, external library docs, "
                        + "or any internal documents uploaded by the team. "
                        + "Returns ranked excerpts with source references.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("query", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Natural-language description of what you want to find"
                                )))
                                .putAdditionalProperty("sourceTypes", JsonValue.from(Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string",
                                                "enum", List.of("jira", "confluence", "jira-attachment",
                                                        "web-docs", "static-file")),
                                        "description", "Restrict search to these source types (omit for all sources)"
                                )))
                                .putAdditionalProperty("topK", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Maximum results to return (default: 10, max: 25)"
                                )))
                                .build())
                        .addRequired("query")
                        .build())
                .build();
    }

    private static Tool lookupCustomerContext() {
        return Tool.builder()
                .name("lookup_customer_context")
                .description("Resolve a customer name (or ID) to its full context: deployment environments "
                        + "with AWS account IDs and IAM roles, associated products (git repos, Jira projects, "
                        + "Confluence spaces), and team members by role. "
                        + "ALWAYS call this first when the user mentions a customer name — it stores the "
                        + "`customerId` in the workspace so AWS tools (aws_ecs, aws_cloudwatch_metrics, "
                        + "aws_cloudwatch_logs, aws_rds) can use the correct account without you having to "
                        + "repeat it. "
                        + "Call with NO parameters to list all registered customers with their environments. "
                        + "Lookup priority: customerName (partial, case-insensitive) → customerId (exact) "
                        + "→ jiraProject key → productId.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer name or partial name as the user typed it "
                                                + "(case-insensitive, partial match). Use this when the user "
                                                + "refers to a customer by name, e.g. \"Acme Corp\" or \"acme\"."
                                )))
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Exact customer ID slug, e.g. \"acme-corp\". "
                                                + "Use when the ID is already known from a previous lookup."
                                )))
                                .putAdditionalProperty("jiraProject", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira project key — resolves the owning customer via the linked product"
                                )))
                                .putAdditionalProperty("productId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Product ID — resolves the owning customer and returns full context"
                                )))
                                .build())
                        .build())
                .build();
    }

    private static Tool setProductContext() {
        return Tool.builder()
                .name("set_product_context")
                .description("Set the active product context for the conversation. "
                        + "This configures workspace metadata for git repositories, Jira projects, "
                        + "Confluence spaces, and other product-specific settings. "
                        + "Use after lookup_customer_context to switch between different products. "
                        + "Other tools like search_code, semantic_search, and query_code_graph will "
                        + "automatically use the active product context.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("productId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Product ID from the customer registry"
                                )))
                                .build())
                        .addRequired("productId")
                        .build())
                .build();
    }

    // ─── Jira MCP tool schemas ────────────────────────────────────────────────────

    private static Tool jiraSearchIssues() {
        return Tool.builder()
                .name("jira_search_issues")
                .description("Search Jira issues using JQL (Jira Query Language). "
                        + "Requires a linked Jira account. Use this to find issues by project, assignee, status, etc. "
                        + "Example JQL: 'project = PROJ AND status = Open AND assignee = currentUser()'")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("jql", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "JQL query string, e.g. 'project = PROJ AND status != Done'"
                                )))
                                .putAdditionalProperty("maxResults", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Maximum number of results (1-50, default 10)"
                                )))
                                .build())
                        .addRequired("jql")
                        .build())
                .build();
    }

    private static Tool jiraGetIssue() {
        return Tool.builder()
                .name("jira_get_issue")
                .description("Get full details of a single Jira issue by its key.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .build())
                        .addRequired("key")
                        .build())
                .build();
    }

    private static Tool jiraGetComments() {
        return Tool.builder()
                .name("jira_get_comments")
                .description("Get all comments for a Jira issue.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .build())
                        .addRequired("key")
                        .build())
                .build();
    }

    private static Tool jiraCreateIssue() {
        return Tool.builder()
                .name("jira_create_issue")
                .description("Create a new Jira issue in a project. Supports parent linking (for user stories under a feature/epic), "
                        + "named billing field shortcuts (billingCategory, billingCode), and a generic customFields map for any other fields.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("project", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Project key, e.g. 'PROJ'"
                                )))
                                .putAdditionalProperty("summary", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Issue summary/title"
                                )))
                                .putAdditionalProperty("description", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Issue description (optional)"
                                )))
                                .putAdditionalProperty("issueType", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Issue type: 'Task', 'Bug', 'Story', etc. (default: Task)"
                                )))
                                .putAdditionalProperty("parent", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Parent issue key to link this issue as a child, e.g. 'PROJ-10' (optional). Use for creating Stories under a Feature or Epic."
                                )))
                                .putAdditionalProperty("billingCategory", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Value for the billing-category custom field (optional). Requires jira.billing-category-field to be configured."
                                )))
                                .putAdditionalProperty("billingCode", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Value for the billing-code custom field (optional). Requires jira.billing-code-field to be configured."
                                )))
                                .putAdditionalProperty("customFields", JsonValue.from(Map.of(
                                        "type", "object",
                                        "description", "Arbitrary map of Jira custom field IDs to values, e.g. {\"customfield_10001\": \"value\"}. Use this to copy any fields from a parent issue."
                                )))
                                .build())
                        .addRequired("project")
                        .addRequired("summary")
                        .build())
                .build();
    }

    private static Tool jiraUpdateIssue() {
        return Tool.builder()
                .name("jira_update_issue")
                .description("Update an existing Jira issue. Supports summary, description, assignee (unassign by passing empty string), "
                        + "and moving the issue to another project (best-effort).")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("summary", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "New summary (optional)"
                                )))
                                .putAdditionalProperty("description", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "New description (optional)"
                                )))
                                .putAdditionalProperty("assignee", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira accountId to assign the issue to. Pass empty string \"\" to unassign. Omit to leave unchanged."
                                )))
                                .putAdditionalProperty("project", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Project key to move the issue to, e.g. 'NEWPROJ'. Note: the issue key will change after the move."
                                )))
                                .build())
                        .addRequired("key")
                        .build())
                .build();
    }

    private static Tool jiraAddComment() {
        return Tool.builder()
                .name("jira_add_comment")
                .description("Add a comment to a Jira issue.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("body", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Comment text"
                                )))
                                .build())
                        .addRequired("key")
                        .addRequired("body")
                        .build())
                .build();
    }

    private static Tool jiraTransitionIssue() {
        return Tool.builder()
                .name("jira_transition_issue")
                .description("Transition a Jira issue to a new status (e.g. 'In Progress', 'Done', 'In Review'). "
                        + "The transition must be valid for the issue's current status.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("transitionName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Target status name, e.g. 'In Progress', 'Done', 'In Review'"
                                )))
                                .build())
                        .addRequired("key")
                        .addRequired("transitionName")
                        .build())
                .build();
    }

    private static Tool jiraGetWorklogs() {
        return Tool.builder()
                .name("jira_get_worklogs")
                .description("Get all worklogs (time tracking entries) for a Jira issue.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .build())
                        .addRequired("key")
                        .build())
                .build();
    }

    private static Tool jiraAddWorklog() {
        return Tool.builder()
                .name("jira_add_worklog")
                .description("Add a worklog (time tracking entry) to a Jira issue.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("key", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("timeSpent", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Time spent, e.g. '2h 30m', '1d', '45m'"
                                )))
                                .putAdditionalProperty("comment", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Worklog comment (optional)"
                                )))
                                .putAdditionalProperty("started", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Start datetime in ISO 8601 format, e.g. '2024-01-15T09:00:00.000+0000' (optional, defaults to now)"
                                )))
                                .build())
                        .addRequired("key")
                        .addRequired("timeSpent")
                        .build())
                .build();
    }

    // ─── Confluence MCP tool schemas ──────────────────────────────────────────────

    private static Tool confluenceSearch() {
        return Tool.builder()
                .name("confluence_search")
                .description("Search Confluence pages by text content. "
                        + "Returns matching pages with their IDs and URLs.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("query", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Search query text"
                                )))
                                .putAdditionalProperty("spaceKey", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Restrict search to this space key (optional)"
                                )))
                                .putAdditionalProperty("maxResults", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Maximum number of results (1-50, default 10)"
                                )))
                                .build())
                        .addRequired("query")
                        .build())
                .build();
    }

    private static Tool confluenceGetPage() {
        return Tool.builder()
                .name("confluence_get_page")
                .description("Get the full content of a Confluence page by its ID.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("pageId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Confluence page ID (numeric)"
                                )))
                                .build())
                        .addRequired("pageId")
                        .build())
                .build();
    }

    private static Tool confluenceCreatePage() {
        return Tool.builder()
                .name("confluence_create_page")
                .description("Create a new Confluence page in a space. Body is Markdown.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("spaceKey", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Confluence space key, e.g. 'ENG'"
                                )))
                                .putAdditionalProperty("title", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Page title"
                                )))
                                .putAdditionalProperty("body", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Page content in Markdown format"
                                )))
                                .putAdditionalProperty("parentPageId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Parent page ID to nest the page under (optional)"
                                )))
                                .build())
                        .addRequired("spaceKey")
                        .addRequired("title")
                        .addRequired("body")
                        .build())
                .build();
    }

    private static Tool confluenceUpdatePage() {
        return Tool.builder()
                .name("confluence_update_page")
                .description("Update an existing Confluence page. Body is Markdown.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("pageId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Confluence page ID (numeric)"
                                )))
                                .putAdditionalProperty("title", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "New page title"
                                )))
                                .putAdditionalProperty("body", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "New page content in Markdown format"
                                )))
                                .build())
                        .addRequired("pageId")
                        .addRequired("title")
                        .addRequired("body")
                        .build())
                .build();
    }

    // ─── Agent action tool schemas ────────────────────────────────────────────────

    private static Tool agentRunFix() {
        return Tool.builder()
                .name("agent_run_fix")
                .description("Submit a code-fix job to the agent. The agent will clone the repo, "
                        + "implement the fix described in the prompt, push a branch, and create a PR. "
                        + "Returns a job ID that can be polled with agent_get_job_status.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("repoUrl", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Repository HTTPS URL, e.g. 'https://bitbucket.org/workspace/repo.git'"
                                )))
                                .putAdditionalProperty("jiraKey", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key to associate with the job, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("prompt", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Description of the fix to implement"
                                )))
                                .putAdditionalProperty("branchName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Branch name to push to (optional, auto-generated if omitted)"
                                )))
                                .build())
                        .addRequired("repoUrl")
                        .addRequired("jiraKey")
                        .addRequired("prompt")
                        .build())
                .build();
    }

    private static Tool agentGetJobStatus() {
        return Tool.builder()
                .name("agent_get_job_status")
                .description("Get the current status and result of a previously submitted agent job.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("jobId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Job ID returned by agent_run_fix"
                                )))
                                .build())
                        .addRequired("jobId")
                        .build())
                .build();
    }

    private static Tool agentSubmitReviewJob() {
        return Tool.builder()
                .name("agent_submit_review_job")
                .description("Submit a PR review job to the agent queue. The agent will clone the repo, "
                        + "review the pull request code changes, analyze code quality, and provide feedback. "
                        + "Returns a job ID that can be polled with agent_get_job_status.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("repoUrl", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Repository HTTPS URL, e.g. 'https://bitbucket.org/workspace/repo.git'"
                                )))
                                .putAdditionalProperty("prId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Pull request ID or number"
                                )))
                                .putAdditionalProperty("targetBranch", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Target branch name (optional, defaults to main/master)"
                                )))
                                .putAdditionalProperty("jiraKey", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key to associate with the review, e.g. 'PROJ-123' (optional)"
                                )))
                                .putAdditionalProperty("extraRules", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Additional review rules or guidelines to apply (optional)"
                                )))
                                .build())
                        .addRequired("repoUrl")
                        .addRequired("prId")
                        .build())
                .build();
    }

    // ─── AWS tool schemas ─────────────────────────────────────────────────────────

    private static Tool awsCloudWatchLogs() {
        return Tool.builder()
                .name("aws_cloudwatch_logs")
                .description("Query AWS CloudWatch Logs for a customer environment. "
                        + "Use this to fetch application logs, search for errors or exceptions, "
                        + "or tail log streams from ECS Fargate containers. "
                        + "Cross-account access is handled automatically via IAM role assumption. "
                        + "Actions: list_groups (list log groups), list_streams (list streams in a group), "
                        + "filter_events (filter log events by pattern and/or time range).")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID from the registry (e.g. 'acme-corp')"
                                )))
                                .putAdditionalProperty("environmentName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Environment name (e.g. 'production', 'acceptance')"
                                )))
                                .putAdditionalProperty("action", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("list_groups", "list_streams", "filter_events"),
                                        "description", "Action to perform"
                                )))
                                .putAdditionalProperty("logGroupName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Log group name or prefix. Required for list_streams and filter_events."
                                )))
                                .putAdditionalProperty("logStreamName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Restrict filter_events to a specific log stream (optional)"
                                )))
                                .putAdditionalProperty("filterPattern", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "CloudWatch filter pattern, e.g. 'ERROR' or '?Exception ?Error'"
                                )))
                                .putAdditionalProperty("startTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Start of the time range, ISO-8601 (e.g. 2025-01-01T00:00:00Z)"
                                )))
                                .putAdditionalProperty("endTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "End of the time range, ISO-8601 (defaults to now)"
                                )))
                                .putAdditionalProperty("limit", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Maximum number of log events to return (default: 100, max: 500)"
                                )))
                                .build())
                        .addRequired("customerId")
                        .addRequired("environmentName")
                        .addRequired("action")
                        .build())
                .build();
    }

    private static Tool awsEcs() {
        return Tool.builder()
                .name("aws_ecs")
                .description("Inspect AWS ECS / Fargate resources for a customer environment. "
                        + "Use this to check container health, service status, task definitions, "
                        + "and diagnose deployment or configuration issues. "
                        + "Cross-account access is handled automatically via IAM role assumption. "
                        + "Actions: list_clusters, describe_cluster, list_services, describe_service "
                        + "(shows desired/running/pending counts and deployment status), "
                        + "list_tasks (use desiredStatus=STOPPED to find failed tasks), "
                        + "describe_task (shows container exit codes and stop reasons), "
                        + "describe_task_definition (shows image, CPU/memory, env var count).")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID from the registry (e.g. 'acme-corp')"
                                )))
                                .putAdditionalProperty("environmentName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Environment name (e.g. 'production', 'acceptance')"
                                )))
                                .putAdditionalProperty("action", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("list_clusters", "describe_cluster", "list_services",
                                                "describe_service", "list_tasks", "describe_task",
                                                "describe_task_definition"),
                                        "description", "Action to perform"
                                )))
                                .putAdditionalProperty("clusterArn", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS cluster ARN or name. Required for most actions."
                                )))
                                .putAdditionalProperty("serviceArn", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS service ARN or name. Required for describe_service; optional filter for list_tasks."
                                )))
                                .putAdditionalProperty("taskArn", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS task ARN. Required for describe_task."
                                )))
                                .putAdditionalProperty("taskDefinitionArn", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Task definition ARN or family:revision. Required for describe_task_definition."
                                )))
                                .putAdditionalProperty("desiredStatus", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("RUNNING", "STOPPED"),
                                        "description", "Filter list_tasks by desired status. Use STOPPED to find failed tasks."
                                )))
                                .build())
                        .addRequired("customerId")
                        .addRequired("environmentName")
                        .addRequired("action")
                        .build())
                .build();
    }

    private static Tool awsCloudWatchMetrics() {
        return Tool.builder()
                .name("aws_cloudwatch_metrics")
                .description("Query AWS CloudWatch Metrics for ECS service resource utilisation. "
                        + "Use this to retrieve CPU or memory utilisation trends for an ECS service "
                        + "over a time window — useful for spotting spikes, capacity issues, or "
                        + "comparing acceptance vs production resource usage. "
                        + "Cross-account access is handled automatically via IAM role assumption.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID from the registry (e.g. 'acme-corp')"
                                )))
                                .putAdditionalProperty("environmentName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Environment name (e.g. 'production', 'acceptance')"
                                )))
                                .putAdditionalProperty("metricName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "CloudWatch metric name, e.g. CPUUtilization or MemoryUtilization"
                                )))
                                .putAdditionalProperty("clusterName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS cluster name (used as dimension filter)"
                                )))
                                .putAdditionalProperty("serviceName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS service name (used as dimension filter)"
                                )))
                                .putAdditionalProperty("startTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Start of the time range, ISO-8601 (defaults to 1 hour ago)"
                                )))
                                .putAdditionalProperty("endTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "End of the time range, ISO-8601 (defaults to now)"
                                )))
                                .putAdditionalProperty("period", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Aggregation period in seconds (minimum 60, default 300)"
                                )))
                                .putAdditionalProperty("stat", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("Average", "Maximum", "Minimum", "Sum"),
                                        "description", "Statistic to retrieve (default: Average)"
                                )))
                                .build())
                        .addRequired("customerId")
                        .addRequired("environmentName")
                        .addRequired("metricName")
                        .build())
                .build();
    }

    private static Tool awsRds() {
        return Tool.builder()
                .name("aws_rds")
                .description("Inspect AWS RDS DB instances and Aurora clusters, and fetch database metrics. "
                        + "Use this to list or describe RDS instances/clusters (engine, status, endpoint, storage, "
                        + "backup retention) or to retrieve CloudWatch AWS/RDS metrics such as CPUUtilization, "
                        + "DatabaseConnections, FreeStorageSpace, ReadLatency, or WriteLatency for a specific instance. "
                        + "Cross-account access is handled automatically via IAM role assumption.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID from the registry (e.g. 'acme-corp')"
                                )))
                                .putAdditionalProperty("environmentName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Environment name (e.g. 'production', 'acceptance')"
                                )))
                                .putAdditionalProperty("action", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("list_instances", "describe_instance",
                                                "list_clusters", "describe_cluster", "get_instance_metrics"),
                                        "description", "Action to perform"
                                )))
                                .putAdditionalProperty("dbInstanceId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "DB instance identifier — required for describe_instance and get_instance_metrics"
                                )))
                                .putAdditionalProperty("dbClusterId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "DB cluster identifier — required for describe_cluster"
                                )))
                                .putAdditionalProperty("metricName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "CloudWatch metric name for get_instance_metrics, "
                                                + "e.g. CPUUtilization, DatabaseConnections, FreeStorageSpace, "
                                                + "ReadLatency, WriteLatency, ReadIOPS, WriteIOPS"
                                )))
                                .putAdditionalProperty("startTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Start of the time range, ISO-8601 (defaults to 1 hour ago)"
                                )))
                                .putAdditionalProperty("endTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "End of the time range, ISO-8601 (defaults to now)"
                                )))
                                .putAdditionalProperty("period", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Aggregation period in seconds for get_instance_metrics (minimum 60, default 300)"
                                )))
                                .putAdditionalProperty("stat", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("Average", "Maximum", "Minimum", "Sum"),
                                        "description", "Statistic to retrieve for get_instance_metrics (default: Average)"
                                )))
                                .build())
                        .addRequired("customerId")
                        .addRequired("environmentName")
                        .addRequired("action")
                        .build())
                .build();
    }

}
