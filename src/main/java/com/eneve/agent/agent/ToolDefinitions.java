package com.eneve.agent.agent;

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
                ToolUnion.ofTool(lookupCustomerContext())
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
                ToolUnion.ofTool(lookupCustomerContext())
        );
    }

    public static List<ToolUnion> chat() {
        return List.of(
                ToolUnion.ofTool(searchKnowledgeBase()),
                ToolUnion.ofTool(lookupCustomerContext()),
                ToolUnion.ofTool(semanticSearch()),
                ToolUnion.ofTool(searchCode()),
                ToolUnion.ofTool(queryCodeGraph()),
                ToolUnion.ofTool(fetchUrl())
        );
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
                        + "and Jira file attachments by meaning. "
                        + "Use this BEFORE answering questions about past issues, known bugs, "
                        + "architecture decisions, runbooks, or team knowledge. "
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
                                                "enum", List.of("jira", "confluence", "jira-attachment")),
                                        "description", "Restrict search to these source types (omit for all sources)"
                                )))
                                .putAdditionalProperty("productId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Restrict search to a specific product's knowledge (omit for all products)"
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
                .description("Look up customer and product information from the registry: "
                        + "team members by role (productOwner, engineering, devops, operations, qa), "
                        + "deployment environments with AWS account IDs and regions, "
                        + "Jira project keys, Confluence space keys, and Git workspace details. "
                        + "Use this when you need to know who to notify, which AWS account an environment runs in, "
                        + "or which Jira project to reference.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("productId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Product ID from the registry"
                                )))
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID (returns all products if multiple exist)"
                                )))
                                .putAdditionalProperty("jiraProject", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira project key — used to look up the owning product"
                                )))
                                .build())
                        .build())
                .build();
    }

}
