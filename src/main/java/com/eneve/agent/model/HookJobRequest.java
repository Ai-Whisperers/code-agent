package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request produced by the hook evaluator when an automation hook fires.
 * Carries everything the agent runner needs to execute the hook's action.
 */
@Schema(description = "Automation hook job request")
public record HookJobRequest(
        String repoUrl,
        String workspace,
        String repoSlug,
        String branchName,
        String targetBranch,
        String prompt,
        List<String> ruleNames,
        String extraRules,
        boolean commitDirect,
        String hookName
) implements JobPayload {}
