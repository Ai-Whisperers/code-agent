package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public final class ConfluenceToolSchemas {

    private ConfluenceToolSchemas() { }

    public static Tool confluenceSearch() {
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

    public static Tool confluenceGetPage() {
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

    public static Tool confluenceCreatePage() {
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

    public static Tool confluenceUpdatePage() {
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
}
