package com.eneve.agent;

import com.eneve.agent.model.RoadmapRecord;
import com.eneve.agent.roadmap.RoadmapService;
import com.eneve.agent.roadmap.RoadmapService.ActiveJobExistsException;
import com.eneve.agent.roadmap.RoadmapService.CreateRoadmapResult;
import com.eneve.agent.roadmap.RoadmapService.ItemOverriddenException;
import com.eneve.agent.roadmap.RoadmapService.JiraIssueNotFoundException;
import com.eneve.agent.roadmap.RoadmapService.ReviewAllResult;
import com.eneve.agent.roadmap.RoadmapService.RoadmapNotFoundException;
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
 * Integration tests for {@link RoadmapResource}.
 * {@link RoadmapService} is mocked so no database, Jira, or job-queue
 * infrastructure is needed.
 */
@QuarkusTest
class RoadmapResourceTest {

    @InjectMock
    RoadmapService roadmapService;

    private static final String ROADMAP_ID = "rm-001";
    private static final RoadmapRecord SAMPLE_ROADMAP =
            new RoadmapRecord(ROADMAP_ID, "Q1 Roadmap", "roadmap-q1", "Epic", "Story", "Sub-task", Instant.now());

    // ── GET /api/roadmap ──────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void listRoadmaps_returns200WithList() {
        when(roadmapService.listRoadmaps()).thenReturn(List.of(SAMPLE_ROADMAP));

