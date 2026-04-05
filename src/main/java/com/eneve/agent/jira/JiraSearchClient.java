package com.eneve.agent.jira;

import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JQL-based search operations against the Jira REST API.
 * Package-private — all access goes through {@link JiraService}.
 */
@ApplicationScoped
class JiraSearchClient {

    private static final Logger LOG = Logger.getLogger(JiraSearchClient.class);

    @Inject JiraHttpClient http;
    @Inject JiraAdfParser adf;
    @Inject SettingsService settings;
    @Inject ObjectMapper mapper;

    List<JiraService.JiraIssueRef> searchIssuesByLabel(String label) {
        String jql = "labels = \"" + JiraHttpClient.escapeJson(label) + "\" AND statusCategory != Done ORDER BY created DESC";
        String body = "{\"jql\":\"" + JiraHttpClient.escapeJson(jql) + "\",\"fields\":[\"summary\"],\"maxResults\":50}";

        String json = http.postForBody("/rest/api/3/search/jql", body, "search issues by label");
        if (json == null) return List.of();

        try {
            var root = mapper.readTree(json);
            var issues = root.path("issues");
            if (!issues.isArray()) return List.of();

            var results = new ArrayList<JiraService.JiraIssueRef>();
            for (var issue : issues) {
                String key = issue.path("key").asText("");
                String summary = issue.path("fields").path("summary").asText("");
                if (!key.isBlank()) results.add(new JiraService.JiraIssueRef(key, summary));
            }
            LOG.infof("JIRA search: found %d open issues with label %s", results.size(), label);
            return results;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA search results: %s", e.getMessage());
            return List.of();
        }
    }

    JiraService.JiraIssueDetail fetchIssueDetail(String issueKey) {
        var results = searchIssues("key = \"" + JiraHttpClient.escapeJson(issueKey) + "\"", 1);
        return results.isEmpty() ? null : results.get(0);
    }

    String fetchIssueStatus(String issueKey) {
        String json = http.get("/rest/api/3/issue/" + JiraHttpClient.escapeJson(issueKey) + "?fields=status",
                "fetch status " + issueKey);
        if (json == null) return null;
        try {
            var root = mapper.readTree(json);
            return root.path("fields").path("status").path("name").asText(null);
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA status for %s: %s", issueKey, e.getMessage());
            return null;
        }
    }

    List<JiraService.JiraIssueDetail> searchEpicsByLabel(String label) {
        return searchEpicsByLabel(label, settings.get("roadmap.jira.epic-issuetype", "Epic"));
    }

    List<JiraService.JiraIssueDetail> searchEpicsByLabel(String label, String issuetype) {
        String jql = "issuetype = \"" + JiraHttpClient.escapeJson(issuetype) + "\" AND labels = \""
                + JiraHttpClient.escapeJson(label) + "\" ORDER BY created ASC";
        return searchIssues(jql, 100);
    }

    List<JiraService.JiraIssueDetail> searchEpicsByLabels(List<String> labels, String issuetype) {
        if (labels == null || labels.isEmpty()) return List.of();
        if (labels.size() == 1) return searchEpicsByLabel(labels.get(0), issuetype);
        String labelsIn = labels.stream()
                .map(l -> "\"" + JiraHttpClient.escapeJson(l) + "\"")
                .collect(Collectors.joining(", "));
        String jql = "issuetype = \"" + JiraHttpClient.escapeJson(issuetype) + "\" AND labels in (" + labelsIn + ") ORDER BY created ASC";
        return searchIssues(jql, 500);
    }

    List<JiraService.JiraIssueDetail> searchFeaturesByLabels(List<String> labels, String issuetype) {
        if (labels == null || labels.isEmpty()) return List.of();
        String labelsIn = labels.stream()
                .map(l -> "\"" + JiraHttpClient.escapeJson(l) + "\"")
                .collect(Collectors.joining(", "));
        String jql = "issuetype = \"" + JiraHttpClient.escapeJson(issuetype) + "\" AND labels in (" + labelsIn + ") ORDER BY created ASC";
        return searchIssues(jql, 500);
    }

