package com.eneve.agent.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mirrors the {@code qa_test_plans} table row.
 * A test plan belongs to a Jira feature ({@code issueKey}) and is shared across
 * all scopes that contain that feature via the {@code qa_scope_test_plans} join table.
 *
 * <p>Holds the two-stage AI-generated test plan:
 * <ol>
 *   <li>{@code analysisText} — raw markdown from the first Claude call (editable)</li>
 *   <li>{@code planJson}     — structured featureTestPlan JSON from the second Claude call</li>
 * </ol>
 * KPI fields are extracted from {@code planJson} on save and stored as flat columns
 * for fast querying without parsing JSONB at read time.
 */
public record QaTestPlan(
        String id,
        String issueKey,

        /** Raw markdown analysis from the first Claude call. Null until generated. */
        String analysisText,

        /** Serialised featureTestPlan JSON. Null until the formatter step is run. */
        String planJson,

        /** Snapshot of feature + child story context used to detect requirements drift. Nullable. */
        String specifications,

        Instant generatedAt,
        String generatedBy,

        /** True when the user has manually edited {@code analysisText} after AI generation. */
        boolean analysisEdited,

        // ── KPIs extracted from plan_json ─────────────────────────────────────
        Integer kpiStoryCount,
        Integer kpiBehaviourTcCount,
        Integer kpiCapabilityTcCount,
        Integer kpiRiskCount,
        Integer kpiOpenClarifications,
        BigDecimal kpiCoveragePct,
        Integer kpiHighRisks,
        Integer kpiGapsCount,
        String kpiReadiness,

        // ── Requirements drift ────────────────────────────────────────────────
        /** SHA-256 of the {@code specifications} JSON at last generation time. */
        String kpiSpecHash,
        /** Timestamp when drift was first detected (spec changed after last generation). */
        Instant kpiDriftDetectedAt,
        /** How many times the plan has been regenerated for this feature. */
        int kpiRegenCount,
        /** Cumulative count of manual edits to {@code analysisText}. */
        int kpiAnalysisEditCount,

        // ── Jira / Xray sync ──────────────────────────────────────────────────
        /** Optional Jira issue key linking this test plan to a Jira Test Plan issue for Xray sync. */
        String jiraIssueKey,
        /** Xray sync status: pending | synced | error. */
        String xraySyncStatus,
        /** Timestamp of last successful Xray sync. */
        Instant xraySyncedAt
) {}
