package com.eneve.agent.settings;

import java.time.Instant;

/**
 * Immutable snapshot of a single row from the agent_settings table.
 * The {@code value} field holds the raw stored value: encrypted ciphertext for
 * secrets, plaintext for non-secrets. Callers should use {@link SettingsService}
 * rather than accessing this record directly to ensure correct decryption.
 */
public record SettingRow(
        String  key,
        String  value,
        boolean isSecret,
        String  description,
        Instant updatedAt
) {}