    List<JiraService.JiraIssueDetail> previewIssuesByLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) return List.of();
        String labelsIn = labels.stream()
                .map(l -> "\"" + JiraHttpClient.escapeJson(l) + "\"")
                .collect(Collectors.joining(", "));
        String jql = "labels in (" + labelsIn + ") ORDER BY created DESC";
        return searchIssues(jql, 1000);
    }

    List<JiraService.JiraIssueDetail> searchFeaturesForEpic(String epicKey) {
        return searchFeaturesForEpic(epicKey, settings.get("roadmap.jira.feature-issuetype", "Story"));
    }

    List<JiraService.JiraIssueDetail> searchFeaturesForEpic(String epicKey, String issuetype) {
        String jql = "issuetype = \"" + JiraHttpClient.escapeJson(issuetype) + "\" AND parent = \""
                + JiraHttpClient.escapeJson(epicKey) + "\" ORDER BY created ASC";
        return searchIssues(jql, 100);
    }

    /** Search for features (not epics) with the given labels. Used for QA scope feature discovery. */
    List<JiraService.JiraIssueDetail> searchQAFeaturesByLabels(List<String> labels, String featureIssuetype) {
        if (labels == null || labels.isEmpty()) return List.of();
        String labelsIn = labels.stream()
                .map(l -> "\"" + JiraHttpClient.escapeJson(l) + "\"")
                .collect(Collectors.joining(", "));
        String jql = "issuetype = \"" + JiraHttpClient.escapeJson(featureIssuetype) + "\" AND labels in (" + labelsIn + ") ORDER BY created ASC";
        return searchIssues(jql, 500);
    }

    /**
     * Returns all issue keys linked to {@code issueKey} via a "test" relationship
     * (inward "is tested by" / outward "tests").
     *
     * <p>Jira Clouds represent the relationship in one of two ways depending on which
     * end of the link was used to create it:
     * <ul>
     *   <li>The test-plan ticket appears as {@code inwardIssue} when the link type's
     *       {@code inward} name contains "tested by".</li>
     *   <li>The test-plan ticket appears as {@code outwardIssue} when the link type's
     *       {@code outward} name contains "tests".</li>
     * </ul>
     * Both cases are handled here. The raw issuelinks array is logged at INFO level
     * so mismatches can be diagnosed.
     */
    List<JiraService.JiraIssueRef> fetchIsTestedByLinks(String issueKey) {
        String json = http.get(
                "/rest/api/3/issue/" + JiraHttpClient.escapeJson(issueKey) + "?fields=issuelinks",
                "fetch issue links " + issueKey);
        if (json == null) return List.of();
        try {
            var root = mapper.readTree(json);
            var links = root.path("fields").path("issuelinks");
            if (!links.isArray()) {
                LOG.infof("JIRA fetchIsTestedByLinks: no issuelinks field for %s", issueKey);
                return List.of();
            }

            // Log the raw issuelinks so link-type names can be verified in the server log
            LOG.infof("JIRA fetchIsTestedByLinks: %d raw link(s) for %s — %s",
                    links.size(), issueKey, links.toString());

            var results = new ArrayList<JiraService.JiraIssueRef>();
            for (var link : links) {
                var typeNode     = link.path("type");
                String inward    = typeNode.path("inward").asText("").toLowerCase();
                String outward   = typeNode.path("outward").asText("").toLowerCase();

                // Case 1 – test plan is in the inwardIssue slot
                //   type.inward = "is tested by"  →  the OTHER issue is in inwardIssue
                if (inward.contains("tested by") && !link.path("inwardIssue").isMissingNode()) {
                    var node = link.path("inwardIssue");
                    String key = node.path("key").asText("");
                    if (!key.isBlank()) {
                        LOG.infof("JIRA fetchIsTestedByLinks: %s → inwardIssue %s", issueKey, key);
                        results.add(new JiraService.JiraIssueRef(key,
                                node.path("fields").path("summary").asText("")));
                        continue;
                    }
                }

                // Case 2 – test plan is in the outwardIssue slot
                //   type.outward = "tests"  →  the OTHER issue is in outwardIssue
                if ((outward.contains("tests") || outward.contains("tested by"))
                        && !link.path("outwardIssue").isMissingNode()) {
                    var node = link.path("outwardIssue");
                    String key = node.path("key").asText("");
                    if (!key.isBlank()) {
                        LOG.infof("JIRA fetchIsTestedByLinks: %s → outwardIssue %s", issueKey, key);
                        results.add(new JiraService.JiraIssueRef(key,
                                node.path("fields").path("summary").asText("")));
                    }
                }
            }

            LOG.infof("JIRA fetchIsTestedByLinks: resolved %d test plan link(s) for %s",
                    results.size(), issueKey);
            return results;
        } catch (Exception e) {
            LOG.warnf("Failed to parse issue links for %s: %s", issueKey, e.getMessage());
            return List.of();
        }
    }

    List<JiraService.JiraIssueDetail> searchStoriesForFeature(String featureKey) {
        return searchStoriesForFeature(featureKey, settings.get("roadmap.jira.userstory-issuetype", "Sub-task"));
    }

    List<JiraService.JiraIssueDetail> searchStoriesForFeature(String featureKey, String issuetype) {
        String jql = "issuetype = \"" + JiraHttpClient.escapeJson(issuetype) + "\" AND parent = \""
                + JiraHttpClient.escapeJson(featureKey) + "\" ORDER BY created ASC";
        return searchIssues(jql, 100);
    }

    List<JiraService.JiraIssueDetail> searchIssues(String jql, int maxResults) {
        int cap = Math.min(Math.max(1, maxResults), 100);
        var fieldsList = List.of("summary", "description", "status", "reporter", "assignee",
                "labels", "comment", "attachment", "updated", "customfield_10020", "priority");

        var body = mapper.createObjectNode();
        body.put("jql", jql);
        body.set("fields", mapper.valueToTree(fieldsList));
        body.put("maxResults", cap);
        body.put("expand", "renderedFields");

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            LOG.warnf("Failed to serialize search request: %s", e.getMessage());
            return List.of();
        }

        String json = http.postForBody("/rest/api/3/search/jql", jsonBody, "search issues");
        if (json == null) return List.of();

        try {
            var root = mapper.readTree(json);
            var issues = root.path("issues");
            if (!issues.isArray()) return List.of();

            var results = new ArrayList<JiraService.JiraIssueDetail>();
            for (var issue : issues) {
                String key = issue.path("key").asText("");
                var fieldsNode = issue.path("fields");

                String summary = fieldsNode.path("summary").asText("");
                String description = adf.extractAdfText(fieldsNode.path("description"));
                String status = fieldsNode.path("status").path("name").asText("");
                String reporter = fieldsNode.path("reporter").path("displayName").asText("");
                String assignee = fieldsNode.path("assignee").path("displayName").asText("");

                var labels = new ArrayList<String>();
                for (var lbl : fieldsNode.path("labels")) labels.add(lbl.asText(""));

                var comments = new ArrayList<String>();
                for (var c : fieldsNode.path("comment").path("comments")) {
                    String commentBody = adf.extractAdfText(c.path("body"));
                    String author = c.path("author").path("displayName").asText("unknown");
                    if (!commentBody.isBlank()) comments.add(author + ": " + commentBody);
                }

                var attachments = new ArrayList<JiraService.JiraAttachment>();
                for (var att : fieldsNode.path("attachment")) {
                    attachments.add(new JiraService.JiraAttachment(
                            att.path("id").asText(""), att.path("filename").asText(""),
                            att.path("mimeType").asText(""), att.path("size").asLong(0),
                            att.path("content").asText("")));
                }

                java.time.Instant updatedAt = JiraHttpClient.parseJiraTimestamp(fieldsNode.path("updated").asText(null));

                String sprintName = null;
                java.time.Instant sprintStart = null;
                java.time.Instant sprintEnd = null;
                var sprintArray = fieldsNode.path("customfield_10020");
                if (sprintArray.isArray() && sprintArray.size() > 0) {
                    var lastSprint = sprintArray.get(sprintArray.size() - 1);
                    sprintName  = lastSprint.path("name").asText(null);
                    sprintStart = JiraHttpClient.parseJiraTimestamp(lastSprint.path("startDate").asText(null));
                    sprintEnd   = JiraHttpClient.parseJiraTimestamp(lastSprint.path("endDate").asText(null));
                }

                String priority = fieldsNode.path("priority").path("name").asText(null);

                // Use Markdown conversion so proposals display rich formatting
                String descriptionMd = adf.adfToMarkdown(fieldsNode.path("description"));
                results.add(new JiraService.JiraIssueDetail(key, summary, descriptionMd, status,
                        reporter, assignee, labels, comments, attachments, updatedAt,
                        sprintName, sprintStart, sprintEnd, priority));
            }
            LOG.infof("JIRA searchIssues: found %d issues for JQL: %s", results.size(), jql);
            return results;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA search results: %s", e.getMessage());
            return List.of();
        }
    }

    List<JiraService.JiraIssueDetail> searchIssues(String jql, int maxResults, JiraService.JiraCredentials creds) {
        int cap = Math.min(Math.max(1, maxResults), 100);
        var fieldsList = List.of("summary", "description", "status", "reporter", "assignee",
                "labels", "comment", "attachment", "updated");

        String body;
        try {
            var node = mapper.createObjectNode();
            node.put("jql", jql);
            node.put("maxResults", cap);
            node.set("fields", mapper.valueToTree(fieldsList));
            node.put("expand", "renderedFields");
            body = mapper.writeValueAsString(node);
        } catch (Exception e) {
            LOG.warnf("Failed to build search request body: %s", e.getMessage());
            return List.of();
        }

        String json = http.postForBodyWithCreds("/rest/api/3/search/jql", body, "search issues", creds);
        if (json == null) return List.of();

        try {
            var root = mapper.readTree(json);
            var issues = root.path("issues");
            if (!issues.isArray()) return List.of();

            var results = new ArrayList<JiraService.JiraIssueDetail>();
            for (var issue : issues) {
                String key = issue.path("key").asText("");
                var fieldsNode = issue.path("fields");

                String summary = fieldsNode.path("summary").asText("");
                String description = adf.adfToMarkdown(fieldsNode.path("description"));
                String status = fieldsNode.path("status").path("name").asText("");
                String reporter = fieldsNode.path("reporter").path("displayName").asText("");
                String assignee = fieldsNode.path("assignee").path("displayName").asText("");

                var labels = new ArrayList<String>();
                for (var lbl : fieldsNode.path("labels")) labels.add(lbl.asText(""));

                var comments = new ArrayList<String>();
                for (var c : fieldsNode.path("comment").path("comments")) {
                    String commentBody = adf.extractAdfText(c.path("body"));
                    String author = c.path("author").path("displayName").asText("unknown");
                    if (!commentBody.isBlank()) comments.add(author + ": " + commentBody);
                }

                var attachments = new ArrayList<JiraService.JiraAttachment>();
                for (var att : fieldsNode.path("attachment")) {
                    attachments.add(new JiraService.JiraAttachment(
                            att.path("id").asText(""), att.path("filename").asText(""),
                            att.path("mimeType").asText(""), att.path("size").asLong(0),
                            att.path("content").asText("")));
                }

                java.time.Instant updatedAt = JiraHttpClient.parseJiraTimestamp(fieldsNode.path("updated").asText(null));

                results.add(new JiraService.JiraIssueDetail(key, summary, description, status,
                        reporter, assignee, labels, comments, attachments, updatedAt));
            }
            LOG.infof("JIRA searchIssues(creds): found %d issues for JQL: %s", results.size(), jql);
            return results;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA search results: %s", e.getMessage());
            return List.of();
        }
    }
}
