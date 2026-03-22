package com.eneve.agent.jira;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * JIRA Cloud REST API 3 client.
 * Handles comments, transitions, and worklog for issue tracking.
 */
@ApplicationScoped
public class JiraService {

    private static final Logger LOG = Logger.getLogger(JiraService.class);

    @ConfigProperty(name = "jira.base.url")
    String baseUrl;

    @ConfigProperty(name = "jira.user")
    String user;

    @ConfigProperty(name = "jira.api.token")
    String apiToken;

    @ConfigProperty(name = "jira.transition.in-review", defaultValue = "")
    String transitionInReview;

    @ConfigProperty(name = "jira.transition.done", defaultValue = "")
    String transitionDone;

    @ConfigProperty(name = "jira.transition.rejected", defaultValue = "")
    String transitionRejected;

    @ConfigProperty(name = "jira.default.worklog", defaultValue = "30m")
    String defaultWorklog;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public record JiraIssueRef(String key, String summary) {}

    /**
     * Parsed context from a JIRA issue description: Aikido candidate IDs and container names.
     */
    public record JiraDescriptionContext(
            java.util.List<Integer> aikidoCandidateIds,
            java.util.List<String> containerNames
    ) {}

    /**
     * Search for open issues with the given label.
     */
    public java.util.List<JiraIssueRef> searchIssuesByLabel(String label) {
        String jql = "labels = \"" + escapeJson(label) + "\" AND statusCategory != Done ORDER BY created DESC";
        String body = "{\"jql\":\"" + escapeJson(jql) + "\",\"fields\":[\"summary\"],\"maxResults\":50}";

        String json = postForBody("/rest/api/3/search/jql", body, "search issues by label");
        if (json == null) return java.util.List.of();

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var issues = root.path("issues");
            if (!issues.isArray()) return java.util.List.of();

            var results = new java.util.ArrayList<JiraIssueRef>();
            for (var issue : issues) {
                String key = issue.path("key").asText("");
                String summary = issue.path("fields").path("summary").asText("");
                if (!key.isBlank()) {
                    results.add(new JiraIssueRef(key, summary));
                }
            }
            LOG.infof("JIRA search: found %d open issues with label %s", results.size(), label);
            return results;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA search results: %s", e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Fetch the issue summary only (for generating branch names, etc).
     */
    public String fetchIssueSummary(String issueKey) {
        String json = get("/rest/api/3/issue/" + issueKey + "?fields=summary",
                "fetch summary " + issueKey);
        if (json == null) return null;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            return root.path("fields").path("summary").asText(null);
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA summary for %s: %s", issueKey, e.getMessage());
            return null;
        }
    }

    /**
     * Search the JIRA issue description for Aikido issue candidate IDs.
     * Extracts all numeric IDs from Aikido URLs (groupId, sidebarIssue, /issues/groups/ path).
     * Returns them in priority order so the caller can try each against the API.
     */
    public java.util.List<Integer> extractAikidoCandidateIds(String issueKey) {
        return extractDescriptionContext(issueKey).aikidoCandidateIds();
    }

