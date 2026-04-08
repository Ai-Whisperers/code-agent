package com.eneve.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /jobs/{jobId}/restart}.
 */
public record RestartJobRequest(
        @JsonProperty("additionalIterations")
        int additionalIterations
) {
    public RestartJobRequest {
        if (additionalIterations < 0) additionalIterations = 0;
        if (additionalIterations > 200) additionalIterations = 200;
    }

    /** Returns a request with zero additional iterations (use default remaining cap). */
    public static RestartJobRequest defaults() {
        return new RestartJobRequest(0);
    }
}
