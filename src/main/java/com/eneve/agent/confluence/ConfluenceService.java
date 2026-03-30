package com.eneve.agent.confluence;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import com.eneve.agent.agent.MermaidPngRenderer;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Client for the Confluence Cloud REST API v2.
 * Creates or updates pages in Confluence spaces using XHTML storage format.
 * Mermaid diagrams are rendered locally via the Mermaid CLI (mmdc) and uploaded
 * as page attachments.
 */
@ApplicationScoped
public class ConfluenceService {

    private static final Logger LOG = Logger.getLogger(ConfluenceService.class);
    @Inject ObjectMapper mapper;

    @Inject
    SettingsService settingsService;

    @Inject
    MermaidPngRenderer mermaidPngRenderer;

    @Inject HttpClient httpClient;

    public boolean isEnabled() {
        return !settingsService.get("confluence.base.url", "").isBlank()
                && !settingsService.get("confluence.user", "").isBlank()
                && !settingsService.getSecret("confluence.api.token").isBlank();
    }

    public boolean isConfigured() {
        return isEnabled();
    }

    // Getters for system credentials (used by LinkedAccountService for fallback)
    public String getBaseUrl() { return settingsService.get("confluence.base.url", ""); }
    public String getUser() { return settingsService.get("confluence.user", ""); }
    public String getApiToken() { return settingsService.getSecret("confluence.api.token"); }

