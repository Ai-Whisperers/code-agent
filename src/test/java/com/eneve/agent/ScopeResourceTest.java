package com.eneve.agent;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.ScopeRecord;
import com.eneve.agent.scope.ScopeEvaluationService;
import com.eneve.agent.scope.ScopeExceptions.ActiveJobExistsException;
import com.eneve.agent.scope.ScopeExceptions.CreateScopeResult;
import com.eneve.agent.scope.ScopeExceptions.ItemOverriddenException;
import com.eneve.agent.scope.ScopeExceptions.JiraIssueNotFoundException;
import com.eneve.agent.scope.ScopeExceptions.ReviewAllResult;
import com.eneve.agent.scope.ScopeExceptions.ScopeNotFoundException;
import com.eneve.agent.scope.ScopeManagementService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link ScopeManagementResource} and {@link ScopeEvaluationResource}.
 * Both services are mocked so no database, Jira, or job-queue infrastructure is needed.
 */
@QuarkusTest
class ScopeResourceTest {

    @InjectMock
    ScopeManagementService managementService;

    @InjectMock
    ScopeEvaluationService evaluationService;

    @InjectMock
    JobStore jobStore;

    private static final String SCOPE_ID = "rm-001";
    private static final ScopeRecord SAMPLE_SCOPE =
            new ScopeRecord(SCOPE_ID, "Q1 Scope", List.of("scope-q1"), "Epic", "Story", "Sub-task", Instant.now(), "po", null);

    // ── GET /api/scope ──────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void listScopes_returns200WithList() {
        when(managementService.listScopesByType(any())).thenReturn(List.of(SAMPLE_SCOPE));

