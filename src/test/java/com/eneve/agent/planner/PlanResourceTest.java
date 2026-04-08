package com.eneve.agent.planner;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link PlanResource} that verify REST layer wiring,
 * auth, request validation, and status-transition responses.
 *
 * <p>{@link PlanStore}, {@link PlannerService}, and {@link PlanOrchestratorService}
 * are mocked so no database or AI infrastructure is needed.
 */
@QuarkusTest
class PlanResourceTest {

    @InjectMock
    PlanStore planStore;

    @InjectMock
    PlannerService plannerService;

    @InjectMock
    PlanOrchestratorService orchestratorService;

    @InjectMock
    PlanTrackedJobStore trackedJobStore;

    @BeforeEach
    void setUp() {
        // Default: trackedJobStore.findAll returns empty list (used on startup rehydration)
        when(trackedJobStore.findAll()).thenReturn(List.of());
    }

    // ─── POST /plans ──────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void createPlan_missingSpecText_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "repoUrl": "https://example.com/repo.git" }
                """)
        .when()
            .post("/api/plans")
        .then()
            .statusCode(400)
            .body("error", containsString("specText"));
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void createPlan_missingRepoUrl_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "specText": "Implement feature X" }
                """)
        .when()
            .post("/api/plans")
        .then()
            .statusCode(400)
            .body("error", containsString("repoUrl"));
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void createPlan_specTextTooLong_returns400() {
        String longSpec = "x".repeat(10_001);
        given()
            .contentType(ContentType.JSON)
            .body("{\"repoUrl\":\"https://example.com/repo.git\",\"specText\":\"" + longSpec + "\"}")
        .when()
            .post("/api/plans")
        .then()
            .statusCode(400)
            .body("error", containsString("10,000"));
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void createPlan_validRequest_returns201() {
        ExecutionPlan generated = draftPlan("plan-1");
        when(plannerService.generatePlan(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(generated);
        when(planStore.find("plan-1")).thenReturn(Optional.of(generated));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "specText": "Implement feature X",
                  "repoUrl": "https://example.com/repo.git",
                  "targetBranch": "main"
                }
                """)
        .when()
            .post("/api/plans")
        .then()
            .statusCode(201)
            .body("planId", equalTo("plan-1"));
    }

    // ─── POST /plans/{id}/pause ───────────────────────────────────────────────

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void pausePlan_whenExecuting_returns200() {
        String planId = "plan-exec";
        when(planStore.find(planId)).thenReturn(Optional.of(executingPlan(planId)));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/plans/" + planId + "/pause")
        .then()
            .statusCode(200);

        verify(orchestratorService).pausePlan(planId);
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void pausePlan_whenDraft_returns400() {
        String planId = "plan-draft";
        when(planStore.find(planId)).thenReturn(Optional.of(draftPlan(planId)));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/plans/" + planId + "/pause")
        .then()
            .statusCode(400);
    }

    // ─── DELETE /plans/{id} ───────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void deletePlan_whenExecuting_returns409() {
        String planId = "plan-running";
        when(planStore.find(planId)).thenReturn(Optional.of(executingPlan(planId)));

        given()
        .when()
            .delete("/api/plans/" + planId)
        .then()
            .statusCode(409);
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void deletePlan_whenDraftByCreator_returns200() {
        String planId = "plan-draft-del";
        ExecutionPlan plan = planWithCreator(planId, "DRAFT", "dev");
        when(planStore.find(planId)).thenReturn(Optional.of(plan));
        when(planStore.delete(planId)).thenReturn(true);

        given()
        .when()
            .delete("/api/plans/" + planId)
        .then()
            .statusCode(200)
            .body("action", equalTo("deleted"));
    }

    // ─── GET /plans ───────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void listPlans_excludesArchivedByDefault() {
        when(planStore.listAll(false)).thenReturn(List.of(draftPlan("p1")));
        when(planStore.listAll(true)).thenReturn(List.of(draftPlan("p1"), archivedPlan("p2")));

        given()
        .when()
            .get("/api/plans")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1));

        verify(planStore).listAll(false);
        verify(planStore, never()).listAll(true);
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void listPlans_includeArchivedTrue_returnsAll() {
        when(planStore.listAll(true)).thenReturn(List.of(draftPlan("p1"), archivedPlan("p2")));

        given()
            .queryParam("includeArchived", "true")
        .when()
            .get("/api/plans")
        .then()
            .statusCode(200)
            .body("size()", equalTo(2));

        verify(planStore).listAll(true);
    }

    // ─── POST /plans/{id}/archive ─────────────────────────────────────────────

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void archivePlan_byNonCreatorNonAdmin_returns403() {
        String planId = "plan-other";
        ExecutionPlan plan = planWithCreator(planId, "COMPLETED", "other-user");
        when(planStore.find(planId)).thenReturn(Optional.of(plan));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/plans/" + planId + "/archive")
        .then()
            .statusCode(403);
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void archivePlan_byCreator_returns200() {
        String planId = "plan-mine";
        ExecutionPlan plan = planWithCreator(planId, "COMPLETED", "dev");
        when(planStore.find(planId)).thenReturn(Optional.of(plan));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/plans/" + planId + "/archive")
        .then()
            .statusCode(200)
            .body("action", equalTo("archived"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static ExecutionPlan draftPlan(String planId) {
        return planWithCreator(planId, "DRAFT", "dev");
    }

    private static ExecutionPlan executingPlan(String planId) {
        return planWithCreator(planId, "EXECUTING", "dev");
    }

    private static ExecutionPlan archivedPlan(String planId) {
        return new ExecutionPlan(planId, "COMPLETED", "FREE_TEXT", null,
                "https://example.com/repo.git", null, "main", "Archived plan",
                new PlanData(List.of()), Instant.now(), Instant.now(), null,
                null, null, null, null, null, null, true, "dev");
    }

    private static ExecutionPlan planWithCreator(String planId, String status, String createdBy) {
        return new ExecutionPlan(planId, status, "FREE_TEXT", null,
                "https://example.com/repo.git", null, "main", "Test plan",
                new PlanData(List.of()), Instant.now(), Instant.now(), null,
                null, null, null, null, null, null, false, createdBy);
    }
}
