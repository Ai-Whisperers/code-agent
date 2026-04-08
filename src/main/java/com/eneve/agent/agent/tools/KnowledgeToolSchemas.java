package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public final class KnowledgeToolSchemas {

    private KnowledgeToolSchemas() { }

    public static Tool searchKnowledgeBase() {
        return Tool.builder()
                .name("search_knowledge_base")
                .description("Search previously indexed Jira tickets, Confluence documentation pages, "
                        + "Jira file attachments, web documentation, and admin-uploaded static files "
                        + "(.txt, .md, .pdf) by meaning. "
                        + "Use this BEFORE answering questions about past issues, known bugs, "
                        + "architecture decisions, runbooks, team knowledge, external library docs, "
                        + "or any internal documents uploaded by the team. "
                        + "Returns ranked excerpts with source references.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("query", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Natural-language description of what you want to find"
                                )))
                                .putAdditionalProperty("sourceTypes", JsonValue.from(Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string",
                                                "enum", List.of("jira", "confluence", "jira-attachment",
                                                        "web-docs", "static-file")),
                                        "description", "Restrict search to these source types (omit for all sources)"
                                )))
                                .putAdditionalProperty("topK", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Maximum results to return (default: 10, max: 25)"
                                )))
                                .build())
                        .addRequired("query")
                        .build())
                .build();
    }

    public static Tool lookupCustomerContext() {
        return Tool.builder()
                .name("lookup_customer_context")
                .description("Resolve a customer name (or ID) to its full context: deployment environments "
                        + "with AWS account IDs and IAM roles, associated products (git repos, Jira projects, "
                        + "Confluence spaces), and team members by role. "
                        + "ALWAYS call this first when the user mentions a customer name — it stores the "
                        + "`customerId` in the workspace so AWS tools (aws_ecs, aws_cloudwatch_metrics, "
                        + "aws_cloudwatch_logs, aws_rds) can use the correct account without you having to "
                        + "repeat it. "
                        + "Call with NO parameters to list all registered customers with their environments. "
                        + "Lookup priority: customerName (partial, case-insensitive) → customerId (exact) "
                        + "→ jiraProject key → productId.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer name or partial name as the user typed it "
                                                + "(case-insensitive, partial match). Use this when the user "
                                                + "refers to a customer by name, e.g. \"Acme Corp\" or \"acme\"."
                                )))
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Exact customer ID slug, e.g. \"acme-corp\". "
                                                + "Use when the ID is already known from a previous lookup."
                                )))
                                .putAdditionalProperty("jiraProject", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira project key — resolves the owning customer via the linked product"
                                )))
                                .putAdditionalProperty("productId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Product ID — resolves the owning customer and returns full context"
                                )))
                                .build())
                        .build())
                .build();
    }

    public static Tool setProductContext() {
        return Tool.builder()
                .name("set_product_context")
                .description("Set the active product context for the conversation. "
                        + "This configures workspace metadata for git repositories, Jira projects, "
                        + "Confluence spaces, and other product-specific settings. "
                        + "Use after lookup_customer_context to switch between different products. "
                        + "Other tools like search_code, semantic_search, and query_code_graph will "
                        + "automatically use the active product context.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("productId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Product ID from the customer registry"
                                )))
                                .build())
                        .addRequired("productId")
                        .build())
                .build();
    }
}
