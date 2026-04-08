package com.eneve.agent.servicedesk;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.*;
import com.eneve.agent.agent.TokenBudgetTracker;
import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.service.KnowledgeSearchService;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.agent.store.KnowledgeEmbeddingStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.ServiceDeskTriageRequest;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Two-stage AI pipeline for Jira Service Desk tickets.
 *
 * <p><b>Stage 1 (Haiku):</b> Classifies the ticket as QUESTION, REQUEST, BUG_REPORT, or
 * OUTAGE_REPORT with severity and confidence.
 *
 * <p><b>Stage 2 (Sonnet, BUG_REPORT / OUTAGE_REPORT only):</b> Performs a deep root-cause
 * analysis using the knowledge index (similar past tickets) and a JQL search for related
 * open issues. Produces a structured markdown report.
 *
 * <p>All output is posted as <em>internal</em> Jira comments (invisible to the customer reporter)
 * via {@link JiraService#addInternalComment(String, String)}.
 */
@ApplicationScoped
public class ServiceDeskTriageService {

    private static final Logger LOG = Logger.getLogger(ServiceDeskTriageService.class);

    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_BACKOFF_MS = 3_000;
    private static final int SIMILAR_TICKETS_TOP_K = 5;
    private static final int RELATED_ISSUES_MAX = 5;

    @Inject AnthropicClient anthropicClient;
    @Inject AiCallStore aiCallStore;
    @Inject SettingsService settings;
    @Inject TokenBudgetTracker tokenBudgetTracker;
    @Inject ObjectMapper objectMapper;
    @Inject PromptTemplateService promptTemplates;
    @Inject KnowledgeSearchService knowledgeSearchService;
    @Inject JiraService jiraService;
    @Inject ServiceDeskTriageFindingsStore findingsStore;

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Runs the full triage (and optional deep analysis) pipeline for a service desk ticket.
     * Posts results as internal Jira comments and persists findings to the DB.
     *
     * @param request the ticket payload captured at webhook time
     */
    public void triage(ServiceDeskTriageRequest request) {
        LOG.infof("ServiceDeskTriageService: triaging %s (%s)", request.issueKey(), request.projectKey());

        // ── Stage 1: Haiku classification ─────────────────────────────────────
        TriageResult triage = callHaikuTriage(request);
        if (triage == null) {
            LOG.warnf("ServiceDeskTriageService: triage failed for %s — aborting", request.issueKey());
            return;
        }

        LOG.infof("ServiceDeskTriageService: %s → category=%s severity=%s confidence=%.2f",
                request.issueKey(), triage.category(), triage.severity(), triage.confidence());

        // Persist Stage 1 result
        long findingId = findingsStore.upsertTriage(
                request.issueKey(), request.projectKey(),
                triage.category(), triage.severity(),
                triage.confidence(), triage.reason());

        // Post Stage 1 internal comment
        String triageComment = buildTriageComment(request.issueKey(), triage);
        postInternalComment(request.issueKey(), triageComment);

        // ── Stage 2: Sonnet deep analysis (bugs and outages only) ─────────────
        if ("BUG_REPORT".equals(triage.category()) || "OUTAGE_REPORT".equals(triage.category())) {
            performDeepAnalysis(request, triage, findingId);
        }
    }

    // ── Stage 1: Haiku triage ─────────────────────────────────────────────────

    private TriageResult callHaikuTriage(ServiceDeskTriageRequest request) {
        String modelName = settings.get("anthropic.fast-model", "claude-haiku-4-5");
        String prompt = buildTriagePrompt(request);

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
                    null, null, "SERVICE_DESK_TRIAGE", modelName, null,
                    0, 0, 0, 0, null, null, durationMs,
                    true, e.getMessage(), Instant.now(), prompt, null, null, 0));
            LOG.warnf("ServiceDeskTriageService: Haiku triage call failed for %s: %s",
                    request.issueKey(), e.getMessage());
            return null;
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        String responseText = extractText(response);
        Usage usage = response.usage();
        String stopReason = response.stopReason().map(Object::toString).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, null, "SERVICE_DESK_TRIAGE", modelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now(), prompt, responseText, null, 0));

        return parseTriageResponse(responseText);
    }

    private String buildTriagePrompt(ServiceDeskTriageRequest request) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("PROJECT_KEY", nvl(request.projectKey()));
        placeholders.put("ISSUE_TYPE",  nvl(request.issueType()));
        placeholders.put("PRIORITY",    nvl(request.priority()));
        placeholders.put("SUMMARY",     nvl(request.summary()));
        placeholders.put("DESCRIPTION", truncate(nvl(request.description()), 2000));
        return promptTemplates.resolve("service-desk-triage", placeholders);
    }

    private TriageResult parseTriageResponse(String responseText) {
        if (responseText == null || responseText.isBlank()) return null;
        try {
            String json = responseText.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").trim();
            JsonNode node = objectMapper.readTree(json);
            String category   = node.path("category").asText("QUESTION");
            String severity   = node.path("severity").asText("low");
            double confidence = node.path("confidence").asDouble(0.5);
            String reason     = node.path("reason").asText("");
            return new TriageResult(category, severity, confidence, reason);
        } catch (Exception e) {
            LOG.warnf("ServiceDeskTriageService: failed to parse triage response '%s': %s",
                    responseText, e.getMessage());
            return null;
        }
    }

    // ── Stage 2: Sonnet deep analysis ─────────────────────────────────────────

    private void performDeepAnalysis(ServiceDeskTriageRequest request,
                                     TriageResult triage, long findingId) {
        LOG.infof("ServiceDeskTriageService: starting deep analysis for %s", request.issueKey());

        // Search knowledge index for similar past tickets
        String searchQuery = request.summary() + " " + truncate(nvl(request.description()), 500);
        List<KnowledgeEmbeddingStore.KnowledgeSearchResult> similar =
                knowledgeSearchService.search(searchQuery, List.of("jira"), SIMILAR_TICKETS_TOP_K);

        List<String> similarKeys = similar.stream()
                .map(r -> r.sourceId() != null ? r.sourceId() : "")
                .filter(s -> !s.isBlank() && !s.equals(request.issueKey()))
                .distinct()
                .collect(Collectors.toList());

        // Search for related open Jira issues in the same project
        List<JiraService.JiraIssueDetail> relatedIssues = fetchRelatedIssues(request, triage.category());

        String modelName = settings.get("anthropic.model", "claude-sonnet-4-5");
        String prompt = buildDeepAnalysisPrompt(request, triage, similar, relatedIssues);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(2048)
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
                    null, null, "SERVICE_DESK_DEEP_ANALYSIS", modelName, null,
                    0, 0, 0, 0, null, null, durationMs,
                    true, e.getMessage(), Instant.now(), prompt, null,
                    null, 0));
            LOG.warnf("ServiceDeskTriageService: deep analysis call failed for %s: %s",
                    request.issueKey(), e.getMessage());
            return;
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        String analysisText = extractText(response);
        Usage usage = response.usage();
        String stopReason = response.stopReason().map(Object::toString).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, null, "SERVICE_DESK_DEEP_ANALYSIS", modelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now(), prompt, analysisText,
                null, 0));

        if (analysisText != null && !analysisText.isBlank()) {
            findingsStore.saveDeepAnalysis(findingId, analysisText, similarKeys);
            postInternalComment(request.issueKey(), analysisText);
            LOG.infof("ServiceDeskTriageService: deep analysis saved for %s (%d chars)",
                    request.issueKey(), analysisText.length());
        }
    }

    private List<JiraService.JiraIssueDetail> fetchRelatedIssues(ServiceDeskTriageRequest request,
                                                                   String category) {
        try {
            String issueTypeFilter = "OUTAGE_REPORT".equals(category)
                    ? "issuetype in (Bug, Incident)"
                    : "issuetype = Bug";
            String jql = String.format(
                    "project = \"%s\" AND %s AND status != Done AND issue != %s ORDER BY created DESC",
                    request.projectKey(), issueTypeFilter, request.issueKey());
            return jiraService.searchIssues(jql, RELATED_ISSUES_MAX);
        } catch (Exception e) {
            LOG.warnf("ServiceDeskTriageService: failed to fetch related issues for %s: %s",
                    request.issueKey(), e.getMessage());
            return List.of();
        }
    }

    private String buildDeepAnalysisPrompt(ServiceDeskTriageRequest request,
                                            TriageResult triage,
                                            List<KnowledgeEmbeddingStore.KnowledgeSearchResult> similar,
                                            List<JiraService.JiraIssueDetail> relatedIssues) {
        String similarSection = buildSimilarTicketsSection(similar);
        String relatedSection = buildRelatedIssuesSection(relatedIssues);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("ISSUE_KEY",                request.issueKey());
        placeholders.put("PROJECT_KEY",              nvl(request.projectKey()));
        placeholders.put("CATEGORY",                 triage.category());
        placeholders.put("SEVERITY",                 triage.severity());
        placeholders.put("TRIAGE_REASON",            triage.reason());
        placeholders.put("SUMMARY",                  nvl(request.summary()));
        placeholders.put("DESCRIPTION",              truncate(nvl(request.description()), 3000));
        placeholders.put("SIMILAR_TICKETS_SECTION",  similarSection);
        placeholders.put("RELATED_OPEN_ISSUES_SECTION", relatedSection);
        return promptTemplates.resolve("service-desk-deep-analysis", placeholders);
    }

    private String buildSimilarTicketsSection(List<KnowledgeEmbeddingStore.KnowledgeSearchResult> results) {
        if (results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## Similar Past Tickets\n\n");
        for (KnowledgeEmbeddingStore.KnowledgeSearchResult r : results) {
            String key = r.sourceId() != null ? r.sourceId() : "(unknown)";
            String snippet = truncate(r.contentChunk() != null ? r.contentChunk() : "", 300);
            sb.append(String.format("- **%s** (score: %.2f): %s%n", key, r.score(), snippet));
        }
        return sb.toString();
    }

    private String buildRelatedIssuesSection(List<JiraService.JiraIssueDetail> issues) {
        if (issues.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## Related Open Issues\n\n");
        for (JiraService.JiraIssueDetail issue : issues) {
            sb.append(String.format("- **%s** (%s): %s%n",
                    issue.key(), issue.status(), issue.summary()));
        }
        return sb.toString();
    }

    // ── Comment formatting ────────────────────────────────────────────────────

    private String buildTriageComment(String issueKey, TriageResult triage) {
        String emoji = switch (triage.category()) {
            case "BUG_REPORT"    -> "🐛";
            case "OUTAGE_REPORT" -> "🚨";
            case "REQUEST"       -> "💡";
            default              -> "❓";
        };
        return String.format(
                "%s *[AI Triage — Internal]* %s classified as *%s* (severity: %s, confidence: %.0f%%)\n\n_%s_",
                emoji, issueKey, triage.category(), triage.severity(),
                triage.confidence() * 100, triage.reason());
    }

    private void postInternalComment(String issueKey, String text) {
        try {
            jiraService.addInternalComment(issueKey, text);
        } catch (Exception e) {
            LOG.warnf("ServiceDeskTriageService: failed to post internal comment on %s: %s",
                    issueKey, e.getMessage());
        }
    }

    // ── Shared AI call helper ─────────────────────────────────────────────────

    private Message callWithRetry(MessageCreateParams params) throws InterruptedException {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                tokenBudgetTracker.waitIfNeeded();
                return anthropicClient.messages().create(params);
            } catch (RateLimitException e) {
                if (attempt == MAX_RETRIES) throw e;
                long waitMs = INITIAL_BACKOFF_MS * (1L << attempt);
                waitMs += (long) (waitMs * 0.5 * ThreadLocalRandom.current().nextDouble());
                LOG.warnf("ServiceDeskTriageService: rate limited (attempt %d/%d), waiting %dms",
                        attempt + 1, MAX_RETRIES, waitMs);
                Thread.sleep(waitMs);
            }
        }
        throw new RuntimeException("Exhausted retries after rate limiting");
    }

    private static String extractText(Message response) {
        for (ContentBlock block : response.content()) {
            if (block.isText()) return block.asText().text().trim();
        }
        return null;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    // ── Internal types ────────────────────────────────────────────────────────

    private record TriageResult(String category, String severity, double confidence, String reason) {}
}
