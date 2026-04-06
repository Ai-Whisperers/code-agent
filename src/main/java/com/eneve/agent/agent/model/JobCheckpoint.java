package com.eneve.agent.agent.model;

import com.anthropic.models.messages.MessageParam;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a job's full conversation state at a specific iteration,
 * together with the git commit SHA that represents the workspace at that point.
 */
public record JobCheckpoint(
        String jobId,
        int iteration,
        List<MessageParam> messages,
        String gitCommitSha,
        Instant checkpointedAt
) {}
