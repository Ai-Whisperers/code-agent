package com.eneve.agent.agent;

import com.anthropic.models.messages.ToolUnion;
import com.eneve.agent.agent.tools.AgentJobToolSchemas;
import com.eneve.agent.agent.tools.AwsToolSchemas;
import com.eneve.agent.agent.tools.CommentChatToolSchemas;
import com.eneve.agent.agent.tools.ConfluenceToolSchemas;
import com.eneve.agent.agent.tools.JiraToolSchemas;
import com.eneve.agent.agent.tools.KnowledgeToolSchemas;
import com.eneve.agent.agent.tools.PlanToolSchemas;
import com.eneve.agent.agent.tools.WorkspaceToolSchemas;
import com.eneve.agent.agent.tools.XrayToolSchemas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defines the tool schemas exposed to Claude during the agentic loop.
 * Schema implementations live in the {@code tools} sub-package, grouped by domain.
 */
public final class ToolDefinitions {

    private ToolDefinitions() { }

    /**
     * Read-only tool set for the scope AI-improvement loop.
     * Lets Claude research the linked product's knowledge base, code semantics,
     * code graph, and external documentation before writing the improved issue.
     */
    public static List<ToolUnion> scopeImprove() {
        return List.of(
                ToolUnion.ofTool(KnowledgeToolSchemas.searchKnowledgeBase()),
                ToolUnion.ofTool(WorkspaceToolSchemas.semanticSearch()),
                ToolUnion.ofTool(WorkspaceToolSchemas.queryCodeGraph()),
                ToolUnion.ofTool(WorkspaceToolSchemas.fetchUrl())
        );
    }

    public static List<ToolUnion> all() {
        return List.of(
                ToolUnion.ofTool(WorkspaceToolSchemas.readFile()),
                ToolUnion.ofTool(WorkspaceToolSchemas.writeFile()),
                ToolUnion.ofTool(WorkspaceToolSchemas.runCommand()),
                ToolUnion.ofTool(WorkspaceToolSchemas.listFiles()),
                ToolUnion.ofTool(WorkspaceToolSchemas.fetchUrl()),
                ToolUnion.ofTool(KnowledgeToolSchemas.searchKnowledgeBase()),
                ToolUnion.ofTool(KnowledgeToolSchemas.lookupCustomerContext())
        );
    }

    public static List<ToolUnion> readOnly() {
        return List.of(
                ToolUnion.ofTool(WorkspaceToolSchemas.readFile()),
                ToolUnion.ofTool(WorkspaceToolSchemas.searchCode()),
                ToolUnion.ofTool(WorkspaceToolSchemas.queryCodeGraph()),
                ToolUnion.ofTool(WorkspaceToolSchemas.semanticSearch()),
                ToolUnion.ofTool(WorkspaceToolSchemas.runCommand()),
                ToolUnion.ofTool(WorkspaceToolSchemas.listFiles()),
                ToolUnion.ofTool(WorkspaceToolSchemas.fetchUrl()),
                ToolUnion.ofTool(KnowledgeToolSchemas.searchKnowledgeBase()),
                ToolUnion.ofTool(KnowledgeToolSchemas.lookupCustomerContext()),
                ToolUnion.ofTool(KnowledgeToolSchemas.setProductContext())
        );
    }

    public static List<ToolUnion> docsGeneration() {
        return List.of(
                ToolUnion.ofTool(WorkspaceToolSchemas.readFile()),
                ToolUnion.ofTool(WorkspaceToolSchemas.writeFile()),
                ToolUnion.ofTool(WorkspaceToolSchemas.listFiles()),
                ToolUnion.ofTool(WorkspaceToolSchemas.searchCode()),
                ToolUnion.ofTool(WorkspaceToolSchemas.queryCodeGraph()),
                ToolUnion.ofTool(WorkspaceToolSchemas.semanticSearch()),
                ToolUnion.ofTool(WorkspaceToolSchemas.runCommand()),
                ToolUnion.ofTool(WorkspaceToolSchemas.fetchUrl()),
                ToolUnion.ofTool(KnowledgeToolSchemas.searchKnowledgeBase()),
                ToolUnion.ofTool(KnowledgeToolSchemas.lookupCustomerContext()),
                ToolUnion.ofTool(KnowledgeToolSchemas.setProductContext())
        );
    }

