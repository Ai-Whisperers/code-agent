package com.eneve.agent.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A hunk (contiguous changed block) within a file diff")
public record DiffHunkEntry(

        @Schema(description = "Hunk header, e.g. '@@ -12,7 +12,9 @@'")
        String header,

        @Schema(description = "Lines within this hunk")
        List<DiffLineEntry> lines
) {}
