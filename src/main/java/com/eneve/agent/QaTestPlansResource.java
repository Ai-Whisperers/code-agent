package com.eneve.agent;

import com.eneve.agent.model.QaTestPlan;
import com.eneve.agent.qa.QaTestPlanStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Global (cross-scope) read endpoints for QA test plans.
 *
 * <pre>
 * GET /qa/test-plans   — all plans, ordered by most-recently generated
 * </pre>
 */
@Path("/qa/test-plans")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class QaTestPlansResource {

    private static final Logger LOG = Logger.getLogger(QaTestPlansResource.class);

    @Inject QaTestPlanStore store;
    @Inject ObjectMapper mapper;

    /**
     * Returns the full test plan record by Jira issue key (used by the TestCasesPage).
     * E.g. {@code GET /qa/test-plans/by-key/AURORA-143}
     */
    @GET
    @Path("/by-key/{issueKey}")
    public Response getByKey(@PathParam("issueKey") String issueKey) {
        return store.findByKey(issueKey)
                .map(p -> Response.ok(buildRow(p)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Test plan not found for issue key: " + issueKey)).build());
    }

    /**
     * Returns the full test plan record by UUID (used by the TestCasesPage to resolve feature key).
     */
    @GET
    @Path("/{planId}")
    public Response getById(@PathParam("planId") String planId) {
        return store.findById(planId)
                .map(p -> Response.ok(buildRow(p)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Test plan not found: " + planId)).build());
    }

    /**
     * Returns a lightweight summary of every test plan, ordered by most-recently
     * generated first. Does not include {@code analysisText}, {@code planJson},
     * or {@code specifications} to keep the payload small.
     */
    @GET
    public Response listAll() {
        List<QaTestPlan> plans = store.findAll();

        List<Map<String, Object>> result = new ArrayList<>(plans.size());
        for (QaTestPlan p : plans) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("issueKey", p.issueKey());
            row.put("generatedAt", p.generatedAt());
            row.put("generatedBy", p.generatedBy());
            row.put("analysisEdited", p.analysisEdited());
            row.put("testPlanStatus", deriveStatus(p));
            row.put("kpiStoryCount", p.kpiStoryCount());
            row.put("kpiBehaviourTcCount", p.kpiBehaviourTcCount());
            row.put("kpiCapabilityTcCount", p.kpiCapabilityTcCount());
            row.put("kpiRiskCount", p.kpiRiskCount());
            row.put("kpiOpenClarifications", p.kpiOpenClarifications());
            row.put("kpiCoveragePct", p.kpiCoveragePct());
            row.put("kpiHighRisks", p.kpiHighRisks());
            row.put("kpiGapsCount", p.kpiGapsCount());
            row.put("kpiReadiness", p.kpiReadiness());
            row.put("kpiRegenCount", p.kpiRegenCount());
            row.put("kpiAnalysisEditCount", p.kpiAnalysisEditCount());
            row.put("kpiDriftDetectedAt", p.kpiDriftDetectedAt());
            result.add(row);
        }
        return Response.ok(result).build();
    }

    private Map<String, Object> buildRow(QaTestPlan p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.id());
        row.put("issueKey", p.issueKey());
        row.put("generatedAt", p.generatedAt());
        row.put("generatedBy", p.generatedBy());
        row.put("analysisEdited", p.analysisEdited());
        row.put("analysisText", p.analysisText());
        row.put("testPlanStatus", deriveStatus(p));
        row.put("isStale", false);
        row.put("planJson", parsePlanJson(p.planJson()));
        row.put("kpiStoryCount", p.kpiStoryCount());
        row.put("kpiBehaviourTcCount", p.kpiBehaviourTcCount());
        row.put("kpiCapabilityTcCount", p.kpiCapabilityTcCount());
        row.put("kpiRiskCount", p.kpiRiskCount());
        row.put("kpiOpenClarifications", p.kpiOpenClarifications());
        row.put("kpiCoveragePct", p.kpiCoveragePct());
        row.put("kpiHighRisks", p.kpiHighRisks());
        row.put("kpiGapsCount", p.kpiGapsCount());
        row.put("kpiReadiness", p.kpiReadiness());
        row.put("kpiRegenCount", p.kpiRegenCount());
        row.put("kpiAnalysisEditCount", p.kpiAnalysisEditCount());
        row.put("kpiDriftDetectedAt", p.kpiDriftDetectedAt());
        row.put("jiraIssueKey", p.jiraIssueKey());
        row.put("xraySyncStatus", p.xraySyncStatus());
        row.put("xraySyncedAt", p.xraySyncedAt());
        return row;
    }

    private JsonNode parsePlanJson(String raw) {
        if (raw == null) return null;
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode inner = root.path("featureTestPlan");
            return inner.isMissingNode() ? root : inner;
        } catch (Exception e) {
            LOG.warnf("QaTestPlansResource.parsePlanJson: failed to parse plan_json — %s", e.getMessage());
            return null;
        }
    }

    private static String deriveStatus(QaTestPlan plan) {
        if (plan.planJson() != null) return "json_ready";
        if (plan.analysisText() != null) return "analysis";
        return "none";
    }
}