    public static List<ToolUnion> planExecution() {
        return List.of(
                ToolUnion.ofTool(PlanToolSchemas.planRead()),
                ToolUnion.ofTool(PlanToolSchemas.planUpdate()),
                ToolUnion.ofTool(KnowledgeToolSchemas.searchKnowledgeBase()),
                ToolUnion.ofTool(KnowledgeToolSchemas.lookupCustomerContext()),
                ToolUnion.ofTool(KnowledgeToolSchemas.setProductContext()),
                ToolUnion.ofTool(WorkspaceToolSchemas.semanticSearch()),
                ToolUnion.ofTool(WorkspaceToolSchemas.searchCode()),
                ToolUnion.ofTool(WorkspaceToolSchemas.queryCodeGraph()),
                ToolUnion.ofTool(WorkspaceToolSchemas.fetchUrl()),
                // Jira MCP tools
                ToolUnion.ofTool(JiraToolSchemas.jiraSearchIssues()),
                ToolUnion.ofTool(JiraToolSchemas.jiraGetIssue()),
                ToolUnion.ofTool(JiraToolSchemas.jiraGetComments()),
                ToolUnion.ofTool(JiraToolSchemas.jiraCreateIssue()),
                ToolUnion.ofTool(JiraToolSchemas.jiraUpdateIssue()),
                ToolUnion.ofTool(JiraToolSchemas.jiraAddComment()),
                ToolUnion.ofTool(JiraToolSchemas.jiraTransitionIssue()),
                ToolUnion.ofTool(JiraToolSchemas.jiraGetWorklogs()),
                ToolUnion.ofTool(JiraToolSchemas.jiraAddWorklog()),
                // Confluence MCP tools
                ToolUnion.ofTool(ConfluenceToolSchemas.confluenceSearch()),
                ToolUnion.ofTool(ConfluenceToolSchemas.confluenceGetPage()),
                ToolUnion.ofTool(ConfluenceToolSchemas.confluenceCreatePage()),
                ToolUnion.ofTool(ConfluenceToolSchemas.confluenceUpdatePage()),
                // Agent action tools
                ToolUnion.ofTool(AgentJobToolSchemas.agentRunFix()),
                ToolUnion.ofTool(AgentJobToolSchemas.agentGetJobStatus()),
                ToolUnion.ofTool(AgentJobToolSchemas.agentSubmitReviewJob())
        );
    }

    /**
     * Returns the read-only tool set for the Ask mode.
     * Identical to {@code chat(false, false)}: knowledge/search/code/Jira-read/Confluence-read only.
     * No Jira write, Confluence write, agent action, or AWS tools are included.
     */
    public static List<ToolUnion> chatAsk() {
        return chat(false, false);
    }

    public static List<ToolUnion> chat() {
        return chat(true);
    }

    /**
     * Returns the tool set for the chat loop, filtered by the caller's permissions.
     *
     * @param canExecuteJobs {@code true} for DEVELOPER / ADMINISTRATOR roles;
     *                       {@code false} for USER / STAFF (read-only + Jira/Confluence reads only)
     */
    public static List<ToolUnion> chat(boolean canExecuteJobs) {
        return chat(canExecuteJobs, true);
    }

