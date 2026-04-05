package com.eneve.agent.model;

/**
 * Payload for a {@link JobType#QA_TESTPLAN_ANALYSIS} job.
 * Triggers the first Claude call (qa.testplan.analysis prompt) for a Jira feature.
 */
public record QaTestPlanAnalysisRequest(String scopeId, String issueKey) implements JobPayload {}
