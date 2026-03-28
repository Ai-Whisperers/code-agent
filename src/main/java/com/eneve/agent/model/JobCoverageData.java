package com.eneve.agent.model;

import com.eneve.agent.agent.model.QualityReport;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Captures the before/after coverage snapshots taken during a GENERATE_TESTS job.
 * Stored as JSONB in the {@code coverage_data} column of {@code jobs} / {@code job_history}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobCoverageData(
        QualityReport.CoverageSection before,
        QualityReport.CoverageSection after
) {}
