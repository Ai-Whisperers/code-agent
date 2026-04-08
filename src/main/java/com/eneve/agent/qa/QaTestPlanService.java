package com.eneve.agent.qa;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.agent.store.ScopeStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.jira.JiraService.JiraIssueDetail;
import com.eneve.agent.model.QaTestPlan;
import com.eneve.agent.model.ScopeItem;
import com.eneve.agent.model.ScopeRecord;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Orchestrates the two-stage AI test plan generation for a Jira feature.
 *
 * <p>Test plans are stored per {@code issueKey} (not per scope) because the same
 * feature can appear in multiple scopes — they all share one test plan record.
 * The {@code scopeId} is only used to resolve the scope's issue-type configuration
 * and to link the plan to the scope via the join table.
 */
@ApplicationScoped
public class QaTestPlanService {

    private static final Logger LOG = Logger.getLogger(QaTestPlanService.class);

    @Inject QaTestPlanStore store;
    @Inject ScopeStore scopeStore;
    @Inject ScopeItemStore scopeItemStore;
    @Inject JiraService jiraService;
    @Inject PromptTemplateService promptTemplates;
    @Inject AnthropicClient anthropicClient;
    @Inject AiCallStore aiCallStore;
    @Inject SettingsService settings;
    @Inject ObjectMapper mapper;

    // ─── Public API ───────────────────────────────────────────────────────────

    public Optional<QaTestPlan> getTestPlan(String issueKey) {
        return store.findByKey(issueKey);
    }

    public List<QaTestPlan> listTestPlans(String scopeId) {
        return store.findAllByScope(scopeId);
    }

    /**
     * Step 1: Runs the analysis Claude call and stores the markdown result.
     * Detects requirements drift by comparing the spec hash with the stored one.
     *
     * @param scopeId used to resolve issue-type config and to link the plan to the scope
     */
    public QaTestPlan generateAnalysis(String scopeId, String issueKey, String jobId) {
        ScopeRecord scope = scopeStore.findById(scopeId)
                .orElseThrow(() -> new IllegalArgumentException("Scope not found: " + scopeId));

        // Load feature from scope_items (validates the feature belongs to this scope)
        ScopeItem feature = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey)
                .orElseThrow(() -> new IllegalArgumentException("Feature not found in scope: " + issueKey));

        // Fetch full Jira detail for the feature
        JiraIssueDetail featureDetail = jiraService.fetchIssueDetail(issueKey);
        String featureSummary = featureDetail != null ? featureDetail.summary() : feature.summary();
        String featureDescription = featureDetail != null && featureDetail.description() != null
                ? featureDetail.description() : "(no description)";

        // Fetch child stories
        List<JiraIssueDetail> stories = jiraService.searchStoriesForFeature(
                issueKey, scope.userstoryIssuetype());
        String storiesText = buildStoriesText(stories);

        // Build specifications snapshot and compute hash
        String specificationsJson = buildSpecificationsJson(featureDetail, stories);
        String specHash = sha256(specificationsJson);

        // Detect drift against the existing stored hash
        boolean driftDetected = store.findByKey(issueKey)
                .map(existing -> existing.kpiSpecHash() != null && !existing.kpiSpecHash().equals(specHash))
                .orElse(false);

        // Ensure the plan row exists and is linked to this scope
        store.ensureExists(scopeId, issueKey);

