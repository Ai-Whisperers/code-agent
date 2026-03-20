package com.eneve.agent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.models.messages.MessageParam;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory store for multi-turn chat conversation histories.
 *
 * <p>Keyed by conversation ID. Evicts the least-recently-used entry once
 * {@link #MAX_CONVERSATIONS} is exceeded, preventing unbounded memory growth.
 */
@ApplicationScoped
public class ConversationStore {

    private static final int MAX_CONVERSATIONS = 500;

    private final Map<String, List<MessageParam>> store = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<MessageParam>> eldest) {
                    return size() > MAX_CONVERSATIONS;
                }
            });

    /**
     * Returns a mutable copy of the stored history, or an empty list if none exists.
     */
    public List<MessageParam> get(String conversationId) {
        List<MessageParam> history = store.get(conversationId);
        return history != null ? new ArrayList<>(history) : new ArrayList<>();
    }

    /**
     * Persists a defensive copy of {@code messages} under the given conversation ID.
     */
    public void save(String conversationId, List<MessageParam> messages) {
        store.put(conversationId, new ArrayList<>(messages));
    }

    /**
     * Removes all history for the given conversation ID.
     */
    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}
