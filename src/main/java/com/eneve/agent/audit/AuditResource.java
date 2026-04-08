package com.eneve.agent.audit;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST endpoint for querying the general-purpose action audit log.
 * Access is restricted to administrators.
 */
@RolesAllowed("app_admin")
@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Audit", description = "Query the application action audit log")
public class AuditResource {

    @Inject
    AuditStore auditStore;

    @GET
    @Operation(
            operationId = "listAuditLog",
            summary = "List audit log entries",
            description = "Returns audit log entries in reverse chronological order. "
                    + "Supports optional filtering by category, action, and actor substring."
    )
    @APIResponse(responseCode = "200", description = "List of audit log entries")
    public List<AuditEntry> list(
            @Parameter(description = "Filter by category (e.g. JOBS, SETTINGS, REPO_SETTINGS)")
            @QueryParam("category") String category,

            @Parameter(description = "Filter by action name (e.g. JOB_SUBMITTED, SETTING_CHANGED)")
            @QueryParam("action") String action,

            @Parameter(description = "Filter by actor username (partial match, case-insensitive)")
            @QueryParam("actor") String actor,

            @Parameter(description = "Maximum number of results (default 200, max 1000)")
            @QueryParam("limit") @DefaultValue("200") int limit
    ) {
        return auditStore.search(category, action, actor, limit);
    }
}
