package com.eneve.agent;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.agent.store.ScopeStore;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.model.QaTestPlan;
import com.eneve.agent.model.QaTestPlanAnalysisRequest;
import com.eneve.agent.model.QaTestPlanConversionRequest;
import com.eneve.agent.model.ScopeItem;
import com.eneve.agent.qa.QaTestPlanService;
import com.eneve.agent.qa.QaTestPlanStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * REST endpoints for QA test plan generation and management.
 *
 * <pre>
 * GET  /qa-scope/{scopeId}/features
 * GET  /qa-scope/{scopeId}/features/{issueKey}/test-plan
 * POST /qa-scope/{scopeId}/features/{issueKey}/test-plan/generate-analysis
 * PUT  /qa-scope/{scopeId}/features/{issueKey}/test-plan/analysis
 * POST /qa-scope/{scopeId}/features/{issueKey}/test-plan/generate-json
 * </pre>
 *
 * <p>Test plans are stored per {@code issueKey} — the same feature's plan is shared
 * across all scopes that contain it. The {@code scopeId} path segment is used only
 * to filter the feature list and to link the plan to the scope on first generation.
 */
@Path("/qa-scope/{scopeId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class QaTestPlanResource {

    private static final Logger LOG = Logger.getLogger(QaTestPlanResource.class);
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]+-[0-9]+$");

    @Inject QaTestPlanService service;
    @Inject QaTestPlanStore store;
    @Inject ScopeStore scopeStore;
    @Inject ScopeItemStore scopeItemStore;
    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject AuditService auditService;
    @Inject ObjectMapper mapper;
    @Inject JiraService jiraService;
    @Inject SettingsService settingsService;

    // ─── Feature list ─────────────────────────────────────────────────────────

    /**
     * Returns all FEATURE-type scope items for the given scope, enriched with
     * their test plan status (none / analysis / json_ready / stale).
     */
    @GET
    @Path("/features")
    public Response listFeatures(@PathParam("scopeId") String scopeId) {
        if (scopeStore.findById(scopeId).isEmpty()) {
            return notFound("Scope not found: " + scopeId);
        }

        List<ScopeItem> features = scopeItemStore.findByScope(scopeId).stream()
                .filter(i -> "FEATURE".equalsIgnoreCase(i.issueType()))
                .collect(Collectors.toList());

        // Load all test plan rows linked to this scope in one query
        Map<String, QaTestPlan> plansByKey = store.findAllByScope(scopeId).stream()
                .collect(Collectors.toMap(QaTestPlan::issueKey, p -> p));

        // Count child stories per feature
        Map<String, Long> storyCountByFeature = scopeItemStore.findByScope(scopeId).stream()
                .filter(i -> "USERSTORY".equalsIgnoreCase(i.issueType()) && i.parentKey() != null)
                .collect(Collectors.groupingBy(ScopeItem::parentKey, Collectors.counting()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (ScopeItem feature : features) {
            QaTestPlan plan = plansByKey.get(feature.issueKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("issueKey", feature.issueKey());
            row.put("summary", feature.summary());
            row.put("jiraStatus", feature.jiraStatus());
            row.put("childStoryCount", storyCountByFeature.getOrDefault(feature.issueKey(), 0L).intValue());
            row.put("testPlanStatus", deriveStatus(plan));
            row.put("generatedAt", plan != null ? plan.generatedAt() : null);
            row.put("analysisEdited", plan != null && plan.analysisEdited());
            row.put("jiraIssueKey", plan != null ? plan.jiraIssueKey() : null);
            row.put("kpiStoryCount", plan != null ? plan.kpiStoryCount() : null);
            row.put("kpiBehaviourTcCount", plan != null ? plan.kpiBehaviourTcCount() : null);
            row.put("kpiCapabilityTcCount", plan != null ? plan.kpiCapabilityTcCount() : null);
            row.put("kpiRiskCount", plan != null ? plan.kpiRiskCount() : null);
            row.put("kpiOpenClarifications", plan != null ? plan.kpiOpenClarifications() : null);
            row.put("kpiReadiness", plan != null ? plan.kpiReadiness() : null);
            result.add(row);
        }
        return Response.ok(result).build();
    }

    // ─── Test plan record ─────────────────────────────────────────────────────

    /**
     * Returns the full test plan record for a feature, including analysis text,
     * plan JSON, KPIs, and a computed staleness flag.
     */
    @GET
    @Path("/features/{issueKey}/test-plan")
    public Response getTestPlan(@PathParam("scopeId") String scopeId,
                                @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");

        return service.getTestPlan(issueKey)
                .map(plan -> {
                    Map<String, Object> resp = toResponseMap(plan);
                    resp.put("isStale", service.isStale(scopeId, issueKey));
                    return Response.ok(resp).build();
                })
                .orElse(notFound("No test plan found for " + issueKey));
    }

    // ─── Generate analysis ────────────────────────────────────────────────────

    /**
     * Submits a {@code QA_TESTPLAN_ANALYSIS} job and returns immediately with the job ID.
     */
    @POST
    @Path("/features/{issueKey}/test-plan/generate-analysis")
    public Response generateAnalysis(@PathParam("scopeId") String scopeId,
                                     @PathParam("issueKey") String issueKey,
                                     @Context SecurityContext sc) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        if (scopeStore.findById(scopeId).isEmpty()) return notFound("Scope not found: " + scopeId);

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, new QaTestPlanAnalysisRequest(scopeId, issueKey));
        jobStore.put(job);
        jobQueue.submit(job);

        auditService.log("QA", "TESTPLAN_ANALYSIS_QUEUED", "qa_test_plan", issueKey,
                Map.of("jobId", jobId, "scopeId", scopeId));
        LOG.infof("QaTestPlanResource: submitted QA_TESTPLAN_ANALYSIS job %s for %s/%s", jobId, scopeId, issueKey);
        return Response.accepted(Map.of("jobId", jobId, "issueKey", issueKey)).build();
    }

    // ─── Update analysis (manual edit) ────────────────────────────────────────

    /**
     * Saves a manual edit to the analysis text.
     * Sets {@code analysis_edited = true} and increments {@code kpi_analysis_edit_count}.
     */
    @PUT
    @Path("/features/{issueKey}/test-plan/analysis")
    public Response updateAnalysis(@PathParam("scopeId") String scopeId,
                                   @PathParam("issueKey") String issueKey,
                                   Map<String, String> body) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        String analysisText = body != null ? body.get("analysisText") : null;
        if (analysisText == null || analysisText.isBlank()) {
            return badRequest("analysisText is required");
        }
        try {
            QaTestPlan plan = service.updateAnalysis(issueKey, analysisText);
            auditService.log("QA", "TESTPLAN_ANALYSIS_EDITED", "qa_test_plan", issueKey,
                    Map.of("scopeId", scopeId, "editCount", String.valueOf(plan.kpiAnalysisEditCount())));
            return Response.ok(toResponseMap(plan)).build();
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            LOG.errorf("QaTestPlanResource.updateAnalysis: %s / %s: %s", scopeId, issueKey, e.getMessage());
            return serverError("Failed to update analysis: " + e.getMessage());
        }
    }

    // ─── Update Jira issue key ────────────────────────────────────────────────

    /**
     * Sets (or clears) the optional Jira issue key on a test plan for Xray sync.
     * Body: {@code {"jiraIssueKey": "PROJ-99"}} or {@code {"jiraIssueKey": null}} to clear.
     */
    @PUT
    @Path("/features/{issueKey}/test-plan/jira-key")
    public Response updateJiraKey(@PathParam("scopeId") String scopeId,
                                  @PathParam("issueKey") String issueKey,
                                  Map<String, String> body) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        String jiraKey = body != null ? body.get("jiraIssueKey") : null;
        try {
            store.findByKey(issueKey)
                    .orElseThrow(() -> new IllegalArgumentException("No test plan found for " + issueKey));
            store.updateJiraKey(issueKey, jiraKey);
            auditService.log("QA", "TESTPLAN_JIRA_KEY_UPDATED", "qa_test_plan", issueKey,
                    Map.of("scopeId", scopeId, "jiraIssueKey", jiraKey != null ? jiraKey : ""));
            return service.getTestPlan(issueKey)
                    .map(plan -> Response.ok(toResponseMap(plan)).build())
                    .orElse(notFound("No test plan found for " + issueKey));
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            LOG.errorf("QaTestPlanResource.updateJiraKey: %s / %s: %s", scopeId, issueKey, e.getMessage());
            return serverError("Failed to update Jira key: " + e.getMessage());
        }
    }

    // ─── Export to Jira ───────────────────────────────────────────────────────

    /**
     * Creates or updates a Jira QA issue for this test plan.
     *
     * <ul>
     *   <li>If the plan already has a {@code jiraIssueKey}, the existing Jira issue is updated
     *       with the latest summary and description.</li>
     *   <li>Otherwise a new Jira issue is created in {@code projectKey} and linked to the
     *       feature via a "Tests" issue link. The new key is persisted to the test plan.</li>
     * </ul>
     *
     * <p>Request body (all fields optional):
     * <pre>{@code
     * {
     *   "projectKey": "QA",       // required when creating a new issue
     *   "issueType": "Story",     // optional, defaults to "Story"
     *   "linkType": "Tests"       // optional issue-link type name, defaults to "Tests"
     * }
     * }</pre>
     */
    @POST
    @Path("/features/{issueKey}/test-plan/export-to-jira")
    public Response exportToJira(@PathParam("scopeId") String scopeId,
                                 @PathParam("issueKey") String issueKey,
                                 Map<String, String> body) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");

        QaTestPlan plan = store.findByKey(issueKey)
                .orElse(null);
        if (plan == null || plan.planJson() == null) {
            return badRequest("No generated test plan JSON found for " + issueKey + " — generate JSON first");
        }

        if (!jiraService.isConfigured()) {
            return serverError("Jira is not configured — check system settings");
        }

        String projectKey  = body != null ? body.getOrDefault("projectKey", "") : "";
        String issueType   = body != null ? body.getOrDefault("issueType", "Story") : "Story";
        if (issueType == null || issueType.isBlank()) issueType = "Story";
        // Prefer explicit request body, fall back to system setting, then default "Tests"
        String linkTypeFromBody = body != null ? body.get("linkType") : null;
        String linkType = (linkTypeFromBody != null && !linkTypeFromBody.isBlank())
                ? linkTypeFromBody
                : settingsService.get("jira.qa-link-type", "tests");

        String featureSummary = jiraService.fetchIssueSummary(issueKey);
        String planTitle = "QA Test Plan: " + (featureSummary != null ? featureSummary : issueKey) + " [" + issueKey + "]";
        String description = buildJiraDescription(plan, issueKey);

        try {
            if (plan.jiraIssueKey() != null && !plan.jiraIssueKey().isBlank()) {
                // Update existing issue
                jiraService.updateIssueSystem(plan.jiraIssueKey(), planTitle, description);
                auditService.log("QA", "TESTPLAN_JIRA_UPDATED", "qa_test_plan", issueKey,
                        Map.of("scopeId", scopeId, "jiraIssueKey", plan.jiraIssueKey()));
                LOG.infof("QaTestPlanResource.exportToJira: updated Jira issue %s for %s", plan.jiraIssueKey(), issueKey);

                return service.getTestPlan(issueKey)
                        .map(updated -> {
                            Map<String, Object> resp = new LinkedHashMap<>();
                            resp.put("jiraIssueKey", updated.jiraIssueKey());
                            resp.put("action", "updated");
                            resp.put("plan", toResponseMap(updated));
                            return Response.ok(resp).build();
                        })
                        .orElse(notFound("Test plan not found after update"));
            } else {
                // Create new issue
                if (projectKey == null || projectKey.isBlank()) {
                    return badRequest("projectKey is required when creating a new Jira issue");
                }
                String newKey = jiraService.createIssueSystem(projectKey, planTitle, description, issueType, null);
                if (newKey == null || newKey.isBlank()) {
                    return serverError("Jira issue creation failed — check Jira configuration and project key");
                }

                // Link: feature "is tested by" QA plan (inward=feature, outward=QA ticket)
                // linkType read from system setting "jira.qa-link-type" (default "Tests")
                String linkError = jiraService.createIssueLink(linkType, issueKey, newKey);
                if (linkError != null) {
                    LOG.warnf("QaTestPlanResource.exportToJira: link '%s' failed for %s -> %s: %s",
                            linkType, issueKey, newKey, linkError);
                }

                // Persist the new Jira key regardless of whether the link succeeded
                store.updateJiraKey(issueKey, newKey);
                auditService.log("QA", "TESTPLAN_JIRA_CREATED", "qa_test_plan", issueKey,
                        Map.of("scopeId", scopeId, "jiraIssueKey", newKey, "projectKey", projectKey));
                LOG.infof("QaTestPlanResource.exportToJira: created Jira issue %s for %s", newKey, issueKey);

                final String finalLinkError = linkError;
                final String finalLinkType = linkType;
                return service.getTestPlan(issueKey)
                        .map(updated -> {
                            Map<String, Object> resp = new LinkedHashMap<>();
                            resp.put("jiraIssueKey", newKey);
                            resp.put("action", "created");
                            resp.put("plan", toResponseMap(updated));
                            if (finalLinkError != null) {
                                resp.put("linkWarning", "Could not create '" + finalLinkType + "' link between "
                                        + issueKey + " and " + newKey + ": " + finalLinkError
                                        + ". Check the 'jira.qa-link-type' system setting.");
                            }
                            return Response.ok(resp).build();
                        })
                        .orElse(notFound("Test plan not found after creation"));
            }
        } catch (Exception e) {
            LOG.errorf("QaTestPlanResource.exportToJira: %s / %s: %s", scopeId, issueKey, e.getMessage());
            return serverError("Export to Jira failed: " + e.getMessage());
        }
    }

    /**
     * Builds a rich markdown description for the Jira QA issue.
     * Includes the feature overview, test approach, and a KPI summary table.
     * The {@link com.eneve.agent.jira.AdfBuilder} will convert the markdown table
     * to a native ADF table when the description is posted to Jira.
     */
    private String buildJiraDescription(QaTestPlan plan, String featureKey) {
        StringBuilder sb = new StringBuilder();

        // Pull rich content from planJson when available
        String featureOverview = null;
        String testApproach = null;
        String methodology = null;
        if (plan.planJson() != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(plan.planJson());
                // planJson may still be wrapped in { "featureTestPlan": {...} } at this point
                com.fasterxml.jackson.databind.JsonNode inner = root.path("featureTestPlan");
                com.fasterxml.jackson.databind.JsonNode planNode = inner.isMissingNode() ? root : inner;
                featureOverview = planNode.path("section01_executiveSummary").path("featureOverview").asText(null);
                testApproach    = planNode.path("section01_executiveSummary").path("testApproach").asText(null);
                methodology     = planNode.path("metadata").path("methodology").asText(null);
            } catch (Exception ignored) {
                // Fall through to KPI-only description
            }
        }

        sb.append("## QA Test Plan — ").append(featureKey).append("\n\n");

        if (featureOverview != null && !featureOverview.isBlank()) {
            sb.append("### Feature Overview\n\n");
            sb.append(featureOverview).append("\n\n");
        }

        if (testApproach != null && !testApproach.isBlank()) {
            sb.append("### Test Approach\n\n");
            sb.append(testApproach).append("\n\n");
        }

        sb.append("### Test Coverage Summary\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|---|---|\n");
        if (plan.kpiStoryCount() != null)         sb.append("| Stories | ").append(plan.kpiStoryCount()).append(" |\n");
        if (plan.kpiBehaviourTcCount() != null)   sb.append("| Behaviour Test Conditions | ").append(plan.kpiBehaviourTcCount()).append(" |\n");
        if (plan.kpiCapabilityTcCount() != null)  sb.append("| Capability Test Conditions | ").append(plan.kpiCapabilityTcCount()).append(" |\n");
        if (plan.kpiRiskCount() != null)          sb.append("| Risks Identified | ").append(plan.kpiRiskCount()).append(" |\n");
        if (plan.kpiHighRisks() != null)          sb.append("| High Risks | ").append(plan.kpiHighRisks()).append(" |\n");
        if (plan.kpiCoveragePct() != null)        sb.append("| Coverage | ").append(plan.kpiCoveragePct()).append("% |\n");
        if (plan.kpiOpenClarifications() != null) sb.append("| Open Clarifications | ").append(plan.kpiOpenClarifications()).append(" |\n");
        if (plan.kpiGapsCount() != null)          sb.append("| Coverage Gaps | ").append(plan.kpiGapsCount()).append(" |\n");
        if (plan.kpiReadiness() != null)          sb.append("| Readiness | ").append(plan.kpiReadiness()).append(" |\n");
        if (methodology != null && !methodology.isBlank()) sb.append("| Methodology | ").append(methodology).append(" |\n");

        if (plan.generatedAt() != null) {
            sb.append("\n_Generated: ").append(plan.generatedAt()).append("_\n");
        }
        return sb.toString();
    }

    // ─── Generate JSON ────────────────────────────────────────────────────────

    /**
     * Submits a {@code QA_TESTPLAN_CONVERSION} job and returns immediately with the job ID.
     */
    @POST
    @Path("/features/{issueKey}/test-plan/generate-json")
    public Response generateJson(@PathParam("scopeId") String scopeId,
                                 @PathParam("issueKey") String issueKey,
                                 @Context SecurityContext sc) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");

        if (store.findByKey(issueKey).map(p -> p.analysisText() == null).orElse(true)) {
            return badRequest("No analysis text found for " + issueKey + " — run generate-analysis first");
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, new QaTestPlanConversionRequest(issueKey));
        jobStore.put(job);
        jobQueue.submit(job);

        auditService.log("QA", "TESTPLAN_CONVERSION_QUEUED", "qa_test_plan", issueKey,
                Map.of("jobId", jobId, "scopeId", scopeId));
        LOG.infof("QaTestPlanResource: submitted QA_TESTPLAN_CONVERSION job %s for %s/%s", jobId, scopeId, issueKey);
        return Response.accepted(Map.of("jobId", jobId, "issueKey", issueKey)).build();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String deriveStatus(QaTestPlan plan) {
        if (plan == null) return "none";
        if (plan.planJson() != null) return "json_ready";
        if (plan.analysisText() != null) return "analysis";
        return "none";
    }

    private Map<String, Object> toResponseMap(QaTestPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", plan.id());
        m.put("issueKey", plan.issueKey());
        m.put("analysisText", plan.analysisText());
        m.put("analysisEdited", plan.analysisEdited());
        m.put("planJson", parsePlanJson(plan.planJson()));
        m.put("generatedAt", plan.generatedAt());
        m.put("generatedBy", plan.generatedBy());
        m.put("testPlanStatus", deriveStatus(plan));
        m.put("kpiStoryCount", plan.kpiStoryCount());
        m.put("kpiBehaviourTcCount", plan.kpiBehaviourTcCount());
        m.put("kpiCapabilityTcCount", plan.kpiCapabilityTcCount());
        m.put("kpiRiskCount", plan.kpiRiskCount());
        m.put("kpiOpenClarifications", plan.kpiOpenClarifications());
        m.put("kpiCoveragePct", plan.kpiCoveragePct());
        m.put("kpiHighRisks", plan.kpiHighRisks());
        m.put("kpiGapsCount", plan.kpiGapsCount());
        m.put("kpiReadiness", plan.kpiReadiness());
        m.put("kpiSpecHash", plan.kpiSpecHash());
        m.put("kpiDriftDetectedAt", plan.kpiDriftDetectedAt());
        m.put("kpiRegenCount", plan.kpiRegenCount());
        m.put("kpiAnalysisEditCount", plan.kpiAnalysisEditCount());
        m.put("jiraIssueKey", plan.jiraIssueKey());
        m.put("xraySyncStatus", plan.xraySyncStatus());
        m.put("xraySyncedAt", plan.xraySyncedAt());
        return m;
    }

    /**
     * Parses the raw {@code plan_json} string from the DB and returns the inner
     * {@code featureTestPlan} node as a {@link JsonNode} so that Jackson serialises
     * it as an embedded JSON object (not a double-encoded string).
     *
     * <p>The formatter prompt wraps the plan in a {@code {"featureTestPlan":{...}}} envelope.
     * The frontend type {@code FeatureTestPlan} and the UI components expect the inner
     * object directly, so we unwrap the envelope here.
     *
     * <p>Falls back gracefully:
     * <ul>
     *   <li>If the input is {@code null}, returns {@code null}.</li>
     *   <li>If the root has no {@code featureTestPlan} key (e.g. old/malformed data),
     *       returns the whole root node so existing content is still visible.</li>
     *   <li>If parsing fails, returns {@code null} and logs a warning.</li>
     * </ul>
     */
    private JsonNode parsePlanJson(String raw) {
        if (raw == null) return null;
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode inner = root.path("featureTestPlan");
            return inner.isMissingNode() ? root : inner;
        } catch (Exception e) {
            LOG.warnf("QaTestPlanResource.parsePlanJson: failed to parse plan_json — %s", e.getMessage());
            return null;
        }
    }

    private static boolean isValidIssueKey(String key) {
        return key != null && ISSUE_KEY_PATTERN.matcher(key).matches();
    }

    private static Response notFound(String msg) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", msg)).build();
    }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg)).build();
    }

    private static Response serverError(String msg) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", msg)).build();
    }
}