        // ── Detect Jira "is tested by" link ───────────────────────────────────
        // If the feature already has an associated Jira test plan ticket, import
        // its description as the analysis text instead of calling the AI.
        List<JiraService.JiraIssueRef> testPlanLinks = jiraService.fetchIsTestedByLinks(issueKey);
        if (!testPlanLinks.isEmpty()) {
            JiraService.JiraIssueRef tpRef = testPlanLinks.get(0);
            LOG.infof("QaTestPlanService: detected Jira test plan link for %s → %s", issueKey, tpRef.key());

            // Persist the Jira test plan ticket key
            store.updateJiraKey(issueKey, tpRef.key());

            // Fetch the test plan ticket description to use as analysis text.
            // Only treat it as a real analysis if it contains substantial content
            // (> 200 chars). A short description like "Test Plan for AURORA-XXX" is
            // just a placeholder and should not suppress AI analysis.
            JiraService.JiraIssueDetail tpDetail = jiraService.fetchIssueDetail(tpRef.key());
            String tpDescription = tpDetail != null ? tpDetail.description() : null;
            if (tpDescription != null && tpDescription.length() > 200) {
                LOG.infof("QaTestPlanService: importing description from %s as analysis for %s (length=%d)",
                        tpRef.key(), issueKey, tpDescription.length());
                store.saveAnalysis(issueKey, tpDescription, specificationsJson, specHash, driftDetected);

                store.findByKey(issueKey).ifPresent(plan ->
                        store.insertHistory(plan.id(), issueKey,
                                null, null, null, null, null, null, null, null,
                                specHash, "import_from_jira")
                );
                return store.findByKey(issueKey).orElseThrow();
            }
            LOG.infof("QaTestPlanService: Jira test plan %s description too short (%d chars) — falling through to AI analysis",
                    tpRef.key(), tpDescription != null ? tpDescription.length() : 0);
        }

        // ── Fallback: run AI analysis ──────────────────────────────────────────
        String prompt = promptTemplates.resolve("qa.testplan.analysis", Map.of(
                "FEATURE_ID", issueKey,
                "FEATURE_SUMMARY", featureSummary,
                "FEATURE_DESCRIPTION", featureDescription,
                "STORIES", storiesText
        ));

        String analysisText = callClaude(prompt, "qa-analysis-" + issueKey, jobId, "QA_TESTPLAN_ANALYSIS");
        if (analysisText == null) {
            throw new RuntimeException("Claude call failed for qa.testplan.analysis on " + issueKey);
        }

        // Persist analysis (keyed by issueKey only)
        store.saveAnalysis(issueKey, analysisText, specificationsJson, specHash, driftDetected);

        // Append history snapshot (no JSON KPIs yet at this stage)
        store.findByKey(issueKey).ifPresent(plan ->
                store.insertHistory(plan.id(), issueKey,
                        null, null, null, null, null, null, null, null,
                        specHash, "generate_analysis")
        );

