package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Request body for rejecting a job")
public record RejectRequest(

        @Schema(description = "Reason for rejecting the PR", example = "Changes are too broad")
        String reason
) {
}
