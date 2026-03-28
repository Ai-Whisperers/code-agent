package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A single line in a diff hunk")
public record DiffLineEntry(

        @Schema(description = "Line type: 'add', 'del', or 'ctx'")
        String type,

        @Schema(description = "Old-side line number (0 if not applicable)")
        int oldLine,

        @Schema(description = "New-side line number (0 if not applicable)")
        int newLine,

        @Schema(description = "Raw line content (without the leading +/-/space prefix)")
        String content
) {}
