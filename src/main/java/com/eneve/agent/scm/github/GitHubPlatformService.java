package com.eneve.agent.scm.github;

import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scm.ThreadComment;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;

/**
 * GitHub REST API v3 implementation of {@link GitPlatformService}.
 * <p>
 * Uses Personal Access Token (PAT) or fine-grained token authentication
 * via the {@code Authorization: Bearer} header.
 * <p>
 * Parameter mapping: org = GitHub owner (user or organisation), project = "" (ignored),
 * repo = repository name.
 * <p>
 * Typed to its concrete class so CDI does not expose it as a {@link GitPlatformService}
 * bean — the {@link com.eneve.agent.scm.GitPlatformProducer} is the single source
 * for the interface.
 */
@ApplicationScoped
@Typed(GitHubPlatformService.class)
public class GitHubPlatformService implements GitPlatformService {

    private static final Logger LOG = Logger.getLogger(GitHubPlatformService.class);

    @ConfigProperty(name = "github.base.url", defaultValue = "https://api.github.com")
    String baseUrl;

    @Inject
    SettingsService settingsService;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String[] createPullRequest(String org, String project, String repo,
                                      String sourceBranch, String targetBranch,
                                      String title, String description) {
        String url = baseUrl + "/repos/" + org + "/" + repo + "/pulls";
        String body = """
                {
                  "head": "%s",
                  "base": "%s",
                  "title": "%s",
                  "body": "%s"
                }
                """.formatted(
                escapeJson(sourceBranch),
                escapeJson(targetBranch),
                escapeJson(title),
                escapeJson(description)
        );

        String responseBody = postAndReturn(url, body, "create PR");
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String prNumber = String.valueOf(node.path("number").asInt());
            String prUrl = node.path("html_url").asText("");
            LOG.infof("Created PR #%s: %s", prNumber, prUrl);
            return new String[] { prUrl, prNumber };
        } catch (Exception e) {
            LOG.errorf("Failed to parse create-PR response: %s", e.getMessage());
            return new String[] { "", "" };
        }
    }

    @Override
    public void mergePullRequest(String org, String project, String repo, String prId) {
        String url = baseUrl + "/repos/" + org + "/" + repo + "/pulls/" + prId + "/merge";
        putAndReturn(url, "{}", "merge PR #" + prId);
        LOG.infof("Merged PR #%s in %s/%s", prId, org, repo);
    }

    @Override
    public void declinePullRequest(String org, String project, String repo, String prId) {
        String url = baseUrl + "/repos/" + org + "/" + repo + "/pulls/" + prId;
        String body = """
                { "state": "closed" }
                """;
        patchAndReturn(url, body, "close PR #" + prId);
        LOG.infof("Closed PR #%s in %s/%s", prId, org, repo);
    }

    @Override
    public Map<String, String> getPullRequestInfo(String org, String project, String repo, String prId) {
        String url = baseUrl + "/repos/" + org + "/" + repo + "/pulls/" + prId;
        String responseBody = getAndReturn(url, "get PR #" + prId);

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String sourceBranch = node.path("head").path("ref").asText();
            String destBranch = node.path("base").path("ref").asText();
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
    public long addPrComment(String org, String project, String repo, String prId, String commentBody) {
        String url = baseUrl + "/repos/" + org + "/" + repo + "/issues/" + prId + "/comments";
        String body = """
                { "body": "%s" }
                """.formatted(escapeJson(commentBody));
        String responseBody = postAndReturn(url, body, "comment on PR #" + prId);
        long commentId = parseId(responseBody);
        LOG.infof("Added review comment %d to PR #%s in %s/%s", commentId, prId, org, repo);
        return commentId;
    }

    @Override
    public void updatePrComment(String org, String project, String repo, String prId,
                                long commentId, String commentBody) {
        String safeId = sanitizeId(prId);
        String url = baseUrl + "/repos/" + org + "/" + repo + "/issues/comments/" + commentId;
        String body = """
                { "body": "%s" }
                """.formatted(escapeJson(commentBody));
        patchAndReturn(url, body, "update comment #" + commentId + " on PR #" + safeId);
        LOG.infof("Updated review comment %d on PR #%s in %s/%s", commentId, prId, org, repo);
    }

    @Override
    public long addInlinePrComment(String org, String project, String repo, String prId,
                                   String filePath, int line, String commentBody) {
        // Fetch the HEAD commit SHA from the PR to form a valid review comment position
        String headSha = fetchPrHeadSha(org, repo, prId);
        if (headSha == null) {
            LOG.warnf("Could not fetch HEAD SHA for PR #%s — falling back to general comment", prId);
            return addPrComment(org, project, repo, prId, commentBody);
        }

        String url = baseUrl + "/repos/" + org + "/" + repo + "/pulls/" + prId + "/comments";
        String body = """
                {
                  "body": "%s",
                  "commit_id": "%s",
                  "path": "%s",
                  "line": %d,
                  "side": "RIGHT"
                }
                """.formatted(
                escapeJson(commentBody),
                escapeJson(headSha),
                escapeJson(filePath),
                line
        );

        try {
            String responseBody = postAndReturn(url, body,
                    "inline comment on PR #" + prId + " " + filePath + ":" + line);
            long commentId = parseId(responseBody);
            LOG.infof("Added inline comment %d to PR #%s at %s:%d", commentId, prId, filePath, line);
            return commentId;
        } catch (Exception e) {
            LOG.warnf("Inline comment failed for PR #%s at %s:%d (%s) — falling back to general comment",
                    prId, filePath, line, e.getMessage());
            return addPrComment(org, project, repo, prId, commentBody);
        }
    }

    @Override
    public long replyToComment(String org, String project, String repo, String prId,
                               long parentCommentId, String commentBody) {
        String url = baseUrl + "/repos/" + org + "/" + repo + "/pulls/" + prId
                + "/comments/" + parentCommentId + "/replies";
        String body = """
                { "body": "%s" }
                """.formatted(escapeJson(commentBody));
        String responseBody = postAndReturn(url, body,
                "reply to comment #" + parentCommentId + " on PR #" + prId);
        long replyId = parseId(responseBody);
        LOG.infof("Replied (comment %d) to comment #%d on PR #%s", replyId, parentCommentId, prId);
        return replyId;
    }

    @Override
    public List<ThreadComment> getCommentThread(String org, String project, String repo,
                                                String prId, long rootCommentId) {
        List<ThreadComment> result = new ArrayList<>();

        String url = baseUrl + "/repos/" + org + "/" + repo
                + "/pulls/" + prId + "/comments?per_page=100";

        while (url != null) {
            HttpResponse<String> response = getWithResponse(url, "get review comments for PR #" + prId);
            try {
                JsonNode comments = objectMapper.readTree(response.body());
                if (comments.isArray()) {
                    for (JsonNode comment : comments) {
                        long id = comment.path("id").asLong(0);
                        long inReplyTo = comment.path("in_reply_to_id").asLong(0);

                        // Include root comment and direct/indirect replies
                        if (id == rootCommentId || inReplyTo == rootCommentId) {
                            String author = comment.path("user").path("login").asText("unknown");
                            String content = comment.path("body").asText("").trim();
                            String createdAt = comment.path("created_at").asText("");
                            long parentId = (id == rootCommentId) ? 0L : rootCommentId;
                            boolean isAgent = !agentUser().isEmpty() && author.equalsIgnoreCase(agentUser());
                            result.add(new ThreadComment(id, parentId, author, content, createdAt, isAgent));
                        }
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.errorf("Failed to parse review comments response: %s", e.getMessage());
                break;
            }
        }

        result.sort((a, b) -> a.createdOn().compareTo(b.createdOn()));
        LOG.infof("Fetched %d comments in thread #%d on PR #%s", result.size(), rootCommentId, prId);
        return result;
    }

    @Override
    public List<String> getPullRequestComments(String org, String project, String repo, String prId) {
        // Fetch general issue comments and inline review comments in parallel — independent endpoints
        String issueUrl = baseUrl + "/repos/" + org + "/" + repo
                + "/issues/" + prId + "/comments?per_page=100";
        String reviewUrl = baseUrl + "/repos/" + org + "/" + repo
                + "/pulls/" + prId + "/comments?per_page=100";

        List<String> issueComments = new CopyOnWriteArrayList<>();
        List<String> reviewComments = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> issueFuture = CompletableFuture.runAsync(
                () -> collectComments(issueUrl, "get issue comments for PR #" + prId, false, issueComments),
                ForkJoinPool.commonPool());
        CompletableFuture<Void> reviewFuture = CompletableFuture.runAsync(
                () -> collectReviewComments(reviewUrl, "get review comments for PR #" + prId, false, reviewComments),
                ForkJoinPool.commonPool());

        CompletableFuture.allOf(issueFuture, reviewFuture).join();

        List<String> comments = new ArrayList<>(issueComments);
        comments.addAll(reviewComments);
        LOG.infof("Fetched %d review comments from PR #%s in %s/%s", comments.size(), prId, org, repo);
        return comments;
    }

    @Override
    public List<AgentComment> getAgentPrComments(String org, String project, String repo, String prId) {
        List<AgentComment> comments = new ArrayList<>();

        // General issue comments (non-inline)
        String issueUrl = baseUrl + "/repos/" + org + "/" + repo
                + "/issues/" + prId + "/comments?per_page=100";
        String url = issueUrl;
        while (url != null) {
            HttpResponse<String> response = getWithResponse(url, "get agent issue comments for PR #" + prId);
            try {
                JsonNode nodes = objectMapper.readTree(response.body());
                if (nodes.isArray()) {
                    for (JsonNode comment : nodes) {
                        String author = comment.path("user").path("login").asText("");
                        if (agentUser().isEmpty() || !author.equalsIgnoreCase(agentUser())) continue;
                        String content = comment.path("body").asText("").trim();
                        if (!content.isEmpty()) {
                            comments.add(new AgentComment("", 0, content));
                        }
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.errorf("Failed to parse agent issue comments response: %s", e.getMessage());
                break;
            }
        }

        // Inline review comments
        String reviewUrl = baseUrl + "/repos/" + org + "/" + repo
                + "/pulls/" + prId + "/comments?per_page=100";
        url = reviewUrl;
        while (url != null) {
            HttpResponse<String> response = getWithResponse(url, "get agent review comments for PR #" + prId);
            try {
                JsonNode nodes = objectMapper.readTree(response.body());
                if (nodes.isArray()) {
                    for (JsonNode comment : nodes) {
                        String author = comment.path("user").path("login").asText("");
                        if (agentUser().isEmpty() || !author.equalsIgnoreCase(agentUser())) continue;
                        String content = comment.path("body").asText("").trim();
                        if (content.isEmpty()) continue;
                        String filePath = comment.path("path").asText("");
                        int line = comment.path("line").asInt(0);
                        comments.add(new AgentComment(filePath, line, content));
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.errorf("Failed to parse agent review comments response: %s", e.getMessage());
                break;
            }
        }

        LOG.infof("Fetched %d agent comments from PR #%s in %s/%s", comments.size(), prId, org, repo);
        return comments;
    }

    @Override
    public void resolveComment(String org, String project, String repo, String prId, long commentId) {
        // GitHub review thread resolution requires the GraphQL API (resolveReviewThread mutation).
        // The REST API does not expose thread resolution, so this is intentionally a no-op.
        LOG.debugf("resolveComment is a no-op for GitHub (comment %d on PR #%s in %s/%s)",
                commentId, prId, org, repo);
    }

    @Override
    public List<String> listRepositories(String org) {
        List<String> repos = new ArrayList<>();
        String url = baseUrl + "/orgs/" + org + "/repos?per_page=100";

        while (url != null) {
            HttpResponse<String> response = getWithResponse(url, "list repositories for org " + org);
            try {
                JsonNode nodes = objectMapper.readTree(response.body());
                if (nodes.isArray()) {
                    for (JsonNode repo : nodes) {
                        String name = repo.path("name").asText("");
                        if (!name.isEmpty()) repos.add(name);
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.errorf("Failed to parse repositories response: %s", e.getMessage());
                break;
            }
        }

        LOG.infof("Listed %d repositories for org %s", repos.size(), org);
        return repos;
    }

    private String token() {
        return settingsService.getSecret("github.token");
    }

    private String agentUser() {
        return settingsService.get("github.agent.user");
    }

    @Override
    public String buildCloneUrl(String workspace, String repoSlug) {
        String user = !agentUser().isBlank() ? agentUser() : "x-access-token";
        return "https://" + user + ":" + token() + "@github.com/" + workspace + "/" + repoSlug + ".git";
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String fetchPrHeadSha(String org, String repo, String prId) {
        try {
            String url = baseUrl + "/repos/" + org + "/" + repo + "/pulls/" + prId;
            String responseBody = getAndReturn(url, "get PR HEAD SHA #" + prId);
            JsonNode node = objectMapper.readTree(responseBody);
            String sha = node.path("head").path("sha").asText("");
            return sha.isBlank() ? null : sha;
        } catch (Exception e) {
            LOG.warnf("Failed to fetch HEAD SHA for PR #%s: %s", prId, e.getMessage());
            return null;
        }
    }

    private void collectComments(String startUrl, String operation,
                                 boolean agentOnly, List<String> out) {
        String url = startUrl;
        while (url != null) {
            HttpResponse<String> response = getWithResponse(url, operation);
            try {
                JsonNode nodes = objectMapper.readTree(response.body());
                if (nodes.isArray()) {
                    for (JsonNode comment : nodes) {
                        String author = comment.path("user").path("login").asText("");
                        boolean isAgent = !agentUser().isEmpty() && author.equalsIgnoreCase(agentUser());
                        if (agentOnly && !isAgent) continue;
                        if (!agentOnly && isAgent) continue;
                        String content = comment.path("body").asText("").trim();
                        if (!content.isEmpty()) out.add(content);
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.errorf("Failed to parse comments response: %s", e.getMessage());
                break;
            }
        }
    }

    private void collectReviewComments(String startUrl, String operation,
                                       boolean agentOnly, List<String> out) {
        String url = startUrl;
        while (url != null) {
            HttpResponse<String> response = getWithResponse(url, operation);
            try {
                JsonNode nodes = objectMapper.readTree(response.body());
                if (nodes.isArray()) {
                    for (JsonNode comment : nodes) {
                        String author = comment.path("user").path("login").asText("");
                        boolean isAgent = !agentUser().isEmpty() && author.equalsIgnoreCase(agentUser());
                        if (agentOnly && !isAgent) continue;
                        if (!agentOnly && isAgent) continue;
                        String content = comment.path("body").asText("").trim();
                        if (content.isEmpty()) continue;
                        String filePath = comment.path("path").asText("");
                        int line = comment.path("line").asInt(0);
                        if (!filePath.isEmpty() && line > 0) {
                            out.add("[%s:%d] %s".formatted(filePath, line, content));
                        } else if (!filePath.isEmpty()) {
                            out.add("[%s] %s".formatted(filePath, content));
                        } else {
                            out.add(content);
                        }
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.errorf("Failed to parse review comments response: %s", e.getMessage());
                break;
            }
        }
    }

    private long parseId(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.path("id").asLong(0);
        } catch (Exception e) {
            LOG.warnf("Failed to parse ID from response: %s", e.getMessage());
            return 0;
        }
    }

    /**
     * Extract the next-page URL from the {@code Link} response header (RFC 5988), if present.
     * Returns null when there are no more pages.
     */
    private static String nextPageUrl(HttpResponse<String> response) {
        String linkHeader = response.headers().firstValue("link").orElse("");
        for (String part : linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int start = part.indexOf('<');
                int end = part.indexOf('>');
                if (start >= 0 && end > start) {
                    return part.substring(start + 1, end).trim();
                }
            }
        }
        return null;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private String getAndReturn(String url, String operation) {
        return getWithResponse(url, operation).body();
    }

    private HttpResponse<String> getWithResponse(String url, String operation) {
        requireTrustedUrl(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response;
            } else {
                LOG.errorf("GitHub %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                throw new RuntimeException("GitHub " + operation + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("GitHub %s error: %s", operation, e.getMessage());
            throw new RuntimeException("GitHub " + operation + " error: " + e.getMessage(), e);
        }
    }

    private String postAndReturn(String url, String body, String operation) {
        return sendAndReturn(url, body, "POST", operation);
    }

    private String putAndReturn(String url, String body, String operation) {
        return sendAndReturn(url, body, "PUT", operation);
    }

    private String patchAndReturn(String url, String body, String operation) {
        return sendAndReturn(url, body, "PATCH", operation);
    }

    private String sendAndReturn(String url, String body, String method, String operation) {
        requireTrustedUrl(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("GitHub %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.errorf("GitHub %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                throw new RuntimeException("GitHub " + operation + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("GitHub %s error: %s", operation, e.getMessage());
            throw new RuntimeException("GitHub " + operation + " error: " + e.getMessage(), e);
        }
    }

    /**
     * Validates that the target URL is directed at the configured GitHub host,
     * preventing SSRF by ensuring requests never leave the configured API endpoint.
     */
    private void requireTrustedUrl(String url) {
        try {
            String configuredHost = URI.create(baseUrl).getHost();
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
