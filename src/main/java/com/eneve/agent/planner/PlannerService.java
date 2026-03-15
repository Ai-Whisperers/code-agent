package com.eneve.agent.planner;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.Usage;
import com.eneve.agent.agent.AiCallRecord;
import com.eneve.agent.agent.AiCallStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Generates an AI-powered execution plan from a specification.
 * Uses a single Claude call (no tool-use loop) to decompose a spec into
 * ordered phases and steps that can be submitted to the job queue.
 * Follows the same single-call pattern as PrSummaryGenerator.
 */
@ApplicationScoped
public class PlannerService {

    private static final Logger LOG = Logger.getLogger(PlannerService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "anthropic.api.key")
    String apiKey;

    @ConfigProperty(name = "anthropic.model", defaultValue = "claude-sonnet-4-20250514")
    String modelName;

    @ConfigProperty(name = "planner.max-tokens", defaultValue = "8192")
    long maxTokens;

    @Inject
    AiCallStore aiCallStore;

    /**
     * Generate an execution plan from a specification text.
     *
     * @param specText     the full specification (Jira ticket text, free text, etc.)
     * @param repoUrl      the target repository URL
     * @param targetBranch the base branch (e.g. "main")
     * @param sourceType   JIRA, FREE_TEXT, or URL
     * @param sourceRef    JIRA key, URL, or null
     * @return a DRAFT ExecutionPlan with phases and steps, or null on failure
     */
    public ExecutionPlan generatePlan(String specText, String repoUrl,
                                      String targetBranch, String sourceType, String sourceRef) {
        if (specText == null || specText.isBlank()) {
            LOG.warn("PlannerService: empty spec text, cannot generate plan");
            return null;
        }

        String prompt = buildPrompt(specText, repoUrl, targetBranch);
        String planId = "plan-" + UUID.randomUUID();

        String responseText = callClaude(prompt, planId);
        if (responseText == null) {
            return null;
        }

        PlanData planData = parsePlanData(responseText, planId);
        if (planData == null) {
            return null;
        }

        String title = deriveTitle(specText, sourceRef);
        Instant now = Instant.now();

        return new ExecutionPlan(
                planId,
                PlanStatus.DRAFT.name(),
                sourceType != null ? sourceType : "FREE_TEXT",
                sourceRef,
                repoUrl,
                targetBranch != null ? targetBranch : "main",
                title,
                planData,
                now,
                now,
                null,
                null,
                null
        );
    }

    private String buildPrompt(String specText, String repoUrl, String targetBranch) {
        return """
                You are a software project planning assistant. Your job is to analyze a specification
                and decompose it into a structured, phased execution plan for an automated coding agent.

                The agent supports the following job types:
                - FIX: Make code changes (implement features, fix bugs, refactor). Used for creating or \
                modifying source files, migrations, configuration.
                - GENERATE_TESTS: Generate unit and integration tests for specified source files or classes.
                - GENERATE_DOCS: Generate or update documentation (README, API docs, architecture docs).
                - REVIEW: AI code review of a pull request — checks security, design, quality, tests.

                Repository: %s
                Target branch: %s

                ## Specification
                %s

                ## Instructions
                Decompose the specification into an ordered list of phases. Each phase contains one or
                more steps. Steps within a phase can run in parallel. Phases run sequentially.

                Design principles:
                - Phase 1 should be the core implementation (FIX jobs) — schema/migrations first, then \
                stores/services, then webhooks/APIs. Group tightly related files into a single step.
                - Phase 2 should be unit tests (GENERATE_TESTS jobs) targeting the new/changed classes.
                - Phase 3 should be documentation (GENERATE_DOCS) if the spec involves public APIs or \
                significant architectural changes.
                - Final phase should be a code review (REVIEW) of the resulting pull request.
                - Mark phases with "gateOnSuccess": true when a failure in that phase should block \
                subsequent phases (e.g. implementation must succeed before generating tests).
                - Each step's "prompt" field must be a detailed, self-contained instruction that the \
                agent can execute without any additional context — include file paths, class names, \
                method signatures, and patterns to follow where known.
                - For FIX steps, include relevant context like "follow the pattern of X class", \
                "use the existing Y service for Z", "create in package com.example.feature".
                - For GENERATE_TESTS steps, list the specific source files to test in the prompt \
                and in the "sourceFiles" param as a comma-separated string.
                - Assign each step a short unique stepId (kebab-case, e.g. "v13-migration", \
                "settings-store", "ticket-analyzer").
                - Assign each step a concise human-readable title (one line).
                - Set "status" to "PENDING" for all steps.
                - Set "jobId" to null for all steps.

                ## Output Format
                Return ONLY a JSON object (no markdown fences, no explanation):
                {
                  "phases": [
                    {
                      "order": 1,
                      "name": "Phase name",
                      "gateOnSuccess": true,
                      "steps": [
                        {
                          "stepId": "unique-id",
                          "jobType": "FIX",
                          "title": "Short title",
                          "prompt": "Detailed instruction for the agent...",
                          "status": "PENDING",
                          "jobId": null,
                          "params": {
                            "branchName": "agent/plan-PLACEHOLDER-step-unique-id"
                          }
                        }
                      ]
                    }
                  ]
                }

                For GENERATE_TESTS steps, include a "sourceFiles" key in params:
                  "params": { "sourceFiles": "src/main/.../Foo.java,src/main/.../Bar.java" }

                Rules:
                - Output ONLY the JSON object. No markdown, no explanation, no fences.
                - Every step must have all fields: stepId, jobType, title, prompt, status, jobId, params.
                - params must always be an object (use {} if nothing specific is needed).
                - The "branchName" in params should use a descriptive slug derived from the step.
                - Keep prompts comprehensive but focused — the agent will use them as its primary context.
                """.formatted(
                repoUrl != null ? repoUrl : "(unspecified)",
                targetBranch != null ? targetBranch : "main",
                specText
        );
    }

