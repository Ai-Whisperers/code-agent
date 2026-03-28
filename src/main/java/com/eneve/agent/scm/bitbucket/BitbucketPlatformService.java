package com.eneve.agent.scm.bitbucket;

import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scm.ThreadComment;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bitbucket Cloud REST API 2.0 implementation of {@link GitPlatformService}.
 * <p>
 * The {@code org} parameter maps to the Bitbucket workspace and {@code repo}
 * maps to the repository slug. The {@code project} parameter is ignored.
 * <p>
 * Typed to its concrete class so CDI does not expose it as a {@link GitPlatformService}
 * bean — the {@link com.eneve.agent.scm.GitPlatformProducer} is the single source
 * for the interface.
 */
@ApplicationScoped
@Typed(BitbucketPlatformService.class)
public class BitbucketPlatformService implements GitPlatformService {

    private static final Logger LOG = Logger.getLogger(BitbucketPlatformService.class);

    @Inject
    SettingsService settingsService;

    private String baseUrl() { return settingsService.get("bitbucket.base.url", "https://api.bitbucket.org/2.0"); }

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Lazily resolved actual Bitbucket account username (distinct from the HTTP auth credential). */
    private volatile String cachedAccountUsername;

    @Override
    public String[] createPullRequest(String org, String project, String repo,
                                      String sourceBranch, String targetBranch,
                                      String title, String description) {
        String body = """
                {
                  "title": "%s",
                  "description": "%s",
                  "source": { "branch": { "name": "%s" } },
                  "destination": { "branch": { "name": "%s" } },
                  "close_source_branch": false
                }
                """.formatted(
                escapeJson(title),
                escapeJson(description),
                escapeJson(sourceBranch),
                escapeJson(targetBranch)
        );

        String path = "/repositories/" + org + "/" + repo + "/pullrequests";
        String responseBody = postAndReturn(path, body, "create PR");

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String prUrl = node.path("links").path("html").path("href").asText();
            String prId = node.path("id").asText();
            LOG.infof("Created PR #%s: %s", prId, prUrl);
            return new String[] { prUrl, prId };
        } catch (Exception e) {
            LOG.errorf("Failed to parse create-PR response: %s", e.getMessage());
            return new String[] { "", "" };
        }
    }

    @Override
    public void mergePullRequest(String org, String project, String repo, String prId) {
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + prId + "/merge";
        String body = """
                {
                  "merge_strategy": "merge_commit",
                  "close_source_branch": true
                }
                """;
        postAndReturn(path, body, "merge PR #" + prId);
        LOG.infof("Merged PR #%s in %s/%s", prId, org, repo);
    }

    @Override
    public void declinePullRequest(String org, String project, String repo, String prId) {
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + prId + "/decline";
        postAndReturn(path, "{}", "decline PR #" + prId);
        LOG.infof("Declined PR #%s in %s/%s", prId, org, repo);
    }

    @Override
    public Map<String, String> getPullRequestInfo(String org, String project, String repo, String prId) {
        String path = "/repositories/" + org + "/" + repo + "/pullrequests/" + prId;
        String responseBody = getAndReturn(path, "get PR #" + prId);

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String sourceBranch = node.path("source").path("branch").path("name").asText();
            String destBranch = node.path("destination").path("branch").path("name").asText();
            String title = node.path("title").asText();
            return Map.of(
                    "sourceBranch", sourceBranch,
                    "destinationBranch", destBranch,
                    "title", title
            );
        } catch (Exception e) {
            LOG.errorf("Failed to parse PR info response: %s", e.getMessage());
            throw new RuntimeException("Failed to parse PR info: " + e.getMessage(), e);
        }
    }

    @Override
    public String getPullRequestDiff(String org, String project, String repo, String prId) {
        try {
            String url = baseUrl() + "/repositories/" + org + "/" + repo + "/pullrequests/" + prId + "/diff";
            requireTrustedUrl(url);
            // Bitbucket Cloud may 302-redirect to the actual diff file; use NORMAL redirect policy.
            HttpClient redirectingClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader())
                    .header("Accept", "text/plain")
                    .GET()
                    .build();
            HttpResponse<String> response = redirectingClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            LOG.warnf("Bitbucket get PR diff failed (HTTP %d) for PR #%s", response.statusCode(), prId);
            return "";
        } catch (Exception e) {
            LOG.warnf("Bitbucket get PR diff error for PR #%s: %s", prId, e.getMessage());
            return "";
        }
    }

    @Override
    public long addPrComment(String org, String project, String repo, String prId, String commentBody) {
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + prId + "/comments";
        String body = """
                {
                  "content": { "raw": "%s" }
                }
                """.formatted(escapeJson(commentBody));
        String responseBody = postAndReturn(path, body, "comment on PR #" + prId);
        long commentId = parseCommentId(responseBody);
        LOG.infof("Added review comment %d to PR #%s in %s/%s", commentId, prId, org, repo);
        return commentId;
    }

    @Override
    public void updatePrComment(String org, String project, String repo, String prId,
                                long commentId, String commentBody) {
        String safeId = sanitizeId(prId);
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + safeId + "/comments/" + commentId;
        String body = """
                {
                  "content": { "raw": "%s" }
                }
                """.formatted(escapeJson(commentBody));
        putAndReturn(path, body, "update comment #" + commentId + " on PR #" + safeId);
        LOG.infof("Updated review comment %d on PR #%s in %s/%s", commentId, prId, org, repo);
    }

    @Override
    public long addInlinePrComment(String org, String project, String repo, String prId,
                                   String filePath, int line, String commentBody) {
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + prId + "/comments";
        String body = """
                {
                  "content": { "raw": "%s" },
                  "inline": {
                    "to": %d,
                    "path": "%s"
                  }
                }
                """.formatted(escapeJson(commentBody), line, escapeJson(filePath));
        String responseBody = postAndReturn(path, body,
                "inline comment on PR #" + prId + " " + filePath + ":" + line);
        long commentId = parseCommentId(responseBody);
        LOG.infof("Added inline comment %d to PR #%s at %s:%d", commentId, prId, filePath, line);
        return commentId;
    }

    @Override
    public long replyToComment(String org, String project, String repo, String prId,
                               long parentCommentId, String commentBody) {
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + prId + "/comments";
        String body = """
                {
                  "content": { "raw": "%s" },
                  "parent": { "id": %d }
                }
                """.formatted(escapeJson(commentBody), parentCommentId);
        String responseBody = postAndReturn(path, body,
                "reply to comment #" + parentCommentId + " on PR #" + prId);
        long commentId = parseCommentId(responseBody);
        LOG.infof("Replied (comment %d) to comment #%d on PR #%s", commentId, parentCommentId, prId);
        return commentId;
    }

    @Override
    public List<ThreadComment> getCommentThread(String org, String project, String repo,
                                                String prId, long rootCommentId) {
        List<ThreadComment> thread = new ArrayList<>();
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + prId + "/comments?pagelen=50";

        while (path != null) {
            String responseBody = getAndReturn(path, "get thread for comment #" + rootCommentId);
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode values = root.path("values");
                if (values.isArray()) {
                    for (JsonNode comment : values) {
                        long id = comment.path("id").asLong(0);
                        long parentId = comment.path("parent").path("id").asLong(0);

                        if (id == rootCommentId || parentId == rootCommentId) {
                            String author = comment.path("user").path("display_name").asText(
                                    comment.path("user").path("username").asText("unknown"));
                            String raw = comment.path("content").path("raw").asText("").trim();
                            String createdOn = comment.path("created_on").asText("");
                            boolean isAgent = comment.path("user").path("username").asText(
                                    comment.path("user").path("nickname").asText("")).equals(effectiveBotUsername());
                            thread.add(new ThreadComment(id, parentId, author, raw, createdOn, isAgent));
                        }
                    }
                }

                String next = root.path("next").asText(null);
                path = next != null ? next.replace(baseUrl(), "") : null;
            } catch (Exception e) {
                LOG.errorf("Failed to parse comment thread response: %s", e.getMessage());
                break;
            }
        }

        thread.sort((a, b) -> a.createdOn().compareTo(b.createdOn()));
        LOG.infof("Fetched %d comments in thread #%d on PR #%s", thread.size(), rootCommentId, prId);
        return thread;
    }

    @Override
    public List<String> getPullRequestComments(String org, String project, String repo, String prId) {
        List<String> comments = new ArrayList<>();
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + prId + "/comments?pagelen=50";

        while (path != null) {
            String responseBody = getAndReturn(path, "get comments for PR #" + prId);
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode values = root.path("values");
                if (values.isArray()) {
                    for (JsonNode comment : values) {
                        String author = comment.path("user").path("username").asText(
                                comment.path("user").path("nickname").asText(""));
                        if (author.equals(effectiveBotUsername())) {
                            continue;
                        }

                        String raw = comment.path("content").path("raw").asText("").trim();
                        if (raw.isEmpty()) {
                            continue;
                        }

                        JsonNode inline = comment.path("inline");
                        if (!inline.isMissingNode() && inline.has("path")) {
                            String file = inline.path("path").asText("");
                            int line = inline.path("to").asInt(inline.path("from").asInt(0));
                            if (!file.isEmpty() && line > 0) {
                                comments.add("[%s:%d] %s".formatted(file, line, raw));
                            } else if (!file.isEmpty()) {
                                comments.add("[%s] %s".formatted(file, raw));
                            } else {
                                comments.add(raw);
                            }
                        } else {
                            comments.add(raw);
                        }
                    }
                }

                String next = root.path("next").asText(null);
                path = next != null ? next.replace(baseUrl(), "") : null;
            } catch (Exception e) {
                LOG.errorf("Failed to parse PR comments response: %s", e.getMessage());
                break;
            }
        }

        LOG.infof("Fetched %d review comments from PR #%s in %s/%s", comments.size(), prId, org, repo);
        return comments;
    }

    @Override
    public List<AgentComment> getAgentPrComments(String org, String project, String repo, String prId) {
        List<AgentComment> comments = new ArrayList<>();
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + prId + "/comments?pagelen=50";

        while (path != null) {
            String responseBody = getAndReturn(path, "get agent comments for PR #" + prId);
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode values = root.path("values");
                if (values.isArray()) {
                    for (JsonNode comment : values) {
                        String author = comment.path("user").path("username").asText(
                                comment.path("user").path("nickname").asText(""));
                        if (!author.equals(effectiveBotUsername())) {
                            continue;
                        }

                        String raw = comment.path("content").path("raw").asText("").trim();
                        if (raw.isEmpty()) {
                            continue;
                        }

                        JsonNode inline = comment.path("inline");
                        if (!inline.isMissingNode() && inline.has("path")) {
                            String file = inline.path("path").asText("");
                            int line = inline.path("to").asInt(inline.path("from").asInt(0));
                            comments.add(new AgentComment(file, line, raw));
                        } else {
                            comments.add(new AgentComment("", 0, raw));
                        }
                    }
                }

                String next = root.path("next").asText(null);
                path = next != null ? next.replace(baseUrl(), "") : null;
            } catch (Exception e) {
                LOG.errorf("Failed to parse agent PR comments response: %s", e.getMessage());
                break;
            }
        }

        LOG.infof("Fetched %d agent comments from PR #%s in %s/%s", comments.size(), prId, org, repo);
        return comments;
    }

    /**
     * Uploads a file to the Bitbucket repository Downloads section.
     * The uploaded file is publicly accessible (subject to repository visibility) at:
     * {@code https://bitbucket.org/{org}/{repo}/downloads/{filename}}
     * <p>
     * Re-uploading a file with the same name replaces the existing file.
     *
     * @return public download URL, or {@code null} on failure
     */
    @Override
    public String uploadDownload(String org, String repo, String filename,
                                 byte[] data, String contentType) {
        String path = "/repositories/" + org + "/" + repo + "/downloads";
        requireTrustedUrl(baseUrl() + path);
        String boundary = "----DownloadBoundary" + System.nanoTime();
        byte[] body = buildMultipartBody(boundary, filename, data, contentType);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + path))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String downloadUrl = "https://bitbucket.org/" + org + "/" + repo + "/downloads/" + filename;
                LOG.infof("Uploaded diagram '%s' to Bitbucket downloads: %s", filename, downloadUrl);
                return downloadUrl;
            } else {
                if (response.statusCode() == 401) {
                    LOG.errorf("Bitbucket upload returned 401 Unauthorized — URL: %s, credentials: %s",
                            request.uri(), credentialsDiagnostic());
                }
                LOG.warnf("Bitbucket download upload failed (HTTP %d): %s",
                        response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.warnf("Bitbucket download upload error for '%s': %s", filename, e.getMessage());
            return null;
        }
    }

    private static byte[] buildMultipartBody(String boundary, String filename,
                                             byte[] data, String contentType) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"files\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n";
            bos.write(header.getBytes(StandardCharsets.UTF_8));
            bos.write(data);
            String footer = "\r\n--" + boundary + "--\r\n";
            bos.write(footer.getBytes(StandardCharsets.UTF_8));
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build multipart body", e);
        }
    }

    @Override
    public void resolveComment(String org, String project, String repo, String prId, long commentId) {
        String path = "/repositories/" + org + "/" + repo
                + "/pullrequests/" + prId + "/comments/" + commentId;
        String body = """
                {
                  "resolution": { "type": "resolved" }
                }
                """;
        putAndReturn(path, body, "resolve comment #" + commentId + " on PR #" + prId);
        LOG.infof("Resolved comment %d on PR #%s in %s/%s", commentId, prId, org, repo);
    }

    @Override
    public List<String> listRepositories(String org) {
        List<String> slugs = new ArrayList<>();
        String path = "/repositories/" + org + "?pagelen=100";

        while (path != null) {
            String responseBody = getAndReturn(path, "list repos for workspace " + org);
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode values = root.path("values");
                if (values.isArray()) {
                    for (JsonNode repo : values) {
                        String slug = repo.path("slug").asText("");
                        if (!slug.isEmpty()) {
                            slugs.add(slug);
                        }
                    }
                }
                String next = root.path("next").asText(null);
                path = next != null ? next.replace(baseUrl(), "") : null;
            } catch (Exception e) {
                LOG.errorf("Failed to parse repository list response: %s", e.getMessage());
                break;
            }
        }

        LOG.infof("Listed %d repositories in workspace '%s'", slugs.size(), org);
        return slugs;
    }

    /**
     * Returns a map of {@code uuid → url} for all webhooks registered on the given repository.
     * Bitbucket Cloud API 2.0 exposes the webhook identifier as {@code "uuid"}, not {@code "uid"}.
     */
    public Map<String, String> listWebhooks(String workspace, String repo) {
        Map<String, String> hooks = new LinkedHashMap<>();
        String path = "/repositories/" + workspace + "/" + repo + "/hooks?pagelen=100";

        while (path != null) {
            String responseBody = getAndReturn(path, "list webhooks for " + workspace + "/" + repo);
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode values = root.path("values");
                if (values.isArray()) {
                    for (JsonNode hook : values) {
                        String uuid = hook.path("uuid").asText("");
                        String url = hook.path("url").asText("");
                        if (!uuid.isEmpty() && !url.isEmpty()) {
                            hooks.put(uuid, url);
                        }
                    }
                }
                String next = root.path("next").asText(null);
                path = next != null ? next.replace(baseUrl(), "") : null;
            } catch (Exception e) {
                LOG.errorf("Failed to parse webhook list response for %s/%s: %s", workspace, repo, e.getMessage());
                break;
            }
        }

        return hooks;
    }

    /**
     * Creates a webhook on the given repository.
     *
     * @param workspace  Bitbucket workspace slug
     * @param repo       Repository slug
     * @param webhookUrl Public URL the webhook will POST to
     * @param secret     HMAC-SHA256 secret for payload signing
     * @param events     Bitbucket event keys (e.g. {@code pullrequest:created})
     */
    public void createWebhook(String workspace, String repo, String webhookUrl, String secret, List<String> events) {
        String path = "/repositories/" + workspace + "/" + repo + "/hooks";
        try {
            List<String> quotedEvents = events.stream()
                    .map(e -> "\"" + e + "\"")
                    .toList();
            String eventsJson = "[" + String.join(",", quotedEvents) + "]";
            String body = """
                    {
                      "description": "code-agent",
                      "url": "%s",
                      "active": true,
                      "secret": "%s",
                      "events": %s
                    }
                    """.formatted(webhookUrl, secret, eventsJson);
            postAndReturn(path, body, "create webhook " + webhookUrl + " on " + workspace + "/" + repo);
            LOG.infof("Created webhook %s on %s/%s", webhookUrl, workspace, repo);
        } catch (Exception e) {
            LOG.errorf("Failed to create webhook %s on %s/%s: %s", webhookUrl, workspace, repo, e.getMessage());
            throw e;
        }
    }

    /**
     * Deletes all webhooks on the given repository whose URL matches {@code targetUrl}.
     */
    public void deleteWebhooksByUrl(String workspace, String repo, String targetUrl) {
        Map<String, String> existing = listWebhooks(workspace, repo);
        for (Map.Entry<String, String> entry : existing.entrySet()) {
            if (targetUrl.equals(entry.getValue())) {
                deleteWebhookByUuid(workspace, repo, entry.getKey(), targetUrl);
            }
        }
    }

    /**
     * Deletes all webhooks on the given repository whose {@code description} field matches
     * {@code targetDescription}. Use this to remove all agent-owned hooks regardless of the
     * URL they were registered with (handles hostname changes between deployments).
     */
    public void deleteWebhooksByDescription(String workspace, String repo, String targetDescription) {
        String path = "/repositories/" + workspace + "/" + repo + "/hooks?pagelen=100";

        while (path != null) {
            String responseBody = getAndReturn(path, "list webhooks for " + workspace + "/" + repo);
            List<String[]> toDelete = new java.util.ArrayList<>();
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseBody);
                com.fasterxml.jackson.databind.JsonNode values = root.path("values");
                if (values.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode hook : values) {
                        String uuid = hook.path("uuid").asText("");
                        String url = hook.path("url").asText("");
                        String description = hook.path("description").asText("");
                        if (!uuid.isEmpty() && targetDescription.equals(description)) {
                            toDelete.add(new String[]{uuid, url});
                        }
                    }
                }
                String next = root.path("next").asText(null);
                path = next != null ? next.replace(baseUrl(), "") : null;
            } catch (Exception e) {
                LOG.errorf("Failed to parse webhook list response for %s/%s: %s", workspace, repo, e.getMessage());
                break;
            }
            for (String[] hook : toDelete) {
                deleteWebhookByUuid(workspace, repo, hook[0], hook[1]);
            }
        }
    }

    private void deleteWebhookByUuid(String workspace, String repo, String uuid, String urlForLog) {
        // Bitbucket returns UUIDs wrapped in curly braces (e.g. "{abc-123}"); strip them
        // before embedding in a URI path where { and } are illegal characters.
        String bareUuid = uuid.replaceAll("[{}]", "");
        String deletePath = "/repositories/" + workspace + "/" + repo + "/hooks/" + bareUuid;
        requireTrustedUrl(baseUrl() + deletePath);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + deletePath))
                    .header("Authorization", authHeader())
                    .DELETE()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("Deleted webhook %s (uuid=%s) from %s/%s", urlForLog, uuid, workspace, repo);
            } else {
                if (response.statusCode() == 401) {
                    LOG.errorf("Bitbucket delete webhook returned 401 Unauthorized — URL: %s, credentials: %s",
                            request.uri(), credentialsDiagnostic());
                }
                LOG.warnf("Failed to delete webhook uuid=%s from %s/%s: HTTP %d",
                        uuid, workspace, repo, response.statusCode());
            }
        } catch (Exception e) {
            LOG.errorf("Error deleting webhook uuid=%s from %s/%s: %s", uuid, workspace, repo, e.getMessage());
        }
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private String getAndReturn(String path, String operation) {
        requireTrustedUrl(baseUrl() + path);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + path))
                    .header("Authorization", authHeader())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                if (response.statusCode() == 401) {
                    LOG.errorf("Bitbucket %s returned 401 Unauthorized — URL: %s, credentials: %s",
                            operation, request.uri(), credentialsDiagnostic());
                }
                LOG.errorf("Bitbucket %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                throw new RuntimeException("Bitbucket " + operation + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("Bitbucket %s error: %s", operation, e.getMessage());
            throw new RuntimeException("Bitbucket " + operation + " error: " + e.getMessage(), e);
        }
    }

    private String postAndReturn(String path, String body, String operation) {
        return sendAndReturn(path, body, "POST", operation);
    }

    private String putAndReturn(String path, String body, String operation) {
        return sendAndReturn(path, body, "PUT", operation);
    }

    private String sendAndReturn(String path, String body, String method, String operation) {
        requireTrustedUrl(baseUrl() + path);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + path))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("Bitbucket %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                if (response.statusCode() == 401) {
                    LOG.errorf("Bitbucket %s returned 401 Unauthorized — URL: %s, credentials: %s",
                            operation, request.uri(), credentialsDiagnostic());
                }
                LOG.errorf("Bitbucket %s failed (HTTP %d) for user '%s': %s",
                        operation, response.statusCode(), bbUser(), response.body());
                throw new RuntimeException("Bitbucket " + operation + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("Bitbucket %s error: %s", operation, e.getMessage());
            throw new RuntimeException("Bitbucket " + operation + " error: " + e.getMessage(), e);
        }
    }

    private String bbUser() {
        return settingsService.get("bitbucket.user");
    }

    /**
     * Returns the actual Bitbucket account username of the authenticated service account,
     * as it appears in webhook payloads and comment author fields. This is distinct from the
     * HTTP auth credential ({@code x-token-auth} is used for Bearer/Access Token auth but is
     * not a real Bitbucket username).
     * <p>
     * The result is fetched once from {@code GET /user} and then cached for the lifetime of
     * the application.
     *
     * @return resolved Bitbucket account username, or empty string on failure
     */
    @Override
    public String getCurrentUserUsername() {
        if (cachedAccountUsername != null) return cachedAccountUsername;
        synchronized (this) {
            if (cachedAccountUsername != null) return cachedAccountUsername;
            try {
                String responseBody = getAndReturn("/user", "get current user");
                JsonNode node = objectMapper.readTree(responseBody);
                String username = node.path("username").asText(
                        node.path("nickname").asText(""));
                if (!username.isBlank()) {
                    cachedAccountUsername = username;
                    LOG.infof("Resolved Bitbucket account username: '%s'", username);
                    return username;
                }
                LOG.warnf("Bitbucket /user response contained no username or nickname");
            } catch (Exception e) {
                LOG.warnf("Failed to resolve Bitbucket account username (self-filter may be impaired): %s",
                        e.getMessage());
            }
            return "";
        }
    }

    /**
     * Returns the username to use when comparing comment authors to determine if a comment
     * was posted by this agent (loop guard). Prefers the API-resolved account username over
     * the configured HTTP auth credential, which may be {@code x-token-auth} rather than the
     * real Bitbucket account username.
     */
    private String effectiveBotUsername() {
        String resolved = getCurrentUserUsername();
        return resolved.isBlank() ? bbUser() : resolved;
    }

    private String credentialsDiagnostic() {
        String user = bbUser();
        String pwd = appPassword();
        String userDiag = user == null ? "<null>"
                : String.format("len=%d, starts='%s', ends='%s', hasWhitespace=%b, hasLineBreak=%b",
                        user.length(),
                        user.length() >= 4 ? user.substring(0, 4) : user,
                        user.length() >= 4 ? user.substring(user.length() - 4) : user,
                        user.chars().anyMatch(Character::isWhitespace),
                        user.contains("\n") || user.contains("\r"));
        String pwdDiag = pwd == null ? "<null>"
                : String.format("len=%d, starts='%s', ends='%s', hasWhitespace=%b, hasLineBreak=%b",
                        pwd.length(),
                        pwd.length() >= 4 ? pwd.substring(0, 4) : "****",
                        pwd.length() >= 4 ? pwd.substring(pwd.length() - 4) : "****",
                        pwd.chars().anyMatch(Character::isWhitespace),
                        pwd.contains("\n") || pwd.contains("\r"));
        return "user=[" + userDiag + "], password=[" + pwdDiag + "]";
    }

    private String appPassword() {
        return settingsService.getSecret("bitbucket.app.password");
    }

    /**
     * Repository/Workspace Access Tokens use Bearer auth.
     * App Passwords use Basic auth with username:password.
     */
    private String authHeader() {
        String user = bbUser();
        String password = appPassword();
        if ("x-token-auth".equals(user)) {
            return "Bearer " + password;
        }
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private long parseCommentId(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.path("id").asLong(0);
        } catch (Exception e) {
            LOG.warnf("Failed to parse comment ID from response: %s", e.getMessage());
            return 0;
        }
    }

    @Override
    public String buildCloneUrl(String workspace, String repoSlug) {
        return "https://" + bbUser() + ":" + appPassword()
                + "@bitbucket.org/" + workspace + "/" + repoSlug + ".git";
    }

    /**
     * Validates that the target URL is directed at the configured Bitbucket host,
     * preventing SSRF by ensuring requests never leave the configured API endpoint.
     */
    private void requireTrustedUrl(String url) {
        try {
            String configuredHost = URI.create(baseUrl()).getHost();
            String targetHost = URI.create(url).getHost();
            if (!targetHost.equalsIgnoreCase(configuredHost)) {
                throw new IllegalArgumentException(
                        "URL host '" + targetHost + "' does not match configured host '" + configuredHost + "'");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
    }

    /**
     * Removes characters from an identifier that are not word characters, hyphens, or dots,
     * preventing injection of metacharacters via user-supplied IDs.
     */
    private static String sanitizeId(String id) {
        if (id == null) return "";
        return id.replaceAll("[^\\w.-]", "");
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
