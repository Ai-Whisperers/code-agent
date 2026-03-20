package com.eneve.agent.agent;

import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Converts Anthropic SDK {@link MessageParam} objects to and from JSON strings suitable
 * for storage in the PostgreSQL {@code chat_messages.message_json} JSONB column.
 *
 * <p>The Anthropic Java SDK annotates all its types with standard Jackson annotations
 * ({@code @JsonProperty}, {@code @JsonDeserialize}, etc.), so serialisation is handled
 * transparently by the Quarkus-managed {@link ObjectMapper}.
 */
@ApplicationScoped
public class MessageSerializer {

    @Inject
    ObjectMapper objectMapper;

    /**
     * Serialises a single {@link MessageParam} to a JSON string.
     *
     * @throws RuntimeException if Jackson serialisation fails
     */
    public String toJson(MessageParam message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise MessageParam", e);
        }
    }

    /**
     * Deserialises a JSON string (previously produced by {@link #toJson}) back into a
     * {@link MessageParam}.
     *
     * @throws RuntimeException if Jackson deserialisation fails
     */
    public MessageParam fromJson(String json) {
        try {
            return objectMapper.readValue(json, MessageParam.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialise MessageParam: " + e.getMessage(), e);
        }
    }
}
