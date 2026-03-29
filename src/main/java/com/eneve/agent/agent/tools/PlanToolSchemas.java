package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public final class PlanToolSchemas {

    private PlanToolSchemas() { }

    public static Tool planRead() {
        return Tool.builder()
                .name("plan_read")
                .description("Read the current markdown content of the active execution plan. "
                        + "Use this to check which tasks are pending, in-progress, or completed before starting work.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .build())
                .build();
    }

    public static Tool planUpdate() {
        return Tool.builder()
                .name("plan_update")
                .description("Update the markdown content of the active execution plan. "
                        + "Use this after completing each task to tick it off (change '- [ ]' to '- [x]') "
                        + "and add a brief result note. Always read the plan first with plan_read before updating.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("markdownContent", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The full updated markdown content of the plan"
                                )))
                                .build())
                        .addRequired("markdownContent")
                        .build())
                .build();
    }
}
