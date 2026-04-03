package com.eneve.agent.loganalysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.*;
import com.eneve.agent.agent.TokenBudgetTracker;
import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.agent.store.CloudAccountStore;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.model.CloudAccount;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.model.LogAnalysisConfig;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.tools.AwsClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scheduled log analyser service.
 *
 * <p>On each {@link #analyzeAll()} call:
 * <ol>
 *   <li>Iterates all customer environments where {@code logAnalysis.enabled = true}.</li>
 *   <li>Queries CloudWatch for exceptions/errors in the configured lookback window.</li>
 *   <li>Groups events by fingerprint (SHA-256 of exception class + top 3 stack frames).</li>
 *   <li>Gate 1 — DB suppress check: if already seen within 24 h, increments count and skips.</li>
 *   <li>Gate 2 — Haiku triage: single-shot Claude call; GENUINE findings are persisted for the UI.</li>
 *   <li>Prunes findings older than 90 days.</li>
 * </ol>
 */
@ApplicationScoped
public class LogAnalysisService {

    private static final Logger LOG = Logger.getLogger(LogAnalysisService.class);

    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_BACKOFF_MS = 3_000;
    private static final int RETENTION_DAYS = 90;

    private static final Pattern EXCEPTION_PATTERN =
            Pattern.compile("([a-zA-Z][\\w$.]*(?:Exception|Error|Throwable))");
    private static final Pattern STACK_FRAME_PATTERN =
            Pattern.compile("\\s+at\\s+(\\S+)");

    @Inject CustomerRegistryStore customerRegistryStore;
    @Inject CloudAccountStore cloudAccountStore;
    @Inject AwsClientFactory awsClientFactory;
    @Inject LogAnalysisFindingsStore findingsStore;
    @Inject AnthropicClient anthropicClient;
    @Inject AiCallStore aiCallStore;
    @Inject SettingsService settings;
    @Inject TokenBudgetTracker tokenBudgetTracker;
    @Inject ObjectMapper objectMapper;
    @Inject PromptTemplateService promptTemplates;

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Runs the full analysis cycle across all enabled environments.
     * Called by {@link com.eneve.agent.agent.scheduler.LogAnalysisScheduler}.
     */
    public void analyzeAll() {
        List<EnabledEnv> targets = resolveEnabledEnvironments();
        if (targets.isEmpty()) {
            LOG.debug("LogAnalysisService: no environments have log analysis enabled — skipping");
            return;
        }

        LOG.infof("LogAnalysisService: analysing %d enabled environment(s)", targets.size());
        int totalFindings = 0;

        for (EnabledEnv target : targets) {
            try {
                int found = analyzeEnvironment(target);
                totalFindings += found;
            } catch (Exception e) {
                LOG.errorf("LogAnalysisService: failed to analyse %s/%s: %s",
                        target.customerId(), target.env().name(), e.getMessage());
            }
        }

        findingsStore.pruneOlderThan(RETENTION_DAYS);
        LOG.infof("LogAnalysisService: complete — %d genuine finding(s) surfaced across %d environment(s)",
                totalFindings, targets.size());
    }

    // ── Per-environment analysis ──────────────────────────────────────────────

    private int analyzeEnvironment(EnabledEnv target) {
        LogAnalysisConfig cfg = target.env().logAnalysis();
        String customerId = target.customerId();
        String envName = effectiveEnvName(target.env());

        List<String> groupNames = cfg.effectiveLogGroupNames();
        if (groupNames.isEmpty()) {
            LOG.warnf("LogAnalysisService: no log group names configured for %s/%s — skipping",
                    customerId, envName);
            return 0;
        }

        LOG.debugf("LogAnalysisService: querying CloudWatch for %s/%s (%d group(s), lookback=%dm)",
                customerId, envName, groupNames.size(), cfg.effectiveLookbackMinutes());

        // Collect events from all configured log groups
        List<FilteredLogEvent> events = new ArrayList<>();
        for (String groupName : groupNames) {
            events.addAll(fetchLogEvents(target, cfg, groupName));
        }

        if (events.isEmpty()) {
            LOG.debugf("LogAnalysisService: no matching events for %s/%s", customerId, envName);
            return 0;
        }

        Map<String, FingerprintGroup> groups = groupByFingerprint(events);
        LOG.debugf("LogAnalysisService: %d unique fingerprint(s) from %d event(s) for %s/%s",
                groups.size(), events.size(), customerId, envName);

        // Sort by occurrence count descending, cap at maxFingerprintsPerRun
        List<FingerprintGroup> candidates = groups.values().stream()
                .sorted(Comparator.comparingInt(FingerprintGroup::count).reversed())
                .limit(cfg.effectiveMaxFingerprintsPerRun())
                .collect(Collectors.toList());

        int genuineCount = 0;
        for (FingerprintGroup group : candidates) {
            boolean isGenuine = processFingerprint(group, customerId, envName, cfg);
            if (isGenuine) genuineCount++;
        }
        return genuineCount;
    }

    // ── CloudWatch query ──────────────────────────────────────────────────────

    private List<FilteredLogEvent> fetchLogEvents(EnabledEnv target, LogAnalysisConfig cfg, String logGroupName) {
        CloudAccount cloudAccount = resolveCloudAccount(target.customer());
        String roleArn = target.env().aws() != null ? target.env().aws().iamRole() : null;
        String region  = target.env().aws() != null ? target.env().aws().region()  : null;

        try (CloudWatchLogsClient cwClient = awsClientFactory.cloudWatchLogsClient(
                roleArn != null ? roleArn : "", region != null ? region : "eu-west-1", cloudAccount)) {

            var req = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupName)
                    .filterPattern("?Exception ?ERROR ?FATAL")
                    .startTime(Instant.now()
                            .minus(cfg.effectiveLookbackMinutes(), ChronoUnit.MINUTES)
                            .toEpochMilli())
                    .limit(500)
                    .build();

            return cwClient.filterLogEvents(req).events();
        } catch (Exception e) {
            LOG.warnf("LogAnalysisService: CloudWatch query failed for %s/%s: %s",
                    target.customerId(), effectiveEnvName(target.env()), e.getMessage());
            return List.of();
        }
    }

    // ── Fingerprinting ────────────────────────────────────────────────────────

    private Map<String, FingerprintGroup> groupByFingerprint(List<FilteredLogEvent> events) {
        Map<String, FingerprintGroup> groups = new LinkedHashMap<>();
        for (FilteredLogEvent event : events) {
            String message = event.message() != null ? event.message() : "";
            ParsedEvent parsed = parseEvent(message);
            String fp = computeFingerprint(parsed);
            groups.computeIfAbsent(fp, k -> new FingerprintGroup(fp, parsed))
                  .increment(message);
        }
        return groups;
    }

    private ParsedEvent parseEvent(String message) {
        String exceptionClass = null;
        Matcher em = EXCEPTION_PATTERN.matcher(message);
        if (em.find()) {
            exceptionClass = em.group(1);
        }

        List<String> frames = new ArrayList<>();
        Matcher fm = STACK_FRAME_PATTERN.matcher(message);
        while (fm.find() && frames.size() < 3) {
            frames.add(fm.group(1));
        }

        return new ParsedEvent(exceptionClass, frames, message);
    }

    private String computeFingerprint(ParsedEvent parsed) {
        String input;
        if (parsed.exceptionClass() != null && !parsed.frames().isEmpty()) {
            input = parsed.exceptionClass() + "|" + String.join("|", parsed.frames());
        } else {
            // Fallback: use first 80 chars of the message
            String msg = parsed.rawMessage().replaceAll("\\s+", " ").trim();
            input = "MSG|" + (msg.length() > 80 ? msg.substring(0, 80) : msg);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            throw new RuntimeException(e);
        }
    }

    // ── Two-gate dedup + triage ───────────────────────────────────────────────

    /**
     * @return true if the finding was classified as GENUINE
     */
    private boolean processFingerprint(FingerprintGroup group, String customerId,
                                        String envName, LogAnalysisConfig cfg) {
        // Gate 1 — DB suppress check
        Optional<LogAnalysisFinding> existing = findingsStore.find(group.fingerprint(), customerId, envName);
        if (existing.isPresent() && existing.get().suppressUntil() != null
                && existing.get().suppressUntil().isAfter(Instant.now())) {
            findingsStore.incrementOccurrence(existing.get().id(), group.sampleMessage());
            LOG.debugf("LogAnalysisService: Gate 1 suppressed %s for %s/%s (suppress_until=%s)",
                    group.fingerprint().substring(0, 8), customerId, envName,
                    existing.get().suppressUntil());
            return false;
        }

        // Gate 2 — Haiku triage
        TriageResult triage = callHaikuTriage(group, customerId, envName, cfg);
        if (triage == null) {
            LOG.warnf("LogAnalysisService: triage failed for %s/%s fingerprint %s — skipping",
                    customerId, envName, group.fingerprint().substring(0, 8));
            return false;
        }

        String decision = triage.genuine() ? "GENUINE" : "NOISE";
        findingsStore.upsertAfterTriage(
                group.fingerprint(), customerId, envName,
                cfg.logGroupName(),
                group.parsed().exceptionClass(),
                group.topFramesText(),
                group.sampleMessage(),
                group.count(),
                decision, triage.severity(), triage.reason());

        LOG.infof("LogAnalysisService: %s — %s/%s fingerprint %s (class=%s, severity=%s): %s",
                decision, customerId, envName, group.fingerprint().substring(0, 8),
                group.parsed().exceptionClass(), triage.severity(), triage.reason());

        return triage.genuine();
    }

    // ── Haiku single-shot call ────────────────────────────────────────────────

    private TriageResult callHaikuTriage(FingerprintGroup group, String customerId,
                                          String envName, LogAnalysisConfig cfg) {
        String prompt = buildTriagePrompt(group, customerId, envName, cfg);
        String modelName = settings.get("anthropic.fast-model", "claude-haiku-4-5");

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(256)
                .messages(List.of(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(prompt)
                                .build()
                ))
                .build();

        long startNs = System.nanoTime();
        Message response;
        try {
            response = callWithRetry(params);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            aiCallStore.save(new AiCallRecord(
                    null, null, "LOG_ANALYSIS_TRIAGE", modelName, null,
                    0, 0, 0, 0, null, null, durationMs,
                    true, e.getMessage(), Instant.now(), prompt, null));
            LOG.warnf("LogAnalysisService: Haiku triage call failed: %s", e.getMessage());
            return null;
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        String responseText = null;
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                responseText = block.asText().text().trim();
                break;
            }
        }

        Usage usage = response.usage();
        String stopReason = response.stopReason().map(Object::toString).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, null, "LOG_ANALYSIS_TRIAGE", modelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now(), prompt, responseText));

        return parseTriageResponse(responseText);
    }

    private Message callWithRetry(MessageCreateParams params) throws InterruptedException {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                tokenBudgetTracker.waitIfNeeded();
                return anthropicClient.messages().create(params);
            } catch (RateLimitException e) {
                if (attempt == MAX_RETRIES) throw e;
                long waitMs = INITIAL_BACKOFF_MS * (1L << attempt);
                waitMs += (long) (waitMs * 0.5 * ThreadLocalRandom.current().nextDouble());
                LOG.warnf("LogAnalysisService: rate limited (attempt %d/%d), waiting %dms",
                        attempt + 1, MAX_RETRIES, waitMs);
                Thread.sleep(waitMs);
            }
        }
        throw new RuntimeException("Exhausted retries after rate limiting");
    }

    private TriageResult parseTriageResponse(String responseText) {
        if (responseText == null || responseText.isBlank()) return null;
        try {
            // Strip markdown code fences if present
            String json = responseText.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").trim();
            JsonNode node = objectMapper.readTree(json);
            boolean genuine = node.path("genuine").asBoolean(false);
            String severity = node.path("severity").asText("low");
            String reason   = node.path("reason").asText("");
            return new TriageResult(genuine, severity, reason);
        } catch (Exception e) {
            LOG.warnf("LogAnalysisService: failed to parse triage response '%s': %s", responseText, e.getMessage());
            return null;
        }
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    private String buildTriagePrompt(FingerprintGroup group, String customerId,
                                      String envName, LogAnalysisConfig cfg) {
        String template = loadPromptTemplate();
        return template
                .replace("{{CUSTOMER_ID}}", customerId)
                .replace("{{ENVIRONMENT_NAME}}", envName)
                .replace("{{EXCEPTION_CLASS}}", group.parsed().exceptionClass() != null
                        ? group.parsed().exceptionClass() : "(unknown)")
                .replace("{{LOOKBACK_MINUTES}}", String.valueOf(cfg.effectiveLookbackMinutes()))
                .replace("{{OCCURRENCE_COUNT}}", String.valueOf(group.count()))
                .replace("{{TOP_FRAMES}}", group.topFramesText())
                .replace("{{SAMPLE_MESSAGE}}", truncate(group.sampleMessage(), 500));
    }

    private String loadPromptTemplate() {
        String template = promptTemplates.getTemplate("log-analysis-triage");
        if (!template.isBlank()) {
            return template;
        }
        LOG.warn("LogAnalysisService: log-analysis-triage template is empty, using inline fallback");
        return inlineFallbackPrompt();
    }

    private String inlineFallbackPrompt() {
        return """
                Triage this production log exception. Reply with JSON only: {"genuine": true/false, "severity": "high|medium|low", "reason": "one sentence"}
                Exception: {{EXCEPTION_CLASS}}
                Occurrences: {{OCCURRENCE_COUNT}} in {{LOOKBACK_MINUTES}} minutes
                Frames: {{TOP_FRAMES}}
                Message: {{SAMPLE_MESSAGE}}
                """;
    }

    // ── Environment resolution ────────────────────────────────────────────────

    private List<EnabledEnv> resolveEnabledEnvironments() {
        return customerRegistryStore.listCustomers().stream()
                .flatMap(customer -> {
                    if (customer.environments() == null) return java.util.stream.Stream.empty();
                    return customer.environments().stream()
                            .filter(env -> env.logAnalysis() != null && env.logAnalysis().enabled())
                            .map(env -> new EnabledEnv(customer, env));
                })
                .collect(Collectors.toList());
    }

    private CloudAccount resolveCloudAccount(CustomerConfig customer) {
        if (customer.cloudAccountId() == null || customer.cloudAccountId().isBlank()) return null;
        return cloudAccountStore.getCloudAccountUnmasked(customer.cloudAccountId()).orElse(null);
    }

    private static String effectiveEnvName(EnvironmentConfig env) {
        return (env.name() != null && !env.name().isBlank()) ? env.name() : env.type();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    // ── Internal types ────────────────────────────────────────────────────────

    private record EnabledEnv(CustomerConfig customer, EnvironmentConfig env) {
        String customerId() { return customer.customerId(); }
    }

    private record ParsedEvent(String exceptionClass, List<String> frames, String rawMessage) {}

    private record TriageResult(boolean genuine, String severity, String reason) {}

    private static class FingerprintGroup {
        private final String fingerprint;
        private final ParsedEvent parsed;
        private int count = 1;
        private String sampleMessage;

        FingerprintGroup(String fingerprint, ParsedEvent parsed) {
            this.fingerprint = fingerprint;
            this.parsed = parsed;
            this.sampleMessage = truncate(parsed.rawMessage(), 1000);
        }

        void increment(String message) {
            count++;
            // Keep the most recent message as sample
            this.sampleMessage = truncate(message, 1000);
        }

        String fingerprint() { return fingerprint; }
        ParsedEvent parsed()  { return parsed; }
        int count()           { return count; }
        String sampleMessage(){ return sampleMessage; }

        String topFramesText() {
            if (parsed.frames().isEmpty()) return "(no stack frames)";
            return String.join("\n", parsed.frames());
        }

        private static String truncate(String s, int maxLen) {
            if (s == null) return "";
            return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
        }
    }
}
