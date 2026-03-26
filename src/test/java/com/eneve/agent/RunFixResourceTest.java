package com.eneve.agent;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.audit.AuditService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for {@link RunFixResource} that verify REST layer wiring,
 * auth, request validation, and job-submission response shape.
 *
 * <p>Heavy dependencies ({@link JobQueue}, {@link JobStore}, {@link AuditService})
 * are mocked with {@link InjectMock} so no database or agent infrastructure is needed.
 */
@QuarkusTest
class RunFixResourceTest {

    @InjectMock
    JobQueue jobQueue;

    @InjectMock
    JobStore jobStore;

    @InjectMock
    AuditService auditService;

    @BeforeEach
    void setUp() {
        // Default: queue accepts every job
        Mockito.when(jobQueue.submit(Mockito.any())).thenReturn(true);
        // jobStore.put is void — no-op by default
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void validRequest_returns202WithJobId() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "repoUrl": "https://bitbucket.org/org/repo",
                  "branchName": "agent/TEST-1",
                  "jiraKey": "TEST-1",
                  "prompt": "Fix the null pointer exception"
                }
                """)
        .when()
            .post("/api/run-fix")
        .then()
            .statusCode(202)
            .body("jobId", not(emptyString()));
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void missingRepoUrl_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "branchName": "agent/TEST-1",
                  "jiraKey": "TEST-1"
                }
                """)
        .when()
            .post("/api/run-fix")
        .then()
            .statusCode(400)
            .body("error", containsString("repoUrl"));
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void missingBranchName_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "repoUrl": "https://bitbucket.org/org/repo",
                  "jiraKey": "TEST-1"
                }
                """)
        .when()
            .post("/api/run-fix")
        .then()
            .statusCode(400)
            .body("error", containsString("branchName"));
    }

    @Test
    @TestSecurity(user = "dev", roles = {"app_developer"})
    void queueFull_returns429() {
        Mockito.when(jobQueue.submit(Mockito.any())).thenReturn(false);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "repoUrl": "https://bitbucket.org/org/repo",
                  "branchName": "agent/TEST-1",
                  "jiraKey": "TEST-1"
                }
                """)
        .when()
            .post("/api/run-fix")
        .then()
            .statusCode(429)
            .body("error", containsString("queue"));
    }

    @Test
    void unauthenticatedRequest_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body("{}")
        .when()
            .post("/api/run-fix")
        .then()
            .statusCode(401);
    }
}
