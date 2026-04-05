package com.eneve.agent.qa;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.jira.JiraService.JiraIssueDetail;
import com.eneve.agent.model.QaTestCase;
import com.eneve.agent.model.QaTestPlan;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Orchestrates AI test case generation for all child stories of a feature test plan.
 *
 * <p>For each child story in the plan's executive summary, calls Claude with the
 * {@code qa.testcase.formatter} prompt, parses the JSON output, extracts KPIs,
 * and persists the test cases via {@link QaTestCaseStore}.
 */
@ApplicationScoped
public class QaTestCaseService {

    private static final Logger LOG = Logger.getLogger(QaTestCaseService.class);

    @Inject QaTestPlanStore planStore;
    @Inject QaTestCaseStore caseStore;
    @Inject JiraService jiraService;
    @Inject PromptTemplateService promptTemplates;
    @Inject AnthropicClient anthropicClient;
    @Inject AiCallStore aiCallStore;
    @Inject SettingsService settings;
    @Inject ObjectMapper mapper;

    /** Result summary returned to the job handler after generation. */
    public record GenerationResult(int totalCases, int storiesProcessed, int storiesFailed) {}

    /** Result summary returned after a "Upload to Jira" sync. */
    public record SyncResult(int created, int updated, int failed) {}

    /** One suggestion returned when confidence is 40-79 (needs manual review). */
    public record MatchSuggestion(String etrKey, String etrTitle,
                                   String matchedAiId, int confidence, String reasoning) {}

    /** Result returned by {@link #importFromJira}. */
    public record ImportResult(int autoLinked, int newInserted, int storiesSearched,
                                List<MatchSuggestion> suggestions) {}

    /**
     * Generates test cases for all child stories of the given plan.
     * Deletes existing test cases for the plan before inserting new ones.
     *
     * @param planId UUID of the qa_test_plans row
     * @return summary of generation results
     */
    public GenerationResult generateForPlan(String planId, String jobId) {
        QaTestPlan plan = planStore.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Test plan not found: " + planId));

        if (plan.planJson() == null || plan.planJson().isBlank()) {
            throw new IllegalStateException("Test plan has no JSON yet — run conversion first for " + plan.issueKey());
        }

        String featureKey = plan.issueKey();
        String featureTitle = extractFeatureTitle(plan.planJson());

        // Extract child stories and context from the plan JSON
        List<String> storyKeys = extractChildStoryKeys(plan.planJson());
        String dependencies = extractDependencies(plan.planJson());
        String riskReferences = extractRiskReferences(plan.planJson());

        if (storyKeys.isEmpty()) {
            LOG.warnf("QaTestCaseService: no child stories found in plan %s", planId);
            return new GenerationResult(0, 0, 0);
        }

        // Delete existing test cases for this plan before regenerating
        caseStore.deleteByPlan(planId);

        int totalCases = 0;
        int storiesProcessed = 0;
        int storiesFailed = 0;

        for (String storyKey : storyKeys) {
            try {
                JiraIssueDetail story = jiraService.fetchIssueDetail(storyKey);
                String storyTitle = story != null ? story.summary() : storyKey;
                String storyDescription = story != null && story.description() != null
                        ? story.description() : "(no description)";

                String prompt = promptTemplates.resolve("qa.testcase.formatter", Map.of(
                        "FEATURE_KEY", featureKey,
                        "FEATURE_TITLE", featureTitle,
                        "STORY_KEY", storyKey,
                        "STORY_TITLE", storyTitle,
                        "STORY_DESCRIPTION", storyDescription,
                        "DEPENDENCIES", dependencies,
                        "RISK_REFERENCES", riskReferences
                ));

                String rawJson = callClaude(prompt, "qa-testcase-" + storyKey, jobId);
                if (rawJson == null) {
                    LOG.warnf("QaTestCaseService: Claude call returned null for story %s — skipping", storyKey);
                    storiesFailed++;
                    continue;
                }

                rawJson = stripFences(rawJson);
                List<QaTestCase> cases = parseTestCases(rawJson, planId, featureKey, storyKey);
                if (!cases.isEmpty()) {
                    caseStore.insertBatch(planId, featureKey, cases);
                    totalCases += cases.size();
                }
                storiesProcessed++;

            } catch (Exception e) {
                LOG.errorf("QaTestCaseService: failed to generate test cases for story %s: %s",
                        storyKey, e.getMessage());
                storiesFailed++;
            }
        }

        return new GenerationResult(totalCases, storiesProcessed, storiesFailed);
    }

