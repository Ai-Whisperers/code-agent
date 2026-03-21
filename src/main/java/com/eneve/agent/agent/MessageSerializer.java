package com.eneve.agent.agent;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Converts Anthropic SDK {@link MessageParam} objects to and from JSON strings suitable
 * for storage in the PostgreSQL {@code chat_messages.message_json} JSONB column.
 *
 * <p>Uses the SDK's own {@link ObjectMappers#jsonMapper()} rather than the Quarkus-managed
 * {@link ObjectMapper}. The SDK mapper disables all Jackson auto-detection
 * ({@code AUTO_DETECT_GETTERS}, {@code AUTO_DETECT_IS_GETTERS}, etc.) and relies solely on
 * {@code @JsonProperty} annotations. This prevents internal SDK methods such as
 * {@code isValid()} from being emitted as JSON fields, which the Anthropic API rejects with
 * {@code "Extra inputs are not permitted"}.
 *
 * <h3>Legacy data migration</h3>
 * <p>Before this class was updated to use the SDK mapper, the Quarkus-managed
 * {@link ObjectMapper} was used. That mapper has {@code AUTO_DETECT_IS_GETTERS} enabled, so
 * it serialised {@code MessageParam.isValid()} as {@code "valid": true} in every stored
 * message. When such legacy messages are loaded from the DB and forwarded to the Anthropic API,
 * the extra {@code valid} field causes a 400 error. {@link #fromJson} therefore strips
 * {@code "valid"} from {@code additionalProperties} after deserialisation to repair legacy rows
 * transparently without requiring a DB migration.
 */
@ApplicationScoped
public class MessageSerializer {

    private static final ObjectMapper SDK_MAPPER = ObjectMappers.jsonMapper();

    /**
     * Serialises a single {@link MessageParam} to a JSON string.
     *
     * @throws RuntimeException if Jackson serialisation fails
     */
    public String toJson(MessageParam message) {
        try {
            return SDK_MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise MessageParam", e);
        }
    }

    /**
     * Deserialises a JSON string back into a {@link MessageParam}, stripping any SDK-internal
     * fields ({@code "valid"}) that older serialisers may have stored in the JSONB column.
     *
     * @throws RuntimeException if Jackson deserialisation fails
     */
    public MessageParam fromJson(String json) {
        try {
            MessageParam msg = SDK_MAPPER.readValue(json, MessageParam.class);
            if (msg._additionalProperties().containsKey("valid")) {
                msg = msg.toBuilder().removeAdditionalProperty("valid").build();
            }
            return msg;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialise MessageParam: " + e.getMessage(), e);
        }
    }
}
