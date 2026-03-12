package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Request to reply to a developer's comment on a review finding")
public record ReplyCommentRequest(

        @Schema(required = true, description = "Repository URL (HTTPS or SSH)",
                example = "https://bitbucket.org/workspace/repo.git")
        String repoUrl,

        @Schema(required = true, description = "Pull request number",
                example = "42")
        String prId,

        @Schema(required = true, description = "Comment ID of the agent's original comment that was replied to")
        long parentCommentId,

        @Schema(required = true, description = "The developer's reply text")
        String humanMessage,

        @Schema(description = "File path the original comment was anchored to (may be null for general comments)")
        String filePath,

        @Schema(description = "Line number the original comment was anchored to (0 for general comments)")
        int line
) {}
