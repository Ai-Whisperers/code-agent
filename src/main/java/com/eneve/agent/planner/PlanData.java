package com.eneve.agent.planner;

import java.util.List;

/**
 * The structured content of an execution plan: an ordered list of phases,
 * each containing parallel-executable steps.
 * Stored as JSONB in the {@code execution_plans} table.
 */
public record PlanData(List<PlanPhase> phases) {}
