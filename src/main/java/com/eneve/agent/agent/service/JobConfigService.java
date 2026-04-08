package com.eneve.agent.agent.service;

import com.eneve.agent.agent.store.JobConfigRow;
import com.eneve.agent.agent.store.JobConfigStore;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class JobConfigService {

    public static final String TIER_FAST    = "FAST";
    public static final String TIER_DEFAULT = "DEFAULT";
    public static final String TIER_HIGH    = "HIGH";

    private static final List<JobTypeDefault> KNOWN_JOBS = List.of(
            new JobTypeDefault("CHAT",                TIER_DEFAULT, true),
            new JobTypeDefault("FIX",                 TIER_DEFAULT, true),
            new JobTypeDefault("REVIEW",              TIER_DEFAULT, true),
            new JobTypeDefault("FIX_PR",              TIER_DEFAULT, true),
            new JobTypeDefault("GENERATE_TESTS",      TIER_DEFAULT, true),
            new JobTypeDefault("GENERATE_DOCS",       TIER_DEFAULT, true),
            new JobTypeDefault("REWRITE",             TIER_HIGH,    true),
            new JobTypeDefault("SELF_ANALYSIS",       TIER_DEFAULT, true),
            new JobTypeDefault("LOG_ANALYSIS_TRIAGE", TIER_FAST,    false),
            new JobTypeDefault("LOG_ANALYSIS_DEEP",   TIER_DEFAULT, false)
    );

    private record JobTypeDefault(String jobType, String defaultTier, boolean thinkingSupported) {}

    @Inject
    JobConfigStore store;

    @Inject
    SettingsService settings;

    public JobConfigView getConfig(String jobType) {
        Optional<JobConfigRow> row = store.findByJobType(jobType);
        JobTypeDefault defaults = KNOWN_JOBS.stream()
                .filter(j -> j.jobType().equals(jobType))
                .findFirst()
                .orElse(new JobTypeDefault(jobType, TIER_DEFAULT, true));
        return buildView(jobType, row.orElse(null), defaults);
    }

    public List<JobConfigView> listAll() {
        Map<String, JobConfigRow> stored = new HashMap<>();
        store.findAll().forEach(r -> stored.put(r.jobType(), r));
        return KNOWN_JOBS.stream()
                .map(d -> buildView(d.jobType(), stored.get(d.jobType()), d))
                .toList();
    }

    public void save(String jobType, String modelTier, boolean thinkingEnabled,
                     Integer budget, Integer maxTokens) {
        validateTier(modelTier);
        boolean effectiveThinking = thinkingEnabled && !TIER_FAST.equals(modelTier);
        store.upsert(new JobConfigRow(jobType, modelTier, effectiveThinking, budget, maxTokens, null));
    }

    public boolean reset(String jobType) {
        return store.delete(jobType);
    }

    public String resolveModelName(String tier) {
        return switch (tier) {
            case TIER_FAST -> settings.get("anthropic.fast-model", "claude-haiku-4-5-202501001");
            case TIER_HIGH -> settings.get("anthropic.high-model", "claude-opus-4-6");
            default        -> settings.get("anthropic.model", "claude-sonnet-4-6");
        };
    }

    public long resolveMaxTokens(String tier, Integer storedMaxTokens) {
        if (TIER_FAST.equals(tier)) {
            return 8192L;
        }
        if (storedMaxTokens != null) {
            return storedMaxTokens;
        }
        return Long.parseLong(settings.get("anthropic.max-tokens", "8192"));
    }

    private JobConfigView buildView(String jobType, JobConfigRow row, JobTypeDefault defaults) {
        String tier = row != null ? row.modelTier() : defaults.defaultTier();
        boolean thinkingEnabled = row != null && row.thinkingEnabled();
        Integer storedBudget = row != null ? row.thinkingBudget() : null;
        Integer storedMaxTokens = row != null ? row.maxOutputTokens() : null;

        int defaultBudget = "CHAT".equals(jobType) ? 5000 : 10000;
        int effectiveBudget = storedBudget != null ? storedBudget : defaultBudget;
        long effectiveMaxTokens = resolveMaxTokens(tier, storedMaxTokens);
        String effectiveModel = resolveModelName(tier);

        return new JobConfigView(
                jobType,
                tier,
                thinkingEnabled,
                storedBudget,
                effectiveBudget,
                storedMaxTokens,
                effectiveMaxTokens,
                effectiveModel,
                defaults.thinkingSupported(),
                row != null
        );
    }

    private void validateTier(String tier) {
        if (!Set.of(TIER_FAST, TIER_DEFAULT, TIER_HIGH).contains(tier)) {
            throw new IllegalArgumentException(
                    "Invalid model tier: " + tier + ". Must be FAST, DEFAULT, or HIGH.");
        }
    }

    public record JobConfigView(
            String jobType,
            String modelTier,
            boolean thinkingEnabled,
            Integer storedThinkingBudget,
            int effectiveThinkingBudget,
            Integer storedMaxOutputTokens,
            long effectiveMaxTokens,
            String effectiveModel,
            boolean thinkingSupported,
            boolean hasOverride
    ) {}
}
