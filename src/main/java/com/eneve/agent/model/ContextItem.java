package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Base interface for context items that can be attached to conversations
 */
public sealed interface ContextItem 
    permits ContextItem.CustomerContextItem, ContextItem.ProductContextItem, 
            ContextItem.AikidoIssueContextItem, ContextItem.JiraIssueContextItem, 
            ContextItem.ConfluenceDocContextItem {

    @Schema(description = "Customer context item")
    record CustomerContextItem(
        @Schema(required = true, description = "Customer ID") 
        String customerId,
        @Schema(required = true, description = "Customer name")
        String name,
        @Schema(description = "Customer metadata summary")
        String metadataSummary
    ) implements ContextItem {}

    @Schema(description = "Product context item")
    record ProductContextItem(
        @Schema(required = true, description = "Product ID")
        String productId,
        @Schema(required = true, description = "Product display name") 
        String displayName,
        @Schema(description = "Customer ID this product is linked to")
        String customerId,
        @Schema(description = "Customer name this product is linked to")
        String customerName
    ) implements ContextItem {}

    @Schema(description = "Aikido security issue context item")
    record AikidoIssueContextItem(
        @Schema(required = true, description = "Aikido issue group ID")
        Integer issueGroupId,
        @Schema(required = true, description = "Issue type")
        String issueType,
        @Schema(required = true, description = "Severity level")
        String severity,
        @Schema(description = "Affected package name")
        String packageName,
        @Schema(description = "CVE identifier")
        String cveId,
        @Schema(description = "Repository name")
        String repoName
    ) implements ContextItem {}

    @Schema(description = "Jira issue context item")
    record JiraIssueContextItem(
        @Schema(required = true, description = "Jira issue key")
        String issueKey,
        @Schema(required = true, description = "Issue summary")
        String summary,
        @Schema(description = "Issue status")
        String status,
        @Schema(description = "Issue type")
        String issueType,
        @Schema(description = "Issue assignee")
        String assignee
    ) implements ContextItem {}

    @Schema(description = "Confluence document context item")
    record ConfluenceDocContextItem(
        @Schema(required = true, description = "Confluence page ID")
        String pageId,
        @Schema(required = true, description = "Page title")
        String title,
        @Schema(description = "Space key")
        String spaceKey,
        @Schema(description = "Space name")
        String spaceName,
        @Schema(description = "Content preview")
        String contentPreview
    ) implements ContextItem {}
}
