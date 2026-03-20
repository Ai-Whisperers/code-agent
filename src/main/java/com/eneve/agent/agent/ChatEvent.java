package com.eneve.agent.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed hierarchy of events emitted by the streaming chat loop.
 * Each subtype maps directly to an SSE event delivered to the browser.
 *
 * JSON shape: every record carries {@code "type"} for the UI to switch on.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatEvent.TextDelta.class,     name = "text"),
        @JsonSubTypes.Type(value = ChatEvent.ThinkingDelta.class, name = "thinking"),
        @JsonSubTypes.Type(value = ChatEvent.ToolStart.class,     name = "tool_start"),
        @JsonSubTypes.Type(value = ChatEvent.ToolEnd.class,       name = "tool_end"),
        @JsonSubTypes.Type(value = ChatEvent.Done.class,          name = "done"),
        @JsonSubTypes.Type(value = ChatEvent.Error.class,         name = "error")
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
    record ToolStart(String type, String tool) implements ChatEvent {
        public ToolStart(String toolName) { this("tool_start", toolName); }
    }

    /** Tool execution finished. Hide the progress indicator. */
    record ToolEnd(String type, String tool) implements ChatEvent {
        public ToolEnd(String toolName) { this("tool_end", toolName); }
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
