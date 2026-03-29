package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public final class WorkspaceToolSchemas {

    private WorkspaceToolSchemas() { }

    public static Tool readFile() {
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

    public static Tool writeFile() {
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

    public static Tool runCommand() {
        return Tool.builder()
                .name("run_command")
                .description("Run a shell command in the repository root. "
                        + "Allowed command prefixes: ./mvnw, mvn, gradle, git diff, git status, git log, git add, git commit, ls, find, cat, grep, dotnet, npm, npx. "
                        + "Prefer ./mvnw over mvn when a Maven wrapper is present. "
                        + "For JavaScript/TypeScript projects: always run 'npm ci' (or 'npm install' when no package-lock.json) before running 'npm test', because node_modules are not present in a fresh clone. "
                        + "Returns exit code and stdout/stderr output.")
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

    public static Tool searchCode() {
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

    public static Tool queryCodeGraph() {
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

    public static Tool semanticSearch() {
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

    public static Tool listFiles() {
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

    public static Tool fetchUrl() {
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
}
