package com.eneve.agent.agent.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * Sealed hierarchy of events emitted by the streaming chat loop.
 * Each subtype maps directly to an SSE event delivered to the browser.
 *
 * JSON shape: every record carries {@code "type"} for the UI to switch on.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatEvent.TextDelta.class,       name = "text"),
        @JsonSubTypes.Type(value = ChatEvent.ThinkingDelta.class,   name = "thinking"),
        @JsonSubTypes.Type(value = ChatEvent.ToolStart.class,       name = "tool_start"),
        @JsonSubTypes.Type(value = ChatEvent.ToolEnd.class,         name = "tool_end"),
        @JsonSubTypes.Type(value = ChatEvent.PlanStart.class,       name = "plan_start"),
        @JsonSubTypes.Type(value = ChatEvent.PlanCreated.class,     name = "plan_created"),
        @JsonSubTypes.Type(value = ChatEvent.PlanUpdated.class,     name = "plan_updated"),
        @JsonSubTypes.Type(value = ChatEvent.ProposalUpdated.class, name = "proposal_updated"),
        @JsonSubTypes.Type(value = ChatEvent.Done.class,            name = "done"),
        @JsonSubTypes.Type(value = ChatEvent.Error.class,           name = "error")
})
public sealed interface ChatEvent {

    /** Incremental text output from Claude. Accumulate all deltas for the full response. */
    record TextDelta(String type, String text) implements ChatEvent {
        public TextDelta(String text) { this("text", text); }
    }

    /** Intermediate reasoning text emitted by Claude before or between tool calls. */
    record ThinkingDelta(String type, String text) implements ChatEvent {
        public ThinkingDelta(String text) { this("thinking", text); }
    }

    /** Claude is about to execute a tool call. Show a progress indicator in the UI. */
    record ToolStart(String type, String tool, Map<String, Object> input, long timestamp) implements ChatEvent {
        public ToolStart(String toolName, Map<String, Object> input) { this("tool_start", toolName, input, System.currentTimeMillis()); }
    }

    /** Tool execution finished. Hide the progress indicator. */
    record ToolEnd(String type, String tool, String result, long timestamp) implements ChatEvent {
        public ToolEnd(String toolName, String result) { this("tool_end", toolName, result, System.currentTimeMillis()); }
    }

    /** Plan generation has started. Show a loading indicator while waiting for the plan. */
    record PlanStart(String type, String conversationId, String title) implements ChatEvent {
        public PlanStart(String conversationId, String title) { this("plan_start", conversationId, title); }
    }

    /** A new ExecutionPlan was created during the chat conversation. */
    record PlanCreated(String type, String planId, String title, String status) implements ChatEvent {
        public PlanCreated(String planId, String title, String status) { this("plan_created", planId, title, status); }
    }

    /** An existing ExecutionPlan was updated during the chat conversation. */
    record PlanUpdated(String type, String planId, String title, String status) implements ChatEvent {
        public PlanUpdated(String planId, String title, String status) { this("plan_updated", planId, title, status); }
    }

    /**
     * Emitted after {@code update_proposal} tool execution succeeds.
     * The UI should merge the returned proposal fields into the matching tab's state.
     */
    record ProposalUpdated(String type, String proposalId, Map<String, Object> proposal) implements ChatEvent {
        public ProposalUpdated(String proposalId, Map<String, Object> proposal) {
            this("proposal_updated", proposalId, proposal);
        }
    }

    /** The full response has been streamed. Conversation ID is optional. */
    record Done(String type, String conversationId) implements ChatEvent {
        public Done(String conversationId) { this("done", conversationId); }
    }

    /** An error occurred during streaming. */
    record Error(String type, String message) implements ChatEvent {
        public Error(String errorMessage) { this("error", errorMessage); }
    }
}
