package com.eneve.agent.webhooks;

import com.eneve.agent.aikido.AikidoTriageService;
import com.eneve.agent.audit.AuditEntry;
import com.eneve.agent.audit.AuditStore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test/simulation endpoint for the Aikido remediation flow.
 *
 * <p>{@code POST /test/aikido-webhook} accepts a simplified payload and runs the full
 * triage pipeline (deduplication → JIRA lookup/creation → fix job dispatch → Teams alert).
 * All test invocations are tagged with an {@code AIKIDO_TEST_TRIGGERED} audit event so they
 * are distinguishable from real Aikido webhook events in the audit log.
 *
 * <p>Requires {@code app_developer} or {@code app_admin} role.
 */
@Path("/test")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "Test", description = "Simulation endpoints for testing the remediation workflow")
@ApplicationScoped
public class AikidoTestResource {

    private static final Logger LOG = Logger.getLogger(AikidoTestResource.class);

    @Inject AikidoTriageService aikidoTriageService;
    @Inject AuditStore auditStore;

    /**
     * Simplified test payload for simulating an Aikido vulnerability webhook.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AikidoTestPayload(
            @Schema(required = true, description = "Aikido issue group ID to triage", example = "12345")
            Integer groupId,

            @Schema(description = "Severity: 'critical' or 'high' (default: high)", example = "critical")
            String severity,

            @Schema(description = "Issue type (e.g. 'sca', 'sast')", example = "sca")
            String issueType,

            @Schema(description = "Repository clone URL (optional — resolved from Aikido if absent)",
                    example = "https://bitbucket.org/workspace/my-repo.git")
            String repoUrl
    ) {}

    @POST
    @Path("/aikido-webhook")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "simulateAikidoWebhook",
            summary = "Simulate an Aikido Security webhook event for end-to-end testing",
            description = "Runs the full triage pipeline (dedup → JIRA → fix job → Teams) "
                    + "without requiring a real Aikido event. "
                    + "Tagged with AIKIDO_TEST_TRIGGERED in the audit log so it is distinguishable "
                    + "from real events. Returns a detailed trace of what happened."
    )
    @APIResponse(responseCode = "200", description = "Triage pipeline completed (may be skipped or dispatched)",
            content = @Content(schema = @Schema(example = """
                    {
                      "result": "dispatched",
                      "groupId": 12345,
                      "severity": "critical",
                      "issueType": "sca",
                      "jiraKey": "PROJ-42",
                      "jiraCreated": true,
                      "jobId": "550e8400-...",
                      "branchName": "agent/PROJ-42-log4j-fix",
                      "teamsNotified": true
                    }""")))
    @APIResponse(responseCode = "400", description = "Missing groupId in payload")
    public Response simulateAikidoWebhook(AikidoTestPayload payload) {
        if (payload == null || payload.groupId() == null) {
            return Response.status(400)
                    .entity(Map.of("error", "groupId is required"))
                    .build();
        }

        int groupId = payload.groupId();
        String severity = payload.severity() != null && !payload.severity().isBlank()
                ? payload.severity().toLowerCase() : "high";
        String issueType = payload.issueType() != null && !payload.issueType().isBlank()
                ? payload.issueType().toLowerCase() : "sca";
        String repoUrl = payload.repoUrl();

        LOG.infof("Aikido test simulation triggered: groupId=%d severity=%s issueType=%s",
                groupId, severity, issueType);

        // Audit: mark this as a test run
        Thread.ofVirtual().name("audit-aikido-test").start(() -> {
            try {
                String detail = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(Map.of(
                                "groupId", groupId, "severity", severity,
                                "issueType", issueType, "test", true));
                auditStore.save(new AuditEntry(null, "system", "TEST",
                        "AIKIDO_TEST_TRIGGERED", "group", String.valueOf(groupId),
                        detail, Instant.now()));
            } catch (Exception ignored) {}
        });

        // Run the triage pipeline
        AikidoTriageService.TriageResult result;
        try {
            result = aikidoTriageService.handleNewIssue(groupId, repoUrl, severity, issueType);
        } catch (Exception e) {
            LOG.errorf("Aikido test simulation failed for group %d: %s", groupId, e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Triage pipeline failed: " + e.getMessage()))
                    .build();
        }

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("groupId", groupId);
        trace.put("severity", severity);
        trace.put("issueType", issueType);

        if (result.skipped()) {
            trace.put("result", "skipped");
            trace.put("skipReason", result.skipReason());
        } else {
            trace.put("result", "dispatched");
            trace.put("jiraKey", result.jiraKey());
            trace.put("jiraCreated", result.jiraCreated());
            trace.put("jobId", result.jobId());
            trace.put("branchName", result.branchName());
            trace.put("teamsNotified", true);
            if (result.issueInfo() != null) {
                trace.put("package", result.issueInfo().packageName());
                trace.put("currentVersion", result.issueInfo().currentVersion());
                trace.put("fixedVersion", result.issueInfo().fixedVersion());
                trace.put("cve", result.issueInfo().cveId());
            }
        }

        return Response.ok(trace).build();
    }
}