    // ─── Import from Jira (ETR) ───────────────────────────────────────────────

    /**
     * Fetches ETR test cases from Jira for all child stories in the plan,
     * then either inserts them directly or runs AI matching against existing
     * AI-generated cases depending on whether the plan already has test cases.
     *
     * @param planId       UUID of the qa_test_plans row
     * @param etrProjectKey Jira project key to search (e.g. "ETR"); falls back to setting
     * @param jobId        job ID for AI call audit trail (may be null)
     * @return summary of import results
     */
    public ImportResult importFromJira(String planId, String etrProjectKey, String jobId) {
        QaTestPlan plan = planStore.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Test plan not found: " + planId));

        // Resolve ETR project key: arg → scope → global setting
        String projectKey = etrProjectKey;
        if (projectKey == null || projectKey.isBlank()) {
            projectKey = settings.get("xray.test-project-key", "");
        }
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalStateException(
                    "ETR project key is not configured — pass it in the request or set xray.test-project-key");
        }

        List<String> storyKeys = plan.planJson() != null
                ? extractChildStoryKeys(plan.planJson()) : List.of();

        // Search against the feature key and all story keys; ETR links may be on the
        // feature rather than individual stories, so we try all candidate keys.
        List<String> searchKeys = new ArrayList<>();
        searchKeys.add(plan.issueKey());          // feature key first
        searchKeys.addAll(storyKeys);             // then each story

        // Collect results, deduplicated by ETR key.
        // Also track which story key (or feature key) each ETR test was found under
        // so we can populate the story_key column in qa_test_cases (NOT NULL).
        Map<String, JiraIssueDetail> etrByKey = new LinkedHashMap<>();
        Map<String, String> etrToStoryKey = new LinkedHashMap<>();
        String featureKey = plan.issueKey();
        for (String candidateKey : searchKeys) {
            String jql = String.format("project = \"%s\" AND issue in linkedIssues(\"%s\")",
                    projectKey, candidateKey);
            try {
                List<JiraIssueDetail> found = jiraService.searchIssues(jql, 50);
                if (found != null) {
                    for (JiraIssueDetail etr : found) {
                        if (etrByKey.putIfAbsent(etr.key(), etr) == null) {
                            // First time we see this ETR key: record the story key that found it.
                            // If the search was on the feature key itself, fall back to the first
                            // child story (or the feature key as a last resort).
                            String assignedStoryKey = candidateKey.equals(featureKey)
                                    ? (storyKeys.isEmpty() ? featureKey : storyKeys.get(0))
                                    : candidateKey;
                            etrToStoryKey.put(etr.key(), assignedStoryKey);
                        }
                    }
                }
                LOG.infof("QaTestCaseService.importFromJira: JQL for %s returned %d results",
                        candidateKey, found != null ? found.size() : 0);
            } catch (Exception e) {
                LOG.warnf("QaTestCaseService.importFromJira: JQL failed for %s: %s", candidateKey, e.getMessage());
            }
        }

        List<JiraIssueDetail> etrTests = new ArrayList<>(etrByKey.values());

        LOG.infof("QaTestCaseService.importFromJira: planId=%s found=%d ETR tests (searched feature + %d stories)",
                planId, etrTests.size(), storyKeys.size());

        List<QaTestCase> aiCases = caseStore.findByPlan(planId);

