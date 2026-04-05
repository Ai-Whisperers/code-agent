package com.eneve.agent.model;

import java.time.Instant;

/**
 * Mirrors the {@code qa_test_cases} table row.
 * Each row represents one AI-generated test case for a child story of a feature.
 * Linked to its parent {@code qa_test_plans} row via {@code planId}.
 */
public record QaTestCase(
        String id,
        String planId,

        /** Denormalized from qa_test_plans.issue_key for fast queries. */
        String featureKey,

        /** The child story this test case covers. */
        String storyKey,

        /** Structured ID, e.g. BTC-209-01 or CTC-209-01. */
        String testCaseId,

        String title,
        String description,

        /** JSON array of pre-condition strings. */
        String preConditions,

        /** JSON array of test step strings. */
        String testSteps,

        /** JSON array of expected result strings. */
        String expectedResults,

        /** "Behaviour" or "Capability". */
        String testCaseType,

        /** "High", "Medium", or "Low". */
        String priority,

        /** "Open", "Pass", "Fail", or "Blocked". */
        String status,

        /** Raw string, e.g. "5 mins". */
        String estimatedDuration,

        // ── KPIs: complexity ──────────────────────────────────────────────────
        Integer kpiStepCount,
        Integer kpiEstimatedMins,
        Integer kpiPreconditionCount,

        // ── KPIs: execution tracking ──────────────────────────────────────────
        int kpiExecutionCount,
        String kpiLastResult,
        Instant kpiLastExecutedAt,

        // ── KPIs: automation readiness ────────────────────────────────────────
        /** "manual", "automated", or "in_progress". */
        String kpiAutomationStatus,

        // ── Jira / Xray sync ──────────────────────────────────────────────────
        String jiraIssueKey,
        String xraySyncStatus,
        Instant xraySyncedAt,

        Instant generatedAt
) {}
