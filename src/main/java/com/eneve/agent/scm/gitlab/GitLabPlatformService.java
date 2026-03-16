package com.eneve.agent.scm.gitlab;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * GitLab Cloud REST API v4 implementation of {@link GitPlatformService}.
 * <p>
 * Uses Personal Access Token (PAT) authentication via the {@code PRIVATE-TOKEN} header.
 * <p>
 * Parameter mapping: org = GitLab namespace/group path, project = "" (ignored),
 * repo = project name/slug. The full project path is {@code org/repo}.
 * <p>
 * GitLab uses "merge requests" (MRs) instead of pull requests, "notes" for comments,
 * and "discussions" for threaded/inline comment threads.
 * <p>
 * Typed to its concrete class so CDI does not expose it as a {@link GitPlatformService}
 * bean — the {@link com.eneve.agent.scm.GitPlatformProducer} is the single source
 * for the interface.
 */
@ApplicationScoped
@Typed(GitLabPlatformService.class)
public class GitLabPlatformService implements GitPlatformService {

    private static final Logger LOG = Logger.getLogger(GitLabPlatformService.class);

    @ConfigProperty(name = "gitlab.base.url", defaultValue = "https://gitlab.com/api/v4")
    String baseUrl;

    @ConfigProperty(name = "gitlab.token", defaultValue = "")
    String token;

