package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public final class AgentJobToolSchemas {

    private AgentJobToolSchemas() { }

    public static Tool agentRunFix() {
        return Tool.builder()
                .name("agent_run_fix")
                .description("Submit a code-fix job to the agent. The agent will clone the repo, "
                        + "implement the fix described in the prompt, push a branch, and create a PR. "
                        + "Returns a job ID that can be polled with agent_get_job_status.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("repoUrl", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Repository HTTPS URL, e.g. 'https://bitbucket.org/workspace/repo.git'"
                                )))
                                .putAdditionalProperty("jiraKey", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key to associate with the job, e.g. 'PROJ-123'"
                                )))
                                .putAdditionalProperty("prompt", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Description of the fix to implement"
                                )))
                                .putAdditionalProperty("branchName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Branch name to push to (optional, auto-generated if omitted)"
                                )))
                                .build())
                        .addRequired("repoUrl")
                        .addRequired("jiraKey")
                        .addRequired("prompt")
                        .build())
                .build();
    }

    public static Tool agentGetJobStatus() {
        return Tool.builder()
                .name("agent_get_job_status")
                .description("Get the current status and result of a previously submitted agent job.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("jobId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Job ID returned by agent_run_fix"
                                )))
                                .build())
                        .addRequired("jobId")
                        .build())
                .build();
    }

    public static Tool agentSelfAnalysis() {
        return Tool.builder()
                .name("agent_self_analysis")
                .description("Submit an autonomous self-analysis job. The agent will clone the repository, "
                        + "inspect the codebase and (optionally) recent CloudWatch logs, diagnose issues, "
                        + "implement a fix, push a branch, and create a PR. "
                        + "Returns a job ID that can be polled with agent_get_job_status.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("repoUrl", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Repository HTTPS URL to clone and analyse, "
                                                + "e.g. 'https://bitbucket.org/workspace/repo.git'"
                                )))
                                .putAdditionalProperty("failedJobId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Job ID of a previously failed job that should be investigated "
                                                + "(optional — omit for a general health analysis)"
                                )))
                                .putAdditionalProperty("targetBranch", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Base branch to check out (optional, defaults to 'develop')"
                                )))
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID used to resolve AWS credentials for CloudWatch log fetching (optional)"
                                )))
                                .putAdditionalProperty("environmentName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Environment name to fetch CloudWatch logs from, e.g. 'production' (optional)"
                                )))
                                .putAdditionalProperty("logGroupName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "CloudWatch log group name, e.g. '/ecs/code-agent' (optional)"
                                )))
                                .putAdditionalProperty("jiraProjectKey", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira project key for commit messages and PR description, e.g. 'PROJ' (optional)"
                                )))
                                .build())
                        .addRequired("repoUrl")
                        .build())
                .build();
    }

    public static Tool agentSubmitReviewJob() {
        return Tool.builder()
                .name("agent_submit_review_job")
                .description("Submit a PR review job to the agent queue. The agent will clone the repo, "
                        + "review the pull request code changes, analyze code quality, and provide feedback. "
                        + "Returns a job ID that can be polled with agent_get_job_status.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("repoUrl", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Repository HTTPS URL, e.g. 'https://bitbucket.org/workspace/repo.git'"
                                )))
                                .putAdditionalProperty("prId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Pull request ID or number"
                                )))
                                .putAdditionalProperty("targetBranch", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Target branch name (optional, defaults to main/master)"
                                )))
                                .putAdditionalProperty("jiraKey", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira issue key to associate with the review, e.g. 'PROJ-123' (optional)"
                                )))
                                .putAdditionalProperty("extraRules", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Additional review rules or guidelines to apply (optional)"
                                )))
                                .build())
                        .addRequired("repoUrl")
                        .addRequired("prId")
                        .build())
                .build();
    }
}
