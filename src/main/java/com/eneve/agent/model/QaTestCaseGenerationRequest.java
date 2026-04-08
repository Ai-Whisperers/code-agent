package com.eneve.agent.model;

/**
 * Payload for a {@link JobType#QA_TESTCASE_GENERATION} job.
 * Triggers test case generation for all child stories of a feature's test plan.
 */
public record QaTestCaseGenerationRequest(String planId, String issueKey) implements JobPayload {}
