package com.eneve.agent.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Create and update operations against the Jira REST API.
 * Package-private — all access goes through {@link JiraService}.
 */
@ApplicationScoped
class JiraIssueWriter {

    private static final Logger LOG = Logger.getLogger(JiraIssueWriter.class);

    @Inject JiraHttpClient http;
    @Inject AdfBuilder adfBuilder;
    @Inject ObjectMapper mapper;

    String createIssueSystem(String projectKey, String summary,
                             String description, String issueType, String parentKey) {
        return createIssueSystem(projectKey, summary, description, issueType, parentKey,
                Collections.emptyList(), null, null);
    }

    String createIssueSystem(String projectKey, String summary,
                             String description, String issueType, String parentKey,
                             List<String> labels, LocalDate dueDate) {
        return createIssueSystem(projectKey, summary, description, issueType, parentKey,
                labels, dueDate, null);
    }

    String createIssueSystem(String projectKey, String summary,
                             String description, String issueType, String parentKey,
                             List<String> labels, LocalDate dueDate, String priority) {
        ObjectNode fields = buildFields(projectKey, summary, description, issueType, parentKey, labels, dueDate);

        if (priority != null && !priority.isBlank()) {
            fields.putObject("priority").put("name", priority);
        }

        String result = postIssue(fields, projectKey);
        if (result != null) return result;

        // Retry without priority if Jira rejected the priority field format
        if (priority != null && !priority.isBlank()) {
            LOG.infof("createIssueSystem: retrying without priority field for project %s", projectKey);
            fields.remove("priority");
            result = postIssue(fields, projectKey);
        }
        return result;
    }

    private ObjectNode buildFields(String projectKey, String summary, String description,
                                   String issueType, String parentKey,
                                   List<String> labels, LocalDate dueDate) {
        ObjectNode fields = mapper.createObjectNode();
        fields.put("summary", summary);
        fields.putObject("project").put("key", projectKey);
        fields.putObject("issuetype").put("name", issueType);
        fields.set("description", adfBuilder.markdownToAdf(description));

        if (parentKey != null && !parentKey.isBlank()) {
            fields.putObject("parent").put("key", parentKey);
        }
        if (labels != null && !labels.isEmpty()) {
            var labelsNode = fields.putArray("labels");
            labels.forEach(labelsNode::add);
        }
        if (dueDate != null) {
            fields.put("duedate", dueDate.toString());
        }
        return fields;
    }

    private String postIssue(ObjectNode fields, String projectKey) {
        ObjectNode body = mapper.createObjectNode();
        body.set("fields", fields);

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            LOG.warnf("createIssueSystem: failed to serialize body: %s", e.getMessage());
            return null;
        }

        HttpResponse<String> response = http.postForResponse("/rest/api/3/issue", jsonBody, "create issue " + projectKey);
        if (response == null) return null;

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            LOG.infof("JIRA create issue %s succeeded (HTTP %d)", projectKey, response.statusCode());
            try {
                return mapper.readTree(response.body()).path("key").asText(null);
            } catch (Exception e) {
                LOG.warnf("createIssueSystem: failed to parse response: %s", e.getMessage());
                return null;
            }
        }

        LOG.warnf("JIRA create issue %s failed (HTTP %d): %s", projectKey, response.statusCode(), response.body());

        // Return null to signal failure; caller decides whether to retry
        return null;
    }

    void updateIssueSystem(String issueKey, String summary, String description) {
        updateIssueSystem(issueKey, summary, description, null, null);
    }

    void updateIssueSystem(String issueKey, String summary, String description,
                            List<String> labels, String priority) {
        var fields = mapper.createObjectNode();
        if (summary != null) fields.put("summary", summary);
        if (description != null) fields.set("description", adfBuilder.markdownToAdf(description));
        if (labels != null && !labels.isEmpty()) {
            var arr = fields.putArray("labels");
            labels.forEach(arr::add);
        }
        if (priority != null && !priority.isBlank()) {
            fields.putObject("priority").put("name", priority);
        }

        var body = mapper.createObjectNode();
        body.set("fields", fields);

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            LOG.warnf("updateIssueSystem: failed to serialize body for %s: %s", issueKey, e.getMessage());
            return;
        }

        http.putForBody("/rest/api/3/issue/" + JiraHttpClient.escapeJson(issueKey), jsonBody,
                "update issue " + issueKey);
    }

    String createIssue(String projectKey, String summary, String description,
                       String issueType, String parentKey,
                       String billingCategory, String billingCode,
                       String billingCategoryFieldId, String billingCodeFieldId,
                       Map<String, Object> customFields,
                       JiraService.JiraCredentials creds) {
        try {
            var fieldsNode = mapper.createObjectNode();
            fieldsNode.set("project", mapper.createObjectNode().put("key", projectKey));
            fieldsNode.put("summary", summary);
            fieldsNode.set("issuetype", mapper.createObjectNode().put("name", issueType));
            if (description != null && !description.isBlank()) {
                fieldsNode.set("description", adfBuilder.markdownToAdf(description));
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

            String json = http.postForBodyWithCreds("/rest/api/3/issue", body, "create issue", creds);
            if (json == null) return null;
            return mapper.readTree(json).path("key").asText(null);
        } catch (Exception e) {
            LOG.warnf("Failed to create JIRA issue: %s", e.getMessage());
            return null;
        }
    }

    void updateIssue(String issueKey, String summary, String description,
                     String assignee, String projectKey, JiraService.JiraCredentials creds) {
        try {
            var fieldsNode = mapper.createObjectNode();
            if (summary != null && !summary.isBlank()) fieldsNode.put("summary", summary);
            if (description != null) {
                fieldsNode.set("description", adfBuilder.markdownToAdf(description));
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
            http.putWithCreds("/rest/api/3/issue/" + issueKey, mapper.writeValueAsString(root),
                    "update issue", creds);
        } catch (Exception e) {
            LOG.warnf("Failed to build update issue body for %s: %s", issueKey, e.getMessage());
        }
    }
}
