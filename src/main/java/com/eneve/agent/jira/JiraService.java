package com.eneve.agent.jira;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * JIRA Cloud REST API 3 client — public facade.
 * Delegates to package-private collaborators; keeps all public record types
 * for backward compatibility.
 */
@ApplicationScoped
public class JiraService {

    @Inject JiraHttpClient    httpClient;
    @Inject JiraSearchClient  search;
    @Inject JiraIssueFetcher  fetcher;
    @Inject JiraIssueWriter   writer;
    @Inject JiraTransitionClient transitions;
    @Inject JiraWorklogClient worklogs;

    // ─── Public domain records ────────────────────────────────────────────────

    public record JiraIssueRef(String key, String summary) {}

    /** Parsed context from a JIRA issue description: Aikido candidate IDs and container names. */
    public record JiraDescriptionContext(
            List<Integer> aikidoCandidateIds,
            List<String> containerNames
    ) {}

    /**
     * Full issue detail record for knowledge indexing and roadmap reviews.
     * {@code updatedAt} reflects the Jira {@code updated} timestamp so callers
     * can detect whether the issue was modified after the last AI review.
     * Sprint fields are populated only for FEATURE and USERSTORY items.
     */
    public record JiraIssueDetail(
            String key,
            String summary,
            String description,
            String status,
            String reporter,
            String assignee,
            List<String> labels,
            List<String> comments,
            List<JiraAttachment> attachments,
            Instant updatedAt,
            String sprintName,
            Instant sprintStart,
            Instant sprintEnd
    ) {
        /** Convenience constructor that leaves sprint fields null (backwards-compat). */
        public JiraIssueDetail(String key, String summary, String description, String status,
                               String reporter, String assignee,
                               List<String> labels, List<String> comments,
                               List<JiraAttachment> attachments, Instant updatedAt) {
            this(key, summary, description, status, reporter, assignee,
                    labels, comments, attachments, updatedAt, null, null, null);
        }
    }

    /** Attachment metadata returned by the Jira REST API. */
    public record JiraAttachment(String id, String filename, String mimeType, long size, String contentUrl) {}

    /** Remote link (e.g. a linked Confluence page) on a Jira issue. */
    public record JiraRemoteLink(String title, String url) {}

    /**
     * Credentials for a per-user Jira request.
     *
     * <ul>
     *   <li>{@code "apitoken"} — Basic auth using {@code username:apiToken}</li>
     *   <li>{@code "oauth"}    — Bearer auth using {@code apiToken} as the access token
     *                            ({@code username} is ignored for HTTP auth but kept for display)</li>
     * </ul>
     */
    public record JiraCredentials(String baseUrl, String username, String apiToken, String authType) {

        /** Basic-auth (username + API token) credentials. */
        public static JiraCredentials basic(String baseUrl, String username, String apiToken) {
            return new JiraCredentials(baseUrl, username, apiToken, "apitoken");
        }

        /** OAuth Bearer-token credentials. */
        public static JiraCredentials oauth(String baseUrl, String username, String accessToken) {
            return new JiraCredentials(baseUrl, username, accessToken, "oauth");
        }

        public boolean isOAuth() { return "oauth".equalsIgnoreCase(authType); }
    }

    public record WorklogEntry(String id, String author, String timeSpent, String started, String comment) {}

    public record TransitionOption(String id, String name) {}

    public record JiraIssue(String key, String summary, String description, String status,
                            String issueType, String projectKey) {}

    // ─── Search ───────────────────────────────────────────────────────────────

    public List<JiraIssueRef> searchIssuesByLabel(String label) {
        return search.searchIssuesByLabel(label);
    }

    public List<JiraIssueDetail> searchIssues(String jql, int maxResults) {
        return search.searchIssues(jql, maxResults);
    }

    public List<JiraIssueDetail> searchIssues(String jql, int maxResults, JiraCredentials creds) {
        return search.searchIssues(jql, maxResults, creds);
    }

    public JiraIssueDetail fetchIssueDetail(String issueKey) {
        return search.fetchIssueDetail(issueKey);
    }

    public String fetchIssueStatus(String issueKey) {
        return search.fetchIssueStatus(issueKey);
    }

    public List<JiraIssueDetail> searchEpicsByLabel(String label) {
        return search.searchEpicsByLabel(label);
    }

    public List<JiraIssueDetail> searchEpicsByLabel(String label, String issuetype) {
        return search.searchEpicsByLabel(label, issuetype);
    }

    public List<JiraIssueDetail> searchEpicsByLabels(List<String> labels, String issuetype) {
        return search.searchEpicsByLabels(labels, issuetype);
    }

    public List<JiraIssueDetail> searchFeaturesByLabels(List<String> labels, String issuetype) {
        return search.searchFeaturesByLabels(labels, issuetype);
    }

    public List<JiraIssueDetail> previewIssuesByLabels(List<String> labels) {
        return search.previewIssuesByLabels(labels);
    }

    public List<JiraIssueDetail> searchFeaturesForEpic(String epicKey) {
        return search.searchFeaturesForEpic(epicKey);
    }

    public List<JiraIssueDetail> searchFeaturesForEpic(String epicKey, String issuetype) {
        return search.searchFeaturesForEpic(epicKey, issuetype);
    }

    public List<JiraIssueDetail> searchStoriesForFeature(String featureKey) {
        return search.searchStoriesForFeature(featureKey);
    }

    public List<JiraIssueDetail> searchStoriesForFeature(String featureKey, String issuetype) {
        return search.searchStoriesForFeature(featureKey, issuetype);
    }

    // ─── Fetch ────────────────────────────────────────────────────────────────

    public String fetchIssueSummary(String issueKey) {
        return fetcher.fetchIssueSummary(issueKey);
    }