    /**
     * Returns the tool set for the chat loop.
     *
     * @param canExecuteJobs  {@code true} for DEVELOPER / ADMINISTRATOR roles
     * @param includeAwsTools {@code true} to include AWS tools; pass {@code false} when no
     *                        customer context is resolved yet to avoid paying for their large
     *                        tool schemas on every iteration
     */
    public static List<ToolUnion> chat(boolean canExecuteJobs, boolean includeAwsTools) {
        List<ToolUnion> tools = new ArrayList<>(List.of(
                // ── Read-only / analysis tools (all roles) ────────────────
                ToolUnion.ofTool(KnowledgeToolSchemas.searchKnowledgeBase()),
                ToolUnion.ofTool(KnowledgeToolSchemas.lookupCustomerContext()),
                ToolUnion.ofTool(KnowledgeToolSchemas.setProductContext()),
                ToolUnion.ofTool(WorkspaceToolSchemas.semanticSearch()),
                ToolUnion.ofTool(WorkspaceToolSchemas.searchCode()),
                ToolUnion.ofTool(WorkspaceToolSchemas.queryCodeGraph()),
                ToolUnion.ofTool(WorkspaceToolSchemas.fetchUrl()),
                // Jira read tools (all roles)
                ToolUnion.ofTool(JiraToolSchemas.jiraSearchIssues()),
                ToolUnion.ofTool(JiraToolSchemas.jiraGetIssue()),
                ToolUnion.ofTool(JiraToolSchemas.jiraGetComments()),
                ToolUnion.ofTool(JiraToolSchemas.jiraGetWorklogs()),
                // Confluence read tools (all roles)
                ToolUnion.ofTool(ConfluenceToolSchemas.confluenceSearch()),
                ToolUnion.ofTool(ConfluenceToolSchemas.confluenceGetPage())
        ));

        if (includeAwsTools) {
            tools.addAll(List.of(
                    ToolUnion.ofTool(AwsToolSchemas.awsCloudWatchLogs()),
                    ToolUnion.ofTool(AwsToolSchemas.awsEcs()),
                    ToolUnion.ofTool(AwsToolSchemas.awsCloudWatchMetrics()),
                    ToolUnion.ofTool(AwsToolSchemas.awsRds())
            ));
        }

        if (canExecuteJobs) {
            // ── Write / action tools (DEVELOPER / ADMINISTRATOR only) ────
            tools.addAll(List.of(
                    // Jira write tools
                    ToolUnion.ofTool(JiraToolSchemas.jiraCreateIssue()),
                    ToolUnion.ofTool(JiraToolSchemas.jiraUpdateIssue()),
                    ToolUnion.ofTool(JiraToolSchemas.jiraAddComment()),
                    ToolUnion.ofTool(JiraToolSchemas.jiraTransitionIssue()),
                    ToolUnion.ofTool(JiraToolSchemas.jiraAddWorklog()),
                    // Confluence write tools
                    ToolUnion.ofTool(ConfluenceToolSchemas.confluenceCreatePage()),
                    ToolUnion.ofTool(ConfluenceToolSchemas.confluenceUpdatePage()),
                    // Agent action tools
                    ToolUnion.ofTool(AgentJobToolSchemas.agentRunFix()),
                    ToolUnion.ofTool(AgentJobToolSchemas.agentGetJobStatus()),
                    ToolUnion.ofTool(AgentJobToolSchemas.agentSubmitReviewJob())
            ));
        }

        return Collections.unmodifiableList(tools);
    }

    /**
     * Opt-in tool set for QA workflows.
     * Not included in any default toolset — wire this in when a QA role or explicit
     * setting activates it (e.g. a future QA chat role or a QaReadinessScheduler job).
     */
    public static List<ToolUnion> xrayTools() {
        return List.of(
                ToolUnion.ofTool(XrayToolSchemas.xraySearchTests()),
                ToolUnion.ofTool(XrayToolSchemas.xraySearchExecutions()),
                ToolUnion.ofTool(XrayToolSchemas.xrayGetTestExecution()),
                ToolUnion.ofTool(XrayToolSchemas.xrayGetTestCoverage()),
                ToolUnion.ofTool(XrayToolSchemas.xrayCreateTestExecution()),
                ToolUnion.ofTool(XrayToolSchemas.xrayUpdateTestRunStatus())
        );
    }

    /**
     * Minimal tool set for the comment-chat loop.
     * Provides three action tools that the AI can invoke as a conversation conclusion.
     * commentId and jobId are read from WorkspaceContext metadata — no tool inputs required.
     */
    public static List<ToolUnion> commentChat() {
        return List.of(
                ToolUnion.ofTool(CommentChatToolSchemas.resolveCommentTool()),
                ToolUnion.ofTool(CommentChatToolSchemas.markFalsePositiveTool()),
                ToolUnion.ofTool(CommentChatToolSchemas.requestFixTool())
        );
    }
}