    /**
     * Test Confluence connection with the provided credentials.
     * Returns true if the connection is valid, false otherwise.
     */
    public static boolean testConnectionOAuth(String testBaseUrl, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(testBaseUrl + "/wiki/rest/api/space"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean testConnection(String testBaseUrl, String testUser, String testApiToken) {
        try {
            String credentials = testUser + ":" + testApiToken;
            String auth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(testBaseUrl + "/wiki/rest/api/space"))
                    .header("Authorization", auth)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    public record PageResult(String pageId, String pageUrl) {}

    /**
     * Creates or updates a Confluence page. If a page with the given title already
     * exists under the specified parent, it is updated; otherwise a new page is created.
     * Mermaid diagrams in the markdown are rendered server-side and uploaded as
     * page attachments.
     *
     * @param spaceKey       the Confluence space key
     * @param parentPageId   parent page ID (may be null for top-level pages)
     * @param title          page title
     * @param markdownBody   raw markdown content (converted to storage format internally)
     * @return result containing the page ID and URL, or null on failure
     */
    public PageResult createOrUpdatePage(String spaceKey, String parentPageId,
                                         String title, String markdownBody) {
        if (!isEnabled()) {
            LOG.warn("Confluence is not configured, skipping page publish");
            return null;
        }

        MarkdownToStorageConverter.ConversionResult conversion =
                MarkdownToStorageConverter.convert(markdownBody);

        try {
            String existingPageId = findPageByTitle(spaceKey, title);
            String pageId;
            if (existingPageId != null) {
                String url = updatePage(existingPageId, title, conversion.xhtml());
                pageId = existingPageId;
                if (url == null) return null;
                uploadMermaidDiagrams(pageId, conversion.mermaidDiagrams());
                return new PageResult(pageId, url);
            } else {
                pageId = createPage(spaceKey, parentPageId, title, conversion.xhtml());
                if (pageId == null) return null;
                uploadMermaidDiagrams(pageId, conversion.mermaidDiagrams());
                return new PageResult(pageId, buildPageUrl(pageId));
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
            String url = settingsService.get("confluence.base.url", "") + "/wiki/rest/api/content/search?cql=" + encoded + "&limit=1";

            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
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

            JsonNode root = mapper.readTree(response.body());
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

    // ─── Mermaid rendering & attachment upload ─────────────────────────

    private void uploadMermaidDiagrams(String pageId,
                                       List<MarkdownToStorageConverter.MermaidDiagram> diagrams) {
        if (diagrams.isEmpty()) return;

        for (MarkdownToStorageConverter.MermaidDiagram diagram : diagrams) {
            try {
                byte[] png = renderMermaidToPng(diagram.sourceCode());
                uploadAttachment(pageId, diagram.filename(), png, "image/png");
                LOG.infof("Uploaded Mermaid attachment '%s' (%d bytes) to page %s",
                        diagram.filename(), png.length, pageId);
            } catch (Exception e) {
                LOG.warnf("Failed to render/upload Mermaid diagram '%s': %s",
                        diagram.filename(), e.getMessage());
            }
        }
    }

    /**
     * Renders a Mermaid diagram to PNG by delegating to {@link MermaidPngRenderer}.
     */
    byte[] renderMermaidToPng(String mermaidCode) throws Exception {
        return mermaidPngRenderer.renderToPng(mermaidCode);
    }

    /**
     * Uploads a binary file as a page attachment via the Confluence v1 REST API.
     * If an attachment with the same filename already exists, it is updated.
     */
    void uploadAttachment(String pageId, String filename, byte[] data,
                          String contentType) throws Exception {
        String boundary = "----AttachBoundary" + System.nanoTime();
        byte[] body = buildMultipartBody(boundary, filename, data, contentType);

        String url = settingsService.get("confluence.base.url", "") + "/wiki/rest/api/content/" + pageId + "/child/attachment";
        HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Atlassian-Token", "nocheck")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }

        if (response.statusCode() == 409 || response.statusCode() == 400) {
            updateExistingAttachment(pageId, filename, data, contentType, boundary);
            return;
        }

        throw new RuntimeException("Attachment upload failed (" + response.statusCode() + "): "
                + response.body());
    }

    private void updateExistingAttachment(String pageId, String filename,
                                          byte[] data, String contentType,
                                          String boundary) throws Exception {
        String attachmentId = findAttachmentId(pageId, filename);
        if (attachmentId == null) {
            LOG.warnf("409 on attachment upload but could not find existing attachment '%s'", filename);
            return;
        }

        byte[] body = buildMultipartBody(boundary, filename, data, contentType);
        String url = settingsService.get("confluence.base.url", "") + "/wiki/rest/api/content/" + pageId
                + "/child/attachment/" + attachmentId + "/data";

        HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Atlassian-Token", "nocheck")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Attachment update failed (" + response.statusCode() + "): "
                    + response.body());
        }
    }

    private String findAttachmentId(String pageId, String filename) throws Exception {
        String url = settingsService.get("confluence.base.url", "") + "/wiki/rest/api/content/" + pageId
                + "/child/attachment?filename=" + URLEncoder.encode(filename, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        JsonNode results = mapper.readTree(response.body()).path("results");
        if (results.isArray() && !results.isEmpty()) {
            return results.get(0).path("id").asText();
        }
        return null;
    }

    private static byte[] buildMultipartBody(String boundary, String filename,
                                             byte[] data, String contentType) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n";
            bos.write(header.getBytes(StandardCharsets.UTF_8));
            bos.write(data);
            String footer = "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"minorEdit\"\r\n\r\n"
                    + "true\r\n"
                    + "--" + boundary + "--\r\n";
            bos.write(footer.getBytes(StandardCharsets.UTF_8));
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build multipart body", e);
        }
    }

    // ─── Page CRUD ────────────────────────────────────────────────────

    /**
     * Creates a page and returns the page ID (not the URL),
     * so the caller can upload attachments before building the final URL.
     */
    private String createPage(String spaceKey, String parentPageId,
                              String title, String storageBody) throws Exception {
        ObjectNode body = mapper.createObjectNode();
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

        String url = settingsService.get("confluence.base.url", "") + "/wiki/api/v2/pages";
        HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOG.errorf("Confluence create page failed (%d): %s", response.statusCode(), response.body());
            return null;
        }

        JsonNode result = mapper.readTree(response.body());
        String pageId = result.path("id").asText();
        LOG.infof("Created Confluence page '%s' (id=%s) in space %s", title, pageId, spaceKey);
        return pageId;
    }

    private String updatePage(String pageId, String title, String storageBody) throws Exception {
        int currentVersion = getCurrentVersion(pageId);

        ObjectNode body = mapper.createObjectNode();
        body.put("id", pageId);
        body.put("status", "current");
        body.put("title", title);

        ObjectNode bodyNode = body.putObject("body");
        ObjectNode storage = bodyNode.putObject("storage");
        storage.put("representation", "storage");
        storage.put("value", storageBody);

        ObjectNode version = body.putObject("version");
        version.put("number", currentVersion + 1);

        String url = settingsService.get("confluence.base.url", "") + "/wiki/api/v2/pages/" + pageId;
        HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
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
        String url = settingsService.get("confluence.base.url", "") + "/wiki/api/v2/pages/" + pageId;
        HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode result = mapper.readTree(response.body());
        return result.path("version").path("number").asInt(1);
    }

    /**
     * Resolves a space key to a space ID using the v2 API.
     * The v2 pages endpoint requires a numeric space ID rather than a key.
     */
    private String resolveSpaceId(String spaceKey) throws Exception {
        String url = settingsService.get("confluence.base.url", "") + "/wiki/api/v2/spaces?keys=" + URLEncoder.encode(spaceKey, StandardCharsets.UTF_8) + "&limit=1";
        HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to resolve space key '" + spaceKey + "': HTTP " + response.statusCode());
        }

        JsonNode results = mapper.readTree(response.body()).path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new RuntimeException("Confluence space not found: " + spaceKey);
        }
        return results.get(0).path("id").asText();
    }

