package com.eneve.agent.planner;

import java.time.Instant;

/**
 * An AI-generated execution plan that decomposes a specification into
 * ordered phases and steps. Starts as DRAFT for human review, transitions
 * to APPROVED when the human approves it for execution.
 */
public record ExecutionPlan(
        String planId,
        String status,
        String sourceType,
        String sourceRef,
        String repoUrl,
        String targetBranch,
        String title,
        PlanData planData,
        Instant createdAt,
        Instant updatedAt,
        Instant approvedAt,
        String summary,
        String errorMessage
) {}
