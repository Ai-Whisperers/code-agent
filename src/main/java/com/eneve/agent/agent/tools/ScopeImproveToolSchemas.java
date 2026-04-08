package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.Map;

/** Tool schemas specific to the Scope Improve Chat agentic loop. */
public final class ScopeImproveToolSchemas {

    private ScopeImproveToolSchemas() { }

    /**
     * Tool that allows the AI to update one or more fields of a scope item proposal.
     * The executor ({@code UpdateProposalToolExecutor}) applies the changes to the database
     * and the service layer emits a {@code proposal_updated} SSE event so the UI updates live.
     */
    public static Tool updateProposal() {
        return Tool.builder()
                .name("update_proposal")
                .description("Update one or more fields of a scope item proposal. "
                        + "Call this to apply your suggested improvements directly to the proposal form "
                        + "instead of just describing them in prose. "
                        + "Only include the fields you want to change — omitted fields are left unchanged. "
                        + "The UI will highlight changed fields in real time.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("proposal_id", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "UUID of the proposal to update (required)"
                                )))
                                .putAdditionalProperty("proposed_summary", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Improved issue title / summary"
                                )))
                                .putAdditionalProperty("proposed_description", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Improved issue description (plain text or markdown)"
                                )))
                                .putAdditionalProperty("proposed_criteria", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Acceptance criteria (plain text or markdown)"
                                )))
                                .putAdditionalProperty("proposed_technical", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Technical notes or implementation guidance"
                                )))
                                .putAdditionalProperty("proposed_label", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Primary Jira label to apply"
                                )))
                                .putAdditionalProperty("proposed_priority", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Jira priority name (e.g. High, Medium, Low)"
                                )))
                                .build())
                        .addRequired("proposal_id")
                        .build())
                .build();
    }
}