        if (aiCases.isEmpty()) {
            // No AI cases yet — insert all ETR tests directly
            int inserted = insertEtrTests(planId, featureKey, etrTests, etrToStoryKey);
            return new ImportResult(0, inserted, storyKeys.size(), List.of());
        } else {
            return matchWithAi(planId, featureKey, etrTests, etrToStoryKey, aiCases, storyKeys.size(), jobId);
        }
    }

    /**
     * Calls Claude to match each ETR test against AI-generated cases, then
     * auto-links high-confidence matches, inserts unmatched tests, and returns
     * medium-confidence suggestions for manual review.
     */
    private ImportResult matchWithAi(String planId, String featureKey,
                                      List<JiraIssueDetail> etrTests,
                                      Map<String, String> etrToStoryKey,
                                      List<QaTestCase> aiCases,
                                      int storiesSearched, String jobId) {
        // Build context strings for the prompt
        List<Map<String, Object>> aiJson = new ArrayList<>();
        for (QaTestCase tc : aiCases) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          tc.testCaseId());
            m.put("title",       tc.title());
            m.put("description", tc.description());
            aiJson.add(m);
        }

        List<Map<String, Object>> etrJson = new ArrayList<>();
        for (JiraIssueDetail etr : etrTests) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key",         etr.key());
            m.put("title",       etr.summary());
            m.put("description", etr.description());
            etrJson.add(m);
        }

        String aiCasesJson;
        String etrCasesJson;
        try {
            aiCasesJson  = mapper.writeValueAsString(aiJson);
            etrCasesJson = mapper.writeValueAsString(etrJson);
        } catch (Exception e) {
            LOG.errorf("QaTestCaseService.matchWithAi: failed to serialize JSON: %s", e.getMessage());
            int inserted = insertEtrTests(planId, featureKey, etrTests, etrToStoryKey);
            return new ImportResult(0, inserted, storiesSearched, List.of());
        }

        String prompt = promptTemplates.resolve("qa.testcase.matching", Map.of(
                "AI_CASES_JSON",  aiCasesJson,
                "ETR_CASES_JSON", etrCasesJson
        ));

        String rawResponse = callClaude(prompt, "qa-import-matching-" + planId, jobId);
        if (rawResponse == null) {
            LOG.warnf("QaTestCaseService.matchWithAi: Claude returned null — inserting all ETR tests");
            int inserted = insertEtrTests(planId, featureKey, etrTests, etrToStoryKey);
            return new ImportResult(0, inserted, storiesSearched, List.of());
        }

        // Parse match results
        rawResponse = stripFences(rawResponse);

        // Build a map from etrKey → ETR detail for quick lookup
        Map<String, JiraIssueDetail> etrByKey = new LinkedHashMap<>();
        for (JiraIssueDetail etr : etrTests) etrByKey.put(etr.key(), etr);

        // Build a map from testCaseId → QaTestCase for quick lookup
        Map<String, QaTestCase> aiById = new LinkedHashMap<>();
        for (QaTestCase tc : aiCases) aiById.put(tc.testCaseId(), tc);

        int autoLinked = 0;
        int newInserted = 0;
        List<MatchSuggestion> suggestions = new ArrayList<>();

        try {
            JsonNode results = mapper.readTree(rawResponse);
            if (!results.isArray()) throw new IllegalStateException("Expected JSON array");

            for (JsonNode result : results) {
                String etrKey       = result.path("etrKey").asText(null);
                String matchedAiId  = result.path("matchedAiId").isNull() ? null : result.path("matchedAiId").asText(null);
                int    confidence   = result.path("confidence").asInt(0);
                String reasoning    = result.path("reasoning").asText("");

                if (etrKey == null) continue;
                JiraIssueDetail etr = etrByKey.get(etrKey);
                if (etr == null) continue;

                if (confidence >= 80 && matchedAiId != null) {
                    QaTestCase aiCase = aiById.get(matchedAiId);
                    if (aiCase != null) {
                        String stepsJson;
                        try {
                            stepsJson = mapper.writeValueAsString(
                                    etr.description() != null ? List.of(etr.description()) : List.of());
                        } catch (Exception ex) {
                            stepsJson = "[]";
                        }
                        caseStore.linkJiraKeyAndMatch(aiCase.id(), etr.key(),
                                etr.summary(),
                                etr.description(),
                                stepsJson,
                                "Medium");
                        autoLinked++;
                        etrByKey.remove(etrKey);
                        continue;
                    }
                }

                if (confidence >= 40 && matchedAiId != null) {
                    QaTestCase aiCase = aiById.get(matchedAiId);
                    if (aiCase != null) {
                        suggestions.add(new MatchSuggestion(etrKey, etr.summary(),
                                matchedAiId, confidence, reasoning));
                        etrByKey.remove(etrKey);
                        continue;
                    }
                }

                // confidence < 40 or no match — insert as new row if not already present
                if (caseStore.findByJiraKey(planId, etr.key()).isEmpty()) {
                    insertSingleEtrTest(planId, featureKey, etrToStoryKey.getOrDefault(etr.key(), featureKey), etr);
                    newInserted++;
                }
                etrByKey.remove(etrKey);
            }

            // Any ETR tests not mentioned in the response get inserted as new
            for (JiraIssueDetail remaining : etrByKey.values()) {
                if (caseStore.findByJiraKey(planId, remaining.key()).isEmpty()) {
                    insertSingleEtrTest(planId, featureKey, etrToStoryKey.getOrDefault(remaining.key(), featureKey), remaining);
                    newInserted++;
                }
            }

        } catch (Exception e) {
            LOG.errorf("QaTestCaseService.matchWithAi: failed to parse Claude response: %s", e.getMessage());
            for (JiraIssueDetail etr : etrTests) {
                if (caseStore.findByJiraKey(planId, etr.key()).isEmpty()) {
                    insertSingleEtrTest(planId, featureKey, etrToStoryKey.getOrDefault(etr.key(), featureKey), etr);
                    newInserted++;
                }
            }
        }

        LOG.infof("QaTestCaseService.matchWithAi: planId=%s autoLinked=%d newInserted=%d suggestions=%d",
                planId, autoLinked, newInserted, suggestions.size());
        return new ImportResult(autoLinked, newInserted, storiesSearched, suggestions);
    }

    private int insertEtrTests(String planId, String featureKey,
                               List<JiraIssueDetail> etrTests, Map<String, String> etrToStoryKey) {
        int count = 0;
        for (JiraIssueDetail etr : etrTests) {
            if (caseStore.findByJiraKey(planId, etr.key()).isEmpty()) {
                String storyKey = etrToStoryKey.getOrDefault(etr.key(), featureKey);
                insertSingleEtrTest(planId, featureKey, storyKey, etr);
                count++;
            }
        }
        return count;
    }

    private void insertSingleEtrTest(String planId, String featureKey, String storyKey, JiraIssueDetail etr) {
        String stepsJson;
        try {
            stepsJson = mapper.writeValueAsString(
                    etr.description() != null ? List.of(etr.description()) : List.of());
        } catch (Exception e) {
            stepsJson = "[]";
        }
        QaTestCase tc = new QaTestCase(
                null, planId, featureKey, storyKey,
                etr.key(),
                etr.summary(),
                etr.description(),
                "[]",
                stepsJson,
                "[]",
                "Behaviour",
                "Medium",
                "Open",
                null,
                null, null, null, 0, null, null,
                "manual",
                etr.key(),
                "synced",
                null,
                Instant.now()
        );
        caseStore.insertBatch(planId, featureKey, List.of(tc));
    }

    // ─── Jira sync ────────────────────────────────────────────────────────────

    /**
     * Pushes all test cases for the given plan to Jira.
     *
     * <ul>
     *   <li>If a test case already has a {@code jiraIssueKey} → update the Jira issue.</li>
     *   <li>Otherwise → create a new Jira issue and persist the returned key.</li>
     * </ul>
     *
     * <p>Reads {@code xray.test-project-key} and {@code xray.test-issue-type} from settings.
     *
     * @param planId UUID of the qa_test_plans row
     * @return summary of sync results
     * @throws IllegalStateException if {@code xray.test-project-key} is not configured
     */
    public SyncResult syncToJira(String planId) {
        String projectKey = settings.get("xray.test-project-key", "");
        if (projectKey.isBlank()) {
            throw new IllegalStateException(
                    "xray.test-project-key is not configured — set it in System Settings → Xray Cloud (QA)");
        }
        String issueType = settings.get("xray.test-issue-type", "Test");

        List<QaTestCase> cases = caseStore.findByPlan(planId);
        if (cases.isEmpty()) {
            return new SyncResult(0, 0, 0);
        }

        int created = 0;
        int updated = 0;
        int failed = 0;

        for (QaTestCase tc : cases) {
            try {
                String summary = tc.title();
                String description = buildJiraDescription(tc);

                if (tc.jiraIssueKey() != null && !tc.jiraIssueKey().isBlank()) {
                    jiraService.updateIssueSystem(tc.jiraIssueKey(), summary, description,
                            List.of(), tc.priority());
                    caseStore.markSynced(tc.id());
                    updated++;
                } else {
                    String newKey = jiraService.createIssueSystem(
                            projectKey, summary, description, issueType, null,
                            List.of(), null, tc.priority());
                    if (newKey != null && !newKey.isBlank()) {
                        caseStore.updateJiraKeyAndSync(tc.id(), newKey);
                        created++;
                    } else {
                        LOG.warnf("QaTestCaseService.syncToJira: createIssueSystem returned null for %s", tc.testCaseId());
                        failed++;
                    }
                }
            } catch (Exception e) {
                LOG.errorf("QaTestCaseService.syncToJira: failed for %s: %s", tc.testCaseId(), e.getMessage());
                failed++;
            }
        }

        LOG.infof("QaTestCaseService.syncToJira: planId=%s created=%d updated=%d failed=%d",
                planId, created, updated, failed);
        return new SyncResult(created, updated, failed);
    }

    private String buildJiraDescription(QaTestCase tc) {
        StringBuilder sb = new StringBuilder();
        sb.append("Test Case ID: ").append(tc.testCaseId()).append("\n");
        sb.append("Type: ").append(tc.testCaseType()).append("\n");
        sb.append("Priority: ").append(tc.priority()).append("\n");
        if (tc.estimatedDuration() != null && !tc.estimatedDuration().isBlank()) {
            sb.append("Estimated Duration: ").append(tc.estimatedDuration()).append("\n");
        }
        if (tc.description() != null && !tc.description().isBlank()) {
            sb.append("\nDescription:\n").append(tc.description()).append("\n");
        }

        List<String> preConds = parseJsonArraySafe(tc.preConditions());
        if (!preConds.isEmpty()) {
            sb.append("\nPre-conditions:\n");
            IntStream.range(0, preConds.size())
                    .forEach(i -> sb.append(i + 1).append(". ").append(preConds.get(i)).append("\n"));
        }

        List<String> steps = parseJsonArraySafe(tc.testSteps());
        if (!steps.isEmpty()) {
            sb.append("\nTest Steps:\n");
            IntStream.range(0, steps.size())
                    .forEach(i -> sb.append(i + 1).append(". ").append(steps.get(i)).append("\n"));
        }

        List<String> expected = parseJsonArraySafe(tc.expectedResults());
        if (!expected.isEmpty()) {
            sb.append("\nExpected Results:\n");
            IntStream.range(0, expected.size())
                    .forEach(i -> sb.append(i + 1).append(". ").append(expected.get(i)).append("\n"));
        }

        return sb.toString().trim();
    }

    private List<String> parseJsonArraySafe(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isArray()) return List.of();
            List<String> items = new ArrayList<>();
            for (JsonNode item : node) items.add(item.asText());
            return items;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String callClaude(String prompt, String contextId, String jobId) {
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
        try {
            Message response = anthropicClient.messages().create(params);
            long durationMs = System.currentTimeMillis() - startMs;

            String responseText = null;
            for (ContentBlock block : response.content()) {
                if (block.isText()) { responseText = block.asText().text().trim(); break; }
            }

            var usage = response.usage();
            String stopReason = response.stopReason().map(Object::toString).orElse(null);
            aiCallStore.save(new AiCallRecord(
                    null, jobId, "QA_TESTCASE_GENERATION", modelName, null,
                    usage.inputTokens(), usage.outputTokens(),
                    usage.cacheCreationInputTokens().orElse(0L),
                    usage.cacheReadInputTokens().orElse(0L),
                    stopReason, null, durationMs,
                    false, null, Instant.now(), prompt, responseText));

            return responseText;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            LOG.errorf("QaTestCaseService: Claude call failed [%s]: %s", contextId, e.getMessage());
            aiCallStore.save(new AiCallRecord(
                    null, jobId, "QA_TESTCASE_GENERATION", modelName, null,
                    0, 0, 0, 0, null, null, durationMs,
                    true, e.getMessage(), Instant.now(), prompt, null));
            return null;
        }
    }

    private List<QaTestCase> parseTestCases(String rawJson, String planId, String featureKey, String storyKey) {
        List<QaTestCase> cases = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(rawJson);
            JsonNode testCasesNode = root.path("testCases");
            if (!testCasesNode.isArray()) {
                LOG.warnf("QaTestCaseService: 'testCases' is not an array for story %s", storyKey);
                return cases;
            }
            for (JsonNode tc : testCasesNode) {
                String testCaseId = tc.path("testCaseId").asText(null);
                String title = tc.path("title").asText(null);
                if (testCaseId == null || title == null) continue;

                String preConditionsJson = mapper.writeValueAsString(tc.path("preConditions"));
                String testStepsJson = mapper.writeValueAsString(tc.path("testSteps"));
                String expectedResultsJson = mapper.writeValueAsString(tc.path("expectedResults"));

                int stepCount = tc.path("testSteps").isArray() ? tc.path("testSteps").size() : 0;
                int preconditionCount = tc.path("preConditions").isArray() ? tc.path("preConditions").size() : 0;
                Integer estimatedMins = parseEstimatedMins(tc.path("estimatedDuration").asText(null));

                cases.add(new QaTestCase(
                        null,
                        planId,
                        featureKey,
                        storyKey,
                        testCaseId,
                        title,
                        tc.path("description").asText(null),
                        preConditionsJson,
                        testStepsJson,
                        expectedResultsJson,
                        tc.path("testCaseType").asText("Behaviour"),
                        tc.path("priority").asText("Medium"),
                        "Open",
                        tc.path("estimatedDuration").asText(null),
                        stepCount > 0 ? stepCount : null,
                        estimatedMins,
                        preconditionCount > 0 ? preconditionCount : null,
                        0,
                        null,
                        null,
                        "manual",
                        null,
                        "pending",
                        null,
                        null
                ));
            }
        } catch (Exception e) {
            LOG.errorf("QaTestCaseService: failed to parse test cases JSON for story %s: %s",
                    storyKey, e.getMessage());
        }
        return cases;
    }

    private List<String> extractChildStoryKeys(String planJson) {
        List<String> keys = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(planJson);
            JsonNode childStories = root.path("featureTestPlan")
                    .path("section01_executiveSummary")
                    .path("childStories");
            if (childStories.isArray()) {
                for (JsonNode story : childStories) {
                    String storyId = story.path("storyId").asText(null);
                    if (storyId != null && !storyId.isBlank()) keys.add(storyId);
                }
            }
        } catch (Exception e) {
            LOG.warnf("QaTestCaseService: failed to extract child story keys: %s", e.getMessage());
        }
        return keys;
    }

    private String extractFeatureTitle(String planJson) {
        try {
            JsonNode root = mapper.readTree(planJson);
            return root.path("featureTestPlan")
                    .path("metadata")
                    .path("featureTitle")
                    .asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String extractDependencies(String planJson) {
        try {
            JsonNode root = mapper.readTree(planJson);
            JsonNode gaps = root.path("featureTestPlan")
                    .path("section08_coverageAnalysis")
                    .path("gaps");
            if (!gaps.isArray() || gaps.isEmpty()) return "(none)";
            StringBuilder sb = new StringBuilder();
            for (JsonNode gap : gaps) {
                String gapId = gap.path("gapId").asText(null);
                String desc = gap.path("description").asText(null);
                if (gapId != null) sb.append(gapId);
                if (desc != null) sb.append(": ").append(desc);
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "(none)";
        }
    }

    private String extractRiskReferences(String planJson) {
        try {
            JsonNode root = mapper.readTree(planJson);
            JsonNode risks = root.path("featureTestPlan")
                    .path("section04_riskAssessment")
                    .path("risks");
            if (!risks.isArray() || risks.isEmpty()) return "(none)";
            StringBuilder sb = new StringBuilder();
            for (JsonNode risk : risks) {
                String riskId = risk.path("riskId").asText(null);
                String level = risk.path("riskLevel").asText(null);
                String desc = risk.path("description").asText(null);
                if (riskId != null) sb.append(riskId);
                if (level != null) sb.append(" (").append(level).append(")");
                if (desc != null) sb.append(": ").append(desc);
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "(none)";
        }
    }

    private static Integer parseEstimatedMins(String duration) {
        if (duration == null || duration.isBlank()) return null;
        try {
            String digits = duration.replaceAll("[^0-9]", "").trim();
            return digits.isEmpty() ? null : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripFences(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).strip();
        }
        return s;
    }
}
