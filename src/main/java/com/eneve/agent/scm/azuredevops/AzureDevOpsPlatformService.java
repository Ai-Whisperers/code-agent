package com.eneve.agent.scm.azuredevops;

import com.eneve.agent.model.OpenPrEntry;
import com.eneve.agent.model.PrCommitEntry;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Azure DevOps REST API 7.1 implementation of {@link GitPlatformService}.
 * <p>
 * Uses Personal Access Token (PAT) authentication via Basic auth with an
 * empty username ({@code :PAT} encoded as Base64).
 * <p>
 * Parameter mapping: org = Azure DevOps organization, project = project name,
 * repo = repository name.
 * <p>
 * Typed to its concrete class so CDI does not expose it as a {@link GitPlatformService}
 * bean — the {@link com.eneve.agent.scm.GitPlatformProducer} is the single source
 * for the interface.
 */
@ApplicationScoped
@Typed(AzureDevOpsPlatformService.class)
public class AzureDevOpsPlatformService implements GitPlatformService {

    private static final Logger LOG = Logger.getLogger(AzureDevOpsPlatformService.class);
    private static final String API_VERSION = "api-version=7.1";

    @Inject
    SettingsService settingsService;

    private String baseUrl() { return settingsService.get("azuredevops.base.url", "https://dev.azure.com"); }

    @Inject HttpClient httpClient;
    @Inject ObjectMapper objectMapper;

