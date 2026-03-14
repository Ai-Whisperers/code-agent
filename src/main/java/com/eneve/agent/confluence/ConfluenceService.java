package com.eneve.agent.confluence;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Client for the Confluence Cloud REST API v2.
 * Creates or updates pages in Confluence spaces using XHTML storage format.
 */
@ApplicationScoped
public class ConfluenceService {

    private static final Logger LOG = Logger.getLogger(ConfluenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "confluence.base.url", defaultValue = "")
    String baseUrl;

    @ConfigProperty(name = "confluence.user", defaultValue = "")
    String user;

    @ConfigProperty(name = "confluence.api.token", defaultValue = "")
    String apiToken;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank()
                && user != null && !user.isBlank()
                && apiToken != null && !apiToken.isBlank();
    }

    /**
     * Creates or updates a Confluence page. If a page with the given title already
     * exists under the specified parent, it is updated; otherwise a new page is created.
     *
     * @param spaceKey       the Confluence space key
     * @param parentPageId   parent page ID (may be null for top-level pages)
     * @param title          page title
     * @param markdownBody   raw markdown content (converted to storage format internally)
     * @return the full URL of the created/updated page, or null on failure
     */
    public String createOrUpdatePage(String spaceKey, String parentPageId,
                                     String title, String markdownBody) {
        if (!isEnabled()) {
            LOG.warn("Confluence is not configured, skipping page publish");
            return null;
        }

        String storageBody = MarkdownToStorageConverter.convert(markdownBody);

        try {
            String existingPageId = findPageByTitle(spaceKey, title);
            if (existingPageId != null) {
                return updatePage(existingPageId, title, storageBody);
            } else {
                return createPage(spaceKey, parentPageId, title, storageBody);
            }
        } catch (Exception e) {
            LOG.errorf("Failed to publish Confluence page '%s' in space %s: %s",
                    title, spaceKey, e.getMessage());
            return null;
        }
    }

    /**
     * Searches for a page by title using the Confluence v1 CQL search endpoint.
     * Returns the page ID if found, null otherwise.
     */
    public String findPageByTitle(String spaceKey, String title) {
        try {
            String cql = "space=\"" + spaceKey + "\" AND title=\"" + title + "\" AND type=page";
            String encoded = URLEncoder.encode(cql, StandardCharsets.UTF_8);
            String url = baseUrl + "/wiki/rest/api/content/search?cql=" + encoded + "&limit=1";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Confluence search returned %d: %s", response.statusCode(), response.body());
                return null;
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                return results.get(0).path("id").asText();
            }
            return null;
        } catch (Exception e) {
            LOG.warnf("Failed to search Confluence for page '%s': %s", title, e.getMessage());
            return null;
        }
    }

    private String createPage(String spaceKey, String parentPageId, String title, String storageBody) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("spaceId", resolveSpaceId(spaceKey));
        body.put("status", "current");
        body.put("title", title);

        if (parentPageId != null && !parentPageId.isBlank()) {
            body.put("parentId", parentPageId);
        }

        ObjectNode bodyNode = body.putObject("body");
        ObjectNode storage = bodyNode.putObject("storage");
        storage.put("representation", "storage");
        storage.put("value", storageBody);

        String url = baseUrl + "/wiki/api/v2/pages";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOG.errorf("Confluence create page failed (%d): %s", response.statusCode(), response.body());
            return null;
        }

        JsonNode result = MAPPER.readTree(response.body());
        String pageId = result.path("id").asText();
        LOG.infof("Created Confluence page '%s' (id=%s) in space %s", title, pageId, spaceKey);
        return buildPageUrl(pageId);
    }

    private String updatePage(String pageId, String title, String storageBody) throws Exception {
        int currentVersion = getCurrentVersion(pageId);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("id", pageId);
        body.put("status", "current");
        body.put("title", title);

        ObjectNode bodyNode = body.putObject("body");
        ObjectNode storage = bodyNode.putObject("storage");
        storage.put("representation", "storage");
        storage.put("value", storageBody);

        ObjectNode version = body.putObject("version");
        version.put("number", currentVersion + 1);

        String url = baseUrl + "/wiki/api/v2/pages/" + pageId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOG.errorf("Confluence update page failed (%d): %s", response.statusCode(), response.body());
            return null;
        }

        LOG.infof("Updated Confluence page '%s' (id=%s) to version %d", title, pageId, currentVersion + 1);
        return buildPageUrl(pageId);
    }

    private int getCurrentVersion(String pageId) throws Exception {
        String url = baseUrl + "/wiki/api/v2/pages/" + pageId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode result = MAPPER.readTree(response.body());
        return result.path("version").path("number").asInt(1);
    }

    /**
     * Resolves a space key to a space ID using the v2 API.
     * The v2 pages endpoint requires a numeric space ID rather than a key.
     */
    private String resolveSpaceId(String spaceKey) throws Exception {
        String url = baseUrl + "/wiki/api/v2/spaces?keys=" + URLEncoder.encode(spaceKey, StandardCharsets.UTF_8) + "&limit=1";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to resolve space key '" + spaceKey + "': HTTP " + response.statusCode());
        }

        JsonNode results = MAPPER.readTree(response.body()).path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new RuntimeException("Confluence space not found: " + spaceKey);
        }
        return results.get(0).path("id").asText();
    }

    private String buildPageUrl(String pageId) {
        return baseUrl + "/wiki/pages/" + pageId;
    }

    private String authHeader() {
        String credentials = user + ":" + apiToken;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
