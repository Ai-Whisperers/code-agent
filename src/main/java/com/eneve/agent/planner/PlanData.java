package com.eneve.agent.planner;

import java.util.List;

/**
 * The structured content of an execution plan: an ordered list of phases.
 * Phases run sequentially. Steps within a phase run in parallel.
 * When a phase contains a single step with sequential dependencies on the
 * previous phase's output, the step should carry a {@code sourceBranch} param
 * so its changes build on the prior phase's branch rather than the base branch.
 * Stored as JSONB in the {@code execution_plans} table.
 */
public record PlanData(List<PlanPhase> phases) {}
