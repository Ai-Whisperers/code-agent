package com.eneve.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Per-environment log analysis configuration.
 * When present and {@code enabled=true} on an {@link EnvironmentConfig}, the
 * scheduled log analyser will query the specified CloudWatch log groups for that
 * environment on every run.
 *
 * <p>The {@code logGroupNames} list supersedes the legacy {@code logGroupName} scalar.
 * Both fields are kept so that existing persisted configs deserialise without errors.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Log analysis configuration for a customer environment")
public record LogAnalysisConfig(

        @Schema(description = "Whether log analysis is enabled for this environment")
        boolean enabled,

        @Schema(description = "CloudWatch log group names to scan (one or more)")
        List<String> logGroupNames,

        @Schema(description = "Legacy single log group name — migrated to logGroupNames on first save",
                deprecated = true)
        String logGroupName,

        @Schema(description = "How many minutes back to look on each scheduler run (default 30)",
                example = "30")
        Integer lookbackMinutes,

        @Schema(description = "Maximum number of unique fingerprints to send to AI triage per run (default 5)",
                example = "5")
        Integer maxFingerprintsPerRun
) {
    /** Returns the effective list of log group names, falling back to the legacy scalar field. */
    public List<String> effectiveLogGroupNames() {
        if (logGroupNames != null && !logGroupNames.isEmpty()) {
            return logGroupNames;
        }
        if (logGroupName != null && !logGroupName.isBlank()) {
            return List.of(logGroupName);
        }
        return List.of();
    }

    public int effectiveLookbackMinutes() {
        return lookbackMinutes != null && lookbackMinutes > 0 ? lookbackMinutes : 30;
    }

    public int effectiveMaxFingerprintsPerRun() {
        return maxFingerprintsPerRun != null && maxFingerprintsPerRun > 0 ? maxFingerprintsPerRun : 5;
    }
}
