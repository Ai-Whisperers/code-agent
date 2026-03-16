package com.eneve.agent.agent;

import java.time.Instant;

/**
 * Represents a user-supplied override for one of the built-in AI prompt templates.
 * When present in the database the override takes precedence over the JSON default.
 */
public record PromptTemplate(
        String promptKey,
        String content,
        String description,
        Instant updatedAt) {}
