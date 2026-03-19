package com.eneve.agent.settings;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Runtime settings service backed by the agent_settings database table.
 *
 * Lookup priority:
 *   1. In-memory cache (TTL: settings.cache.ttl-seconds, default 30 s)
 *   2. agent_settings DB table (secret values are decrypted transparently)
 *   3. MicroProfile Config (application.properties / environment variables)
 *
 * This means every existing @ConfigProperty keeps working as a fallback; DB rows
 * only take effect once explicitly stored via {@link #set}.
 *
 * Secrets are encrypted at rest using {@link SettingsEncryption} (AES-256-GCM).
 * They are never logged or returned in plaintext through the API layer.
 */
@ApplicationScoped
public class SettingsService {

    private static final Logger LOG = Logger.getLogger(SettingsService.class);
    private static final String MASKED = "****";

    @ConfigProperty(name = "settings.cache.ttl-seconds", defaultValue = "30")
    int cacheTtlSeconds;

    @Inject
    SettingsStore store;

    @Inject
    SettingsEncryption encryption;

    private final ConcurrentHashMap<String, CacheEntry> cache        = new ConcurrentHashMap<>();
    /**
     * Monotonically increasing per-key write counter. Incremented (before the DB
     * write) by every {@link #set} and {@link #delete} call so that a concurrent
     * {@link #get} can detect that a write happened between its DB read and its
     * cache.put(), and skip the stale cache insertion.
     */
    private final ConcurrentHashMap<String, Long>       writeVersion = new ConcurrentHashMap<>();

    // ─── Public read API ──────────────────────────────────────────────────────

    /**
     * Returns the value for {@code key}, or {@code defaultValue} when not found
     * in the DB or MicroProfile Config.
     *
     * Cache coherency: the write version is sampled before the DB read. If a
     * concurrent {@link #set} or {@link #delete} increments the version between
     * the DB read and the cache.put(), the stale result is not cached — the next
     * call will re-fetch from the DB with the new value already visible.
     */
    public String get(String key, String defaultValue) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired(cacheTtlSeconds)) {
            return entry.value;
        }

        // Snapshot the write version before hitting the DB.
        long versionBeforeRead = writeVersion.getOrDefault(key, 0L);

        Optional<SettingRow> row = store.findByKey(key);
        if (row.isPresent()) {
            String value = resolveValue(row.get());
            // Only cache when no concurrent write changed the version while we
            // were reading from the DB, preventing stale data from being stored.
            if (writeVersion.getOrDefault(key, 0L) == versionBeforeRead) {
                cache.put(key, new CacheEntry(value));
            }
            return value;
        }

        // Fall back to MicroProfile Config (env vars / application.properties)
        String configValue = ConfigProvider.getConfig()
                .getOptionalValue(key, String.class)
                .orElse(defaultValue);
        return configValue;
    }

    /**
     * Convenience overload returning an empty string when the key is absent.
     */
    public String get(String key) {
        return get(key, "");
    }

    /**
     * Returns the value for a secret key. Identical to {@link #get(String)} but
     * makes the intent explicit at the call site (never log the return value).
     */
    public String getSecret(String key) {
        return get(key, "");
    }

    // ─── Public write API ─────────────────────────────────────────────────────

    /**
     * Stores or updates a setting. When {@code isSecret} is true the value is
     * encrypted before being written to the database; the master key must be
     * configured (SETTINGS_ENCRYPTION_KEY) or an exception is thrown.
     */
    public void set(String key, String plainValue, boolean isSecret, String description) {
        // Increment write version before the DB write so any concurrent get()
        // that reads the old value will see the version mismatch and skip caching.
        writeVersion.merge(key, 1L, Long::sum);
        if (isSecret) {
            String encrypted = encryption.encrypt(plainValue);
            store.upsert(key, encrypted, true, description);
            LOG.infof("Secret setting updated: key='%s'", key);
        } else {
            store.upsert(key, plainValue, false, description);
            LOG.infof("Setting updated: key='%s' value='%s'", key, plainValue);
        }
        cache.remove(key);
    }

    /**
     * Removes a DB override. Subsequent reads fall back to MicroProfile Config.
     * Returns {@code false} when no DB row existed for the key.
     */
    public boolean delete(String key) {
        writeVersion.merge(key, 1L, Long::sum);
        boolean deleted = store.delete(key);
        if (deleted) {
            cache.remove(key);
            LOG.infof("Setting deleted: key='%s' (reverted to application.properties / env)", key);
        }
        return deleted;
    }

    /** Forces the cached value for {@code key} to be re-fetched on the next read. */
    public void invalidate(String key) {
        cache.remove(key);
    }

    // ─── List API (for the REST resource) ────────────────────────────────────

    /**
     * Returns all DB-stored settings. Secret values are replaced with {@value MASKED}.
     */
    public List<SettingView> listAll() {
        return store.findAll().stream()
                .map(row -> new SettingView(
                        row.key(),
                        row.isSecret() ? MASKED : row.value(),
                        row.isSecret(),
                        row.description(),
                        row.updatedAt()))
                .toList();
    }

    /**
     * Returns a single setting view. Secret value is masked.
     */
    public Optional<SettingView> findView(String key) {
        return store.findByKey(key).map(row -> new SettingView(
                row.key(),
                row.isSecret() ? MASKED : row.value(),
                row.isSecret(),
                row.description(),
                row.updatedAt()));
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private String resolveValue(SettingRow row) {
        if (row.isSecret()) {
            return encryption.decrypt(row.value());
        }
        return row.value();
    }

    // ─── Value types ─────────────────────────────────────────────────────────

    /** Cached entry holding a decrypted value and the time it was loaded. */
    private static final class CacheEntry {
        final String  value;
        final Instant loadedAt;

        CacheEntry(String value) {
            this.value    = value;
            this.loadedAt = Instant.now();
        }

        boolean isExpired(int ttlSeconds) {
            return Instant.now().isAfter(loadedAt.plusSeconds(ttlSeconds));
        }
    }

    /** Safe view of a setting for API responses — secrets are masked. */
    public record SettingView(
            String  key,
            String  value,
            boolean isSecret,
            String  description,
            Instant updatedAt
    ) {}

    /** Request body for upsert operations. */
    public record UpsertRequest(
            String  value,
            boolean isSecret,
            String  description
    ) {}
}