    @Override
    public String[] createPullRequest(String org, String project, String repo,
                                      String sourceBranch, String targetBranch,
                                      String title, String description) {
        ensureTargetBranchExists(org, project, repo, targetBranch);

        String url = repoApiUrl(org, project, repo) + "/pullrequests?" + API_VERSION;
        String body = """
                {
                  "sourceRefName": "refs/heads/%s",
                  "targetRefName": "refs/heads/%s",
                  "title": "%s",
                  "description": "%s"
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
            String prId = String.valueOf(node.path("pullRequestId").asInt());
            String prUrl = node.path("url").asText("");
            if (prUrl.contains("_apis/")) {
                prUrl = baseUrl() + "/" + org + "/" + project + "/_git/" + repo + "/pullrequest/" + prId;
            }
            LOG.infof("Created PR #%s: %s", prId, prUrl);
            return new String[] { prUrl, prId };
        } catch (Exception e) {
            LOG.errorf("Failed to parse create-PR response: %s", e.getMessage());
            return new String[] { "", "" };
        }
    }

    @Override
    public void mergePullRequest(String org, String project, String repo, String prId) {
        String infoUrl = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "?" + API_VERSION;
        String infoResp = getAndReturn(infoUrl, "get PR #" + prId + " for merge");
        String lastMergeSourceCommitId;
        try {
            JsonNode node = objectMapper.readTree(infoResp);
            lastMergeSourceCommitId = node.path("lastMergeSourceCommit").path("commitId").asText("");
        } catch (Exception e) {
            throw new RuntimeException("Failed to read PR merge commit: " + e.getMessage(), e);
        }

        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "?" + API_VERSION;
        String body = """
                {
                  "status": "completed",
                  "lastMergeSourceCommit": { "commitId": "%s" },
                  "completionOptions": {
                    "deleteSourceBranch": true,
                    "mergeStrategy": "noFastForward"
                  }
                }
                """.formatted(escapeJson(lastMergeSourceCommitId));
        patchAndReturn(url, body, "merge PR #" + prId);
        LOG.infof("Merged PR #%s in %s/%s/%s", prId, org, project, repo);
    }

    @Override
    public void declinePullRequest(String org, String project, String repo, String prId) {
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "?" + API_VERSION;
        String body = """
                {
                  "status": "abandoned"
                }
                """;
        patchAndReturn(url, body, "abandon PR #" + prId);
        LOG.infof("Abandoned PR #%s in %s/%s/%s", prId, org, project, repo);
    }

    @Override
    public Map<String, String> getPullRequestInfo(String org, String project, String repo, String prId) {
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "?" + API_VERSION;
        String responseBody = getAndReturn(url, "get PR #" + prId);

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String sourceBranch = stripRefsHeads(node.path("sourceRefName").asText(""));
            String destBranch = stripRefsHeads(node.path("targetRefName").asText(""));
            String title = node.path("title").asText("");
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
    public void updatePrComment(String org, String project, String repo, String prId,
                                long commentId, String commentBody) {
        String safeId = sanitizeId(prId);
        int threadId = resolveThreadId(org, project, repo, safeId, commentId);
        if (threadId <= 0) {
            LOG.warnf("Could not resolve thread for comment %d on PR #%s — skipping update", commentId, safeId);
            throw new RuntimeException("Could not resolve ADO thread for comment " + commentId);
        }
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + safeId + "/threads/" + threadId
                + "/comments/" + commentId + "?" + API_VERSION;
        String body = """
                {
                  "content": "%s",
                  "commentType": 1
                }
                """.formatted(escapeJson(commentBody));
        patchAndReturn(url, body, "update comment #" + commentId + " on PR #" + safeId);
        LOG.infof("Updated review comment %d (thread %d) on PR #%s in %s/%s/%s",
                commentId, threadId, prId, org, project, repo);
    }

    @Override
    public long addPrComment(String org, String project, String repo, String prId, String commentBody) {
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "/threads?" + API_VERSION;
        String body = """
                {
                  "comments": [
                    { "parentCommentId": 0, "content": "%s", "commentType": 1 }
                  ],
                  "status": 1
                }
                """.formatted(escapeJson(commentBody));
        String responseBody = postAndReturn(url, body, "comment on PR #" + prId);
        long threadId = parseThreadFirstCommentId(responseBody);
        LOG.infof("Added review comment (thread) %d to PR #%s in %s/%s/%s",
                threadId, prId, org, project, repo);
        return threadId;
    }

    @Override
    public long addInlinePrComment(String org, String project, String repo, String prId,
                                   String filePath, int line, String commentBody) {
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "/threads?" + API_VERSION;
        String normalizedPath = filePath.startsWith("/") ? filePath : "/" + filePath;
        String body = """
                {
                  "comments": [
                    { "parentCommentId": 0, "content": "%s", "commentType": 1 }
                  ],
                  "status": 1,
                  "threadContext": {
                    "filePath": "%s",
                    "rightFileStart": { "line": %d, "offset": 1 },
                    "rightFileEnd": { "line": %d, "offset": 1 }
                  }
                }
                """.formatted(escapeJson(commentBody), escapeJson(normalizedPath), line, line);
        String responseBody = postAndReturn(url, body,
                "inline comment on PR #" + prId + " " + filePath + ":" + line);
        long commentId = parseThreadFirstCommentId(responseBody);
        LOG.infof("Added inline comment %d to PR #%s at %s:%d", commentId, prId, filePath, line);
        return commentId;
    }

    @Override
    public long replyToComment(String org, String project, String repo, String prId,
                               long parentCommentId, String commentBody) {
        int threadId = resolveThreadId(org, project, repo, prId, parentCommentId);
        if (threadId <= 0) {
            LOG.warnf("Could not resolve thread for comment %d, creating new thread", parentCommentId);
            return addPrComment(org, project, repo, prId, commentBody);
        }

        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "/threads/" + threadId
                + "/comments?" + API_VERSION;
        String body = """
                {
                  "parentCommentId": %d,
                  "content": "%s",
                  "commentType": 1
                }
                """.formatted(parentCommentId, escapeJson(commentBody));
        String responseBody = postAndReturn(url, body,
                "reply to comment #" + parentCommentId + " on PR #" + prId);

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            long commentId = node.path("id").asLong(0);
            LOG.infof("Replied (comment %d) to comment #%d on PR #%s", commentId, parentCommentId, prId);
            return commentId;
        } catch (Exception e) {
            LOG.warnf("Failed to parse reply comment ID: %s", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<ThreadComment> getCommentThread(String org, String project, String repo,
                                                String prId, long rootCommentId) {
        List<ThreadComment> result = new ArrayList<>();
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "/threads?" + API_VERSION;
        String responseBody = getAndReturn(url, "get threads for PR #" + prId);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode threads = root.path("value");
            if (threads.isArray()) {
                for (JsonNode thread : threads) {
                    JsonNode comments = thread.path("comments");
                    if (!comments.isArray()) continue;

                    boolean containsRoot = false;
                    for (JsonNode c : comments) {
                        if (c.path("id").asLong(0) == rootCommentId) {
                            containsRoot = true;
                            break;
                        }
                    }
                    if (!containsRoot) continue;

                    for (JsonNode c : comments) {
                        long id = c.path("id").asLong(0);
                        long parentId = c.path("parentCommentId").asLong(0);
                        String author = c.path("author").path("displayName").asText(
                                c.path("author").path("uniqueName").asText("unknown"));
                        String content = c.path("content").asText("").trim();
                        String publishedDate = c.path("publishedDate").asText("");
                        String uniqueName = c.path("author").path("uniqueName").asText("");
                        boolean isAgent = !agentUser().isEmpty() && uniqueName.equalsIgnoreCase(agentUser());
                        result.add(new ThreadComment(id, parentId, author, content, publishedDate, isAgent));
                    }
                    break;
                }
            }
        } catch (Exception e) {
            LOG.errorf("Failed to parse comment threads: %s", e.getMessage());
        }

        result.sort((a, b) -> a.createdOn().compareTo(b.createdOn()));
        LOG.infof("Fetched %d comments in thread containing #%d on PR #%s",
                result.size(), rootCommentId, prId);
        return result;
    }

    @Override
    public List<String> getPullRequestComments(String org, String project, String repo, String prId) {
        List<String> comments = new ArrayList<>();
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "/threads?" + API_VERSION;
        String responseBody = getAndReturn(url, "get comments for PR #" + prId);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode threads = root.path("value");
            if (threads.isArray()) {
                for (JsonNode thread : threads) {
                    JsonNode threadComments = thread.path("comments");
                    if (!threadComments.isArray()) continue;

                    for (JsonNode c : threadComments) {
                        String uniqueName = c.path("author").path("uniqueName").asText("");
                        if (!agentUser().isEmpty() && uniqueName.equalsIgnoreCase(agentUser())) continue;
                        int commentType = c.path("commentType").asInt(0);
                        if (commentType == 2) continue; // system comment

                        String content = c.path("content").asText("").trim();
                        if (content.isEmpty()) continue;

                        JsonNode ctx = thread.path("threadContext");
                        if (!ctx.isMissingNode() && ctx.has("filePath")) {
                            String file = ctx.path("filePath").asText("");
                            int line = ctx.path("rightFileStart").path("line").asInt(0);
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
        } catch (Exception e) {
            LOG.errorf("Failed to parse PR threads: %s", e.getMessage());
        }

        LOG.infof("Fetched %d review comments from PR #%s in %s/%s/%s",
                comments.size(), prId, org, project, repo);
        return comments;
    }

    @Override
    public List<AgentComment> getAgentPrComments(String org, String project, String repo, String prId) {
        List<AgentComment> comments = new ArrayList<>();
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "/threads?" + API_VERSION;
        String responseBody = getAndReturn(url, "get agent comments for PR #" + prId);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode threads = root.path("value");
            if (threads.isArray()) {
                for (JsonNode thread : threads) {
                    JsonNode threadComments = thread.path("comments");
                    if (!threadComments.isArray()) continue;

                    // The first comment in the thread is the root (parentCommentId == 0).
                    long firstCommentId = 0L;
                    for (JsonNode c : threadComments) {
                        long parentCommentId = c.path("parentCommentId").asLong(0);
                        if (parentCommentId == 0) {
                            firstCommentId = c.path("id").asLong(0);
                            break;
                        }
                    }

                    for (JsonNode c : threadComments) {
                        String uniqueName = c.path("author").path("uniqueName").asText("");
                        if (agentUser().isEmpty() || !uniqueName.equalsIgnoreCase(agentUser())) continue;

                        String content = c.path("content").asText("").trim();
                        if (content.isEmpty()) continue;

                        long commentId = c.path("id").asLong(0);
                        long parentCommentId = c.path("parentCommentId").asLong(0);
                        // Use the thread root id as parentId for replies; 0 means this is the root.
                        long parentId = (parentCommentId == 0) ? 0L : firstCommentId;
                        JsonNode ctx = thread.path("threadContext");
                        if (!ctx.isMissingNode() && ctx.has("filePath")) {
                            String file = ctx.path("filePath").asText("");
                            int line = ctx.path("rightFileStart").path("line").asInt(0);
                            comments.add(new AgentComment(commentId, file, line, content, parentId));
                        } else {
                            comments.add(new AgentComment(commentId, "", 0, content, parentId));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.errorf("Failed to parse agent PR threads: %s", e.getMessage());
        }

        LOG.infof("Fetched %d agent comments from PR #%s in %s/%s/%s",
                comments.size(), prId, org, project, repo);
        return comments;
    }

    @Override
    public void resolveComment(String org, String project, String repo, String prId, long commentId) {
        int threadId = resolveThreadId(org, project, repo, prId, commentId);
        if (threadId <= 0) {
            LOG.warnf("Could not resolve thread for comment %d on PR #%s — skipping resolve",
                    commentId, prId);
            return;
        }

        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "/threads/" + threadId + "?" + API_VERSION;
        String body = """
                {
                  "status": 2
                }
                """;
        patchAndReturn(url, body, "resolve thread #" + threadId + " on PR #" + prId);
        LOG.infof("Resolved thread %d (comment %d) on PR #%s in %s/%s/%s",
                threadId, commentId, prId, org, project, repo);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private int resolveThreadId(String org, String project, String repo,
                                String prId, long commentId) {
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "/threads?" + API_VERSION;
        String responseBody = getAndReturn(url, "resolve thread for comment #" + commentId);
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode threads = root.path("value");
            if (threads.isArray()) {
                for (JsonNode thread : threads) {
                    JsonNode comments = thread.path("comments");
                    if (!comments.isArray()) continue;
                    for (JsonNode c : comments) {
                        if (c.path("id").asLong(0) == commentId) {
                            return thread.path("id").asInt(0);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to resolve thread for comment %d: %s", commentId, e.getMessage());
        }
        return 0;
    }

    /**
     * Ensures the given branch exists in the remote repository.
     * If absent, it is created from the repository's default branch.
     * Prevents an Azure DevOps HTTP 400 "Target ref does not exist" error when opening a PR.
     */
    private void ensureTargetBranchExists(String org, String project, String repo, String branchName) {
        try {
            String checkUrl = repoApiUrl(org, project, repo)
                    + "/refs?filter=heads/" + branchName + "&" + API_VERSION;
            requireTrustedUrl(checkUrl);
            String checkResponse = getAndReturn(checkUrl, "check branch '" + branchName + "'");
            JsonNode checkNode = objectMapper.readTree(checkResponse);
            if (checkNode.path("count").asInt(0) > 0) {
                return;
            }
        } catch (Exception e) {
            LOG.warnf("Azure DevOps ensureTargetBranchExists: branch check error for '%s': %s",
                    branchName, e.getMessage());
        }

        LOG.infof("Target branch '%s' not found in %s/%s/%s — auto-creating from default branch",
                branchName, org, project, repo);
        try {
            String repoInfoUrl = repoApiUrl(org, project, repo) + "?" + API_VERSION;
            String repoInfo = getAndReturn(repoInfoUrl, "get repo info");
            String defaultRefFull = objectMapper.readTree(repoInfo).path("defaultBranch").asText("refs/heads/main");
            String defaultBranch = defaultRefFull.replaceFirst("^refs/heads/", "");

            String defaultRefsUrl = repoApiUrl(org, project, repo)
                    + "/refs?filter=heads/" + defaultBranch + "&" + API_VERSION;
            String defaultRefsInfo = getAndReturn(defaultRefsUrl, "get default branch ref");
            JsonNode values = objectMapper.readTree(defaultRefsInfo).path("value");
            String sha = (values.isArray() && !values.isEmpty())
                    ? values.get(0).path("objectId").asText("") : "";
            if (sha.isBlank()) {
                throw new RuntimeException("Could not resolve objectId for default branch '" + defaultBranch + "'");
            }

            String createUrl = repoApiUrl(org, project, repo) + "/refs?" + API_VERSION;
            String createBody = """
                    [{ "name": "refs/heads/%s", "newObjectId": "%s", "oldObjectId": "0000000000000000000000000000000000000000" }]
                    """.formatted(escapeJson(branchName), escapeJson(sha));
            postAndReturn(createUrl, createBody, "create branch '" + branchName + "'");
            LOG.infof("Auto-created branch '%s' from '%s' (%s) in %s/%s/%s",
                    branchName, defaultBranch, sha.substring(0, Math.min(8, sha.length())), org, project, repo);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cannot auto-create target branch '%s' in %s/%s/%s: %s"
                            .formatted(branchName, org, project, repo, e.getMessage()), e);
        }
    }

    private String repoApiUrl(String org, String project, String repo) {
        return baseUrl() + "/" + org + "/" + project + "/_apis/git/repositories/" + repo;
    }

    private long parseThreadFirstCommentId(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode comments = node.path("comments");
            if (comments.isArray() && !comments.isEmpty()) {
                return comments.get(0).path("id").asLong(0);
            }
            return node.path("id").asLong(0);
        } catch (Exception e) {
            LOG.warnf("Failed to parse comment/thread ID: %s", e.getMessage());
            return 0;
        }
    }

    private static String stripRefsHeads(String refName) {
        if (refName != null && refName.startsWith("refs/heads/")) {
            return refName.substring("refs/heads/".length());
        }
        return refName != null ? refName : "";
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private String getAndReturn(String url, String operation) {
        requireTrustedUrl(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(url))
                    .header("Authorization", authHeader())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                LOG.errorf("Azure DevOps %s failed (HTTP %d): %s",
                        operation, response.statusCode(), response.body());
                throw new RuntimeException("Azure DevOps " + operation + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("Azure DevOps %s error: %s", operation, e.getMessage());
            throw new RuntimeException("Azure DevOps " + operation + " error: " + e.getMessage(), e);
        }
    }

    private String postAndReturn(String url, String body, String operation) {
        return sendAndReturn(url, body, "POST", operation);
    }

    private String patchAndReturn(String url, String body, String operation) {
        return sendAndReturn(url, body, "PATCH", operation);
    }

    private String sendAndReturn(String url, String body, String method, String operation) {
        requireTrustedUrl(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(url))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("Azure DevOps %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.errorf("Azure DevOps %s failed (HTTP %d): %s",
                        operation, response.statusCode(), response.body());
                throw new RuntimeException("Azure DevOps " + operation + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("Azure DevOps %s error: %s", operation, e.getMessage());
            throw new RuntimeException("Azure DevOps " + operation + " error: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the Azure DevOps PAT for the given organization, checking for an org-specific
     * override ({@code azuredevops.pat.<org>}) before falling back to the global key.
     */
    String patFor(String org) {
        if (org != null && !org.isBlank()) {
            String orgPat = settingsService.getSecret("azuredevops.pat." + org);
            if (orgPat != null && !orgPat.isBlank()) {
                return orgPat;
            }
        }
        return settingsService.getSecret("azuredevops.pat");
    }

    private String agentUser() {
        return settingsService.get("azuredevops.agent.user");
    }

    private String authHeader() {
        return authHeaderFor("");
    }

    private String authHeaderFor(String org) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((":" + patFor(org)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Azure DevOps clone URLs require the project path segment
     * ({@code https://dev.azure.com/{org}/{project}/_git/{repo}}), which is not
     * available from workspace + repoSlug alone. Returns {@code null} so callers
     * skip Azure DevOps repos gracefully; graphs and upgrades are handled at first review.
     */
    @Override
    public String buildCloneUrl(String workspace, String repoSlug) {
        LOG.debugf("buildCloneUrl not supported for Azure DevOps without project — skipping %s/%s",
                workspace, repoSlug);
        return null;
    }

    @Override
    public String buildCloneUrl(String workspace, String project, String repo) {
        String user = !agentUser().isBlank() ? agentUser() : workspace;
        return "https://" + user + ":" + patFor(workspace) + "@dev.azure.com/" + workspace + "/" + project + "/_git/" + repo;
    }

    /**
     * Validates that the target URL is directed at the configured Azure DevOps host,
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

    @Override
    public List<OpenPrEntry> listOpenPullRequests(String org, String project, String repo) {
        List<OpenPrEntry> prs = new ArrayList<>();
        int top = 100;
        int skip = 0;

        while (true) {
            String url = repoApiUrl(org, project, repo)
                    + "/pullrequests?searchCriteria.status=active&$top=" + top + "&$skip=" + skip
                    + "&" + API_VERSION;
            try {
                String responseBody = getAndReturn(url, "list open PRs for " + org + "/" + project + "/" + repo);
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode value = root.path("value");
                if (!value.isArray() || value.isEmpty()) break;

                for (JsonNode pr : value) {
                    String prId = String.valueOf(pr.path("pullRequestId").asInt());
                    String prUrl = baseUrl() + "/" + org + "/" + project + "/_git/" + repo
                            + "/pullrequest/" + prId;
                    String title = pr.path("title").asText("");
                    String sourceBranch = stripRefsHeads(pr.path("sourceRefName").asText(""));
                    String targetBranch = stripRefsHeads(pr.path("targetRefName").asText(""));
                    String author = pr.path("createdBy").path("displayName").asText(
                            pr.path("createdBy").path("uniqueName").asText(""));
                    String createdOn = pr.path("creationDate").asText("");
                    String updatedOn = pr.path("closedDate").asText(createdOn);
                    prs.add(new OpenPrEntry(org, repo, prId, prUrl, title,
                            sourceBranch, targetBranch, author, createdOn, updatedOn, null, "OPEN", false));
                }

                if (value.size() < top) break;
                skip += top;
            } catch (Exception e) {
                LOG.warnf("Failed to list open PRs for Azure DevOps repo %s/%s/%s: %s",
                        org, project, repo, e.getMessage());
                break;
            }
        }

        LOG.infof("Listed %d open PRs for Azure DevOps repo %s/%s/%s", prs.size(), org, project, repo);
        return prs;
    }

    @Override
    public List<OpenPrEntry> listMergedPullRequests(String org, String project, String repo,
                                                     java.time.Instant since) {
        List<OpenPrEntry> prs = new ArrayList<>();
        int top = 100;
        int skip = 0;
        // Azure DevOps: searchCriteria.minTime filters by creation date (closest available filter)
        // We also stop paginating once closedDate is before since
        String sinceStr = since.toString(); // ISO-8601

        outer:
        while (true) {
            String url = repoApiUrl(org, project, repo)
                    + "/pullrequests?searchCriteria.status=completed"
                    + "&searchCriteria.minTime=" + sinceStr
                    + "&$top=" + top + "&$skip=" + skip
                    + "&" + API_VERSION;
            try {
                String responseBody = getAndReturn(url, "list merged PRs for " + org + "/" + project + "/" + repo);
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode value = root.path("value");
                if (!value.isArray() || value.isEmpty()) break;

                for (JsonNode pr : value) {
                    String closedDate = pr.path("closedDate").asText("");
                    if (!closedDate.isBlank()) {
                        try {
                            java.time.Instant closedAt = java.time.Instant.parse(closedDate);
                            if (closedAt.isBefore(since)) break outer;
                        } catch (Exception ignored) { /* unparseable date — include it */ }
                    }
                    String prId = String.valueOf(pr.path("pullRequestId").asInt());
                    String prUrl = baseUrl() + "/" + org + "/" + project + "/_git/" + repo
                            + "/pullrequest/" + prId;
                    String title = pr.path("title").asText("");
                    String sourceBranch = stripRefsHeads(pr.path("sourceRefName").asText(""));
                    String targetBranch = stripRefsHeads(pr.path("targetRefName").asText(""));
                    String author = pr.path("createdBy").path("displayName").asText(
                            pr.path("createdBy").path("uniqueName").asText(""));
                    String createdOn = pr.path("creationDate").asText("");
                    prs.add(new OpenPrEntry(org, repo, prId, prUrl, title,
                            sourceBranch, targetBranch, author, createdOn, closedDate, null, "MERGED", false));
                }

                if (value.size() < top) break;
                skip += top;
            } catch (Exception e) {
                LOG.warnf("Failed to list merged PRs for Azure DevOps repo %s/%s/%s: %s",
                        org, project, repo, e.getMessage());
                break;
            }
        }

        LOG.infof("Listed %d merged PRs (since %s) for Azure DevOps repo %s/%s/%s",
                prs.size(), since, org, project, repo);
        return prs;
    }

    @Override
    public List<PrCommitEntry> getPrCommits(String org, String project, String repo, String prId) {
        String url = repoApiUrl(org, project, repo)
                + "/pullrequests/" + prId + "/commits?" + API_VERSION;
        List<PrCommitEntry> commits = new ArrayList<>();
        try {
            String responseBody = getAndReturn(url, "get commits for PR #" + prId);
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode value = root.path("value");
            if (value.isArray()) {
                for (JsonNode commit : value) {
                    String sha = commit.path("commitId").asText("");
                    String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
                    String message = commit.path("comment").asText("").trim();
                    String authorName = commit.path("author").path("name").asText("");
                    String authorDate = commit.path("author").path("date").asText("");
                    commits.add(new PrCommitEntry(sha, shortSha, message, authorName, authorDate));
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to fetch commits for Azure DevOps PR #%s: %s", prId, e.getMessage());
        }
        LOG.infof("Fetched %d commits for Azure DevOps PR #%s in %s/%s/%s", commits.size(), prId, org, project, repo);
        return commits;
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
