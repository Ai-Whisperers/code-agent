package com.eneve.agent.agent.tools;

import com.anthropic.models.messages.Tool;

public final class CommentChatToolSchemas {

    private CommentChatToolSchemas() { }

    public static Tool resolveCommentTool() {
        return Tool.builder()
                .name("resolve_comment")
                .description("Resolve this review comment on the SCM platform (GitHub, GitLab, Bitbucket, etc.) "
                        + "when the developer has confirmed the concern is addressed or agrees to fix it. "
                        + "Always acknowledge what you are about to do before calling this tool.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .build())
                .build();
    }

    public static Tool markFalsePositiveTool() {
        return Tool.builder()
                .name("mark_false_positive")
                .description("Mark this finding as a false positive when the developer has convinced you "
                        + "that it is not a real issue. Records the feedback so this pattern is suppressed "
                        + "in future reviews. Always acknowledge what you are about to do before calling this tool.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .build())
                .build();
    }

    public static Tool requestFixTool() {
        return Tool.builder()
                .name("request_fix")
                .description("Start an automated fix job for this review comment. "
                        + "Only invoke this tool after the developer explicitly asks you to start an automated fix. "
                        + "Always confirm what you are about to do before calling this tool.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .build())
                .build();
    }
}
