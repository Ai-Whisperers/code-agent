package com.eneve.agent.jira;

import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Issue transition and comment operations.
 * Package-private — all access goes through {@link JiraService}.
 */
@ApplicationScoped
class JiraTransitionClient {

    private static final Logger LOG = Logger.getLogger(JiraTransitionClient.class);

    @Inject JiraHttpClient http;
    @Inject SettingsService settings;
    @Inject ObjectMapper mapper;

    void addComment(String issueKey, String commentText) {
        String body = """
                {"body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}}
                """.formatted(JiraHttpClient.escapeJson(commentText));
        http.post("/rest/api/3/issue/" + issueKey + "/comment", body, "add comment");
    }

    void addComment(String issueKey, String commentText, JiraService.JiraCredentials creds) {
        String body = """
                {"body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}}
                """.formatted(JiraHttpClient.escapeJson(commentText));
        http.postWithCreds("/rest/api/3/issue/" + issueKey + "/comment", body, "add comment", creds);
    }

    /**
     * Posts an <em>internal</em> (agent-only) comment on a Jira Service Management issue.
     * The {@code visibility} block restricts the comment to the "Service Desk Team" role,
     * making it invisible to the customer reporter.
     */
    void addInternalComment(String issueKey, String commentText) {
        String body = """
                {"visibility":{"type":"role","value":"Service Desk Team"},\
                "body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}}
                """.formatted(JiraHttpClient.escapeJson(commentText));
        http.post("/rest/api/3/issue/" + issueKey + "/comment", body, "add internal comment");
    }

    void transitionToInProgress(String issueKey) {
        String id = settings.get("jira.transition.in-progress", "");
        if (id.isBlank()) {
            LOG.warnf("JIRA transition.in-progress not configured, skipping for %s", issueKey);
            return;
        }
        transition(issueKey, id);
    }

    void transitionToInReview(String issueKey) {
        String id = settings.get("jira.transition.in-review", "");
        if (id.isBlank()) {
            LOG.warnf("JIRA transition.in-review not configured, skipping for %s", issueKey);
            return;
        }
        transition(issueKey, id);
    }

    void transitionToDone(String issueKey) {
        String id = settings.get("jira.transition.done", "");
        if (id.isBlank()) {
            LOG.warnf("JIRA transition.done not configured, skipping for %s", issueKey);
            return;
        }
        transition(issueKey, id);
    }

    void transitionToRejected(String issueKey) {
        String id = settings.get("jira.transition.rejected", "");
        if (id.isBlank()) {
            LOG.warnf("JIRA transition.rejected not configured, skipping for %s", issueKey);
            return;
        }
        transition(issueKey, id);
    }

    void transition(String issueKey, String transitionId) {
        String body = """
                {"transition":{"id":"%s"}}
                """.formatted(transitionId);
        http.post("/rest/api/3/issue/" + issueKey + "/transitions", body, "transition");
    }

    void commentStarted(String issueKey, String label, String branchName) {
        addComment(issueKey, label + " started. Branch: " + branchName);
    }

    void commentSuccess(String issueKey, String label, String prUrl, String summary) {
        addComment(issueKey, label + " completed.\n\nPR: " + prUrl + "\n\nSummary: " + summary);
    }

    void commentFailure(String issueKey, String label, String errorMessage) {
        addComment(issueKey, label + " failed: " + errorMessage);
    }

    void commentMerged(String issueKey) {
        addComment(issueKey, "PR merged. Fix deployed.");
    }

    void commentRejected(String issueKey, String reason) {
        addComment(issueKey, "PR rejected. Reason: " + (reason != null ? reason : "No reason provided"));
    }

    List<JiraService.TransitionOption> listTransitions(String issueKey, JiraService.JiraCredentials creds) {
        String json = http.getWithCreds("/rest/api/3/issue/" + issueKey + "/transitions",
                "list transitions " + issueKey, creds);
        if (json == null) return List.of();
        try {
            var root = mapper.readTree(json);
            var transitions = new ArrayList<JiraService.TransitionOption>();
            for (var t : root.path("transitions")) {
                String id = t.path("id").asText("");
                String name = t.path("name").asText("");
                if (!id.isBlank()) transitions.add(new JiraService.TransitionOption(id, name));
            }
            return transitions;
        } catch (Exception e) {
            LOG.warnf("Failed to parse transitions for %s: %s", issueKey, e.getMessage());
            return List.of();
        }
    }

    boolean transitionIssue(String issueKey, String transitionName, JiraService.JiraCredentials creds) {
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
        return http.postWithCreds("/rest/api/3/issue/" + issueKey + "/transitions", body,
                "transition " + issueKey, creds);
    }
}
