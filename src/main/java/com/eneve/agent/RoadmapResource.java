package com.eneve.agent;

import com.eneve.agent.audit.AuditService;
import com.eneve.agent.roadmap.RoadmapService;
import com.eneve.agent.roadmap.RoadmapService.ActiveJobExistsException;
import com.eneve.agent.roadmap.RoadmapService.ImprovementGenerationException;
import com.eneve.agent.roadmap.RoadmapService.ItemOverriddenException;
import com.eneve.agent.roadmap.RoadmapService.JiraIssueNotFoundException;
import com.eneve.agent.roadmap.RoadmapService.ProposalNotFoundException;
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

    @Inject
    AuditService auditService;

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

        auditService.log("ROADMAP", "ROADMAP_CREATED", "roadmap", result.roadmap().id(),
                Map.of("name", name, "label", label, "itemsSynced", result.itemsSynced()));

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
            Object updated = roadmapService.updateRoadmap(id, name, label,
                    body.getOrDefault("epicIssuetype",      ""),
                    body.getOrDefault("featureIssuetype",   ""),
                    body.getOrDefault("userstoryIssuetype", ""));
            auditService.log("ROADMAP", "ROADMAP_UPDATED", "roadmap", id,
                    Map.of("name", name, "label", label));
            return Response.ok(updated).build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteRoadmap(@PathParam("id") String id) {
        try {
            roadmapService.deleteRoadmap(id);
            auditService.log("ROADMAP", "ROADMAP_DELETED", "roadmap", id);
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

    // ─── Sprint view ─────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/sprints")
    public Response getSprintView(@PathParam("id") String roadmapId) {
        try {
            return Response.ok(roadmapService.buildSprintView(roadmapId)).build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
        }
    }

    // ─── Live refresh ─────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/items/{issueKey}/refresh")
    public Response refreshItem(@PathParam("id") String roadmapId,
                                @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            Object result = roadmapService.refreshItem(roadmapId, issueKey);
            auditService.log("ROADMAP", "ITEM_REFRESHED", "roadmap_item", issueKey,
                    Map.of("roadmapId", roadmapId));
            return Response.ok(result).build();
        } catch (RoadmapNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/sync")
    public Response syncRoadmap(@PathParam("id") String roadmapId) {
        try {
            int itemsSynced = roadmapService.syncRoadmap(roadmapId);
            auditService.log("ROADMAP", "ROADMAP_SYNCED", "roadmap", roadmapId,
                    Map.of("itemsSynced", itemsSynced));
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
            auditService.log("ROADMAP", "REVIEW_ENQUEUED", "roadmap_item", issueKey,
                    Map.of("roadmapId", roadmapId, "jobId", jobId));
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
            auditService.log("ROADMAP", "REVIEW_ALL_ENQUEUED", "roadmap", roadmapId,
                    Map.of("jobsEnqueued", result.jobsEnqueued(),
                           "jobsSkipped",  result.jobsSkipped(),
                           "jobsUnchanged", result.jobsUnchanged(),
                           "force", force));
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
            auditService.log("ROADMAP", "OVERRIDE_SET", "roadmap_item", issueKey,
                    Map.of("roadmapId", roadmapId, "status", status));
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
            auditService.log("ROADMAP", "OVERRIDE_CLEARED", "roadmap_item", issueKey,
                    Map.of("roadmapId", roadmapId));
            return Response.noContent().build();
        } catch (RoadmapNotFoundException e) {
            return notFound("Roadmap not found");
        }
    }

    // ─── AI Proposals ────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/items/{issueKey}/improve")
    public Response improveItem(@PathParam("id") String roadmapId,
                                @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            Object proposal = roadmapService.improveItem(roadmapId, issueKey);
            auditService.log("ROADMAP", "PROPOSAL_CREATED", "roadmap_item", issueKey,
                    Map.of("roadmapId", roadmapId));
            return Response.ok(proposal).build();
        } catch (RoadmapNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        } catch (ImprovementGenerationException e) {
            return Response.status(502).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}/items/{issueKey}/proposals")
    public Response getProposals(@PathParam("id") String roadmapId,
                                 @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            return Response.ok(roadmapService.getProposals(roadmapId, issueKey)).build();
        } catch (RoadmapNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @PUT
    @Path("/{id}/proposals/{proposalId}")
    public Response updateProposal(@PathParam("id") String roadmapId,
                                   @PathParam("proposalId") String proposalId,
                                   Map<String, String> body) {
        try {
            Object updated = roadmapService.updateProposal(
                    roadmapId, proposalId,
                    body.getOrDefault("proposedSummary",     ""),
                    body.getOrDefault("proposedDescription", ""),
                    body.getOrDefault("proposedCriteria",    ""),
                    body.getOrDefault("proposedTechnical",   ""));
            auditService.log("ROADMAP", "PROPOSAL_UPDATED", "roadmap_proposal", proposalId,
                    Map.of("roadmapId", roadmapId));
            return Response.ok(updated).build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @POST
    @Path("/{id}/proposals/{proposalId}/accept")
    public Response acceptProposal(@PathParam("id") String roadmapId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            Object result = roadmapService.acceptProposal(roadmapId, proposalId);
            auditService.log("ROADMAP", "PROPOSAL_ACCEPTED", "roadmap_proposal", proposalId,
                    Map.of("roadmapId", roadmapId));
            return Response.ok(result).build();
        } catch (ProposalNotFoundException | RoadmapNotFoundException e) {
            return notFound(e.getMessage());
        } catch (ImprovementGenerationException e) {
            return Response.status(502).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/proposals/{proposalId}/reject")
    public Response rejectProposal(@PathParam("id") String roadmapId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            Object result = roadmapService.rejectProposal(roadmapId, proposalId);
            auditService.log("ROADMAP", "PROPOSAL_REJECTED", "roadmap_proposal", proposalId,
                    Map.of("roadmapId", roadmapId));
            return Response.ok(result).build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}/proposals/{proposalId}")
    public Response deleteProposal(@PathParam("id") String roadmapId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            roadmapService.deleteProposal(roadmapId, proposalId);
            auditService.log("ROADMAP", "PROPOSAL_DELETED", "roadmap_proposal", proposalId,
                    Map.of("roadmapId", roadmapId));
            return Response.noContent().build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // ─── Product links ────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/products")
    public Response listLinkedProducts(@PathParam("id") String roadmapId) {
        try {
            return Response.ok(roadmapService.listLinkedProducts(roadmapId)).build();
        } catch (RoadmapNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @PUT
    @Path("/{id}/products/{productId}")
    public Response linkProduct(@PathParam("id") String roadmapId,
                                @PathParam("productId") String productId) {
        try {
            roadmapService.linkProduct(roadmapId, productId);
            auditService.log("ROADMAP", "PRODUCT_LINKED", "roadmap", roadmapId,
                    Map.of("productId", productId));
            return Response.ok(roadmapService.listLinkedProducts(roadmapId)).build();
        } catch (RoadmapNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}/products/{productId}")
    public Response unlinkProduct(@PathParam("id") String roadmapId,
                                  @PathParam("productId") String productId) {
        try {
            roadmapService.unlinkProduct(roadmapId, productId);
            auditService.log("ROADMAP", "PRODUCT_UNLINKED", "roadmap", roadmapId,
                    Map.of("productId", productId));
            return Response.noContent().build();
        } catch (RoadmapNotFoundException e) {
            return notFound(e.getMessage());
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
