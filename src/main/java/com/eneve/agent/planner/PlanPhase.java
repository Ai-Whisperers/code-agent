package com.eneve.agent.planner;

import java.util.List;

/**
 * An ordered phase within an execution plan.
 * Steps within a phase can run in parallel; phases run sequentially.
 * When {@code gateOnSuccess} is true, all steps must complete successfully
 * before the next phase begins.
 */
public record PlanPhase(
        int order,
        String name,
        boolean gateOnSuccess,
        List<PlanStep> steps
) {}
