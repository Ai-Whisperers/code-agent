package com.eneve.agent;

import com.eneve.agent.scope.ScopeService;
import com.eneve.agent.scope.ScopeService.ActiveJobExistsException;
import com.eneve.agent.scope.ScopeService.ImprovementGenerationException;
import com.eneve.agent.scope.ScopeService.ItemOverriddenException;
import com.eneve.agent.scope.ScopeService.JiraIssueNotFoundException;
import com.eneve.agent.scope.ScopeService.ProposalNotFoundException;
import com.eneve.agent.scope.ScopeService.ScopeNotFoundException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST endpoints for the Scope feature.
 *
 * <p>This class is intentionally thin: input validation and HTTP response
 * mapping live here; all business logic lives in {@link ScopeService}.
 */
@Path("/scope")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class ScopeResource {

    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]+-[0-9]+$");

    @Inject
    ScopeService scopeService;

    // ─── CRUD ───────────────────────────────────────────────────────────────

    @GET
    public Response listScopes() {
        List<Map<String, Object>> result = scopeService.listScopes().stream()
                .map(s -> scopeResponse(s, null))
                .collect(java.util.stream.Collectors.toList());
        return Response.ok(result).build();
    }

    @POST
    public Response createScope(Map<String, Object> body) {
        String name = strOf(body, "name");
        List<String> labels = labelsOf(body);
        if (name.isBlank() || labels.isEmpty()) {
            return badRequest("name and at least one label are required");
        }
        for (String lbl : labels) {
            if (hasInvalidChars(lbl)) return badRequest("label contains invalid characters");
        }

        ScopeService.CreateScopeResult result = scopeService.createScope(
                name, labels,
                strOf(body, "epicIssuetype"),
                strOf(body, "featureIssuetype"),
                strOf(body, "userstoryIssuetype"));

        Map<String, Object> resp = scopeResponse(result.scope(), result.itemsSynced());
        if (result.itemsSynced() == 0) {
            resp.put("warning", "No epics found for the given labels");
        }
        return Response.status(201).entity(resp).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateScope(@PathParam("id") String id, Map<String, Object> body) {
        String name = strOf(body, "name");
        List<String> labels = labelsOf(body);
        if (name.isBlank()) {
            return badRequest("name and label are required");
        }
        try {
            Object updated = scopeService.updateScope(id, name, labels,
                    strOf(body, "epicIssuetype"),
                    strOf(body, "featureIssuetype"),
                    strOf(body, "userstoryIssuetype"));
            return Response.ok(updated).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Preview labels ───────────────────────────────────────────────────────

    /**
     * GET /scope/preview-labels?labels=foo&labels=bar
     *
     * Returns a flat list of Jira issues matching ANY of the given labels.
     * Does NOT persist anything — used for the live preview table in the UI.
     */
    @GET
    @Path("/preview-labels")
    public Response previewLabels(@QueryParam("labels") List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return badRequest("at least one label is required");
        }
        return Response.ok(scopeService.previewLabels(labels)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteScope(@PathParam("id") String id) {
        try {
            scopeService.deleteScope(id);
            return Response.noContent().build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    @GET
    @Path("/{id}")
    public Response getScope(@PathParam("id") String id) {
        try {
            return Response.ok(scopeResponse(scopeService.getScope(id), null)).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Token stats ─────────────────────────────────────────────────────────

    @GET
    @Path("/review-token-stats")
    public Response getReviewTokenStats() {
        return Response.ok(scopeService.getReviewTokenStats()).build();
    }

    // ─── Tree ────────────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/tree")
    public Response getTree(@PathParam("id") String scopeId) {
        try {
            return Response.ok(scopeService.buildTree(scopeId)).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Sprint view ─────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/sprints")
    public Response getSprintView(@PathParam("id") String scopeId) {
        try {
            return Response.ok(scopeService.buildSprintView(scopeId)).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Live refresh ─────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/items/{issueKey}/refresh")
    public Response refreshItem(@PathParam("id") String scopeId,
                                @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            return Response.ok(scopeService.refreshItem(scopeId, issueKey)).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/sync")
    public Response syncScope(@PathParam("id") String scopeId) {
        try {
            return Response.ok(Map.of("itemsSynced", scopeService.syncScope(scopeId))).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Reviews ─────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/review/{issueKey}")
    public Response reviewItem(@PathParam("id") String scopeId,
                               @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            return Response.accepted(Map.of("jobId", scopeService.enqueueReview(scopeId, issueKey))).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        } catch (ItemOverriddenException e) {
            return conflict(e.getMessage());
        } catch (ActiveJobExistsException e) {
            return conflict(e.getMessage());
        }
    }

    @POST
    @Path("/{id}/review-all")
    public Response reviewAll(@PathParam("id") String scopeId,
                               @QueryParam("force") boolean force) {
        try {
            ScopeService.ReviewAllResult result = scopeService.enqueueReviewAll(scopeId, force);
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
    @Path("/{id}/active-review-count")
    public Response activeReviewCount(@PathParam("id") String scopeId) {
        try {
            scopeService.getScope(scopeId);
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
        try {
            return Response.ok(Map.of("count", scopeService.countActiveReviewJobs(scopeId))).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", "Failed to retrieve active review count")).build();
        }
    }

    // ─── Overrides ───────────────────────────────────────────────────────────

    @PUT
    @Path("/{id}/items/{issueKey}/override")
    public Response setOverride(@PathParam("id") String scopeId,
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
            scopeService.setOverride(scopeId, issueKey, status, updatedBy);
            return Response.ok(Map.of("scopeId", scopeId, "issueKey", issueKey, "status", status)).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    @DELETE
    @Path("/{id}/items/{issueKey}/override")
    public Response clearOverride(@PathParam("id") String scopeId,
                                  @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            scopeService.clearOverride(scopeId, issueKey);
            return Response.noContent().build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── AI Proposals ────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/items/{issueKey}/improve")
    public Response improveItem(@PathParam("id") String scopeId,
                                @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            return Response.ok(scopeService.improveItem(scopeId, issueKey)).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        } catch (ImprovementGenerationException e) {
            return Response.status(502).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}/items/{issueKey}/proposals")
    public Response getProposals(@PathParam("id") String scopeId,
                                 @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) {
            return badRequest("Invalid issue key format");
        }
        try {
            return Response.ok(scopeService.getProposals(scopeId, issueKey)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @PUT
    @Path("/{id}/proposals/{proposalId}")
    public Response updateProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId,
                                   Map<String, String> body) {
        try {
            return Response.ok(scopeService.updateProposal(
                    scopeId, proposalId,
                    body.getOrDefault("proposedSummary",     ""),
                    body.getOrDefault("proposedDescription", ""),
                    body.getOrDefault("proposedCriteria",    ""),
                    body.getOrDefault("proposedTechnical",   ""))).build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @POST
    @Path("/{id}/proposals/{proposalId}/accept")
    public Response acceptProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            return Response.ok(scopeService.acceptProposal(scopeId, proposalId)).build();
        } catch (ProposalNotFoundException | ScopeNotFoundException e) {
            return notFound(e.getMessage());
        } catch (ImprovementGenerationException e) {
            return Response.status(502).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/proposals/{proposalId}/reject")
    public Response rejectProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            return Response.ok(scopeService.rejectProposal(scopeId, proposalId)).build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}/proposals/{proposalId}")
    public Response deleteProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            scopeService.deleteProposal(scopeId, proposalId);
            return Response.noContent().build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // ─── Product links ────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/products")
    public Response listLinkedProducts(@PathParam("id") String scopeId) {
        try {
            return Response.ok(scopeService.listLinkedProducts(scopeId)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @PUT
    @Path("/{id}/products/{productId}")
    public Response linkProduct(@PathParam("id") String scopeId,
                                @PathParam("productId") String productId) {
        try {
            scopeService.linkProduct(scopeId, productId);
            return Response.ok(scopeService.listLinkedProducts(scopeId)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}/products/{productId}")
    public Response unlinkProduct(@PathParam("id") String scopeId,
                                  @PathParam("productId") String productId) {
        try {
            scopeService.unlinkProduct(scopeId, productId);
            return Response.noContent().build();
        } catch (ScopeNotFoundException e) {
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
        return key != null && (ISSUE_KEY_PATTERN.matcher(key).matches() || key.startsWith("VIRTUAL-"));
    }

    private static boolean hasInvalidChars(String s) {
        return s.contains("\"") || s.contains("'") || s.contains(";") || s.contains("\\");
    }

    /** Safely extracts a trimmed string value from a loosely-typed JSON body map. */
    private static String strOf(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s ? s.trim() : "";
    }

    /**
     * Extracts the {@code labels} field from the request body.
     * Accepts both {@code List<String>} (JSON array) and {@code String} (single value).
     * The legacy {@code label} field is also accepted for backward compatibility.
     */
    private static List<String> labelsOf(Map<String, Object> body) {
        Object v = body.get("labels");
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) result.add(s.trim());
            }
            if (!result.isEmpty()) return result;
        }
        if (v instanceof String s && !s.isBlank()) return List.of(s.trim());
        // Backward compat: single "label" field
        Object single = body.get("label");
        if (single instanceof String s && !s.isBlank()) return List.of(s.trim());
        return List.of();
    }

    private static Map<String, Object> scopeResponse(com.eneve.agent.model.ScopeRecord scope, Integer itemsSynced) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id",                 scope.id());
        r.put("name",               scope.name());
        r.put("labels",             scope.labels());
        r.put("label",              scope.primaryLabel()); // backward compat
        r.put("epicIssuetype",      scope.epicIssuetype());
        r.put("featureIssuetype",   scope.featureIssuetype());
        r.put("userstoryIssuetype", scope.userstoryIssuetype());
        r.put("createdAt",          scope.createdAt());
        if (itemsSynced != null) r.put("itemsSynced", itemsSynced);
        return r;
    }
}
