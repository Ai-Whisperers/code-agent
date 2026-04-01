package com.eneve.agent;

import com.eneve.agent.scope.QaReadinessService;
import com.eneve.agent.scope.ScopeExceptions.ScopeNotFoundException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST endpoint for QA readiness statistics.
 * URL: {@code GET /scope/{id}/qa-readiness}
 */
@Path("/scope/{id}/qa-readiness")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class QaReadinessResource {

    @Inject QaReadinessService qaReadinessService;

    @GET
    public Response getQaReadiness(@PathParam("id") String scopeId) {
        try {
            return Response.ok(qaReadinessService.buildQaReadiness(scopeId)).build();
        } catch (ScopeNotFoundException e) {
            return Response.status(404).entity(Map.of("error", "Scope not found")).build();
        }
    }
}
