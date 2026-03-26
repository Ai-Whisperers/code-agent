package com.eneve.agent;

import com.eneve.agent.roadmap.RoadmapService;
import com.eneve.agent.roadmap.RoadmapService.ActiveJobExistsException;
import com.eneve.agent.roadmap.RoadmapService.ItemOverriddenException;
import com.eneve.agent.roadmap.RoadmapService.JiraIssueNotFoundException;
import com.eneve.agent.roadmap.RoadmapService.RoadmapNotFoundException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST endpoints for the Roadmap feature.
 *
 * <p>This class is intentionally thin: input validation and HTTP response
 * mapping live here; all business logic lives in {@link RoadmapService}.
 */
@Path("/roadmap")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class RoadmapResource {

    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]+-[0-9]+$");

    @Inject
    RoadmapService roadmapService;

    // ─── CRUD ───────────────────────────────────────────────────────────────

    @GET
    public Response listRoadmaps() {
        return Response.ok(roadmapService.listRoadmaps()).build();
    }

    @POST
    public Response createRoadmap(Map<String, String> body) {
        String name  = body.getOrDefault("name",  "").trim();
        String label = body.getOrDefault("label", "").trim();
        if (name.isBlank() || label.isBlank()) {
            return badRequest("name and label are required");
        }
        if (label.contains("\"") || label.contains("\\")) {
            return badRequest("label contains invalid characters");
        }

        RoadmapService.CreateRoadmapResult result = roadmapService.createRoadmap(
                name, label,
                body.getOrDefault("epicIssuetype",        ""),
                body.getOrDefault("featureIssuetype",     ""),
                body.getOrDefault("userstoryIssuetype",   ""));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id",                   result.roadmap().id());
        response.put("name",                 result.roadmap().name());
        response.put("label",                result.roadmap().label());
        response.put("epicIssuetype",        result.roadmap().epicIssuetype());
        response.put("featureIssuetype",     result.roadmap().featureIssuetype());
        response.put("userstoryIssuetype",   result.roadmap().userstoryIssuetype());
        response.put("createdAt",            result.roadmap().createdAt());
        response.put("itemsSynced",          result.itemsSynced());
        if (result.itemsSynced() == 0) {
            response.put("warning",
                    "No epics found for label '" + label + "' — verify your Jira label and issue type settings");
        }
        return Response.status(201).entity(response).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateRoadmap(@PathParam("id") String id, Map<String, String> body) {
        String name  = body.getOrDefault("name",  "").trim();
        String label = body.getOrDefault("label", "").trim();
        if (name.isBlank() || label.isBlank()) {
            return badRequest("name and label are required");
        }
        try {
            return Response.ok(roadmapService.updateRoadmap(id, name, label,
                    body.getOrDefault("epicIssuetype",      ""),
                    body.getOrDefault("featureIssuetype",   ""),
                    body.getOrDefault("userstoryIssuetype", ""))).build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteRoadmap(@PathParam("id") String id) {
        try {
            roadmapService.deleteRoadmap(id);
            return Response.noContent().build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
        }
    }

    // ─── Tree ────────────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/tree")
    public Response getTree(@PathParam("id") String roadmapId) {
        try {
            return Response.ok(roadmapService.buildTree(roadmapId)).build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
        }
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/sync")
    public Response syncRoadmap(@PathParam("id") String roadmapId) {
        try {
            int itemsSynced = roadmapService.syncRoadmap(roadmapId);
            return Response.ok(Map.of("itemsSynced", itemsSynced)).build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
        }
    }

    // ─── Reviews ─────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/review/{issueKey}")
    public Response reviewItem(@PathParam("id") String roadmapId,
                               @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            String jobId = roadmapService.enqueueReview(roadmapId, issueKey);
            return Response.accepted(Map.of("jobId", jobId)).build();
        } catch (RoadmapNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        } catch (ItemOverriddenException e) {
            return conflict(e.getMessage());
        } catch (ActiveJobExistsException e) {
            return conflict(e.getMessage());
        }
    }

    @POST
    @Path("/{id}/review-all")
    public Response reviewAll(@PathParam("id") String roadmapId,
                               @QueryParam("force") boolean force) {
        try {
            RoadmapService.ReviewAllResult result = roadmapService.enqueueReviewAll(roadmapId, force);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jobsEnqueued",  result.jobsEnqueued());
            resp.put("jobsSkipped",   result.jobsSkipped());
            resp.put("jobsUnchanged", result.jobsUnchanged());
            return Response.ok(resp).build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
        }
    }

    // ─── Overrides ───────────────────────────────────────────────────────────

    @PUT
    @Path("/{id}/items/{issueKey}/override")
    public Response setOverride(@PathParam("id") String roadmapId,
                                @PathParam("issueKey") String issueKey,
                                Map<String, String> body,
                                @Context SecurityContext sc) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        String status = body.getOrDefault("status", "").toUpperCase();
        if (!status.equals("ACCEPTED") && !status.equals("REMOVED")) {
            return badRequest("status must be ACCEPTED or REMOVED");
        }
        try {
            String updatedBy = sc.getUserPrincipal() != null ? sc.getUserPrincipal().getName() : null;
            roadmapService.setOverride(roadmapId, issueKey, status, updatedBy);
            return Response.ok(Map.of("roadmapId", roadmapId, "issueKey", issueKey, "status", status)).build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
        }
    }

    @DELETE
    @Path("/{id}/items/{issueKey}/override")
    public Response clearOverride(@PathParam("id") String roadmapId,
                                  @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            roadmapService.clearOverride(roadmapId, issueKey);
            return Response.noContent().build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
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
        return key != null && ISSUE_KEY_PATTERN.matcher(key).matches();
    }
}
