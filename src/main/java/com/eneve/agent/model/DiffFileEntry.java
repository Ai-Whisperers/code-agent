package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A single file entry in the PR diff")
public record DiffFileEntry(

        @Schema(description = "File path relative to the repository root")
        String filename,

        @Schema(description = "Change status: 'added', 'modified', or 'removed'")
        String status,

        @Schema(description = "Number of added lines")
        int additions,

        @Schema(description = "Number of removed lines")
        int deletions,

        @Schema(description = "Diff hunks for this file")
        List<DiffHunkEntry> hunks
) {}
