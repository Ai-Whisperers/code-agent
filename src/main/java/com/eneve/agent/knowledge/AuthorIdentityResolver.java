package com.eneve.agent.knowledge;

import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a raw git author email to a canonical identity.
 *
 * <p>Three resolution layers, applied in order:
 * <ol>
 *   <li><b>Explicit alias map</b> — {@code agent_settings} key
 *       {@code knowledge-graph.author-aliases}, a JSON object mapping any email
 *       to its canonical email.  Example:
 *       <pre>{"ahoutman@eneve.com":"arjan.houtman@eneve.com",
 *             "arjan.houtman@energy21.com":"arjan.houtman@eneve.com"}</pre>
 *       Admins can edit this via the System Settings UI or directly in the DB.</li>
 *   <li><b>Local-part heuristic</b> — if the local part (before {@code @}) is
 *       identical across two addresses they are considered the same person and
 *       the <em>first-seen</em> canonical email is kept.  This automatically
 *       merges {@code arjan.houtman@energy21.com} with
 *       {@code arjan.houtman@eneve.com} and
 *       {@code arjan.houtman@julesenergy.com}.</li>
 *   <li><b>Identity</b> — email is returned as-is if no rule matches.</li>
 * </ol>
 *
 * <p>The alias map is loaded once per job run (passed in at construction time via
 * {@link #buildAliasMap(SettingsService, ObjectMapper)}) and then used statelessly.
 */
@ApplicationScoped
public class AuthorIdentityResolver {

    private static final Logger LOG = Logger.getLogger(AuthorIdentityResolver.class);

    /** agent_settings key for the explicit alias JSON map. */
    public static final String ALIASES_SETTING_KEY = "knowledge-graph.author-aliases";

    @Inject SettingsService settings;
    @Inject ObjectMapper objectMapper;

    /**
     * Loads the explicit alias map from {@code agent_settings}.
     * Returns an empty map if the setting is absent or malformed.
     */
    public Map<String, String> loadAliasMap() {
        String json = settings.get(ALIASES_SETTING_KEY, "{}");
        if (json == null || json.isBlank() || json.equals("{}")) return Map.of();
        try {
            Map<String, String> raw = objectMapper.readValue(json, new TypeReference<>() {});
            // Normalise all keys and values to lowercase
            Map<String, String> normalised = new HashMap<>(raw.size());
            raw.forEach((k, v) -> {
                if (k != null && v != null) normalised.put(k.trim().toLowerCase(), v.trim().toLowerCase());
            });
            return normalised;
        } catch (Exception e) {
            LOG.warnf("Failed to parse %s: %s — ignoring alias map", ALIASES_SETTING_KEY, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Resolves {@code rawEmail} to its canonical form using the explicit alias map
     * and the local-part heuristic.
     *
     * @param rawEmail   the email as it appears in git log output (already lowercased)
     * @param aliasMap   explicit alias map from {@link #loadAliasMap()}
     * @param localPartIndex  mutable map of {@code localPart → canonicalEmail} built up
     *                        during a single job run; pass the same instance for all
     *                        calls within one run so the heuristic is consistent
     * @return canonical email
     */
    public String resolve(String rawEmail, Map<String, String> aliasMap,
                          Map<String, String> localPartIndex) {
        if (rawEmail == null) return "unknown";
        String email = rawEmail.trim().toLowerCase();

        // Layer 1: explicit alias map
        String explicit = aliasMap.get(email);
        if (explicit != null) return explicit;

        // Layer 2: local-part heuristic
        int atIdx = email.indexOf('@');
        if (atIdx > 0) {
            String localPart = email.substring(0, atIdx);
            String canonical = localPartIndex.computeIfAbsent(localPart, k -> email);
            return canonical;
        }

        return email;
    }

    /**
     * Resolves an author name for a canonical email.
     * Returns the first non-blank name seen for that canonical email.
     *
     * @param canonicalEmail resolved canonical email
     * @param rawName        name from git log (may be null)
     * @param nameIndex      mutable map of {@code canonicalEmail → name}; share across calls
     * @return best known display name
     */
    public String resolveName(String canonicalEmail, String rawName,
                              Map<String, String> nameIndex) {
        if (rawName != null && !rawName.isBlank()) {
            nameIndex.putIfAbsent(canonicalEmail, rawName.trim());
        }
        return nameIndex.getOrDefault(canonicalEmail, canonicalEmail);
    }
}
