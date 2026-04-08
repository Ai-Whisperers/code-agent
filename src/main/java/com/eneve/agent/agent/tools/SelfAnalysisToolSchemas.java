package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

public final class SelfAnalysisToolSchemas {

    private SelfAnalysisToolSchemas() { }

    public static Tool readDb() {
        return Tool.builder()
                .name("read_db")
                .description("Execute a read-only SELECT query against the agent's PostgreSQL database. "
                        + "Use this to inspect job records, AI call logs, and other agent data. "
                        + "Only SELECT statements are permitted — any attempt to write data will be rejected. "
                        + "Key tables: jobs (active jobs), job_history (completed/failed jobs), "
                        + "ai_calls (per-iteration AI call logs with prompt/response text), "
                        + "agent_settings (runtime configuration). "
                        + "Results are capped at 200 rows.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("sql", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "A valid PostgreSQL SELECT statement. "
                                                + "Only SELECT is allowed — INSERT, UPDATE, DELETE, DROP, etc. are rejected."
                                )))
                                .build())
                        .required(java.util.List.of("sql"))
                        .build())
                .build();
    }
}
