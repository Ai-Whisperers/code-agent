package com.eneve.agent.agent.service;

import com.eneve.agent.agent.model.PromptTemplate;
import com.eneve.agent.agent.store.PromptTemplateStore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central service for resolving AI prompt templates.
 *
 * Resolution order for a given key:
 * <ol>
 *   <li>Database override stored in {@code prompt_templates}</li>
 *   <li>Built-in default from {@code default-prompts.json} on the classpath</li>
 * </ol>
 *
 * Placeholders in templates use the {@code {{NAME}}} syntax, consistent with the
 * existing per-repo {@code review_prompt} customisation.
 */
@ApplicationScoped
public class PromptTemplateService {

    private static final Logger LOG = Logger.getLogger(PromptTemplateService.class);
    @Inject ObjectMapper mapper;

    @Inject
    PromptTemplateStore store;

    private Map<String, PromptDefault> defaults;

    @PostConstruct
    void init() {
        Map<String, PromptDefault> metadata;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("default-prompts.json")) {
            if (is == null) {
                LOG.error("default-prompts.json not found on classpath — prompt templates will be unavailable");
                defaults = Map.of();
                return;
            }
            metadata = mapper.readValue(is, new TypeReference<>() {});
        } catch (IOException e) {
            LOG.errorf("Failed to parse default-prompts.json: %s", e.getMessage());
            defaults = Map.of();
            return;
        }

        // Load content for each key from its corresponding prompts/{key}.txt file.
        // This keeps the large prompt text out of the JSON file, making diffs readable.
        Map<String, PromptDefault> loaded = new HashMap<>(metadata.size());
        for (Map.Entry<String, PromptDefault> entry : metadata.entrySet()) {
            String key = entry.getKey();
            PromptDefault meta = entry.getValue();
            String content = loadPromptFile(key);
            loaded.put(key, new PromptDefault(meta.description(), meta.placeholders(), content));
        }
        defaults = Map.copyOf(loaded);
        LOG.infof("Loaded %d built-in prompt templates from default-prompts.json + prompts/*.txt", defaults.size());
    }

    /**
     * Loads the default content for a template key from {@code prompts/{key}.txt}
     * on the classpath. Returns an empty string if the file is missing (and logs a warning).
     */
    private String loadPromptFile(String key) {
        String path = "prompts/" + key + ".txt";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                LOG.warnf("Prompt file not found on classpath: %s — using empty default", path);
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.errorf("Failed to read prompt file %s: %s", path, e.getMessage());
            return "";
        }
    }

    /**
     * Resolves a template by key and substitutes all {@code {{PLACEHOLDER}}} tokens
     * with the supplied values. Missing placeholder values are replaced with an empty string.
     *
     * @param key          the template key (e.g. {@code "review"}, {@code "guardrails.writable"})
     * @param placeholders map of placeholder name → value
     * @return the rendered template text, or an empty string if the key is unknown
     */
    public String resolve(String key, Map<String, String> placeholders) {
        String template = getRawTemplate(key);
        if (template == null) {
            LOG.warnf("No prompt template found for key '%s'", key);
            return "";
        }
        return resolveTemplate(template, placeholders);
    }

    /**
     * Substitutes {@code {{PLACEHOLDER}}} tokens in an already-loaded template string.
     * Useful when the caller has pre-processed the template (e.g. evaluated conditional blocks)
     * before delegating placeholder substitution here.
     *
     * @param template     the template text (not a key)
     * @param placeholders map of placeholder name → value
     * @return the rendered text
     */
    public String resolveTemplate(String template, Map<String, String> placeholders) {
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue() : "";
            template = template.replace("{{" + entry.getKey() + "}}", value);
        }
        return template;
    }

    /**
     * Returns the raw (unrendered) template text for the given key.
     * DB override is checked first; falls back to the JSON default.
     */
    public String getTemplate(String key) {
        String raw = getRawTemplate(key);
        return raw != null ? raw : "";
    }

    /**
     * Returns metadata for all known prompt templates, merging DB overrides with JSON defaults.
     * Keys are returned in alphabetical order.
     */
    public List<PromptTemplateInfo> listAll() {
        Map<String, PromptTemplate> overrides = store.listAll().stream()
                .collect(Collectors.toMap(PromptTemplate::promptKey, Function.identity()));

        return defaults.entrySet().stream()
                .map(entry -> {
                    String key = entry.getKey();
                    PromptDefault def = entry.getValue();
                    PromptTemplate override = overrides.get(key);
                    String currentContent = override != null ? override.content() : def.content();
                    Instant updatedAt = override != null ? override.updatedAt() : null;
                    return new PromptTemplateInfo(
                            key,
                            def.description(),
                            def.placeholders(),
                            currentContent,
                            def.content(),
                            override != null,
                            updatedAt
                    );
                })
                .sorted(Comparator.comparing(PromptTemplateInfo::key))
                .collect(Collectors.toList());
    }

    /**
     * Returns metadata for a specific template key, or empty if the key is unknown.
     */
    public Optional<PromptTemplateInfo> get(String key) {
        PromptDefault def = defaults.get(key);
        if (def == null) {
            return Optional.empty();
        }
        Optional<PromptTemplate> override = store.find(key);
        String currentContent = override.map(PromptTemplate::content).orElse(def.content());
        Instant updatedAt = override.map(PromptTemplate::updatedAt).orElse(null);
        return Optional.of(new PromptTemplateInfo(
                key,
                def.description(),
                def.placeholders(),
                currentContent,
                def.content(),
                override.isPresent(),
                updatedAt
        ));
    }

    /** Returns true if the given key exists in the JSON defaults. */
    public boolean isKnownKey(String key) {
        return defaults.containsKey(key);
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private String getRawTemplate(String key) {
        return store.find(key)
                .map(PromptTemplate::content)
                .orElseGet(() -> {
                    PromptDefault def = defaults.get(key);
                    return def != null ? def.content() : null;
                });
    }

    // ─── Inner types ─────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptDefault(String description, List<String> placeholders, String content) {
        public PromptDefault {
            placeholders = placeholders != null ? placeholders : List.of();
            content = content != null ? content : "";
        }
    }

    public record PromptTemplateInfo(
            String key,
            String description,
            List<String> placeholders,
            String content,
            String defaultContent,
            boolean overridden,
            Instant updatedAt) {}
}
