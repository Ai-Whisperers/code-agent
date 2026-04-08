package com.eneve.agent.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mirrors the {@code qa_test_plan_history} table row.
 * One row is appended on every significant event (generate_analysis, generate_json, manual_edit)
 * to enable KPI trend charts over time.
 */
public record QaTestPlanHistory(
        String id,
        String planId,
        String issueKey,
        Instant snapshotAt,
        Integer kpiBehaviourTcCount,
        Integer kpiCapabilityTcCount,
        Integer kpiRiskCount,
        Integer kpiOpenClarifications,
        BigDecimal kpiCoveragePct,
        Integer kpiHighRisks,
        Integer kpiGapsCount,
        String kpiReadiness,
        String kpiSpecHash,
        /** One of: generate_analysis, generate_json, manual_edit */
        String trigger
) {}
