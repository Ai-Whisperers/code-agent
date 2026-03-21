package com.eneve.agent.planner;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the static helper methods of {@link PlanOrchestratorService} using reflection,
 * since they are private.  These helpers drive the resume-from-failed logic.
 */
class PlanOrchestratorServiceHelpersTest {

    // ── Helpers via reflection ──────────────────────────────────────────────

    private static int findFirstIncompletePhaseOrder(PlanData data) throws Exception {
        Method m = PlanOrchestratorService.class
                .getDeclaredMethod("findFirstIncompletePhaseOrder", PlanData.class);
        m.setAccessible(true);
        return (int) m.invoke(null, data);
    }

    private static PlanData resetStepsFromPhase(PlanData data, int fromOrder) throws Exception {
        Method m = PlanOrchestratorService.class
                .getDeclaredMethod("resetStepsFromPhase", PlanData.class, int.class);
        m.setAccessible(true);
        return (PlanData) m.invoke(null, data, fromOrder);
    }

    private static PlanData resetAllSteps(PlanData data) throws Exception {
        Method m = PlanOrchestratorService.class
                .getDeclaredMethod("resetAllSteps", PlanData.class);
        m.setAccessible(true);
        return (PlanData) m.invoke(null, data);
    }

    // ── Step / Phase factory helpers ──────────────────────────────────────

    private static PlanStep step(String id, String status) {
        return new PlanStep(id, "FIX", "title", "prompt", status, null, Map.of(), null);
    }

    private static PlanPhase phase(int order, String... statuses) {
        List<PlanStep> steps = java.util.Arrays.stream(statuses)
                .map(s -> step("step-" + s + "-" + order, s))
                .toList();
        return new PlanPhase(order, "Phase " + order, true, steps);
    }

    // ── findFirstIncompletePhaseOrder ────────────────────────────────────

    @Test
    void findFirstIncompletePhaseOrder_allSuccess_returnsMinusOne() throws Exception {
        PlanData data = new PlanData(List.of(phase(1, "SUCCESS"), phase(2, "SUCCESS")));
        assertEquals(-1, findFirstIncompletePhaseOrder(data));
    }

    @Test
    void findFirstIncompletePhaseOrder_nullData_returnsMinusOne() throws Exception {
        assertEquals(-1, findFirstIncompletePhaseOrder(null));
    }

    @Test
    void findFirstIncompletePhaseOrder_firstPhaseFailed_returnsOrder1() throws Exception {
        PlanData data = new PlanData(List.of(phase(1, "FAILED"), phase(2, "SKIPPED")));
        assertEquals(1, findFirstIncompletePhaseOrder(data));
    }

    @Test
    void findFirstIncompletePhaseOrder_secondPhaseFailed_returnsOrder2() throws Exception {
        PlanData data = new PlanData(List.of(phase(1, "SUCCESS"), phase(2, "FAILED"), phase(3, "SKIPPED")));
        assertEquals(2, findFirstIncompletePhaseOrder(data));
    }

    @Test
    void findFirstIncompletePhaseOrder_skippedPhase_detectedAsFailed() throws Exception {
        PlanData data = new PlanData(List.of(phase(1, "SUCCESS"), phase(2, "SKIPPED")));
        assertEquals(2, findFirstIncompletePhaseOrder(data));
    }

    // ── resetStepsFromPhase ──────────────────────────────────────────────

    @Test
    void resetStepsFromPhase_resetsOnlyTargetPhaseAndAfter() throws Exception {
        PlanData data = new PlanData(List.of(
                phase(1, "SUCCESS"),
                phase(2, "FAILED"),
                phase(3, "SKIPPED")));

        PlanData result = resetStepsFromPhase(data, 2);

        // Phase 1 untouched
        assertEquals("SUCCESS", result.phases().get(0).steps().get(0).status());
        // Phases 2 and 3 reset to PENDING
        assertEquals("PENDING", result.phases().get(1).steps().get(0).status());
        assertEquals("PENDING", result.phases().get(2).steps().get(0).status());
    }

    @Test
    void resetStepsFromPhase_fromOrder1_resetsAllPhases() throws Exception {
        PlanData data = new PlanData(List.of(phase(1, "SUCCESS"), phase(2, "SUCCESS")));
        PlanData result = resetStepsFromPhase(data, 1);

        result.phases().forEach(p ->
                p.steps().forEach(s -> assertEquals("PENDING", s.status())));
    }

    // ── resetAllSteps ────────────────────────────────────────────────────

    @Test
    void resetAllSteps_setsAllStepsToPending() throws Exception {
        PlanData data = new PlanData(List.of(phase(1, "SUCCESS"), phase(2, "FAILED")));
        PlanData result = resetAllSteps(data);

        result.phases().forEach(p ->
                p.steps().forEach(s -> assertEquals("PENDING", s.status())));
    }

    @Test
    void resetAllSteps_preservesPhaseMetadata() throws Exception {
        PlanData data = new PlanData(List.of(
                new PlanPhase(5, "My Phase", true, List.of(step("s1", "SUCCESS")))));
        PlanData result = resetAllSteps(data);

        PlanPhase p = result.phases().get(0);
        assertEquals(5, p.order());
        assertEquals("My Phase", p.name());
        assertTrue(p.gateOnSuccess());
    }
}
