package com.eneve.agent;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.QaTestCase;
import com.eneve.agent.model.QaTestCaseGenerationRequest;
import com.eneve.agent.qa.QaTestCaseStore;
import com.eneve.agent.qa.QaTestPlanStore;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

/**
 * REST endpoints for QA test case management.
 *
 * <pre>
 * POST   /qa/test-plans/{planId}/test-cases/generate         — submit QA_TESTCASE_GENERATION job
 * GET    /qa/test-plans/{planId}/test-cases                  — list all test cases for a plan
 * GET    /qa/test-plans/{planId}/test-cases/story/{storyKey} — list test cases for a story
 * PUT    /qa/test-plans/{planId}/test-cases/{id}/status      — update test case status
 * PUT    /qa/test-plans/{planId}/test-cases/{id}/jira-key    — set Jira issue key
 * DELETE /qa/test-plans/{planId}/test-cases                  — delete all test cases for a plan
 * </pre>
 */
@Path("/qa/test-plans/{planId}/test-cases")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class QaTestCaseResource {

    private static final Logger LOG = Logger.getLogger(QaTestCaseResource.class);

    @Inject QaTestCaseStore caseStore;
    @Inject QaTestPlanStore planStore;
    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject AuditService auditService;

    // ─── Generate (async job) ─────────────────────────────────────────────────

    /**
     * Submits a {@code QA_TESTCASE_GENERATION} job for the given plan.
     * Returns immediately with the new job ID.
     */
    @POST
    @Path("/generate")
    public Response generate(@PathParam("planId") String planId,
                             @Context SecurityContext sc) {
        var plan = planStore.findById(planId);
        if (plan.isEmpty()) return notFound("Test plan not found: " + planId);
        if (plan.get().planJson() == null) {
            return badRequest("Test plan has no JSON yet — run conversion first");
        }

        String jobId = UUID.randomUUID().toString();
        String issueKey = plan.get().issueKey();
        String submittedBy = sc != null && sc.getUserPrincipal() != null
                ? sc.getUserPrincipal().getName() : "system";

        JobRecord job = new JobRecord(jobId, new QaTestCaseGenerationRequest(planId, issueKey));
        jobStore.put(job);
        jobQueue.submit(job);

        auditService.log("QA", "TESTCASE_GENERATION_QUEUED", "qa_test_plan", planId,
                Map.of("jobId", jobId, "issueKey", issueKey));
        LOG.infof("QaTestCaseResource: submitted QA_TESTCASE_GENERATION job %s for plan %s (%s) by %s",
                jobId, planId, issueKey, submittedBy);

        return Response.accepted(Map.of("jobId", jobId, "issueKey", issueKey)).build();
    }

    // ─── List test cases ──────────────────────────────────────────────────────

    @GET
    public Response listByPlan(@PathParam("planId") String planId) {
        if (planStore.findById(planId).isEmpty()) return notFound("Test plan not found: " + planId);
        List<Map<String, Object>> result = caseStore.findByPlan(planId).stream()
                .map(this::toResponseMap)
                .toList();
        return Response.ok(result).build();
    }

    @GET
    @Path("/story/{storyKey}")
    public Response listByStory(@PathParam("planId") String planId,
                                @PathParam("storyKey") String storyKey) {
        List<Map<String, Object>> result = caseStore.findByStory(planId, storyKey).stream()
                .map(this::toResponseMap)
                .toList();
        return Response.ok(result).build();
    }

    // ─── Update status ────────────────────────────────────────────────────────

    @PUT
    @Path("/{id}/status")
    public Response updateStatus(@PathParam("planId") String planId,
                                 @PathParam("id") String id,
                                 Map<String, String> body) {
        String status = body != null ? body.get("status") : null;
        if (status == null || status.isBlank()) return badRequest("status is required");
        try {
            caseStore.updateStatus(id, status);
            auditService.log("QA", "TESTCASE_STATUS_UPDATED", "qa_test_case", id,
                    Map.of("planId", planId, "status", status));
            return Response.ok(Map.of("id", id, "status", status)).build();
        } catch (Exception e) {
            LOG.errorf("QaTestCaseResource.updateStatus: %s: %s", id, e.getMessage());
            return serverError("Failed to update status: " + e.getMessage());
        }
    }

    // ─── Update Jira key ──────────────────────────────────────────────────────

    @PUT
    @Path("/{id}/jira-key")
    public Response updateJiraKey(@PathParam("planId") String planId,
                                  @PathParam("id") String id,
                                  Map<String, String> body) {
        String jiraKey = body != null ? body.get("jiraIssueKey") : null;
        try {
            caseStore.updateJiraKey(id, jiraKey);
            auditService.log("QA", "TESTCASE_JIRA_KEY_UPDATED", "qa_test_case", id,
                    Map.of("planId", planId, "jiraIssueKey", jiraKey != null ? jiraKey : ""));
            return Response.ok(Map.of("id", id, "jiraIssueKey", jiraKey != null ? jiraKey : "")).build();
        } catch (Exception e) {
            LOG.errorf("QaTestCaseResource.updateJiraKey: %s: %s", id, e.getMessage());
            return serverError("Failed to update Jira key: " + e.getMessage());
        }
    }

    // ─── Delete all ───────────────────────────────────────────────────────────

    @DELETE
    public Response deleteAll(@PathParam("planId") String planId) {
        if (planStore.findById(planId).isEmpty()) return notFound("Test plan not found: " + planId);
        try {
            int deleted = caseStore.countByPlan(planId);
            caseStore.deleteByPlan(planId);
            auditService.log("QA", "TESTCASES_DELETED", "qa_test_plan", planId,
                    Map.of("deletedCount", String.valueOf(deleted)));
            return Response.noContent().build();
        } catch (Exception e) {
            LOG.errorf("QaTestCaseResource.deleteAll: planId=%s: %s", planId, e.getMessage());
            return serverError("Failed to delete test cases: " + e.getMessage());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> toResponseMap(QaTestCase tc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tc.id());
        m.put("planId", tc.planId());
        m.put("featureKey", tc.featureKey());
        m.put("storyKey", tc.storyKey());
        m.put("testCaseId", tc.testCaseId());
        m.put("title", tc.title());
        m.put("description", tc.description());
        m.put("preConditions", tc.preConditions());
        m.put("testSteps", tc.testSteps());
        m.put("expectedResults", tc.expectedResults());
        m.put("testCaseType", tc.testCaseType());
        m.put("priority", tc.priority());
        m.put("status", tc.status());
        m.put("estimatedDuration", tc.estimatedDuration());
        m.put("kpiStepCount", tc.kpiStepCount());
        m.put("kpiEstimatedMins", tc.kpiEstimatedMins());
        m.put("kpiPreconditionCount", tc.kpiPreconditionCount());
        m.put("kpiExecutionCount", tc.kpiExecutionCount());
        m.put("kpiLastResult", tc.kpiLastResult());
        m.put("kpiLastExecutedAt", tc.kpiLastExecutedAt());
        m.put("kpiAutomationStatus", tc.kpiAutomationStatus());
        m.put("jiraIssueKey", tc.jiraIssueKey());
        m.put("xraySyncStatus", tc.xraySyncStatus());
        m.put("xraySyncedAt", tc.xraySyncedAt());
        m.put("generatedAt", tc.generatedAt());
        return m;
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
