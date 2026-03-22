package com.eneve.agent.agent.model;

import java.time.Instant;
import java.util.List;

/**
 * A configurable automation hook stored in the {@code automation_hooks} table.
 * Defines what action to run, when to trigger it, and which branches it applies to.
 */
public record AutomationHook(
        Long id,
        String name,
        String description,
        boolean enabled,
        String triggerType,
        String prEvent,
        String branchPattern,
        String cronExpr,
        String actionType,
        String prompt,
        List<String> ruleNames,
        String extraRules,
        String targetBranch,
        boolean commitDirect,
        Instant createdAt,
        Instant updatedAt
) {}