        return store.findByKey(issueKey).orElseThrow();
    }

    /**
     * Step 2: Runs the formatter Claude call using the stored (possibly edited) analysis text
     * and persists the parsed featureTestPlan JSON with extracted KPIs.
     */
    public QaTestPlan generateJson(String issueKey, String jobId) {
        QaTestPlan existing = store.findByKey(issueKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No test plan record found for " + issueKey + " — run generateAnalysis first"));

        if (existing.analysisText() == null || existing.analysisText().isBlank()) {
            throw new IllegalStateException("Analysis text is empty — run generateAnalysis first");
        }

        String prompt = promptTemplates.resolve("qa.testplan.formatter", Map.of(
                "FEATURE_ID", issueKey,
                "ANALYSIS", existing.analysisText()
        ));

        String rawJson = callClaude(prompt, "qa-formatter-" + issueKey, jobId, "QA_TESTPLAN_CONVERSION");
        if (rawJson == null) {
            throw new RuntimeException("Claude call failed for qa.testplan.formatter on " + issueKey);
        }

        rawJson = stripFences(rawJson);

        try {
            mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Claude returned invalid JSON for qa.testplan.formatter on " + issueKey
                            + " — cannot save to DB. Parser error: " + e.getMessage(), e);
        }

        KpiExtract kpis = extractKpis(rawJson);

        store.savePlanJson(issueKey, rawJson,
                kpis.storyCount, kpis.behaviourTcCount, kpis.capabilityTcCount,
                kpis.riskCount, kpis.openClarifications, kpis.coveragePct,
                kpis.highRisks, kpis.gapsCount, kpis.readiness);

        store.findByKey(issueKey).ifPresent(plan ->
                store.insertHistory(plan.id(), issueKey,
                        kpis.behaviourTcCount, kpis.capabilityTcCount,
                        kpis.riskCount, kpis.openClarifications, kpis.coveragePct,
                        kpis.highRisks, kpis.gapsCount, kpis.readiness,
                        plan.kpiSpecHash(), "generate_json")
        );

        return store.findByKey(issueKey).orElseThrow();
    }

    /**
     * Saves a manual edit to the analysis text.
     * Increments {@code kpi_analysis_edit_count} and sets {@code analysis_edited = true}.
     */
    public QaTestPlan updateAnalysis(String issueKey, String analysisText) {
        store.findByKey(issueKey)
                .orElseThrow(() -> new IllegalArgumentException("No test plan record found for " + issueKey));

        store.updateAnalysisText(issueKey, analysisText);

        store.findByKey(issueKey).ifPresent(plan ->
                store.insertHistory(plan.id(), issueKey,
                        plan.kpiBehaviourTcCount(), plan.kpiCapabilityTcCount(),
                        plan.kpiRiskCount(), plan.kpiOpenClarifications(), plan.kpiCoveragePct(),
                        plan.kpiHighRisks(), plan.kpiGapsCount(), plan.kpiReadiness(),
                        plan.kpiSpecHash(), "manual_edit")
        );

        return store.findByKey(issueKey).orElseThrow();
    }

    /**
     * Returns true when the current Jira spec hash differs from the stored one,
     * indicating requirements have changed since the last generation.
     */
    public boolean isStale(String scopeId, String issueKey) {
        Optional<QaTestPlan> plan = store.findByKey(issueKey);
        if (plan.isEmpty() || plan.get().kpiSpecHash() == null) return false;

        ScopeRecord scope = scopeStore.findById(scopeId).orElse(null);
        if (scope == null) return false;

        JiraIssueDetail featureDetail = jiraService.fetchIssueDetail(issueKey);
        List<JiraIssueDetail> stories = jiraService.searchStoriesForFeature(
                issueKey, scope.userstoryIssuetype());
        String currentHash = sha256(buildSpecificationsJson(featureDetail, stories));
        return !currentHash.equals(plan.get().kpiSpecHash());
    }

    // ─── Jira sync ────────────────────────────────────────────────────────────

    /**
     * Called automatically at the end of every QA-scope sync.
     * For each FEATURE item in the scope it:
     * <ol>
     *   <li>Calls the Jira REST API to detect "is tested by" links.</li>
     *   <li>If a test plan ticket is found, ensures a {@code qa_test_plans} row exists
     *       and stores the Jira key.</li>
     *   <li>If the test plan ticket has a non-blank description <em>and</em> the plan row
     *       does not already have an {@code analysis_text}, imports the description as the
     *       analysis text so the test plan is immediately usable without an AI call.</li>
     * </ol>
     * Already-populated analysis text is never overwritten — a manual edit or AI generation
     * takes precedence.
     */
    public void syncTestPlansFromJira(String scopeId) {
        List<ScopeItem> features = scopeItemStore.findByScope(scopeId).stream()
                .filter(i -> "FEATURE".equalsIgnoreCase(i.issueType()))
                .collect(Collectors.toList());

        LOG.infof("QaTestPlanService.syncTestPlansFromJira: checking %d feature(s) in scope %s for Jira test plan links",
                features.size(), scopeId);

        int linked = 0;
        int imported = 0;

        for (ScopeItem feature : features) {
            List<JiraService.JiraIssueRef> links = jiraService.fetchIsTestedByLinks(feature.issueKey());

            if (links.isEmpty()) {
                LOG.infof("QaTestPlanService.syncTestPlansFromJira: %s — no 'is tested by' link found",
                        feature.issueKey());
                continue;
            }

            JiraService.JiraIssueRef tpRef = links.get(0);
            LOG.infof("QaTestPlanService.syncTestPlansFromJira: %s → found Jira test plan %s (%s)",
                    feature.issueKey(), tpRef.key(), tpRef.summary());

            // Ensure the qa_test_plans row exists and is linked to this scope
            store.ensureExists(scopeId, feature.issueKey());

            // Persist the Jira test plan ticket key
            store.updateJiraKey(feature.issueKey(), tpRef.key());
            linked++;

            // Fetch the full test plan ticket to get its description
            JiraService.JiraIssueDetail tpDetail = jiraService.fetchIssueDetail(tpRef.key());
            String tpDescription = tpDetail != null ? tpDetail.description() : null;

            if (tpDescription == null || tpDescription.length() <= 200) {
                LOG.infof("QaTestPlanService.syncTestPlansFromJira: %s — Jira test plan %s description too short (%d chars); jira_issue_key stored only",
                        feature.issueKey(), tpRef.key(), tpDescription != null ? tpDescription.length() : 0);
                continue;
            }

            // Only populate analysis if there is nothing there yet
            boolean hasExisting = store.findByKey(feature.issueKey())
                    .map(p -> p.analysisText() != null && !p.analysisText().isBlank())
                    .orElse(false);

            if (hasExisting) {
                LOG.infof("QaTestPlanService.syncTestPlansFromJira: %s — analysis already populated, skipping description import from %s",
                        feature.issueKey(), tpRef.key());
            } else {
                LOG.infof("QaTestPlanService.syncTestPlansFromJira: %s — importing %d-char description from %s as analysis text",
                        feature.issueKey(), tpDescription.length(), tpRef.key());
                store.saveAnalysis(feature.issueKey(), tpDescription, null, null, false);
                imported++;
            }
        }

        LOG.infof("QaTestPlanService.syncTestPlansFromJira: done for scope %s — %d/%d feature(s) have Jira test plan links, %d description(s) imported",
                scopeId, linked, features.size(), imported);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String callClaude(String prompt, String contextId, String jobId, String jobType) {
        String modelName = settings.get("anthropic.model", "claude-sonnet-4-6");
        int maxTokens = Integer.parseInt(settings.get("anthropic.max-tokens", "64000"));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(maxTokens)
                .messages(List.of(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(prompt)
                        .build()))
                .build();

        long startMs = System.currentTimeMillis();
        boolean savedToStore = false;
        try {
            Message response = anthropicClient.messages().create(params);
            long durationMs = System.currentTimeMillis() - startMs;

            String responseText = null;
            for (ContentBlock block : response.content()) {
                if (block.isText()) { responseText = block.asText().text().trim(); break; }
            }

            var usage = response.usage();
            String stopReason = response.stopReason().map(Object::toString).orElse(null);
            boolean truncated = StopReason.MAX_TOKENS.equals(response.stopReason().orElse(null));
            aiCallStore.save(new AiCallRecord(
                    null, jobId, jobType, modelName, null,
                    usage.inputTokens(), usage.outputTokens(),
                    usage.cacheCreationInputTokens().orElse(0L),
                    usage.cacheReadInputTokens().orElse(0L),
                    stopReason, null, durationMs,
                    truncated, truncated ? "Response truncated by max_tokens limit" : null,
                    Instant.now(), prompt, responseText, null, 0));
            savedToStore = true;

            if (truncated) {
                throw new RuntimeException(
                        "Claude response truncated (stop_reason=max_tokens) for [" + contextId
                                + "]. Output tokens used: " + usage.outputTokens()
                                + ". Increase qa.testplan.max-tokens or reduce the analysis size.");
            }

            return responseText;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.errorf("QaTestPlanService: Claude call failed [%s]: %s", contextId, e.getMessage());
            if (!savedToStore) {
                aiCallStore.save(new AiCallRecord(
                        null, jobId, jobType, modelName, null,
                        0, 0, 0, 0, null, null, durationMs,
                        true, e.getMessage(), Instant.now(), prompt, null, null, 0));
            }
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("Claude call failed [" + contextId + "]: " + e.getMessage(), e);
        }
    }

    private String buildStoriesText(List<JiraIssueDetail> stories) {
        if (stories == null || stories.isEmpty()) return "(no child stories found)";
        StringBuilder sb = new StringBuilder();
        for (JiraIssueDetail story : stories) {
            sb.append("Story: ").append(story.key()).append("\n");
            sb.append("Summary: ").append(story.summary()).append("\n");
            sb.append("Status: ").append(story.status() != null ? story.status() : "Unknown").append("\n");
            if (story.description() != null && !story.description().isBlank()) {
                sb.append("Description / Acceptance Criteria:\n").append(story.description()).append("\n");
            }
            if (story.comments() != null && !story.comments().isEmpty()) {
                sb.append("Comments:\n");
                story.comments().forEach(c -> sb.append("- ").append(c).append("\n"));
            }
            sb.append("\n---\n\n");
        }
        return sb.toString();
    }

    private String buildSpecificationsJson(JiraIssueDetail feature, List<JiraIssueDetail> stories) {
        try {
            var spec = mapper.createObjectNode();
            if (feature != null) {
                var f = spec.putObject("feature");
                f.put("key", feature.key());
                f.put("summary", feature.summary());
                f.put("description", feature.description() != null ? feature.description() : "");
                f.put("status", feature.status() != null ? feature.status() : "");
            }
            var storiesArr = spec.putArray("stories");
            if (stories != null) {
                for (JiraIssueDetail s : stories) {
                    var node = storiesArr.addObject();
                    node.put("key", s.key());
                    node.put("summary", s.summary());
                    node.put("description", s.description() != null ? s.description() : "");
                    node.put("status", s.status() != null ? s.status() : "");
                }
            }
            return mapper.writeValueAsString(spec);
        } catch (Exception e) {
            LOG.warnf("QaTestPlanService: failed to build specifications JSON: %s", e.getMessage());
            return "{}";
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String stripFences(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int nl  = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).strip();
        }
        return s;
    }

    private KpiExtract extractKpis(String planJson) {
        KpiExtract kpis = new KpiExtract();
        try {
            JsonNode root = mapper.readTree(planJson);
            JsonNode plan = root.path("featureTestPlan");

            JsonNode section01 = plan.path("section01_executiveSummary");
            kpis.behaviourTcCount   = nodeInt(section01, "totalBehaviourTestConditions");
            kpis.capabilityTcCount  = nodeInt(section01, "totalCapabilityTestConditions");
            kpis.riskCount          = nodeInt(section01, "totalRisksIdentified");
            kpis.openClarifications = nodeInt(section01, "criticalClarificationsNeeded");

            JsonNode childStories = section01.path("childStories");
            if (childStories.isArray()) kpis.storyCount = childStories.size();

            JsonNode risks = plan.path("section04_riskAssessment").path("risks");
            int highRisks = 0;
            if (risks.isArray()) {
                for (JsonNode r : risks) {
                    String level = r.path("riskLevel").asText("");
                    if ("High".equalsIgnoreCase(level) || "Critical".equalsIgnoreCase(level)) highRisks++;
                }
            }
            kpis.highRisks = highRisks;

            JsonNode gaps = plan.path("section08_coverageAnalysis").path("gaps");
            if (gaps.isArray()) kpis.gapsCount = gaps.size();

            JsonNode storyCoverage = plan.path("section08_coverageAnalysis").path("storyCoverageStatus");
            if (storyCoverage.isArray() && !storyCoverage.isEmpty()) {
                int total = storyCoverage.size();
                int fullyCovered = 0;
                for (JsonNode sc : storyCoverage) {
                    if ("Fully Covered".equalsIgnoreCase(sc.path("status").asText(""))) fullyCovered++;
                }
                kpis.coveragePct = BigDecimal.valueOf(100.0 * fullyCovered / total)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }

            kpis.readiness = plan.path("section13_readinessForTestCaseDesign")
                    .path("readinessAssessment").path("overallReadiness").asText(null);

        } catch (Exception e) {
            LOG.warnf("QaTestPlanService: KPI extraction failed: %s", e.getMessage());
        }
        return kpis;
    }

    private static Integer nodeInt(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isNumber() ? n.intValue() : null;
    }

    private static class KpiExtract {
        Integer storyCount;
        Integer behaviourTcCount;
        Integer capabilityTcCount;
        Integer riskCount;
        Integer openClarifications;
        BigDecimal coveragePct;
        Integer highRisks;
        Integer gapsCount;
        String readiness;
    }
}