    // ─── Knowledge-base indexing helpers ──────────────────────────────────

    /**
     * Lightweight page descriptor returned by {@link #listPagesInSpace}.
     */
    public record ConfluencePage(String pageId, String title, String url) {}

    /**
     * Lists all current pages in a Confluence space, paginating until exhausted.
     * Returns page IDs, titles, and URLs — body content is fetched separately
     * via {@link #getPageBody(String)}.
     *
     * @param spaceKey the Confluence space key
     * @return list of page descriptors, empty list on error
     */
    public List<ConfluencePage> listPagesInSpace(String spaceKey) {
        if (!isEnabled()) {
            LOG.warn("Confluence not configured, cannot list pages");
            return List.of();
        }
        List<ConfluencePage> pages = new java.util.ArrayList<>();
        String cursor = null;

        try {
            String spaceId = resolveSpaceId(spaceKey);
            while (true) {
                String url = settingsService.get("confluence.base.url", "") + "/wiki/api/v2/pages?spaceId=" + spaceId
                        + "&status=current&limit=50"
                        + (cursor != null ? "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) : "");

                HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                        .uri(URI.create(url))
                        .header("Authorization", authHeader())
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOG.warnf("Confluence listPagesInSpace failed (%d): %s",
                            response.statusCode(), response.body());
                    break;
                }

                JsonNode root = mapper.readTree(response.body());
                JsonNode results = root.path("results");
                if (!results.isArray() || results.isEmpty()) break;

                for (JsonNode page : results) {
                    String pageId = page.path("id").asText();
                    String title = page.path("title").asText("");
                    pages.add(new ConfluencePage(pageId, title, buildPageUrl(pageId)));
                }

                JsonNode nextLink = root.path("_links").path("next");
                if (nextLink.isMissingNode() || nextLink.isNull() || nextLink.asText("").isBlank()) break;

                // Extract cursor from the next link query string
                String nextUrl = nextLink.asText("");
                int ci = nextUrl.indexOf("cursor=");
                if (ci < 0) break;
                String rest = nextUrl.substring(ci + 7);
                int amp = rest.indexOf('&');
                cursor = amp >= 0 ? rest.substring(0, amp) : rest;
            }
            LOG.infof("Confluence listPagesInSpace(%s): found %d pages", spaceKey, pages.size());
        } catch (Exception e) {
            LOG.errorf("Failed to list Confluence pages in space %s: %s", spaceKey, e.getMessage());
        }
        return pages;
    }

    /**
     * Fetches the plain-text body of a Confluence page by stripping its XHTML storage format.
     *
     * @param pageId the Confluence page ID
     * @return plain-text body, or null on error
     */
    public String getPageBody(String pageId) {
        if (!isEnabled()) return null;
        try {
            String url = settingsService.get("confluence.base.url", "") + "/wiki/api/v2/pages/" + pageId
                    + "?body-format=storage";
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(url))
                    .header("Authorization", authHeader())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Confluence getPageBody failed (%d) for page %s", response.statusCode(), pageId);
                return null;
            }

