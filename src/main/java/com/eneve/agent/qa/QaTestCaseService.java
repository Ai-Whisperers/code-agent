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
import java.util.List;
import java.util.Map;

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

    /**
     * Result summary returned to the job handler.
     */
    public record GenerationResult(int totalCases, int storiesProcessed, int storiesFailed) {}

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
