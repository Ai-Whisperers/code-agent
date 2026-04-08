package com.eneve.agent.jira;

import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Worklog operations against the Jira REST API.
 * Package-private — all access goes through {@link JiraService}.
 */
@ApplicationScoped
class JiraWorklogClient {

    private static final Logger LOG = Logger.getLogger(JiraWorklogClient.class);

    @Inject JiraHttpClient http;
    @Inject JiraAdfParser adf;
    @Inject SettingsService settings;
    @Inject ObjectMapper mapper;

    void addWorklog(String issueKey, String timeSpent) {
        String ts = (timeSpent != null && !timeSpent.isBlank()) ? timeSpent : settings.get("jira.default.worklog", "30m");
        String body = """
                {"timeSpent":"%s","comment":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"Automated code fix by agent runner"}]}]}}
                """.formatted(ts);
        http.post("/rest/api/3/issue/" + issueKey + "/worklog", body, "add worklog");
    }

    void addWorklog(String issueKey, String timeSpent, String comment, String started,
                    JiraService.JiraCredentials creds) {
        String ts = (timeSpent != null && !timeSpent.isBlank()) ? timeSpent : settings.get("jira.default.worklog", "30m");
        String commentJson = "";
        if (comment != null && !comment.isBlank()) {
            commentJson = ",\"comment\":{\"type\":\"doc\",\"version\":1,\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + JiraHttpClient.escapeJson(comment) + "\"}]}]}";
        }
        String startedJson = "";
        if (started != null && !started.isBlank()) {
            startedJson = ",\"started\":\"" + started + "\"";
        }
        String body = "{\"timeSpent\":\"" + ts + "\"" + commentJson + startedJson + "}";
        http.postWithCreds("/rest/api/3/issue/" + issueKey + "/worklog", body, "add worklog", creds);
    }

    List<JiraService.WorklogEntry> getWorklogs(String issueKey, JiraService.JiraCredentials creds) {
        String json = http.getWithCreds("/rest/api/3/issue/" + issueKey + "/worklog",
                "fetch worklogs " + issueKey, creds);
        if (json == null) return List.of();
        try {
            var root = mapper.readTree(json);
            var worklogs = new ArrayList<JiraService.WorklogEntry>();
            for (var w : root.path("worklogs")) {
                String id = w.path("id").asText("");
                String author = w.path("author").path("displayName").asText("unknown");
                String timeSpent = w.path("timeSpent").asText("");
                String started = w.path("started").asText("");
                String comment = adf.extractAdfText(w.path("comment"));
                worklogs.add(new JiraService.WorklogEntry(id, author, timeSpent, started, comment));
            }
            return worklogs;
        } catch (Exception e) {
            LOG.warnf("Failed to parse worklogs for %s: %s", issueKey, e.getMessage());
            return List.of();
        }
    }
}
