package com.eneve.agent.planner;

import java.util.List;

/**
 * An ordered phase within an execution plan.
 * Phases run sequentially. Steps within a phase run in parallel.
 * When {@code gateOnSuccess} is true, all steps must complete successfully
 * before the next phase begins.
 *
 * <p>For inherently sequential work (where each step depends on the output of
 * the prior step), each dependent step should be placed in its own phase with
 * {@code gateOnSuccess = true}. FIX steps can be chained by setting the
 * {@code sourceBranch} param to the previous phase's {@code branchName}, so
 * that changes accumulate on a single branch rather than each step branching
 * independently from the base. The orchestrator also auto-injects
 * {@code sourceBranch} as a safety-net when the planner omits it.
 */
public record PlanPhase(
        int order,
        String name,
        boolean gateOnSuccess,
        List<PlanStep> steps
) {}