    private String callClaude(String prompt, String planId) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(maxTokens)
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
            response = client.messages().create(params);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            aiCallStore.save(new AiCallRecord(
                    null, planId, "PLAN", modelName, null,
                    0, 0, 0, 0,
                    null, null, durationMs,
                    true, e.getMessage(), Instant.now()));
            LOG.errorf("PlannerService Claude call failed: %s", e.getMessage());
            return null;
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

        Usage usage = response.usage();
        String stopReason = response.stopReason().map(Object::toString).orElse(null);
        aiCallStore.save(new AiCallRecord(
                null, planId, "PLAN", modelName, null,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                stopReason, null, durationMs,
                false, null, Instant.now()));

        LOG.infof("Plan generated — tokens: in=%d, out=%d, duration=%dms",
                usage.inputTokens(), usage.outputTokens(), durationMs);

        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                return block.asText().text().trim();
            }
        }
        return null;
    }

    private PlanData parsePlanData(String responseText, String planId) {
        String cleaned = responseText.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
            }
        }

        try {
            JsonNode root = MAPPER.readTree(cleaned);
            JsonNode phasesNode = root.path("phases");
            if (!phasesNode.isArray()) {
                LOG.warnf("PlannerService: response missing 'phases' array for plan %s", planId);
                return null;
            }

            var phases = new java.util.ArrayList<PlanPhase>();
            for (JsonNode phaseNode : phasesNode) {
                int order = phaseNode.path("order").asInt(phases.size() + 1);
                String name = phaseNode.path("name").asText("Phase " + order);
                boolean gate = phaseNode.path("gateOnSuccess").asBoolean(true);

                var steps = new java.util.ArrayList<PlanStep>();
                for (JsonNode stepNode : phaseNode.path("steps")) {
                    Map<String, String> stepParams = new java.util.LinkedHashMap<>();
                    JsonNode paramsNode = stepNode.path("params");
                    if (paramsNode.isObject()) {
                        paramsNode.fields().forEachRemaining(entry ->
                                stepParams.put(entry.getKey(), entry.getValue().asText("")));
                    }

                    String jobIdValue = stepNode.path("jobId").isNull() ? null : stepNode.path("jobId").asText(null);

                    steps.add(new PlanStep(
                            stepNode.path("stepId").asText("step-" + (steps.size() + 1)),
                            stepNode.path("jobType").asText("FIX"),
                            stepNode.path("title").asText(""),
                            stepNode.path("prompt").asText(""),
                            stepNode.path("status").asText("PENDING"),
                            jobIdValue,
                            stepParams
                    ));
                }
                phases.add(new PlanPhase(order, name, gate, steps));
            }

            LOG.infof("PlannerService: parsed plan with %d phases for %s", phases.size(), planId);
            return new PlanData(phases);

        } catch (Exception e) {
            LOG.errorf("PlannerService: failed to parse plan JSON for %s: %s", planId, e.getMessage());
            return null;
        }
    }

    private String deriveTitle(String specText, String sourceRef) {
        if (sourceRef != null && !sourceRef.isBlank()) {
            return sourceRef;
        }
        String firstLine = specText.lines().findFirst().orElse("").strip();
        return firstLine.length() > 80 ? firstLine.substring(0, 77) + "..." : firstLine;
    }
}