    /**
     * Parse the JIRA description for both Aikido candidate IDs and container image references.
     * Fetches the description once and extracts all context.
     */
    public JiraDescriptionContext extractDescriptionContext(String issueKey) {
        String json = get("/rest/api/3/issue/" + issueKey + "?fields=description",
                "fetch description " + issueKey);
        if (json == null) return new JiraDescriptionContext(java.util.List.of(), java.util.List.of());

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var description = root.path("fields").path("description");

            String allText = extractAdfTextAndLinks(description);
            LOG.infof("JIRA %s description extracted text+links (%d chars): %s",
                    issueKey, allText.length(),
                    allText.length() > 500 ? allText.substring(0, 500) + "..." : allText);

            var candidateIds = new java.util.LinkedHashSet<Integer>();
            var aikidoPatterns = new java.util.regex.Pattern[]{
                    java.util.regex.Pattern.compile("aikido\\.dev/issues/groups/(\\d+)"),
                    java.util.regex.Pattern.compile("aikido\\.dev[^\\s]*[?&]sidebarIssue=(\\d+)"),
                    java.util.regex.Pattern.compile("aikido\\.dev[^\\s]*[?&]groupId=(\\d+)")
            };
            for (var pattern : aikidoPatterns) {
                var matcher = pattern.matcher(allText);
                while (matcher.find()) {
                    candidateIds.add(Integer.parseInt(matcher.group(1)));
                }
            }
            LOG.infof("JIRA %s: found %d Aikido candidate IDs: %s", issueKey, candidateIds.size(), candidateIds);

            var containerNames = new java.util.ArrayList<String>();
            var containerPattern = java.util.regex.Pattern.compile(
                    "(?i)containers?\\s*:\\s*\\n?\\s*([a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+)");
            var containerMatcher = containerPattern.matcher(allText);
            while (containerMatcher.find()) {
                containerNames.add(containerMatcher.group(1));
            }
            if (!containerNames.isEmpty()) {
                LOG.infof("JIRA %s: found container references: %s", issueKey, containerNames);
            }

            return new JiraDescriptionContext(
                    new java.util.ArrayList<>(candidateIds),
                    containerNames
            );
        } catch (Exception e) {
            LOG.warnf("Failed to extract description context from %s: %s", issueKey, e.getMessage());
            return new JiraDescriptionContext(java.util.List.of(), java.util.List.of());
        }
    }

    /**
     * Extract text AND href URLs from ADF nodes (to capture Aikido links).
     */
    private String extractAdfTextAndLinks(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";

        var sb = new StringBuilder();

        if (node.has("text")) {
            sb.append(node.path("text").asText(""));
        }

        var marks = node.path("marks");
        if (marks.isArray()) {
            for (var mark : marks) {
                if ("link".equals(mark.path("type").asText(""))) {
                    String href = mark.path("attrs").path("href").asText("");
                    if (!href.isBlank()) sb.append(" ").append(href);
                }
            }
        }

        if ("inlineCard".equals(node.path("type").asText(""))) {
            String url = node.path("attrs").path("url").asText("");
            if (!url.isBlank()) sb.append(" ").append(url);
        }

        var content = node.path("content");
        if (content.isArray()) {
            for (var child : content) {
                sb.append(" ").append(extractAdfTextAndLinks(child));
            }
        }

        return sb.toString();
    }

    /**
     * Fetch the issue summary and description (ADF rendered to plain text).
     * Returns "SUMMARY\n\nDESCRIPTION" or just "SUMMARY" if description is empty.
     */
    public String fetchIssuePrompt(String issueKey) {
        String json = get("/rest/api/3/issue/" + issueKey + "?fields=summary,description",
                "fetch issue " + issueKey);
        if (json == null) {
            return null;
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var fields = root.path("fields");

            String summary = fields.path("summary").asText("");
            String description = extractAdfText(fields.path("description"));

            if (description.isBlank()) {
                return summary;
            }
            return summary + "\n\n" + description;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA issue %s: %s", issueKey, e.getMessage());
            return null;
        }
    }

    /**
     * Recursively extract plain text from a JIRA ADF (Atlassian Document Format) node.
     */
    private String extractAdfText(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.has("text")) {
            return node.path("text").asText("");
        }
        var content = node.path("content");
        if (content.isArray()) {
            var sb = new StringBuilder();
            for (var child : content) {
                String childType = child.path("type").asText("");
                String childText = extractAdfText(child);
                if (!childText.isEmpty()) {
                    if ("paragraph".equals(childType) || "heading".equals(childType)
                            || "bulletList".equals(childType) || "orderedList".equals(childType)) {
                        if (!sb.isEmpty()) sb.append("\n");
                    } else if ("listItem".equals(childType)) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append("- ");
                    }
                    sb.append(childText);
                }
            }
            return sb.toString();
        }
        return "";
    }

    public void addComment(String issueKey, String commentText) {
        String body = """
                {"body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}}
                """.formatted(escapeJson(commentText));

        post("/rest/api/3/issue/" + issueKey + "/comment", body, "add comment");
    }

    public void transitionToInReview(String issueKey) {
        if (transitionInReview.isBlank()) {
            LOG.warnf("JIRA transition.in-review not configured, skipping for %s", issueKey);
            return;
        }
        transition(issueKey, transitionInReview);
    }

    public void transitionToDone(String issueKey) {
        if (transitionDone.isBlank()) {
            LOG.warnf("JIRA transition.done not configured, skipping for %s", issueKey);
            return;
        }
        transition(issueKey, transitionDone);
    }

    public void transitionToRejected(String issueKey) {
        if (transitionRejected.isBlank()) {
            LOG.warnf("JIRA transition.rejected not configured, skipping for %s", issueKey);
            return;
        }
        transition(issueKey, transitionRejected);
    }

    public void addWorklog(String issueKey, String timeSpent) {
        String ts = (timeSpent != null && !timeSpent.isBlank()) ? timeSpent : defaultWorklog;
        String body = """
                {"timeSpent":"%s","comment":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"Automated code fix by agent runner"}]}]}}
                """.formatted(ts);

        post("/rest/api/3/issue/" + issueKey + "/worklog", body, "add worklog");
    }

    public void commentStarted(String issueKey, String branchName) {
        addComment(issueKey, "Automated fix started. Branch: " + branchName);
    }

    public void commentSuccess(String issueKey, String prUrl, String summary) {
        String text = "Automated fix completed.\n\nPR: " + prUrl + "\n\nSummary: " + summary;
        addComment(issueKey, text);
    }

    public void commentFailure(String issueKey, String errorMessage) {
        addComment(issueKey, "Automated fix failed: " + errorMessage);
    }

    public void commentMerged(String issueKey) {
        addComment(issueKey, "PR merged. Fix deployed.");
    }

    public void commentRejected(String issueKey, String reason) {
        addComment(issueKey, "PR rejected. Reason: " + (reason != null ? reason : "No reason provided"));
    }

    private void transition(String issueKey, String transitionId) {
        String body = """
                {"transition":{"id":"%s"}}
                """.formatted(transitionId);

        post("/rest/api/3/issue/" + issueKey + "/transitions", body, "transition");
    }

    private String get(String path, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("JIRA %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    private String postForBody(String path, String body, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("JIRA %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    private void post(String path, String body, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("JIRA %s succeeded (HTTP %d)", operation, response.statusCode());
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
        }
    }

    // ─── Knowledge-base indexing helpers ──────────────────────────────────

    /**
     * Full issue detail record for knowledge indexing.
     */
    public record JiraIssueDetail(
            String key,
            String summary,
            String description,
            String status,
            String reporter,
            String assignee,
            java.util.List<String> labels,
            java.util.List<String> comments,
            java.util.List<JiraAttachment> attachments
    ) {}

    /**
     * Attachment metadata returned by the Jira REST API.
     */
    public record JiraAttachment(
            String id,
            String filename,
            String mimeType,
            long size,
            String contentUrl
    ) {}

    /**
     * Remote link (e.g. a linked Confluence page) on a Jira issue.
     */
    public record JiraRemoteLink(String title, String url) {}

    /**
     * Search Jira issues with the given JQL and return full detail for each.
     * Fetches summary, description, status, reporter, assignee, labels, comments,
     * and attachment metadata.
     *
     * @param jql    Jira Query Language expression
     * @param maxResults maximum number of results (capped at 100)
     */
    public java.util.List<JiraIssueDetail> searchIssues(String jql, int maxResults) {
        int cap = Math.min(Math.max(1, maxResults), 100);
        String fields = "summary,description,status,reporter,assignee,labels,comment,attachment";
        String encodedJql;
        try {
            encodedJql = java.net.URLEncoder.encode(jql, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warnf("Failed to encode JQL: %s", e.getMessage());
            return java.util.List.of();
        }
        String path = "/rest/api/3/search?jql=" + encodedJql
                + "&fields=" + fields + "&maxResults=" + cap + "&expand=renderedFields";
        String json = get(path, "search issues");
        if (json == null) return java.util.List.of();

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var issues = root.path("issues");
            if (!issues.isArray()) return java.util.List.of();

            var results = new java.util.ArrayList<JiraIssueDetail>();
            for (var issue : issues) {
                String key = issue.path("key").asText("");
                var fieldsNode = issue.path("fields");

                String summary = fieldsNode.path("summary").asText("");
                String description = extractAdfText(fieldsNode.path("description"));
                String status = fieldsNode.path("status").path("name").asText("");
                String reporter = fieldsNode.path("reporter").path("displayName").asText("");
                String assignee = fieldsNode.path("assignee").path("displayName").asText("");

                var labels = new java.util.ArrayList<String>();
                for (var lbl : fieldsNode.path("labels")) {
                    labels.add(lbl.asText(""));
                }

                var comments = new java.util.ArrayList<String>();
                for (var c : fieldsNode.path("comment").path("comments")) {
                    String commentBody = extractAdfText(c.path("body"));
                    String author = c.path("author").path("displayName").asText("unknown");
                    if (!commentBody.isBlank()) {
                        comments.add(author + ": " + commentBody);
                    }
                }

                var attachments = new java.util.ArrayList<JiraAttachment>();
                for (var att : fieldsNode.path("attachment")) {
                    attachments.add(new JiraAttachment(
                            att.path("id").asText(""),
                            att.path("filename").asText(""),
                            att.path("mimeType").asText(""),
                            att.path("size").asLong(0),
                            att.path("content").asText("")
                    ));
                }

                results.add(new JiraIssueDetail(key, summary, description, status,
                        reporter, assignee, labels, comments, attachments));
            }
            LOG.infof("JIRA searchIssues: found %d issues for JQL: %s", results.size(), jql);
            return results;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA search results: %s", e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Download binary content for a Jira attachment.
     * Returns null on error or if the URL is blank.
     *
     * @param contentUrl the attachment content URL from {@link JiraAttachment#contentUrl()}
     */
    public byte[] downloadAttachment(String contentUrl) {
        if (contentUrl == null || contentUrl.isBlank()) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(contentUrl))
                    .header("Authorization", "Basic " + basicAuth())
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            LOG.warnf("Attachment download failed (HTTP %d) for %s", response.statusCode(), contentUrl);
            return null;
        } catch (Exception e) {
            LOG.errorf("Attachment download error for %s: %s", contentUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch all remote links attached to a Jira issue.
     * Remote links include linked Confluence pages and external URLs.
     */
    public java.util.List<JiraRemoteLink> fetchRemoteLinks(String issueKey) {
        String json = get("/rest/api/3/issue/" + issueKey + "/remotelink", "fetch remote links " + issueKey);
        if (json == null) return java.util.List.of();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var arr = mapper.readTree(json);
            if (!arr.isArray()) return java.util.List.of();

            var results = new java.util.ArrayList<JiraRemoteLink>();
            for (var link : arr) {
                String title = link.path("object").path("title").asText("");
                String url = link.path("object").path("url").asText("");
                if (!url.isBlank()) {
                    results.add(new JiraRemoteLink(title, url));
                }
            }
            return results;
        } catch (Exception e) {
            LOG.warnf("Failed to parse remote links for %s: %s", issueKey, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Check if Jira is configured with valid credentials.
     */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && user != null && !user.isBlank()
                && apiToken != null && !apiToken.isBlank();
    }

    // Getters for system credentials (used by LinkedAccountService for fallback)
    public String getBaseUrl() { return baseUrl; }
    public String getUser() { return user; }
    public String getApiToken() { return apiToken; }

    /**
     * Test Jira connection with the provided credentials.
     * Returns true if the connection is valid, false otherwise.
     */
    public static boolean testConnection(String testBaseUrl, String testUser, String testApiToken) {
        try {
            String auth = Base64.getEncoder()
                    .encodeToString((testUser + ":" + testApiToken).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(testBaseUrl + "/rest/api/3/myself"))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private String basicAuth() {
        return Base64.getEncoder()
                .encodeToString((user + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ─── Credential-based methods for MCP tools ───────────────────────────────────

    public record JiraCredentials(String baseUrl, String username, String apiToken) {}

    public record WorklogEntry(String id, String author, String timeSpent, String started, String comment) {}

    public record TransitionOption(String id, String name) {}

    public record JiraIssue(String key, String summary, String description, String status,
                            String issueType, String projectKey) {}

    /**
     * Get full issue details using provided credentials.
     */
    public JiraIssue getIssue(String issueKey, JiraCredentials creds) {
        String json = getWithCreds("/rest/api/3/issue/" + issueKey + "?fields=summary,description,status,issuetype,project",
                "fetch issue " + issueKey, creds);
        if (json == null) return null;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var fields = root.path("fields");
            return new JiraIssue(
                    root.path("key").asText(""),
                    fields.path("summary").asText(""),
                    extractAdfText(fields.path("description")),
                    fields.path("status").path("name").asText(""),
                    fields.path("issuetype").path("name").asText(""),
                    fields.path("project").path("key").asText("")
            );
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA issue %s: %s", issueKey, e.getMessage());
            return null;
        }
    }

    /**
     * Search issues using provided credentials.
     * Uses POST /rest/api/3/search/jql (the old GET /rest/api/3/search was deprecated and removed).
     */
    public java.util.List<JiraIssueDetail> searchIssues(String jql, int maxResults, JiraCredentials creds) {
        int cap = Math.min(Math.max(1, maxResults), 100);
        var fieldsList = java.util.List.of("summary", "description", "status", "reporter", "assignee", "labels", "comment", "attachment");

        // Build JSON body using ObjectMapper for proper formatting
        String body;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.createObjectNode();
            node.put("jql", jql);
            node.put("maxResults", cap);
            node.set("fields", mapper.valueToTree(fieldsList));
            node.put("expand", "renderedFields");
            body = mapper.writeValueAsString(node);
        } catch (Exception e) {
            LOG.warnf("Failed to build search request body: %s", e.getMessage());
            return java.util.List.of();
        }

        String json = postForBodyWithCreds("/rest/api/3/search/jql", body, "search issues", creds);
        if (json == null) return java.util.List.of();

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var issues = root.path("issues");
            if (!issues.isArray()) return java.util.List.of();

            var results = new java.util.ArrayList<JiraIssueDetail>();
            for (var issue : issues) {
                String key = issue.path("key").asText("");
                var fieldsNode = issue.path("fields");

                String summary = fieldsNode.path("summary").asText("");
                String description = extractAdfText(fieldsNode.path("description"));
                String status = fieldsNode.path("status").path("name").asText("");
                String reporter = fieldsNode.path("reporter").path("displayName").asText("");
                String assignee = fieldsNode.path("assignee").path("displayName").asText("");

                var labels = new java.util.ArrayList<String>();
                for (var lbl : fieldsNode.path("labels")) {
                    labels.add(lbl.asText(""));
                }

                var comments = new java.util.ArrayList<String>();
                for (var c : fieldsNode.path("comment").path("comments")) {
                    String commentBody = extractAdfText(c.path("body"));
                    String author = c.path("author").path("displayName").asText("unknown");
                    if (!commentBody.isBlank()) {
                        comments.add(author + ": " + commentBody);
                    }
                }

                var attachments = new java.util.ArrayList<JiraAttachment>();
                for (var att : fieldsNode.path("attachment")) {
                    attachments.add(new JiraAttachment(
                            att.path("id").asText(""),
                            att.path("filename").asText(""),
                            att.path("mimeType").asText(""),
                            att.path("size").asLong(0),
                            att.path("content").asText("")
                    ));
                }

                results.add(new JiraIssueDetail(key, summary, description, status,
                        reporter, assignee, labels, comments, attachments));
            }
            LOG.infof("JIRA searchIssues: found %d issues for JQL: %s", results.size(), jql);
            return results;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA search results: %s", e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Get comments for an issue using provided credentials.
     */
    public java.util.List<String> getComments(String issueKey, JiraCredentials creds) {
        String json = getWithCreds("/rest/api/3/issue/" + issueKey + "?fields=comment",
                "fetch comments " + issueKey, creds);
        if (json == null) return java.util.List.of();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var comments = new java.util.ArrayList<String>();
            for (var c : root.path("fields").path("comment").path("comments")) {
                String body = extractAdfText(c.path("body"));
                String author = c.path("author").path("displayName").asText("unknown");
                if (!body.isBlank()) {
                    comments.add(author + ": " + body);
                }
            }
            return comments;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA comments for %s: %s", issueKey, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Add a comment using provided credentials.
     */
    public void addComment(String issueKey, String commentText, JiraCredentials creds) {
        String body = """
                {"body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}}
                """.formatted(escapeJson(commentText));
        postWithCreds("/rest/api/3/issue/" + issueKey + "/comment", body, "add comment", creds);
    }

    /**
     * Create a new issue using provided credentials.
     * @param parentKey       optional parent issue key (links Story under Feature/Epic)
     * @param billingCategory optional value for the billing-category custom field
     * @param billingCode     optional value for the billing-code custom field
     * @param billingCategoryFieldId  Jira custom field ID for billing category (e.g. customfield_10100)
     * @param billingCodeFieldId      Jira custom field ID for billing code (e.g. customfield_10101)
     * @param customFields    optional arbitrary map of fieldId -&gt; value to overlay
     */
    public String createIssue(String projectKey, String summary, String description,
                              String issueType, String parentKey,
                              String billingCategory, String billingCode,
                              String billingCategoryFieldId, String billingCodeFieldId,
                              java.util.Map<String, Object> customFields,
                              JiraCredentials creds) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var fieldsNode = mapper.createObjectNode();
            fieldsNode.set("project", mapper.createObjectNode().put("key", projectKey));
            fieldsNode.put("summary", summary);
            fieldsNode.set("issuetype", mapper.createObjectNode().put("name", issueType));
            if (description != null && !description.isBlank()) {
                fieldsNode.set("description", mapper.readTree(
                    "{\"type\":\"doc\",\"version\":1,\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(description) + "\"}]}]}"));
            }
            if (parentKey != null && !parentKey.isBlank()) {
                fieldsNode.set("parent", mapper.createObjectNode().put("key", parentKey));
            }
            if (billingCategory != null && !billingCategory.isBlank()
                    && billingCategoryFieldId != null && !billingCategoryFieldId.isBlank() && !"-".equals(billingCategoryFieldId)) {
                fieldsNode.put(billingCategoryFieldId, billingCategory);
            }
            if (billingCode != null && !billingCode.isBlank()
                    && billingCodeFieldId != null && !billingCodeFieldId.isBlank() && !"-".equals(billingCodeFieldId)) {
                fieldsNode.put(billingCodeFieldId, billingCode);
            }
            if (customFields != null && !customFields.isEmpty()) {
                for (var entry : customFields.entrySet()) {
                    fieldsNode.set(entry.getKey(), mapper.valueToTree(entry.getValue()));
                }
            }

            var root = mapper.createObjectNode();
            root.set("fields", fieldsNode);
            String body = mapper.writeValueAsString(root);

            String json = postForBodyWithCreds("/rest/api/3/issue", body, "create issue", creds);
            if (json == null) return null;
            return mapper.readTree(json).path("key").asText(null);
        } catch (Exception e) {
            LOG.warnf("Failed to create JIRA issue: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Update an issue using provided credentials.
     * @param assignee  accountId to assign; empty string "" to unassign; null to leave unchanged
     * @param projectKey  project key to move issue to (best-effort); null to leave unchanged
     */
    public void updateIssue(String issueKey, String summary, String description,
                            String assignee, String projectKey, JiraCredentials creds) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var fieldsNode = mapper.createObjectNode();
            if (summary != null && !summary.isBlank()) {
                fieldsNode.put("summary", summary);
            }
            if (description != null) {
                fieldsNode.set("description", mapper.readTree(
                    "{\"type\":\"doc\",\"version\":1,\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(description) + "\"}]}]}"));
            }
            if (assignee != null) {
                if (assignee.isBlank()) {
                    fieldsNode.set("assignee", mapper.nullNode());
                } else {
                    fieldsNode.set("assignee", mapper.createObjectNode().put("accountId", assignee));
                }
            }
            if (projectKey != null && !projectKey.isBlank()) {
                fieldsNode.set("project", mapper.createObjectNode().put("key", projectKey));
            }
            if (fieldsNode.isEmpty()) return;

            var root = mapper.createObjectNode();
            root.set("fields", fieldsNode);
            putWithCreds("/rest/api/3/issue/" + issueKey, mapper.writeValueAsString(root), "update issue", creds);
        } catch (Exception e) {
            LOG.warnf("Failed to build update issue body for %s: %s", issueKey, e.getMessage());
        }
    }

    /**
     * List available transitions for an issue.
     */
    public java.util.List<TransitionOption> listTransitions(String issueKey, JiraCredentials creds) {
        String json = getWithCreds("/rest/api/3/issue/" + issueKey + "/transitions",
                "list transitions " + issueKey, creds);
        if (json == null) return java.util.List.of();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var transitions = new java.util.ArrayList<TransitionOption>();
            for (var t : root.path("transitions")) {
                String id = t.path("id").asText("");
                String name = t.path("name").asText("");
                if (!id.isBlank()) {
                    transitions.add(new TransitionOption(id, name));
                }
            }
            return transitions;
        } catch (Exception e) {
            LOG.warnf("Failed to parse transitions for %s: %s", issueKey, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Transition an issue by transition name (case-insensitive match).
     */
    public boolean transitionIssue(String issueKey, String transitionName, JiraCredentials creds) {
        var transitions = listTransitions(issueKey, creds);
        String transitionId = null;
        for (var t : transitions) {
            if (t.name().equalsIgnoreCase(transitionName)) {
                transitionId = t.id();
                break;
            }
        }
        if (transitionId == null) {
            LOG.warnf("Transition '%s' not found for issue %s", transitionName, issueKey);
            return false;
        }

        String body = """
                {"transition":{"id":"%s"}}
                """.formatted(transitionId);
        return postWithCreds("/rest/api/3/issue/" + issueKey + "/transitions", body,
                "transition " + issueKey, creds);
    }

    /**
     * Get worklogs for an issue.
     */
    public java.util.List<WorklogEntry> getWorklogs(String issueKey, JiraCredentials creds) {
        String json = getWithCreds("/rest/api/3/issue/" + issueKey + "/worklog",
                "fetch worklogs " + issueKey, creds);
        if (json == null) return java.util.List.of();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var worklogs = new java.util.ArrayList<WorklogEntry>();
            for (var w : root.path("worklogs")) {
                String id = w.path("id").asText("");
                String author = w.path("author").path("displayName").asText("unknown");
                String timeSpent = w.path("timeSpent").asText("");
                String started = w.path("started").asText("");
                String comment = extractAdfText(w.path("comment"));
                worklogs.add(new WorklogEntry(id, author, timeSpent, started, comment));
            }
            return worklogs;
        } catch (Exception e) {
            LOG.warnf("Failed to parse worklogs for %s: %s", issueKey, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Add a worklog to an issue.
     */
    public void addWorklog(String issueKey, String timeSpent, String comment, String started, JiraCredentials creds) {
        String ts = (timeSpent != null && !timeSpent.isBlank()) ? timeSpent : defaultWorklog;
        String commentJson = "";
        if (comment != null && !comment.isBlank()) {
            commentJson = ",\"comment\":{\"type\":\"doc\",\"version\":1,\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(comment) + "\"}]}]}";
        }
        String startedJson = "";
        if (started != null && !started.isBlank()) {
            startedJson = ",\"started\":\"" + started + "\"";
        }
        String body = "{\"timeSpent\":\"" + ts + "\"" + commentJson + startedJson + "}";
        postWithCreds("/rest/api/3/issue/" + issueKey + "/worklog", body, "add worklog", creds);
    }

    // ─── HTTP helpers with credentials ──────────────────────────────────────────

    private String getWithCreds(String path, String operation, JiraCredentials creds) {
        try {
            String auth = Base64.getEncoder()
                    .encodeToString((creds.username() + ":" + creds.apiToken()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(creds.baseUrl() + path))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    private String postForBodyWithCreds(String path, String body, String operation, JiraCredentials creds) {
        try {
            String auth = Base64.getEncoder()
                    .encodeToString((creds.username() + ":" + creds.apiToken()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(creds.baseUrl() + path))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    private boolean postWithCreds(String path, String body, String operation, JiraCredentials creds) {
        try {
            String auth = Base64.getEncoder()
                    .encodeToString((creds.username() + ":" + creds.apiToken()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(creds.baseUrl() + path))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return false;
        }
    }

    private boolean putWithCreds(String path, String body, String operation, JiraCredentials creds) {
        try {
            String auth = Base64.getEncoder()
                    .encodeToString((creds.username() + ":" + creds.apiToken()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(creds.baseUrl() + path))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return false;
        }
    }
}
