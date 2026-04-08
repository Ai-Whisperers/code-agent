package com.eneve.agent;

import com.eneve.agent.scope.ScopeExceptions.*;
import com.eneve.agent.scope.ScopeEvaluationService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST endpoints for scope evaluation: tree/sprint views, AI review queue, overrides, and item refresh.
 * All URLs are under {@code /scope/{id}/evaluation}.
 */
@Path("/scope/{id}/evaluation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class ScopeEvaluationResource {

    private static final Logger LOG = Logger.getLogger(ScopeEvaluationResource.class);
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]+-[0-9]+$");

    @Inject ScopeEvaluationService evaluationService;

    // ─── Tree ─────────────────────────────────────────────────────────────────

    @GET
    @Path("/tree")
    public Response getTree(@PathParam("id") String scopeId) {
        try {
            return Response.ok(evaluationService.buildTree(scopeId)).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Sprint view ──────────────────────────────────────────────────────────

    @GET
    @Path("/sprints")
    public Response getSprintView(@PathParam("id") String scopeId) {
        try {
            return Response.ok(evaluationService.buildSprintView(scopeId)).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Live refresh ─────────────────────────────────────────────────────────

    @POST
    @Path("/items/{issueKey}/refresh")
    public Response refreshItem(@PathParam("id") String scopeId,
                                @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            return Response.ok(evaluationService.refreshItem(scopeId, issueKey)).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // ─── Reviews ──────────────────────────────────────────────────────────────

    @POST
    @Path("/review/{issueKey}")
    public Response reviewItem(@PathParam("id") String scopeId,
                               @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            return Response.accepted(Map.of("jobId", evaluationService.enqueueReview(scopeId, issueKey))).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        } catch (ItemOverriddenException | ActiveJobExistsException e) {
            return conflict(e.getMessage());
        }
    }

    @POST
    @Path("/review-all")
    public Response reviewAll(@PathParam("id") String scopeId,
                               @QueryParam("force") boolean force) {
        try {
            ReviewAllResult result = evaluationService.enqueueReviewAll(scopeId, force);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jobsEnqueued",  result.jobsEnqueued());
            resp.put("jobsSkipped",   result.jobsSkipped());
            resp.put("jobsUnchanged", result.jobsUnchanged());
            return Response.ok(resp).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    @GET
    @Path("/active-review-count")
    public Response activeReviewCount(@PathParam("id") String scopeId) {
        try {
            long count = evaluationService.countActiveReviewJobs(scopeId);
            return Response.ok(Map.of("count", count)).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", "Failed to retrieve active review count")).build();
        }
    }

    @POST
    @Path("/items/{issueKey}/review-direct")
    public Response reviewItemDirect(@PathParam("id") String scopeId,
                                     @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            com.eneve.agent.model.JiraIssueReview review =
                    evaluationService.reviewItemDirect(scopeId, issueKey);
            return Response.ok(review).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Direct review failed for %s/%s: %s", scopeId, issueKey, e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Review failed: " + e.getMessage()))
                    .build();
        }
    }

    // ─── Overrides ────────────────────────────────────────────────────────────

    @PUT
    @Path("/items/{issueKey}/override")
    public Response setOverride(@PathParam("id") String scopeId,
                                @PathParam("issueKey") String issueKey,
                                Map<String, String> body,
                                @Context SecurityContext sc) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        String status = body.getOrDefault("status", "").toUpperCase();
        if (!status.equals("ACCEPTED") && !status.equals("REMOVED")) {
            return badRequest("status must be ACCEPTED or REMOVED");
        }
        try {
            String updatedBy = sc.getUserPrincipal() != null ? sc.getUserPrincipal().getName() : null;
            evaluationService.setOverride(scopeId, issueKey, status, updatedBy);
            return Response.ok(Map.of("scopeId", scopeId, "issueKey", issueKey, "status", status)).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    @DELETE
    @Path("/items/{issueKey}/override")
    public Response clearOverride(@PathParam("id") String scopeId,
                                  @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            evaluationService.clearOverride(scopeId, issueKey);
            return Response.noContent().build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Response helpers ─────────────────────────────────────────────────────

    private static Response badRequest(String message) {
        return Response.status(400).entity(Map.of("error", message)).build();
    }

    private static Response notFound(String message) {
        return Response.status(404).entity(Map.of("error", message)).build();
    }

    private static Response conflict(String message) {
        return Response.status(409).entity(Map.of("error", message)).build();
    }

    private static boolean isValidIssueKey(String key) {
        return key != null && (ISSUE_KEY_PATTERN.matcher(key).matches()
                || key.startsWith("VIRTUAL-")
                || key.startsWith("NEW-"));
    }
}
