package com.eneve.agent;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.audit.AuditService;
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
import org.jboss.logging.Logger;
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

    private static final Logger LOG = Logger.getLogger(ScopeResource.class);
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]+-[0-9]+$");

    @Inject
    ScopeService scopeService;

    @Inject
    AuditService auditService;

    @Inject
    JobStore jobStore;

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

        ScopeService.CreateScopeResult result = scopeService.createScope(
                name, labels,
                strOf(body, "epicIssuetype"),
                strOf(body, "featureIssuetype"),
                strOf(body, "userstoryIssuetype"));

        auditService.log("SCOPE", "SCOPE_CREATED", "scope", result.scope().id(),
                Map.of("name", name, "labels", labels, "itemsSynced", result.itemsSynced()));

        return Response.status(201).entity(scopeResponse(result.scope(), result.itemsSynced())).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateScope(@PathParam("id") String id, Map<String, Object> body) {
        String name = strOf(body, "name");
        List<String> labels = labelsOf(body);
        if (name.isBlank()) {
            return badRequest("name is required");
        }
        try {
            Object updated = scopeService.updateScope(id, name, labels,
                    strOf(body, "epicIssuetype"),
                    strOf(body, "featureIssuetype"),
                    strOf(body, "userstoryIssuetype"));
            auditService.log("SCOPE", "SCOPE_UPDATED", "scope", id,
                    Map.of("name", name, "labels", labels));
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
            auditService.log("SCOPE", "SCOPE_DELETED", "scope", id);
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
            Object result = scopeService.refreshItem(scopeId, issueKey);
            auditService.log("SCOPE", "ITEM_REFRESHED", "scope_item", issueKey,
                    Map.of("scopeId", scopeId));
            return Response.ok(result).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/sync")
    public Response syncScope(@PathParam("id") String scopeId) {
        try {
            int itemsSynced = scopeService.syncScope(scopeId);
            auditService.log("SCOPE", "SCOPE_SYNCED", "scope", scopeId,
                    Map.of("itemsSynced", itemsSynced));
            return Response.ok(Map.of("itemsSynced", itemsSynced)).build();
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
            String jobId = scopeService.enqueueReview(scopeId, issueKey);
            auditService.log("SCOPE", "REVIEW_ENQUEUED", "scope_item", issueKey,
                    Map.of("scopeId", scopeId, "jobId", jobId));
            return Response.accepted(Map.of("jobId", jobId)).build();
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
            auditService.log("SCOPE", "REVIEW_ALL_ENQUEUED", "scope", scopeId,
                    Map.of("jobsEnqueued", result.jobsEnqueued(),
                           "jobsSkipped",  result.jobsSkipped(),
                           "jobsUnchanged", result.jobsUnchanged(),
                           "force", force));
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
            long count = jobStore.countActiveReviewJobsForRoadmap(scopeId);
            return Response.ok(Map.of("count", count)).build();
        } catch (Exception e) {
            LOG.errorf("Failed to count active review jobs for scope %s: %s", scopeId, e.getMessage());
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
            auditService.log("SCOPE", "OVERRIDE_SET", "scope_item", issueKey,
                    Map.of("scopeId", scopeId, "status", status));
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
            auditService.log("SCOPE", "OVERRIDE_CLEARED", "scope_item", issueKey,
                    Map.of("scopeId", scopeId));
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
            Object proposal = scopeService.improveItem(scopeId, issueKey);
            auditService.log("SCOPE", "PROPOSAL_CREATED", "scope_item", issueKey,
                    Map.of("scopeId", scopeId));
            return Response.ok(proposal).build();
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
            Object updated = scopeService.updateProposal(
                    scopeId, proposalId,
                    body.getOrDefault("proposedSummary",     ""),
                    body.getOrDefault("proposedDescription", ""),
                    body.getOrDefault("proposedCriteria",    ""),
                    body.getOrDefault("proposedTechnical",   ""));
            auditService.log("SCOPE", "PROPOSAL_UPDATED", "scope_proposal", proposalId,
                    Map.of("scopeId", scopeId));
            return Response.ok(updated).build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @POST
    @Path("/{id}/proposals/{proposalId}/accept")
    public Response acceptProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            Object result = scopeService.acceptProposal(scopeId, proposalId);
            auditService.log("SCOPE", "PROPOSAL_ACCEPTED", "scope_proposal", proposalId,
                    Map.of("scopeId", scopeId));
            return Response.ok(result).build();
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
            Object result = scopeService.rejectProposal(scopeId, proposalId);
            auditService.log("SCOPE", "PROPOSAL_REJECTED", "scope_proposal", proposalId,
                    Map.of("scopeId", scopeId));
            return Response.ok(result).build();
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
            auditService.log("SCOPE", "PROPOSAL_DELETED", "scope_proposal", proposalId,
                    Map.of("scopeId", scopeId));
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
            auditService.log("SCOPE", "PRODUCT_LINKED", "scope", scopeId,
                    Map.of("productId", productId));
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
            auditService.log("SCOPE", "PRODUCT_UNLINKED", "scope", scopeId,
                    Map.of("productId", productId));
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
    @SuppressWarnings("unchecked")
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