    @ConfigProperty(name = "gitlab.agent.user", defaultValue = "")
    String agentUser;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String[] createPullRequest(String org, String project, String repo,
                                      String sourceBranch, String targetBranch,
                                      String title, String description) {
        String projectPath = encodedProjectPath(org, repo);
        String url = baseUrl + "/projects/" + projectPath + "/merge_requests";
        String body = """
                {
                  "source_branch": "%s",
                  "target_branch": "%s",
                  "title": "%s",
                  "description": "%s",
                  "remove_source_branch": false
                }
                """.formatted(
                escapeJson(sourceBranch),
                escapeJson(targetBranch),
                escapeJson(title),
                escapeJson(description)
        );

        String responseBody = postAndReturn(url, body, "create MR");
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String mrIid = String.valueOf(node.path("iid").asInt());
            String mrUrl = node.path("web_url").asText("");
            LOG.infof("Created MR !%s: %s", mrIid, mrUrl);
            return new String[] { mrUrl, mrIid };
        } catch (Exception e) {
            LOG.errorf("Failed to parse create-MR response: %s", e.getMessage());
            return new String[] { "", "" };
        }
    }

    @Override
    public void mergePullRequest(String org, String project, String repo, String prId) {
        String projectPath = encodedProjectPath(org, repo);
        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId + "/merge";
        putAndReturn(url, "{}", "merge MR !" + prId);
        LOG.infof("Merged MR !%s in %s/%s", prId, org, repo);
    }

    @Override
    public void declinePullRequest(String org, String project, String repo, String prId) {
        String projectPath = encodedProjectPath(org, repo);
        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId;
        String body = """
                { "state_event": "close" }
                """;
        putAndReturn(url, body, "close MR !" + prId);
        LOG.infof("Closed MR !%s in %s/%s", prId, org, repo);
    }

    @Override
    public Map<String, String> getPullRequestInfo(String org, String project, String repo, String prId) {
        String projectPath = encodedProjectPath(org, repo);
        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId;
        String responseBody = getAndReturn(url, "get MR !" + prId);

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String sourceBranch = node.path("source_branch").asText();
            String destBranch = node.path("target_branch").asText();
            String title = node.path("title").asText();
            return Map.of(
                    "sourceBranch", sourceBranch,
                    "destinationBranch", destBranch,
                    "title", title
            );
        } catch (Exception e) {
            LOG.errorf("Failed to parse MR info response: %s", e.getMessage());
            throw new RuntimeException("Failed to parse MR info: " + e.getMessage(), e);
        }
    }

    @Override
    public long addPrComment(String org, String project, String repo, String prId, String commentBody) {
        String projectPath = encodedProjectPath(org, repo);
        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId + "/notes";
        String body = """
                { "body": "%s" }
                """.formatted(escapeJson(commentBody));
        String responseBody = postAndReturn(url, body, "comment on MR !" + prId);
        long noteId = parseNoteId(responseBody);
        LOG.infof("Added review note %d to MR !%s in %s/%s", noteId, prId, org, repo);
        return noteId;
    }

    @Override
    public void updatePrComment(String org, String project, String repo, String prId,
                                long commentId, String commentBody) {
        String safeId = sanitizeId(prId);
        String projectPath = encodedProjectPath(org, repo);
        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + safeId
                + "/notes/" + commentId;
        String body = """
                { "body": "%s" }
                """.formatted(escapeJson(commentBody));
        putAndReturn(url, body, "update note #" + commentId + " on MR !" + safeId);
        LOG.infof("Updated review note %d on MR !%s in %s/%s", commentId, prId, org, repo);
    }

    @Override
    public long addInlinePrComment(String org, String project, String repo, String prId,
                                   String filePath, int line, String commentBody) {
        String projectPath = encodedProjectPath(org, repo);

        // Fetch diff_refs from the MR to build a valid inline position
        DiffRefs diffRefs = fetchDiffRefs(projectPath, prId);
        if (diffRefs == null) {
            LOG.warnf("Could not fetch diff_refs for MR !%s — falling back to general note", prId);
            return addPrComment(org, project, repo, prId, commentBody);
        }

        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId + "/discussions";
        String body = """
                {
                  "body": "%s",
                  "position": {
                    "position_type": "text",
                    "base_sha": "%s",
                    "head_sha": "%s",
                    "start_sha": "%s",
                    "new_path": "%s",
                    "new_line": %d
                  }
                }
                """.formatted(
                escapeJson(commentBody),
                escapeJson(diffRefs.baseSha()),
                escapeJson(diffRefs.headSha()),
                escapeJson(diffRefs.startSha()),
                escapeJson(filePath),
                line
        );

        try {
            String responseBody = postAndReturn(url, body,
                    "inline comment on MR !" + prId + " " + filePath + ":" + line);
            long noteId = parseDiscussionFirstNoteId(responseBody);
            LOG.infof("Added inline note %d to MR !%s at %s:%d", noteId, prId, filePath, line);
            return noteId;
        } catch (Exception e) {
            LOG.warnf("Inline comment failed for MR !%s at %s:%d (%s) — falling back to general note",
                    prId, filePath, line, e.getMessage());
            return addPrComment(org, project, repo, prId, commentBody);
        }
    }

    @Override
    public long replyToComment(String org, String project, String repo, String prId,
                               long parentNoteId, String commentBody) {
        String projectPath = encodedProjectPath(org, repo);
        String discussionId = resolveDiscussionId(projectPath, prId, parentNoteId);
        if (discussionId == null) {
            LOG.warnf("Could not resolve discussion for note %d — creating new general note", parentNoteId);
            return addPrComment(org, project, repo, prId, commentBody);
        }

        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId
                + "/discussions/" + discussionId + "/notes";
        String body = """
                { "body": "%s" }
                """.formatted(escapeJson(commentBody));
        String responseBody = postAndReturn(url, body,
                "reply to note #" + parentNoteId + " on MR !" + prId);
        long noteId = parseNoteId(responseBody);
        LOG.infof("Replied (note %d) to note #%d on MR !%s", noteId, parentNoteId, prId);
        return noteId;
    }

    @Override
    public List<ThreadComment> getCommentThread(String org, String project, String repo,
                                                String prId, long rootNoteId) {
        String projectPath = encodedProjectPath(org, repo);
        List<ThreadComment> result = new ArrayList<>();

        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId
                + "/discussions?per_page=100";

        while (url != null) {
            HttpResponse<String> response = getWithResponse(url, "get discussions for MR !" + prId);
            try {
                JsonNode discussions = objectMapper.readTree(response.body());
                if (discussions.isArray()) {
                    for (JsonNode discussion : discussions) {
                        JsonNode notes = discussion.path("notes");
                        if (!notes.isArray() || notes.isEmpty()) continue;

                        boolean containsRoot = false;
                        for (JsonNode note : notes) {
                            if (note.path("id").asLong(0) == rootNoteId) {
                                containsRoot = true;
                                break;
                            }
                        }
                        if (!containsRoot) continue;

                        long firstNoteId = notes.get(0).path("id").asLong(0);
                        for (JsonNode note : notes) {
                            long id = note.path("id").asLong(0);
                            long parentId = (id == firstNoteId) ? 0L : firstNoteId;
                            String author = note.path("author").path("username").asText("unknown");
                            String displayName = note.path("author").path("name").asText(author);
                            String content = note.path("body").asText("").trim();
                            String createdAt = note.path("created_at").asText("");
                            boolean isAgent = !agentUser.isEmpty() && author.equalsIgnoreCase(agentUser);
                            result.add(new ThreadComment(id, parentId, displayName, content, createdAt, isAgent));
                        }
                        break;
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.errorf("Failed to parse discussion thread response: %s", e.getMessage());
                break;
            }
        }

        result.sort((a, b) -> a.createdOn().compareTo(b.createdOn()));
        LOG.infof("Fetched %d notes in thread #%d on MR !%s", result.size(), rootNoteId, prId);
        return result;
    }

    @Override
    public List<String> getPullRequestComments(String org, String project, String repo, String prId) {
        String projectPath = encodedProjectPath(org, repo);
        List<String> comments = new ArrayList<>();

        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId
                + "/discussions?per_page=100";

        while (url != null) {
            HttpResponse<String> response = getWithResponse(url, "get discussions for MR !" + prId);
            try {
                JsonNode discussions = objectMapper.readTree(response.body());
                if (discussions.isArray()) {
                    for (JsonNode discussion : discussions) {
                        JsonNode notes = discussion.path("notes");
                        if (!notes.isArray()) continue;

                        for (JsonNode note : notes) {
                            String author = note.path("author").path("username").asText("");
                            if (!agentUser.isEmpty() && author.equalsIgnoreCase(agentUser)) continue;
                            if (note.path("system").asBoolean(false)) continue;

                            String content = note.path("body").asText("").trim();
                            if (content.isEmpty()) continue;

                            JsonNode position = note.path("position");
                            if (!position.isMissingNode() && position.has("new_path")) {
                                String file = position.path("new_path").asText("");
                                int line = position.path("new_line").asInt(0);
                                if (!file.isEmpty() && line > 0) {
                                    comments.add("[%s:%d] %s".formatted(file, line, content));
                                } else if (!file.isEmpty()) {
                                    comments.add("[%s] %s".formatted(file, content));
                                } else {
                                    comments.add(content);
                                }
                            } else {
                                comments.add(content);
                            }
                        }
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.errorf("Failed to parse MR discussions response: %s", e.getMessage());
                break;
            }
        }

        LOG.infof("Fetched %d review notes from MR !%s in %s/%s", comments.size(), prId, org, repo);
        return comments;
    }

    @Override
    public List<AgentComment> getAgentPrComments(String org, String project, String repo, String prId) {
        String projectPath = encodedProjectPath(org, repo);
        List<AgentComment> comments = new ArrayList<>();

        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId
                + "/discussions?per_page=100";

        while (url != null) {
            HttpResponse<String> response = getWithResponse(url, "get agent discussions for MR !" + prId);
            try {
                JsonNode discussions = objectMapper.readTree(response.body());
                if (discussions.isArray()) {
                    for (JsonNode discussion : discussions) {
                        JsonNode notes = discussion.path("notes");
                        if (!notes.isArray()) continue;

                        for (JsonNode note : notes) {
                            String author = note.path("author").path("username").asText("");
                            if (agentUser.isEmpty() || !author.equalsIgnoreCase(agentUser)) continue;

                            String content = note.path("body").asText("").trim();
                            if (content.isEmpty()) continue;

                            JsonNode position = note.path("position");
                            if (!position.isMissingNode() && position.has("new_path")) {
                                String file = position.path("new_path").asText("");
                                int line = position.path("new_line").asInt(0);
                                comments.add(new AgentComment(file, line, content));
                            } else {
                                comments.add(new AgentComment("", 0, content));
                            }
                        }
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.errorf("Failed to parse agent MR discussions response: %s", e.getMessage());
                break;
            }
        }

        LOG.infof("Fetched %d agent notes from MR !%s in %s/%s", comments.size(), prId, org, repo);
        return comments;
    }

    @Override
    public void resolveComment(String org, String project, String repo, String prId, long noteId) {
        String projectPath = encodedProjectPath(org, repo);
        String discussionId = resolveDiscussionId(projectPath, prId, noteId);
        if (discussionId == null) {
            LOG.warnf("Could not resolve discussion for note %d on MR !%s — skipping resolve", noteId, prId);
            return;
        }

        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId
                + "/discussions/" + discussionId + "?resolved=true";
        putAndReturn(url, "{}", "resolve discussion for note #" + noteId + " on MR !" + prId);
        LOG.infof("Resolved discussion %s (note %d) on MR !%s in %s/%s",
                discussionId, noteId, prId, org, repo);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Find the discussion ID that contains the given note ID.
     * Used to post replies into the correct discussion thread and to resolve discussions.
     */
    private String resolveDiscussionId(String projectPath, String prId, long noteId) {
        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId
                + "/discussions?per_page=100";

        while (url != null) {
            HttpResponse<String> response = getWithResponse(url,
                    "resolve discussion for note #" + noteId);
            try {
                JsonNode discussions = objectMapper.readTree(response.body());
                if (discussions.isArray()) {
                    for (JsonNode discussion : discussions) {
                        JsonNode notes = discussion.path("notes");
                        if (!notes.isArray()) continue;
                        for (JsonNode note : notes) {
                            if (note.path("id").asLong(0) == noteId) {
                                return discussion.path("id").asText(null);
                            }
                        }
                    }
                }
                url = nextPageUrl(response);
            } catch (Exception e) {
                LOG.warnf("Failed to resolve discussion for note %d: %s", noteId, e.getMessage());
                break;
            }
        }
        return null;
    }

    /**
     * Fetch the diff_refs from a merge request, needed to post inline comments with valid positions.
     */
    private DiffRefs fetchDiffRefs(String projectPath, String prId) {
        String url = baseUrl + "/projects/" + projectPath + "/merge_requests/" + prId;
        try {
            String responseBody = getAndReturn(url, "get MR diff_refs !" + prId);
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode diffRefs = node.path("diff_refs");
            if (diffRefs.isMissingNode()) return null;
            String baseSha = diffRefs.path("base_sha").asText("");
            String headSha = diffRefs.path("head_sha").asText("");
            String startSha = diffRefs.path("start_sha").asText("");
            if (baseSha.isEmpty() || headSha.isEmpty() || startSha.isEmpty()) return null;
            return new DiffRefs(baseSha, headSha, startSha);
        } catch (Exception e) {
            LOG.warnf("Failed to fetch diff_refs for MR !%s: %s", prId, e.getMessage());
            return null;
        }
    }

    private record DiffRefs(String baseSha, String headSha, String startSha) {}

    private long parseDiscussionFirstNoteId(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode notes = node.path("notes");
            if (notes.isArray() && !notes.isEmpty()) {
                return notes.get(0).path("id").asLong(0);
            }
            return 0;
        } catch (Exception e) {
            LOG.warnf("Failed to parse discussion note ID: %s", e.getMessage());
            return 0;
        }
    }

    private long parseNoteId(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.path("id").asLong(0);
        } catch (Exception e) {
            LOG.warnf("Failed to parse note ID from response: %s", e.getMessage());
            return 0;
        }
    }

    /**
     * URL-encode the project path (e.g., "mygroup/myrepo" -> "mygroup%2Fmyrepo").
     */
    private static String encodedProjectPath(String org, String repo) {
        String path = (org == null || org.isBlank()) ? repo : org + "/" + repo;
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
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
                    .header("PRIVATE-TOKEN", token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response;
            } else {
                LOG.errorf("GitLab %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                throw new RuntimeException("GitLab " + operation + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("GitLab %s error: %s", operation, e.getMessage());
            throw new RuntimeException("GitLab " + operation + " error: " + e.getMessage(), e);
        }
    }

    private String postAndReturn(String url, String body, String operation) {
        return sendAndReturn(url, body, "POST", operation);
    }

    private String putAndReturn(String url, String body, String operation) {
        return sendAndReturn(url, body, "PUT", operation);
    }

    private String sendAndReturn(String url, String body, String method, String operation) {
        requireTrustedUrl(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("GitLab %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.errorf("GitLab %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                throw new RuntimeException("GitLab " + operation + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("GitLab %s error: %s", operation, e.getMessage());
            throw new RuntimeException("GitLab " + operation + " error: " + e.getMessage(), e);
        }
    }

    @Override
    public String buildCloneUrl(String workspace, String repoSlug) {
        String user = agentUser != null && !agentUser.isBlank() ? agentUser : "gitlab-ci-token";
        return "https://" + user + ":" + token + "@gitlab.com/" + workspace + "/" + repoSlug + ".git";
    }

    /**
     * Validates that the target URL is directed at the configured GitLab host,
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