    public List<Integer> extractAikidoCandidateIds(String issueKey) {
        return fetcher.extractAikidoCandidateIds(issueKey);
    }

    public JiraDescriptionContext extractDescriptionContext(String issueKey) {
        return fetcher.extractDescriptionContext(issueKey);
    }

    public String fetchIssuePrompt(String issueKey) {
        return fetcher.fetchIssuePrompt(issueKey);
    }

    public List<JiraRemoteLink> fetchRemoteLinks(String issueKey) {
        return fetcher.fetchRemoteLinks(issueKey);
    }

    public byte[] downloadAttachment(String contentUrl) {
        return fetcher.downloadAttachment(contentUrl);
    }

    public String[] getIssueSlaMeta(String issueKey) {
        return fetcher.getIssueSlaMeta(issueKey);
    }

    public JiraIssue getIssue(String issueKey, JiraCredentials creds) {
        return fetcher.getIssue(issueKey, creds);
    }

    public List<String> getComments(String issueKey, JiraCredentials creds) {
        return fetcher.getComments(issueKey, creds);
    }

    // ─── Write ────────────────────────────────────────────────────────────────

    public String createIssueSystem(String projectKey, String summary,
                                    String description, String issueType, String parentKey) {
        return writer.createIssueSystem(projectKey, summary, description, issueType, parentKey);
    }

    public String createIssueSystem(String projectKey, String summary,
                                    String description, String issueType, String parentKey,
                                    List<String> labels, LocalDate dueDate) {
        return writer.createIssueSystem(projectKey, summary, description, issueType, parentKey, labels, dueDate);
    }

    public void updateIssueSystem(String issueKey, String summary, String description) {
        writer.updateIssueSystem(issueKey, summary, description);
    }

    public String createIssue(String projectKey, String summary, String description,
                              String issueType, String parentKey,
                              String billingCategory, String billingCode,
                              String billingCategoryFieldId, String billingCodeFieldId,
                              Map<String, Object> customFields,
                              JiraCredentials creds) {
        return writer.createIssue(projectKey, summary, description, issueType, parentKey,
                billingCategory, billingCode, billingCategoryFieldId, billingCodeFieldId,
                customFields, creds);
    }

    public void updateIssue(String issueKey, String summary, String description,
                            String assignee, String projectKey, JiraCredentials creds) {
        writer.updateIssue(issueKey, summary, description, assignee, projectKey, creds);
    }

    // ─── Transitions & comments ───────────────────────────────────────────────

    public void addComment(String issueKey, String commentText) {
        transitions.addComment(issueKey, commentText);
    }

    public void addComment(String issueKey, String commentText, JiraCredentials creds) {
        transitions.addComment(issueKey, commentText, creds);
    }

    public void transitionToInProgress(String issueKey) {
        transitions.transitionToInProgress(issueKey);
    }

    public void transitionToInReview(String issueKey) {
        transitions.transitionToInReview(issueKey);
    }

    public void transitionToDone(String issueKey) {
        transitions.transitionToDone(issueKey);
    }

    public void transitionToRejected(String issueKey) {
        transitions.transitionToRejected(issueKey);
    }

    public void commentStarted(String issueKey, String branchName) {
        transitions.commentStarted(issueKey, branchName);
    }

    public void commentSuccess(String issueKey, String prUrl, String summary) {
        transitions.commentSuccess(issueKey, prUrl, summary);
    }

    public void commentFailure(String issueKey, String errorMessage) {
        transitions.commentFailure(issueKey, errorMessage);
    }

    public void commentMerged(String issueKey) {
        transitions.commentMerged(issueKey);
    }

    public void commentRejected(String issueKey, String reason) {
        transitions.commentRejected(issueKey, reason);
    }

    public List<TransitionOption> listTransitions(String issueKey, JiraCredentials creds) {
        return transitions.listTransitions(issueKey, creds);
    }

    public boolean transitionIssue(String issueKey, String transitionName, JiraCredentials creds) {
        return transitions.transitionIssue(issueKey, transitionName, creds);
    }

    // ─── Worklogs ─────────────────────────────────────────────────────────────

    public void addWorklog(String issueKey, String timeSpent) {
        worklogs.addWorklog(issueKey, timeSpent);
    }

    public void addWorklog(String issueKey, String timeSpent, String comment, String started,
                           JiraCredentials creds) {
        worklogs.addWorklog(issueKey, timeSpent, comment, started, creds);
    }

    public List<WorklogEntry> getWorklogs(String issueKey, JiraCredentials creds) {
        return worklogs.getWorklogs(issueKey, creds);
    }

    // ─── Meta (projects / components) ────────────────────────────────────────

    public String listProjectsRaw() {
        return httpClient.get("/rest/api/3/project", "list projects");
    }

    public String listComponentsRaw(String projectKey) {
        return httpClient.get("/rest/api/3/project/" + JiraHttpClient.escapeJson(projectKey) + "/components",
                "list components for " + projectKey);
    }

    // ─── Configuration ────────────────────────────────────────────────────────

    public boolean isConfigured() { return httpClient.isConfigured(); }

    public String getBaseUrl()   { return httpClient.getBaseUrl(); }
    public String getUser()      { return httpClient.getUser(); }
    public String getApiToken()  { return httpClient.getApiToken(); }

    public static boolean testConnection(String testBaseUrl, String testUser, String testApiToken) {
        return JiraHttpClient.testConnection(testBaseUrl, testUser, testApiToken);
    }

    public static boolean testConnectionOAuth(String testBaseUrl, String accessToken) {
        return JiraHttpClient.testConnectionOAuth(testBaseUrl, accessToken);
    }
}
