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
import java.util.zip.Deflater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Client for the Confluence Cloud REST API v2.
 * Creates or updates pages in Confluence spaces using XHTML storage format.
 * Mermaid diagrams are rendered server-side via mermaid.ink and uploaded
 * as page attachments.
 */
@ApplicationScoped
public class ConfluenceService {

    private static final Logger LOG = Logger.getLogger(ConfluenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MERMAID_INK_BASE = "https://mermaid.ink/img/pako:";

    @ConfigProperty(name = "confluence.base.url", defaultValue = "")
    String baseUrl;

    @ConfigProperty(name = "confluence.user", defaultValue = "")
    String user;

    @ConfigProperty(name = "confluence.api.token", defaultValue = "")
    String apiToken;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank()
                && user != null && !user.isBlank()
                && apiToken != null && !apiToken.isBlank();
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
     * Renders a Mermaid diagram to PNG via the mermaid.ink public service.
     * Uses the pako (zlib deflate) encoding format expected by mermaid.ink.
     */
    byte[] renderMermaidToPng(String mermaidCode) throws Exception {
        String json = "{\"code\":" + MAPPER.writeValueAsString(mermaidCode)
                + ",\"mermaid\":{\"theme\":\"default\"}}";

        Deflater deflater = new Deflater(9);
        deflater.setInput(json.getBytes(StandardCharsets.UTF_8));
        deflater.finish();

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            int count = deflater.deflate(buf);
            compressed.write(buf, 0, count);
        }
        deflater.end();

        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(compressed.toByteArray());
        String url = MERMAID_INK_BASE + encoded + "?type=png&bgColor=!white";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "image/png")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RuntimeException("mermaid.ink returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * Uploads a binary file as a page attachment via the Confluence v1 REST API.
     * If an attachment with the same filename already exists, it is updated.
     */
    void uploadAttachment(String pageId, String filename, byte[] data,
                          String contentType) throws Exception {
        String boundary = "----AttachBoundary" + System.nanoTime();
        byte[] body = buildMultipartBody(boundary, filename, data, contentType);

        String url = baseUrl + "/wiki/rest/api/content/" + pageId + "/child/attachment";
        HttpRequest request = HttpRequest.newBuilder()
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

        if (response.statusCode() == 409) {
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
        String url = baseUrl + "/wiki/rest/api/content/" + pageId
                + "/child/attachment/" + attachmentId + "/data";

        HttpRequest request = HttpRequest.newBuilder()
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
        String url = baseUrl + "/wiki/rest/api/content/" + pageId
                + "/child/attachment?filename=" + URLEncoder.encode(filename, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        JsonNode results = MAPPER.readTree(response.body()).path("results");
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
        return pageId;
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
