package com.eneve.agent;

import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.agent.service.ScopeImproveChatService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.ConversationContext;
import com.eneve.agent.scope.ScopeService;
import com.eneve.agent.scope.ScopeService.ActiveJobExistsException;
import com.eneve.agent.scope.ScopeService.ImprovementGenerationException;
import com.eneve.agent.scope.ScopeService.ItemOverriddenException;
import com.eneve.agent.scope.ScopeService.JiraIssueNotFoundException;
import com.eneve.agent.scope.ScopeService.ProposalNotFoundException;
import com.eneve.agent.scope.ScopeService.ScopeNotFoundException;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

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

    @Inject ScopeService scopeService;
    @Inject ScopeImproveChatService scopeImproveChatService;
    @Inject jakarta.enterprise.inject.Instance<JsonWebToken> jwtInstance;

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

    @POST
    @Path("/{id}/items/{issueKey}/proposal/init")
    public Response initProposal(@PathParam("id") String scopeId,
                                  @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            ScopeService.InitProposalResult result = scopeService.initProposal(scopeId, issueKey);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("proposal",       result.proposal());
            resp.put("jiraUpdatedAt",  result.jiraUpdatedAt() != null ? result.jiraUpdatedAt().toString() : null);
            resp.put("attachments", result.attachments().stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",         a.id());
                m.put("filename",   a.filename());
                m.put("mimeType",   a.mimeType());
                m.put("size",       a.size());
                // contentUrl is the Jira content URL; the browser should use the proxy endpoint
                m.put("contentUrl", a.contentUrl());
                return m;
            }).collect(java.util.stream.Collectors.toList()));
            return Response.ok(resp).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    /**
     * Analyses the given EPIC with Claude, identifies missing features, and returns a list
     * of newly created DRAFT proposals (may be empty if nothing is missing).
     */
    @POST
    @Path("/{id}/items/{issueKey}/propose-features")
    public Response proposeFeaturesForEpic(@PathParam("id") String scopeId,
                                            @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            List<com.eneve.agent.model.ScopeProposal> proposals =
                    scopeService.proposeFeaturesForEpic(scopeId, issueKey);
            return Response.ok(proposals).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    /**
     * Analyses the given FEATURE with Claude, identifies missing user stories, and returns a list
     * of newly created DRAFT proposals (may be empty if nothing is missing).
     */
    @POST
    @Path("/{id}/items/{issueKey}/propose-stories")
    public Response proposeUserStoriesForFeature(@PathParam("id") String scopeId,
                                                  @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            List<com.eneve.agent.model.ScopeProposal> proposals =
                    scopeService.proposeUserStoriesForFeature(scopeId, issueKey);
            return Response.ok(proposals).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    /**
     * Runs an AI readiness review synchronously, bypassing the job queue.
     * Returns the persisted {@link com.eneve.agent.model.JiraIssueReview} immediately — used
     * by the Scope Improve page to refresh the score automatically after saving a proposal.
     */
    @POST
    @Path("/{id}/items/{issueKey}/review-direct")
    public Response reviewItemDirect(@PathParam("id") String scopeId,
                                     @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            com.eneve.agent.model.JiraIssueReview review =
                    scopeService.reviewItemDirect(scopeId, issueKey);
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

    /**
     * Creates a new blank DRAFT FEATURE proposal that is not yet backed by a Jira issue.
     * A synthetic issue key ({@code NEW-XXXXXXXX}) is generated; the real Jira issue is
     * created when the user accepts the proposal.
     *
     * <p>Request body: {@code { "parentKey": "PROJ-123", "proposedSummary": "optional title" }}</p>
     */
    @POST
    @Path("/{id}/proposals/new-feature")
    public Response createNewFeatureProposal(@PathParam("id") String scopeId,
                                              Map<String, String> body) {
        String parentKey = body != null ? body.getOrDefault("parentKey", null) : null;
        String proposedSummary = body != null ? body.getOrDefault("proposedSummary", null) : null;
        if (parentKey == null || parentKey.isBlank()) return badRequest("parentKey is required");
        try {
            return Response.ok(scopeService.createNewFeatureProposal(scopeId, parentKey, proposedSummary)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    /**
     * Proxy endpoint that streams a Jira attachment through the backend so the browser
     * avoids Jira CORS/auth issues. The attachment is identified by its Jira content URL
     * which is passed as a query parameter (URL-encoded).
     */
    @GET
    @Path("/{id}/items/{issueKey}/attachments")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response proxyAttachment(@PathParam("id") String scopeId,
                                     @PathParam("issueKey") String issueKey,
                                     @QueryParam("url") String contentUrl) {
        if (contentUrl == null || contentUrl.isBlank()) return badRequest("url query parameter is required");
        try {
            byte[] bytes = scopeService.fetchJiraAttachmentBytes(contentUrl);
            if (bytes == null) return Response.status(502).entity("Failed to fetch attachment from Jira").build();
            return Response.ok(bytes, MediaType.APPLICATION_OCTET_STREAM).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    /**
     * Returns the list of Jira attachments for a scope item, fetched live from Jira.
     * Used by the Attachments tab in the Improve UI so attachment metadata is always fresh.
     * Synthetic keys (NEW-*, VIRTUAL-*) return an empty list immediately.
     */
    @GET
    @Blocking
    @Path("/{id}/items/{issueKey}/attachments-list")
    public Response listAttachments(@PathParam("id") String scopeId,
                                    @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        if (issueKey.startsWith("NEW-") || issueKey.startsWith("VIRTUAL-"))
            return Response.ok(List.of()).build();
        try {
            List<JiraService.JiraAttachment> atts = scopeService.fetchAttachmentsForIssue(issueKey);
            List<Map<String, Object>> payload = atts.stream()
                    .map(a -> Map.<String, Object>of(
                            "id",         a.id(),
                            "filename",   a.filename(),
                            "mimeType",   a.mimeType(),
                            "size",       a.size(),
                            "contentUrl", a.contentUrl()
                    ))
                    .toList();
            return Response.ok(payload).build();
        } catch (Exception e) {
            LOG.errorf("Failed to list attachments for %s: %s", issueKey, e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Blocking
    @Path("/{id}/items/{issueKey}/improve-chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<ChatEvent> improveChat(@PathParam("id") String scopeId,
                                         @PathParam("issueKey") String issueKey,
                                         Map<String, Object> body) {
        if (!isValidIssueKey(issueKey)) {
            return Multi.createFrom().item(new ChatEvent.Error("Invalid issue key format"));
        }
        String message = strOf(body, "message");
        if (message.isBlank()) {
            return Multi.createFrom().item(new ChatEvent.Error("message is required"));
        }

        @SuppressWarnings("unchecked")
        List<String> proposalIds = body.get("proposalIds") instanceof List<?> l
                ? (List<String>) l : List.of();

        ConversationContext ctx = null;
        if (body.get("conversationContext") instanceof Map<?, ?>) {
            try {
                ctx = new com.fasterxml.jackson.databind.ObjectMapper()
                        .convertValue(body.get("conversationContext"), ConversationContext.class);
            } catch (Exception ignored) { }
        }

        ScopeImproveChatService.ScopeImproveChatRequest request =
                new ScopeImproveChatService.ScopeImproveChatRequest(
                        message,
                        strOf(body, "conversationId"),
                        scopeId,
                        issueKey,
                        proposalIds,
                        ctx,
                        strOf(body, "mode"));

        return scopeImproveChatService.chatStream(request, resolveUserId());
    }

    @PUT
    @Path("/{id}/proposals/{proposalId}")
    public Response updateProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId,
                                   Map<String, String> body) {
        try {
            return Response.ok(scopeService.updateProposal(
                    scopeId, proposalId,
                    body.getOrDefault("proposedSummary",     null),
                    body.getOrDefault("proposedDescription", null),
                    body.getOrDefault("proposedCriteria",    null),
                    body.getOrDefault("proposedTechnical",   null),
                    body.getOrDefault("proposedLabel",       null),
                    body.getOrDefault("proposedPriority",    null),
                    resolveUserDisplay())).build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @POST
    @Path("/{id}/proposals/{proposalId}/accept")
    public Response acceptProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            return Response.ok(scopeService.acceptProposal(scopeId, proposalId, resolveUserDisplay())).build();
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
        return key != null && (ISSUE_KEY_PATTERN.matcher(key).matches()
                || key.startsWith("VIRTUAL-")
                || key.startsWith("NEW-"));
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

    private String resolveUserId() {
        try {
            if (!jwtInstance.isUnsatisfied() && !jwtInstance.isAmbiguous()) {
                JsonWebToken jwt = jwtInstance.get();
                String sub = jwt.getSubject();
                if (sub != null && !sub.isBlank()) return sub;
            }
        } catch (Exception ignored) { }
        return "anonymous";
    }

    /** Returns a human-readable display name: preferred_username > name > email > subject. */
    private String resolveUserDisplay() {
        try {
            if (!jwtInstance.isUnsatisfied() && !jwtInstance.isAmbiguous()) {
                JsonWebToken jwt = jwtInstance.get();
                for (String claim : new String[]{"preferred_username", "name", "email"}) {
                    Object val = jwt.getClaim(claim);
                    if (val instanceof String s && !s.isBlank()) return s;
                }
                String sub = jwt.getSubject();
                if (sub != null && !sub.isBlank()) return sub;
            }
        } catch (Exception ignored) { }
        return "anonymous";
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
