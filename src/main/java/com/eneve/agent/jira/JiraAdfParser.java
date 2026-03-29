package com.eneve.agent.jira;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stateless utility for converting Jira ADF (Atlassian Document Format) nodes to plain text.
 * Package-private — only used by collaborators within this package.
 */
@ApplicationScoped
class JiraAdfParser {

    /**
     * Recursively extract plain text from an ADF node.
     */
    String extractAdfText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.has("text")) return node.path("text").asText("");

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

    /**
     * Extract both text AND href URLs from ADF nodes (needed to capture Aikido inline-card links).
     */
    String extractAdfTextAndLinks(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";

        var sb = new StringBuilder();
        if (node.has("text")) sb.append(node.path("text").asText(""));

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
}
