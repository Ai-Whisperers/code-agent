package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Structured diff for a job's pull request")
public record JobDiffResponse(

        @Schema(description = "Source (feature) branch name")
        String sourceBranch,

        @Schema(description = "Target branch the PR will merge into")
        String targetBranch,

        @Schema(description = "Total lines added across all files")
        int totalAdditions,

        @Schema(description = "Total lines deleted across all files")
        int totalDeletions,

        @Schema(description = "Per-file diff entries")
        List<DiffFileEntry> files
) {}
