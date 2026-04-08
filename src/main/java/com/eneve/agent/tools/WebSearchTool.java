package com.eneve.agent.tools;

import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.workspace.WorkspaceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Calls the Tavily Search REST API to perform web searches on behalf of the Claude agent.
 *
 * <p>Returns a human-readable block containing:
 * <ul>
 *   <li>An optional AI-generated answer summary from Tavily</li>
 *   <li>Numbered search results with title, URL, and content snippet</li>
 * </ul>
 *
 * <p>Configuration keys (via {@link SettingsService}):
 * <ul>
 *   <li>{@code tools.web-search.enabled}          — toggle (default: true)</li>
 *   <li>{@code tools.web-search.tavily-api-key}   — Tavily API key (required)</li>
 *   <li>{@code tools.web-search.max-results}       — results per query (default: 5)</li>
 * </ul>
 */
@ApplicationScoped
public class WebSearchTool implements ToolExecutor {

    private static final String TAVILY_SEARCH_URL = "https://api.tavily.com/search";
    private static final int    TIMEOUT_SECONDS   = 20;
    private static final int    MAX_SNIPPET_CHARS = 500;

    @Inject SettingsService settings;
    @Inject ObjectMapper    mapper;

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        if (!Boolean.parseBoolean(settings.get("tools.web-search.enabled", "true"))) {
            return "ERROR: web_search tool is disabled.";
        }

        String apiKey = settings.getSecret("tools.web-search.tavily-api-key");
        if (apiKey == null || apiKey.isBlank()) {
            return "ERROR: Tavily API key is not configured. Set tools.web-search.tavily-api-key.";
        }

        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return "ERROR: 'query' parameter is required";
        }

        int maxResults;
        try {
            maxResults = Integer.parseInt(settings.get("tools.web-search.max-results", "5"));
        } catch (NumberFormatException e) {
            maxResults = 5;
        }

        // Allow per-call override from Claude
        if (input.get("max_results") instanceof Number n) {
            maxResults = Math.min(n.intValue(), 10);
        }

        try {
            String requestBody = mapper.writeValueAsString(Map.of(
                    "api_key",       apiKey,
                    "query",         query,
                    "search_depth",  "basic",
                    "max_results",   maxResults,
                    "include_answer", true
            ));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_SEARCH_URL))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                return "ERROR: Tavily API key is invalid or expired.";
            }
            if (response.statusCode() == 429) {
                return "ERROR: Tavily rate limit exceeded. Try again shortly.";
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "ERROR: Tavily API returned HTTP " + response.statusCode();
            }

            return formatResults(response.body(), query);

        } catch (java.net.http.HttpTimeoutException e) {
            return "ERROR: Tavily search timed out after " + TIMEOUT_SECONDS + "s";
        } catch (Exception e) {
            return "ERROR: web_search failed: " + e.getMessage();
        }
    }

    private String formatResults(String json, String query) {
        try {
            JsonNode root = mapper.readTree(json);
            StringBuilder sb = new StringBuilder();
            sb.append("=== WEB SEARCH RESULTS — UNTRUSTED EXTERNAL CONTENT ===\n");
            sb.append("Query: ").append(query).append("\n\n");

            // Optional AI-generated answer
            JsonNode answer = root.path("answer");
            if (!answer.isMissingNode() && !answer.isNull() && !answer.asText().isBlank()) {
                sb.append("Summary: ").append(answer.asText()).append("\n\n");
            }

            // Individual results
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                int idx = 1;
                for (JsonNode result : results) {
                    String title   = result.path("title").asText("");
                    String url     = result.path("url").asText("");
                    String content = result.path("content").asText("");

                    if (content.length() > MAX_SNIPPET_CHARS) {
                        content = content.substring(0, MAX_SNIPPET_CHARS) + "…";
                    }

                    sb.append(idx++).append(". **").append(title).append("**\n");
                    sb.append("   URL: ").append(url).append("\n");
                    if (!content.isBlank()) {
                        sb.append("   ").append(content.replace("\n", "\n   ")).append("\n");
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("No results found.\n");
            }

            sb.append("=== END OF WEB SEARCH RESULTS ===");
            return sb.toString();

        } catch (Exception e) {
            return "ERROR: Failed to parse Tavily response: " + e.getMessage();
        }
    }
}
