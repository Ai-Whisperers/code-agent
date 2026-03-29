package com.eneve.agent.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fetch operations for individual Jira issues via direct REST endpoints.
 * Package-private — all access goes through {@link JiraService}.
 */
@ApplicationScoped
class JiraIssueFetcher {

    private static final Logger LOG = Logger.getLogger(JiraIssueFetcher.class);

    @Inject JiraHttpClient http;
    @Inject JiraAdfParser adf;
    @Inject ObjectMapper mapper;

    String fetchIssueSummary(String issueKey) {
        String json = http.get("/rest/api/3/issue/" + issueKey + "?fields=summary",
                "fetch summary " + issueKey);
        if (json == null) return null;
        try {
            var root = mapper.readTree(json);
            return root.path("fields").path("summary").asText(null);
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA summary for %s: %s", issueKey, e.getMessage());
            return null;
        }
    }

    List<Integer> extractAikidoCandidateIds(String issueKey) {
        return extractDescriptionContext(issueKey).aikidoCandidateIds();
    }

    JiraService.JiraDescriptionContext extractDescriptionContext(String issueKey) {
        String json = http.get("/rest/api/3/issue/" + issueKey + "?fields=description",
                "fetch description " + issueKey);
        if (json == null) return new JiraService.JiraDescriptionContext(List.of(), List.of());

        try {
            var root = mapper.readTree(json);
            var description = root.path("fields").path("description");

            String allText = adf.extractAdfTextAndLinks(description);
            LOG.infof("JIRA %s description extracted text+links (%d chars): %s",
                    issueKey, allText.length(),
                    allText.length() > 500 ? allText.substring(0, 500) + "..." : allText);

            var candidateIds = new LinkedHashSet<Integer>();
            var aikidoPatterns = new Pattern[]{
                    Pattern.compile("aikido\\.dev/issues/groups/(\\d+)"),
                    Pattern.compile("aikido\\.dev[^\\s]*[?&]sidebarIssue=(\\d+)"),
                    Pattern.compile("aikido\\.dev[^\\s]*[?&]groupId=(\\d+)")
            };
            for (var pattern : aikidoPatterns) {
                var matcher = pattern.matcher(allText);
                while (matcher.find()) {
                    candidateIds.add(Integer.parseInt(matcher.group(1)));
                }
            }
            LOG.infof("JIRA %s: found %d Aikido candidate IDs: %s", issueKey, candidateIds.size(), candidateIds);

            var containerNames = new ArrayList<String>();
            var containerPattern = Pattern.compile(
                    "(?i)containers?\\s*:\\s*\\n?\\s*([a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+)");
            var containerMatcher = containerPattern.matcher(allText);
            while (containerMatcher.find()) {
                containerNames.add(containerMatcher.group(1));
            }
            if (!containerNames.isEmpty()) {
                LOG.infof("JIRA %s: found container references: %s", issueKey, containerNames);
            }

            return new JiraService.JiraDescriptionContext(new ArrayList<>(candidateIds), containerNames);
        } catch (Exception e) {
            LOG.warnf("Failed to extract description context from %s: %s", issueKey, e.getMessage());
            return new JiraService.JiraDescriptionContext(List.of(), List.of());
        }
    }

    String fetchIssuePrompt(String issueKey) {
        String json = http.get("/rest/api/3/issue/" + issueKey + "?fields=summary,description",
                "fetch issue " + issueKey);
        if (json == null) return null;
        try {
            var root = mapper.readTree(json);
            var fields = root.path("fields");
            String summary = fields.path("summary").asText("");
            String description = adf.extractAdfText(fields.path("description"));
            return description.isBlank() ? summary : summary + "\n\n" + description;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA issue %s: %s", issueKey, e.getMessage());
            return null;
        }
    }

    List<JiraService.JiraRemoteLink> fetchRemoteLinks(String issueKey) {
        String json = http.get("/rest/api/3/issue/" + issueKey + "/remotelink", "fetch remote links " + issueKey);
        if (json == null) return List.of();
        try {
            var arr = mapper.readTree(json);
            if (!arr.isArray()) return List.of();

            var results = new ArrayList<JiraService.JiraRemoteLink>();
            for (var link : arr) {
                String title = link.path("object").path("title").asText("");
                String url = link.path("object").path("url").asText("");
                if (!url.isBlank()) results.add(new JiraService.JiraRemoteLink(title, url));
            }
            return results;
        } catch (Exception e) {
            LOG.warnf("Failed to parse remote links for %s: %s", issueKey, e.getMessage());
            return List.of();
        }
    }

    byte[] downloadAttachment(String contentUrl) {
        return http.downloadAttachment(contentUrl);
    }

    String[] getIssueSlaMeta(String issueKey) {
        String json = http.get("/rest/api/3/issue/" + issueKey
                + "?fields=priority,issuetype,created", "fetch SLA meta " + issueKey);
        if (json == null) return new String[]{null, null, null};
        try {
            var root = mapper.readTree(json);
            var fields = root.path("fields");
            String priority  = fields.path("priority").path("name").asText(null);
            String issueType = fields.path("issuetype").path("name").asText(null);
            String created   = fields.path("created").asText(null);
            return new String[]{priority, issueType, created};
        } catch (Exception e) {
            LOG.warnf("getIssueSlaMeta(%s) failed: %s", issueKey, e.getMessage());
            return new String[]{null, null, null};
        }
    }

    JiraService.JiraIssue getIssue(String issueKey, JiraService.JiraCredentials creds) {
        String json = http.getWithCreds("/rest/api/3/issue/" + issueKey
                + "?fields=summary,description,status,issuetype,project",
                "fetch issue " + issueKey, creds);
        if (json == null) return null;
        try {
            var root = mapper.readTree(json);
            var fields = root.path("fields");
            return new JiraService.JiraIssue(
                    root.path("key").asText(""),
                    fields.path("summary").asText(""),
                    adf.extractAdfText(fields.path("description")),
                    fields.path("status").path("name").asText(""),
                    fields.path("issuetype").path("name").asText(""),
                    fields.path("project").path("key").asText(""));
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA issue %s: %s", issueKey, e.getMessage());
            return null;
        }
    }

    List<String> getComments(String issueKey, JiraService.JiraCredentials creds) {
        String json = http.getWithCreds("/rest/api/3/issue/" + issueKey + "?fields=comment",
                "fetch comments " + issueKey, creds);
        if (json == null) return List.of();
        try {
            var root = mapper.readTree(json);
            var comments = new ArrayList<String>();
            for (var c : root.path("fields").path("comment").path("comments")) {
                String body = adf.extractAdfText(c.path("body"));
                String author = c.path("author").path("displayName").asText("unknown");
                if (!body.isBlank()) comments.add(author + ": " + body);
            }
            return comments;
        } catch (Exception e) {
            LOG.warnf("Failed to parse JIRA comments for %s: %s", issueKey, e.getMessage());
            return List.of();
        }
    }
}