        given()
            .when()
                .get("/api/roadmap")
            .then()
                .statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].id", equalTo(ROADMAP_ID));
    }

    @Test
    void listRoadmaps_unauthenticated_returns401or403() {
        given()
            .when()
                .get("/api/roadmap")
            .then()
                .statusCode(anyOf(equalTo(401), equalTo(403)));
    }

    @Test
    @TestSecurity(user = "user", roles = {"app_user"})
    void listRoadmaps_appUserRole_returns403() {
        given()
            .when()
                .get("/api/roadmap")
            .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"app_admin"})
    void listRoadmaps_adminRole_returns200() {
        when(roadmapService.listRoadmaps()).thenReturn(List.of());

        given()
            .when()
                .get("/api/roadmap")
            .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void listRoadmaps_developerRole_returns200() {
        when(roadmapService.listRoadmaps()).thenReturn(List.of());

        given()
            .when()
                .get("/api/roadmap")
            .then()
                .statusCode(200);
    }

    // ── POST /api/roadmap ─────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createRoadmap_missingName_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("label", "my-label"))
        .when()
            .post("/api/roadmap")
        .then()
            .statusCode(400)
            .body("error", containsString("name"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createRoadmap_missingLabel_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "My Roadmap"))
        .when()
            .post("/api/roadmap")
        .then()
            .statusCode(400)
            .body("error", containsString("label"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createRoadmap_labelWithQuotes_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "Name", "label", "bad\"label"))
        .when()
            .post("/api/roadmap")
        .then()
            .statusCode(400)
            .body("error", containsString("invalid characters"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createRoadmap_validInput_returns201WithWarningOnEmptyEpics() {
        when(roadmapService.createRoadmap("My Roadmap", "my-label", "", "", ""))
                .thenReturn(new CreateRoadmapResult(SAMPLE_ROADMAP, 0));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "My Roadmap", "label", "my-label"))
        .when()
            .post("/api/roadmap")
        .then()
            .statusCode(201)
            .body("id",          equalTo(ROADMAP_ID))
            .body("jobsEnqueued", equalTo(0))
            .body("warning",     containsString("No epics found"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void createRoadmap_withEpics_returnsJobCount() {
        when(roadmapService.createRoadmap("My Roadmap", "my-label", "", "", ""))
                .thenReturn(new CreateRoadmapResult(SAMPLE_ROADMAP, 3));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "My Roadmap", "label", "my-label"))
        .when()
            .post("/api/roadmap")
        .then()
            .statusCode(201)
            .body("jobsEnqueued", equalTo(3))
            .body("warning",      nullValue());
    }

    // ── PUT /api/roadmap/{id} ─────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void updateRoadmap_notFound_returns404() {
        when(roadmapService.updateRoadmap(eq("unknown"), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new RoadmapNotFoundException("unknown"));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "New Name", "label", "new-label"))
        .when()
            .put("/api/roadmap/unknown")
        .then()
            .statusCode(404);
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void updateRoadmap_blankName_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "  ", "label", "some-label"))
        .when()
            .put("/api/roadmap/" + ROADMAP_ID)
        .then()
            .statusCode(400)
            .body("error", containsString("name and label are required"));
    }

    // ── DELETE /api/roadmap/{id} ──────────────────────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void deleteRoadmap_notFound_returns404() {
        doThrow(new RoadmapNotFoundException("unknown")).when(roadmapService).deleteRoadmap("unknown");

        given()
            .when()
                .delete("/api/roadmap/unknown")
            .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void deleteRoadmap_existing_returns204() {
        given()
            .when()
                .delete("/api/roadmap/" + ROADMAP_ID)
            .then()
                .statusCode(204);

        verify(roadmapService).deleteRoadmap(ROADMAP_ID);
    }

    // ── POST /api/roadmap/{id}/review/{issueKey} ──────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_invalidIssueKeyFormat_returns400() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/roadmap/" + ROADMAP_ID + "/review/invalid-key")
        .then()
            .statusCode(400)
            .body("error", containsString("Invalid issue key"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_overriddenItem_returns409() {
        when(roadmapService.enqueueReview(ROADMAP_ID, "PROJ-1"))
                .thenThrow(new ItemOverriddenException("PROJ-1"));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/roadmap/" + ROADMAP_ID + "/review/PROJ-1")
        .then()
            .statusCode(409)
            .body("error", containsString("overridden"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_activeJobExists_returns409() {
        when(roadmapService.enqueueReview(ROADMAP_ID, "PROJ-1"))
                .thenThrow(new ActiveJobExistsException("PROJ-1"));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/roadmap/" + ROADMAP_ID + "/review/PROJ-1")
        .then()
            .statusCode(409)
            .body("error", containsString("already active"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_jiraIssueNotFound_returns404() {
        when(roadmapService.enqueueReview(ROADMAP_ID, "PROJ-1"))
                .thenThrow(new JiraIssueNotFoundException("PROJ-1"));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/roadmap/" + ROADMAP_ID + "/review/PROJ-1")
        .then()
            .statusCode(404);
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewItem_success_returns202WithJobId() {
        when(roadmapService.enqueueReview(ROADMAP_ID, "PROJ-1")).thenReturn("job-abc");

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/roadmap/" + ROADMAP_ID + "/review/PROJ-1")
        .then()
            .statusCode(202)
            .body("jobId", equalTo("job-abc"));
    }

    // ── POST /api/roadmap/{id}/review-all ────────────────────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewAll_notFound_returns404() {
        when(roadmapService.enqueueReviewAll(ROADMAP_ID, false))
                .thenThrow(new RoadmapNotFoundException(ROADMAP_ID));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/roadmap/" + ROADMAP_ID + "/review-all")
        .then()
            .statusCode(404);
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void reviewAll_returnsEnqueuedAndSkippedCounts() {
        when(roadmapService.enqueueReviewAll(ROADMAP_ID, false))
                .thenReturn(new ReviewAllResult(5, 2, 0));

        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/roadmap/" + ROADMAP_ID + "/review-all")
        .then()
            .statusCode(200)
            .body("jobsEnqueued", equalTo(5))
            .body("jobsSkipped",  equalTo(2));
    }

    // ── PUT /api/roadmap/{id}/items/{issueKey}/override ───────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void setOverride_invalidStatus_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "INVALID"))
        .when()
            .put("/api/roadmap/" + ROADMAP_ID + "/items/PROJ-1/override")
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
            .put("/api/roadmap/" + ROADMAP_ID + "/items/PROJ-1/override")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACCEPTED"));

        verify(roadmapService).setOverride(eq(ROADMAP_ID), eq("PROJ-1"), eq("ACCEPTED"), any());
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void setOverride_invalidIssueKeyFormat_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "ACCEPTED"))
        .when()
            .put("/api/roadmap/" + ROADMAP_ID + "/items/bad-key/override")
        .then()
            .statusCode(400)
            .body("error", containsString("Invalid issue key"));
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void setOverride_roadmapNotFound_returns404() {
        doThrow(new RoadmapNotFoundException(ROADMAP_ID))
                .when(roadmapService).setOverride(eq(ROADMAP_ID), eq("PROJ-1"), anyString(), any());

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "ACCEPTED"))
        .when()
            .put("/api/roadmap/" + ROADMAP_ID + "/items/PROJ-1/override")
        .then()
            .statusCode(404);
    }

    // ── DELETE /api/roadmap/{id}/items/{issueKey}/override ────────────────────

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void clearOverride_callsService_returns204() {
        given()
        .when()
            .delete("/api/roadmap/" + ROADMAP_ID + "/items/PROJ-1/override")
        .then()
            .statusCode(204);

        verify(roadmapService).clearOverride(ROADMAP_ID, "PROJ-1");
    }

    @Test
    @TestSecurity(user = "staff", roles = {"app_staff"})
    void clearOverride_roadmapNotFound_returns404() {
        doThrow(new RoadmapNotFoundException(ROADMAP_ID))
                .when(roadmapService).clearOverride(ROADMAP_ID, "PROJ-1");

        given()
        .when()
            .delete("/api/roadmap/" + ROADMAP_ID + "/items/PROJ-1/override")
        .then()
            .statusCode(404);
    }
}