        given()
            .when()
                .get("/api/scope")
            .then()
                .statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].id", equalTo(SCOPE_ID));
    }

    @Test
    void listScopes_unauthenticated_returns401or403() {
        given()
            .when()
                .get("/api/scope")
            .then()
                .statusCode(anyOf(equalTo(401), equalTo(403)));
    }

    @Test
    @TestSecurity(user = "user", roles = {"app_user"})
    void listScopes_appUserRole_returns403() {
        given()
            .when()
                .get("/api/scope")
            .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"app_admin"})
    void listScopes_adminRole_returns200() {
        when(managementService.listScopes()).thenReturn(List.of());

        given()
            .when()
                .get("/api/scope")
            .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void listScopes_developerRole_returns200() {
        when(managementService.listScopes()).thenReturn(List.of());

        given()
            .when()
                .get("/api/scope")
            .then()
                .statusCode(200);
    }

    // ── POST /api/scope ─────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createScope_missingName_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("label", "my-label"))
        .when()
            .post("/api/scope")
        .then()
            .statusCode(400)
            .body("error", containsString("name"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createScope_missingLabel_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "My Scope"))
        .when()
            .post("/api/scope")
        .then()
            .statusCode(400)
            .body("error", containsString("label"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createScope_labelWithQuotes_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "Name", "label", "bad\"label"))
        .when()
            .post("/api/scope")
        .then()
            .statusCode(400)
            .body("error", containsString("invalid characters"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createScope_validInput_returns201WithWarningOnEmptyEpics() {
        when(managementService.createScope("My Scope", List.of("my-label"), "", "", "", "po", ""))
                .thenReturn(new CreateScopeResult(SAMPLE_SCOPE, 0));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "My Scope", "label", "my-label"))
        .when()
            .post("/api/scope")
        .then()
            .statusCode(201)
            .body("id",          equalTo(SCOPE_ID))
            .body("itemsSynced", equalTo(0))
            .body("warning",     containsString("No epics found"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createScope_withEpics_returnsItemsSynced() {
        when(managementService.createScope("My Scope", List.of("my-label"), "", "", "", "po", ""))
                .thenReturn(new CreateScopeResult(SAMPLE_SCOPE, 3));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "My Scope", "label", "my-label"))
        .when()
            .post("/api/scope")
        .then()
            .statusCode(201)
            .body("itemsSynced", equalTo(3))
            .body("warning",     nullValue());
    }

    // ── PUT /api/scope/{id} ─────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void updateScope_notFound_returns404() {
        when(managementService.updateScope(eq("unknown"), anyString(), anyList(), any(), any(), any(), any()))
                .thenThrow(new ScopeNotFoundException("unknown"));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "New Name", "label", "new-label"))
        .when()
            .put("/api/scope/unknown")
        .then()
            .statusCode(404);
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void updateScope_blankName_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "  ", "label", "some-label"))
        .when()
            .put("/api/scope/" + SCOPE_ID)
        .then()
            .statusCode(400)
            .body("error", containsString("name and label are required"));
    }

    // ── DELETE /api/scope/{id} ──────────────────────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void deleteScope_notFound_returns404() {
        doThrow(new ScopeNotFoundException("unknown")).when(managementService).deleteScope("unknown");

        given()
            .when()
                .delete("/api/scope/unknown")
            .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void deleteScope_existing_returns204() {
        given()
            .when()
                .delete("/api/scope/" + SCOPE_ID)
            .then()
                .statusCode(204);

        verify(managementService).deleteScope(SCOPE_ID);
    }

    // ── POST /api/scope/{id}/evaluation/review/{issueKey} ───────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_invalidIssueKeyFormat_returns400() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/scope/" + SCOPE_ID + "/evaluation/review/invalid-key")
        .then()
            .statusCode(400)
            .body("error", containsString("Invalid issue key"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_overriddenItem_returns409() {
        when(evaluationService.enqueueReview(SCOPE_ID, "PROJ-1"))
                .thenThrow(new ItemOverriddenException("PROJ-1"));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/scope/" + SCOPE_ID + "/evaluation/review/PROJ-1")
        .then()
            .statusCode(409)
            .body("error", containsString("overridden"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_activeJobExists_returns409() {
        when(evaluationService.enqueueReview(SCOPE_ID, "PROJ-1"))
                .thenThrow(new ActiveJobExistsException("PROJ-1"));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/scope/" + SCOPE_ID + "/evaluation/review/PROJ-1")
        .then()
            .statusCode(409)
            .body("error", containsString("already active"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_jiraIssueNotFound_returns404() {
        when(evaluationService.enqueueReview(SCOPE_ID, "PROJ-1"))
                .thenThrow(new JiraIssueNotFoundException("PROJ-1"));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/scope/" + SCOPE_ID + "/evaluation/review/PROJ-1")
        .then()
            .statusCode(404);
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_success_returns202WithJobId() {
        when(evaluationService.enqueueReview(SCOPE_ID, "PROJ-1")).thenReturn("job-abc");

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/scope/" + SCOPE_ID + "/evaluation/review/PROJ-1")
        .then()
            .statusCode(202)
            .body("jobId", equalTo("job-abc"));
    }

    // ── POST /api/scope/{id}/evaluation/review-all ──────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewAll_notFound_returns404() {
        when(evaluationService.enqueueReviewAll(SCOPE_ID, false))
                .thenThrow(new ScopeNotFoundException(SCOPE_ID));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/scope/" + SCOPE_ID + "/evaluation/review-all")
        .then()
            .statusCode(404);
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewAll_returnsEnqueuedAndSkippedCounts() {
        when(evaluationService.enqueueReviewAll(SCOPE_ID, false))
                .thenReturn(new ReviewAllResult(5, 2, 0));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/scope/" + SCOPE_ID + "/evaluation/review-all")
        .then()
            .statusCode(200)
            .body("jobsEnqueued", equalTo(5))
            .body("jobsSkipped",  equalTo(2));
    }

    // ── PUT /api/scope/{id}/evaluation/items/{issueKey}/override ────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void setOverride_invalidStatus_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "INVALID"))
        .when()
            .put("/api/scope/" + SCOPE_ID + "/evaluation/items/PROJ-1/override")
        .then()
            .statusCode(400)
            .body("error", containsString("ACCEPTED or REMOVED"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void setOverride_accepted_returns200() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "ACCEPTED"))
        .when()
            .put("/api/scope/" + SCOPE_ID + "/evaluation/items/PROJ-1/override")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACCEPTED"));

        verify(evaluationService).setOverride(eq(SCOPE_ID), eq("PROJ-1"), eq("ACCEPTED"), any());
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void setOverride_invalidIssueKeyFormat_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "ACCEPTED"))
        .when()
            .put("/api/scope/" + SCOPE_ID + "/evaluation/items/bad-key/override")
        .then()
            .statusCode(400)
            .body("error", containsString("Invalid issue key"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void setOverride_scopeNotFound_returns404() {
        doThrow(new ScopeNotFoundException(SCOPE_ID))
                .when(evaluationService).setOverride(eq(SCOPE_ID), eq("PROJ-1"), anyString(), any());

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "ACCEPTED"))
        .when()
            .put("/api/scope/" + SCOPE_ID + "/evaluation/items/PROJ-1/override")
        .then()
            .statusCode(404);
    }

    // ── DELETE /api/scope/{id}/evaluation/items/{issueKey}/override ──────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void clearOverride_callsService_returns204() {
        given()
        .when()
            .delete("/api/scope/" + SCOPE_ID + "/evaluation/items/PROJ-1/override")
        .then()
            .statusCode(204);

        verify(evaluationService).clearOverride(SCOPE_ID, "PROJ-1");
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void clearOverride_scopeNotFound_returns404() {
        doThrow(new ScopeNotFoundException(SCOPE_ID))
                .when(evaluationService).clearOverride(SCOPE_ID, "PROJ-1");

        given()
        .when()
            .delete("/api/scope/" + SCOPE_ID + "/evaluation/items/PROJ-1/override")
        .then()
            .statusCode(404);
    }

    // ── GET /api/scope/{id}/evaluation/active-review-count ──────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void activeReviewCount_unknownScope_returns404() {
        when(evaluationService.countActiveReviewJobs("unknown"))
                .thenThrow(new ScopeNotFoundException("unknown"));

        given()
        .when()
            .get("/api/scope/unknown/evaluation/active-review-count")
        .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void activeReviewCount_knownScope_returnsCount() {
        when(evaluationService.countActiveReviewJobs(SCOPE_ID)).thenReturn(7L);

        given()
        .when()
            .get("/api/scope/" + SCOPE_ID + "/evaluation/active-review-count")
        .then()
            .statusCode(200)
            .body("count", equalTo(7));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void activeReviewCount_zeroJobs_returnsZero() {
        when(evaluationService.countActiveReviewJobs(SCOPE_ID)).thenReturn(0L);

        given()
        .when()
            .get("/api/scope/" + SCOPE_ID + "/evaluation/active-review-count")
        .then()
            .statusCode(200)
            .body("count", equalTo(0));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void activeReviewCount_storeThrows_returns500WithGenericMessage() {
        when(evaluationService.countActiveReviewJobs(SCOPE_ID))
                .thenThrow(new RuntimeException("DB connection failed: password=s3cr3t!"));

        given()
        .when()
            .get("/api/scope/" + SCOPE_ID + "/evaluation/active-review-count")
        .then()
            .statusCode(500)
            .body("error", equalTo("Failed to retrieve active review count"))
            .body("error", not(containsString("password")));
    }

    @Test
    void activeReviewCount_unauthenticated_returns401or403() {
        given()
        .when()
            .get("/api/scope/" + SCOPE_ID + "/evaluation/active-review-count")
        .then()
            .statusCode(anyOf(equalTo(401), equalTo(403)));
    }
}
