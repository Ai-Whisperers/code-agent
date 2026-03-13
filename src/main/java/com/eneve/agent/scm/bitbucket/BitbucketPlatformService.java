package com.eneve.agent.scm.bitbucket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scm.ThreadComment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

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

    @ConfigProperty(name = "bitbucket.base.url", defaultValue = "https://api.bitbucket.org/2.0")
    String baseUrl;

    @ConfigProperty(name = "bitbucket.user")
    String bbUser;

    @ConfigProperty(name = "bitbucket.app.password")
    String appPassword;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                                    comment.path("user").path("nickname").asText("")).equals(bbUser);
                            thread.add(new ThreadComment(id, parentId, author, raw, createdOn, isAgent));
                        }
                    }
                }

                String next = root.path("next").asText(null);
                path = next != null ? next.replace(baseUrl, "") : null;
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
                        if (author.equals(bbUser)) {
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
                path = next != null ? next.replace(baseUrl, "") : null;
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
                        if (!author.equals(bbUser)) {
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
                path = next != null ? next.replace(baseUrl, "") : null;
            } catch (Exception e) {
                LOG.errorf("Failed to parse agent PR comments response: %s", e.getMessage());
                break;
            }
        }

        LOG.infof("Fetched %d agent comments from PR #%s in %s/%s", comments.size(), prId, org, repo);
        return comments;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private String getAndReturn(String path, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", authHeader())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
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
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("Bitbucket %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.errorf("Bitbucket %s failed (HTTP %d) for user '%s': %s",
                        operation, response.statusCode(), bbUser, response.body());
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

    /**
     * Repository/Workspace Access Tokens use Bearer auth.
     * App Passwords use Basic auth with username:password.
     */
    private String authHeader() {
        if ("x-token-auth".equals(bbUser)) {
            return "Bearer " + appPassword;
        }
        return "Basic " + Base64.getEncoder()
                .encodeToString((bbUser + ":" + appPassword).getBytes(StandardCharsets.UTF_8));
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

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
