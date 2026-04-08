package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public final class WebSearchToolSchemas {

    private WebSearchToolSchemas() { }

    public static Tool webSearch() {
        return Tool.builder()
                .name("web_search")
                .description("Search the web for current, real-time information on any topic. "
                        + "Use this when you need up-to-date information that may not be in the "
                        + "knowledge base — such as industry best practices, standards, technology "
                        + "documentation, competitor analysis, regulatory requirements, or any "
                        + "publicly available information relevant to refining a product scope item. "
                        + "Returns a summary answer (when available) plus ranked results with titles, "
                        + "URLs, and content snippets. "
                        + "Follow up with fetch_url on a specific result URL to read the full page.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("query", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The search query — be specific for better results"
                                )))
                                .putAdditionalProperty("max_results", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Number of results to return (default: 5, max: 10)"
                                )))
                                .build())
                        .addRequired("query")
                        .build())
                .build();
    }
}
