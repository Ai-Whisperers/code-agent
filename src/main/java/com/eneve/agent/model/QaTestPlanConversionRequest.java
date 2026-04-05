package com.eneve.agent.model;

/**
 * Payload for a {@link JobType#QA_TESTPLAN_CONVERSION} job.
 * Triggers the second Claude call (qa.testplan.formatter prompt) for a Jira feature,
 * converting the stored analysis text into structured featureTestPlan JSON.
 */
public record QaTestPlanConversionRequest(String issueKey) implements JobPayload {}