            JsonNode root = mapper.readTree(response.body());
            String storageValue = root.path("body").path("storage").path("value").asText("");
            return stripXhtml(storageValue);
        } catch (Exception e) {
            LOG.errorf("Failed to fetch Confluence page body %s: %s", pageId, e.getMessage());
            return null;
        }
    }

    /**
     * Strips XHTML/storage-format tags, returning human-readable plain text.
     * Uses a simple tag-removal regex; sufficient for embedding text.
     */
    private static String stripXhtml(String xhtml) {
        if (xhtml == null || xhtml.isBlank()) return "";
        // Replace block-level tags with newlines before stripping
        String text = xhtml
                .replaceAll("(?i)<(p|li|h[1-6]|br|tr|div)[^>]*>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&quot;", "\"")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return text;
    }

    /**
     * Extracts a page ID from a Confluence page URL.
     * Supports patterns like /pages/12345 and /wiki/spaces/KEY/pages/12345.
     * Returns null if the URL is not a recognisable Confluence page URL.
     */
    public String extractPageIdFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        var m = java.util.regex.Pattern
                .compile("/pages/(\\d+)")
                .matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    private String buildPageUrl(String pageId) {
        return settingsService.get("confluence.base.url", "") + "/wiki/pages/" + pageId;
    }

    private String authHeader() {
        String credentials = settingsService.get("confluence.user", "") + ":" + settingsService.getSecret("confluence.api.token");
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    // ─── Credential-based methods for MCP tools ───────────────────────────────────

    public record ConfluenceCredentials(String baseUrl, String username, String apiToken, String authType) {

        public static ConfluenceCredentials basic(String baseUrl, String username, String apiToken) {
            return new ConfluenceCredentials(baseUrl, username, apiToken, "apitoken");
        }

        public static ConfluenceCredentials oauth(String baseUrl, String username, String accessToken) {
            return new ConfluenceCredentials(baseUrl, username, accessToken, "oauth");
        }

        public boolean isOAuth() { return "oauth".equalsIgnoreCase(authType); }
    }

    private static String authHeader(ConfluenceCredentials creds) {
        if (creds.isOAuth()) return "Bearer " + creds.apiToken();
        String encoded = Base64.getEncoder()
                .encodeToString((creds.username() + ":" + creds.apiToken()).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    /**
     * Search Confluence pages using CQL with provided credentials.
     */
    public List<ConfluencePage> searchPages(String cql, int maxResults, ConfluenceCredentials creds) {
        int cap = Math.min(Math.max(1, maxResults), 50);
        String encodedCql;
        try {
            encodedCql = URLEncoder.encode(cql, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warnf("Failed to encode CQL: %s", e.getMessage());
            return List.of();
        }

        String url = creds.baseUrl() + "/wiki/rest/api/content/search?cql=" + encodedCql + "&limit=" + cap;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(url))
                    .header("Authorization", authHeader(creds))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Confluence search returned %d: %s", response.statusCode(), response.body());
                return List.of();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode results = root.path("results");
            List<ConfluencePage> pages = new java.util.ArrayList<>();
            for (JsonNode r : results) {
                String pageId = r.path("id").asText();
                String title = r.path("title").asText("");
                pages.add(new ConfluencePage(pageId, title, buildPageUrlWithBase(creds.baseUrl(), pageId)));
            }
            return pages;
        } catch (Exception e) {
            LOG.warnf("Failed to search Confluence with CQL '%s': %s", cql, e.getMessage());
            return List.of();
        }
    }

    /**
     * Get a Confluence page by ID using provided credentials.
     * Returns the page title and body content as plain text.
     */
    public record PageContent(String pageId, String title, String body, String url) {}

    public PageContent getPage(String pageId, ConfluenceCredentials creds) {
        String url = creds.baseUrl() + "/wiki/api/v2/pages/" + pageId + "?body-format=storage";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(url))
                    .header("Authorization", authHeader(creds))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Confluence getPage failed (%d) for page %s", response.statusCode(), pageId);
                return null;
            }

            JsonNode root = mapper.readTree(response.body());
            String title = root.path("title").asText("");
            String storageBody = root.path("body").path("storage").path("value").asText("");
            String body = stripXhtml(storageBody);
            return new PageContent(pageId, title, body, buildPageUrlWithBase(creds.baseUrl(), pageId));
        } catch (Exception e) {
            LOG.warnf("Failed to fetch Confluence page %s: %s", pageId, e.getMessage());
            return null;
        }
    }

    /**
     * Create a Confluence page using provided credentials.
     */
    public String createPage(String spaceKey, String parentPageId, String title,
                             String markdownBody, ConfluenceCredentials creds) {
        try {
            MarkdownToStorageConverter.ConversionResult conversion =
                    MarkdownToStorageConverter.convert(markdownBody);

            String spaceId = resolveSpaceIdWithCreds(spaceKey, creds);
            if (spaceId == null) return null;

            ObjectNode body = mapper.createObjectNode();
            body.put("spaceId", spaceId);
            body.put("status", "current");
            body.put("title", title);

            if (parentPageId != null && !parentPageId.isBlank()) {
                body.put("parentId", parentPageId);
            }

            ObjectNode bodyNode = body.putObject("body");
            ObjectNode storage = bodyNode.putObject("storage");
            storage.put("representation", "storage");
            storage.put("value", conversion.xhtml());

            String url = creds.baseUrl() + "/wiki/api/v2/pages";
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(url))
                    .header("Authorization", authHeader(creds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.errorf("Confluence create page failed (%d): %s", response.statusCode(), response.body());
                return null;
            }

            JsonNode result = mapper.readTree(response.body());
            String createdPageId = result.path("id").asText();
            LOG.infof("Created Confluence page '%s' (id=%s) in space %s", title, createdPageId, spaceKey);
            return createdPageId;
        } catch (Exception e) {
            LOG.errorf("Failed to create Confluence page '%s': %s", title, e.getMessage());
            return null;
        }
    }

    /**
     * Update a Confluence page using provided credentials.
     */
    public boolean updatePage(String pageId, String title, String markdownBody, ConfluenceCredentials creds) {
        try {
            int currentVersion = getCurrentVersionWithCreds(pageId, creds);
            MarkdownToStorageConverter.ConversionResult conversion =
                    MarkdownToStorageConverter.convert(markdownBody);

            ObjectNode body = mapper.createObjectNode();
            body.put("id", pageId);
            body.put("status", "current");
            body.put("title", title);

            ObjectNode bodyNode = body.putObject("body");
            ObjectNode storage = bodyNode.putObject("storage");
            storage.put("representation", "storage");
            storage.put("value", conversion.xhtml());

            ObjectNode version = body.putObject("version");
            version.put("number", currentVersion + 1);

            String url = creds.baseUrl() + "/wiki/api/v2/pages/" + pageId;
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(url))
                    .header("Authorization", authHeader(creds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.errorf("Confluence update page failed (%d): %s", response.statusCode(), response.body());
                return false;
            }

            LOG.infof("Updated Confluence page '%s' (id=%s) to version %d", title, pageId, currentVersion + 1);
            return true;
        } catch (Exception e) {
            LOG.errorf("Failed to update Confluence page %s: %s", pageId, e.getMessage());
            return false;
        }
    }

    // ─── Helper methods with credentials ──────────────────────────────────────────

    private String resolveSpaceIdWithCreds(String spaceKey, ConfluenceCredentials creds) throws Exception {
        String url = creds.baseUrl() + "/wiki/api/v2/spaces?keys=" + URLEncoder.encode(spaceKey, StandardCharsets.UTF_8) + "&limit=1";
        HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .header("Authorization", authHeader(creds))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to resolve space key '" + spaceKey + "': HTTP " + response.statusCode());
        }

        JsonNode results = mapper.readTree(response.body()).path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new RuntimeException("Confluence space not found: " + spaceKey);
        }
        return results.get(0).path("id").asText();
    }

    private int getCurrentVersionWithCreds(String pageId, ConfluenceCredentials creds) throws Exception {
        String url = creds.baseUrl() + "/wiki/api/v2/pages/" + pageId;
        HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                .uri(URI.create(url))
                .header("Authorization", authHeader(creds))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode result = mapper.readTree(response.body());
        return result.path("version").path("number").asInt(1);
    }

    private String buildPageUrlWithBase(String base, String pageId) {
        return base + "/wiki/pages/" + pageId;
    }
}
