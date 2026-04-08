package com.eneve.agent;

import com.eneve.agent.audit.AuditService;
import com.eneve.agent.scope.QaReadinessService;
import com.eneve.agent.scope.ScopeExceptions;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST endpoint for QA readiness statistics and QA-ready features.
 * URL: {@code GET /scope/{id}/qa-readiness}
 * URL: {@code GET /scope/{id}/qa-readiness/features}
 */
@Path("/scope/{id}/qa-readiness")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class QaReadinessResource {

    @Inject QaReadinessService qaReadinessService;
    @Inject AuditService auditService;

    @GET
    public Response getQaReadiness(@PathParam("id") String scopeId) {
        try {
            return Response.ok(qaReadinessService.buildQaReadiness(scopeId)).build();
        } catch (ScopeExceptions.ScopeNotFoundException e) {
            return Response.status(404).entity(Map.of("error", "Scope not found")).build();
        }
    }

    @GET
    @Path("/features")
    public Response getQAReadyFeatures(@PathParam("id") String scopeId,
                                       @QueryParam("label") String label) {
        try {
            if (label == null || label.isBlank()) {
                return Response.status(400).entity(Map.of("error", "label parameter is required")).build();
            }
            var features = qaReadinessService.fetchQAReadyFeatures(scopeId, label);
            auditService.log("QA", "QA_FEATURES_FETCHED", "scope", scopeId,
                    Map.of("label", label, "featureCount", String.valueOf(features.size())));
            return Response.ok(features).build();
        } catch (ScopeExceptions.ScopeNotFoundException e) {
            return Response.status(404).entity(Map.of("error", "Scope not found")).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", "Failed to fetch QA-ready features: " + e.getMessage())).build();
        }
    }
}
