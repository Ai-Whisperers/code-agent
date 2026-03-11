package com.eneve.agent.jira;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

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
        String json = get("/rest/api/3/issue/" + issueKey + "?fields=description",
                "fetch description " + issueKey);
        if (json == null) return java.util.List.of();

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var description = root.path("fields").path("description");

            String allText = extractAdfTextAndLinks(description);
            LOG.infof("JIRA %s description extracted text+links (%d chars): %s",
                    issueKey, allText.length(),
                    allText.length() > 500 ? allText.substring(0, 500) + "..." : allText);

            var candidates = new java.util.LinkedHashSet<Integer>();

            var patterns = new java.util.regex.Pattern[]{
                    java.util.regex.Pattern.compile("aikido\\.dev/issues/groups/(\\d+)"),
                    java.util.regex.Pattern.compile("aikido\\.dev[^\\s]*[?&]sidebarIssue=(\\d+)"),
                    java.util.regex.Pattern.compile("aikido\\.dev[^\\s]*[?&]groupId=(\\d+)")
            };
            for (var pattern : patterns) {
                var matcher = pattern.matcher(allText);
                while (matcher.find()) {
                    candidates.add(Integer.parseInt(matcher.group(1)));
                }
            }

            LOG.infof("JIRA %s: found %d Aikido candidate IDs: %s", issueKey, candidates.size(), candidates);
            return new java.util.ArrayList<>(candidates);
        } catch (Exception e) {
            LOG.warnf("Failed to extract Aikido IDs from %s: %s", issueKey, e.getMessage());
            return java.util.List.of();
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
}
