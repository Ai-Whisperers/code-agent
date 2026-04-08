package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to submit a REWRITE job that ports code from a source repository into a target repository.
 *
 * <p>Supports three modes driven by {@code rewriteMode}:
 * <ul>
 *   <li>{@code full_rewrite} — complete cross-language rewrite (e.g. PHP/Laravel → C# .NET)</li>
 *   <li>{@code framework_migration} — same language, different framework (e.g. Angular → React)</li>
 *   <li>{@code extraction} — extract a bounded context from a monolith into a standalone service</li>
 * </ul>
 */
@Schema(description = "Request to submit a new code rewrite job")
public record RewriteRequest(

        @Schema(required = true, description = "Source repository URL (read-only) — the codebase to rewrite from",
                example = "https://bitbucket.org/workspace/my-php-app.git")
        String sourceRepoUrl,

        @Schema(required = true, description = "Target repository URL (read-write) — the codebase to write into",
                example = "https://bitbucket.org/workspace/my-dotnet-app.git")
        String targetRepoUrl,

        @Schema(required = true, description = "Branch name to create or push to in the target repository",
                example = "agent/rewrite/php-to-dotnet")
        String branchName,

        @Schema(description = "Target branch for the PR in the target repository (default: main)",
                example = "main")
        String targetBranch,

        @Schema(description = "Source language and framework (used to guide the planner)",
                example = "php/laravel")
        String sourceLanguage,

        @Schema(description = "Target language and framework (used to guide the planner)",
                example = "dotnet/csharp")
        String targetLanguage,

        @Schema(description = "Rewrite mode: full_rewrite, framework_migration, or extraction. Defaults to full_rewrite.",
                example = "full_rewrite")
        String rewriteMode,

        @Schema(description = "For extraction mode: free-text description of the bounded context or module to extract",
                example = "order management module — OrderService, OrderRepository, OrderController, and related domain models")
        String scopeHint,

        @Schema(description = "Task prompt describing the rewrite goal. Used as the step-level instruction for the agent.",
                example = "Rewrite the domain models from PHP Eloquent to C# EF Core entities")
        String prompt,

        @Schema(description = "Plan ID — when set, the job runs as part of a multi-step execution plan")
        String planId,

        @Schema(description = "When true, skip PR creation after pushing — used for intermediate plan steps that share a single PR")
        Boolean skipPrCreation

) implements JobPayload {

    public String targetBranchOrDefault() {
        return targetBranch != null && !targetBranch.isBlank() ? targetBranch : "main";
    }

    public boolean shouldSkipPrCreation() {
        return skipPrCreation != null && skipPrCreation;
    }

    public String rewriteModeOrDefault() {
        return rewriteMode != null && !rewriteMode.isBlank() ? rewriteMode : "full_rewrite";
    }

    public String scopeHintOrEmpty() {
        return scopeHint != null ? scopeHint : "";
    }
}
