package com.eneve.agent.model;

import java.util.List;

/**
 * Response payload for the {@code GET /jobs/{jobId}/commits} endpoint.
 */
public record JobCommitsResponse(List<PrCommitEntry> commits) {}
