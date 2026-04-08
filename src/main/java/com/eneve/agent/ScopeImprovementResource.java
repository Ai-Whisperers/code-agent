package com.eneve.agent;

import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.agent.service.ScopeImproveChatService;
import com.eneve.agent.model.ConversationContext;
import com.eneve.agent.scope.ScopeExceptions.*;
import com.eneve.agent.scope.ScopeImprovementService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST endpoints for AI-driven scope improvement: proposals, Claude generation, and Jira write-back.
 * All URLs are under {@code /scope/{id}/improvement}.
 */
@Path("/scope/{id}/improvement")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class ScopeImprovementResource {

    private static final Logger LOG = Logger.getLogger(ScopeImprovementResource.class);
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]+-[0-9]+$");

    @Inject ScopeImprovementService improvementService;
    @Inject ScopeImproveChatService scopeImproveChatService;
    @Inject jakarta.enterprise.inject.Instance<JsonWebToken> jwtInstance;

    // ─── Improve ──────────────────────────────────────────────────────────────

    @POST
    @Path("/items/{issueKey}/improve")
    public Response improveItem(@PathParam("id") String scopeId,
                                @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            return Response.ok(improvementService.improveItem(scopeId, issueKey)).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        } catch (ImprovementGenerationException e) {
            return Response.status(502).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ─── Proposals ────────────────────────────────────────────────────────────

    @GET
    @Path("/items/{issueKey}/proposals")
    public Response getProposals(@PathParam("id") String scopeId,
                                 @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            return Response.ok(improvementService.getProposals(scopeId, issueKey)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @POST
    @Path("/items/{issueKey}/proposal/init")
    public Response initProposal(@PathParam("id") String scopeId,
                                  @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            InitProposalResult result = improvementService.initProposal(scopeId, issueKey);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("proposal",      result.proposal());
            resp.put("jiraUpdatedAt", result.jiraUpdatedAt() != null ? result.jiraUpdatedAt().toString() : null);
            resp.put("attachments", result.attachments().stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",         a.id());
                m.put("filename",   a.filename());
                m.put("mimeType",   a.mimeType());
                m.put("size",       a.size());
                m.put("contentUrl", a.contentUrl());
                return m;
            }).collect(java.util.stream.Collectors.toList()));
            return Response.ok(resp).build();
        } catch (ScopeNotFoundException | JiraIssueNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @POST
    @Path("/items/{issueKey}/propose-features")
    public Response proposeFeaturesForEpic(@PathParam("id") String scopeId,
                                            @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            return Response.ok(improvementService.proposeFeaturesForEpic(scopeId, issueKey)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @POST
    @Path("/items/{issueKey}/propose-stories")
    public Response proposeUserStoriesForFeature(@PathParam("id") String scopeId,
                                                  @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        try {
            return Response.ok(improvementService.proposeUserStoriesForFeature(scopeId, issueKey)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @POST
    @Path("/proposals/new-feature")
    public Response createNewFeatureProposal(@PathParam("id") String scopeId,
                                              Map<String, String> body) {
        String parentKey = body != null ? body.getOrDefault("parentKey", null) : null;
        String proposedSummary = body != null ? body.getOrDefault("proposedSummary", null) : null;
        if (parentKey == null || parentKey.isBlank()) return badRequest("parentKey is required");
        try {
            return Response.ok(improvementService.createNewFeatureProposal(scopeId, parentKey, proposedSummary)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @PUT
    @Path("/proposals/{proposalId}")
    public Response updateProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId,
                                   Map<String, String> body) {
        try {
            return Response.ok(improvementService.updateProposal(
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
    @Path("/proposals/{proposalId}/accept")
    public Response acceptProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            return Response.ok(improvementService.acceptProposal(scopeId, proposalId, resolveUserDisplay())).build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @POST
    @Path("/proposals/{proposalId}/reject")
    public Response rejectProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            return Response.ok(improvementService.rejectProposal(scopeId, proposalId)).build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/proposals/{proposalId}")
    public Response deleteProposal(@PathParam("id") String scopeId,
                                   @PathParam("proposalId") String proposalId) {
        try {
            improvementService.deleteProposal(scopeId, proposalId);
            return Response.noContent().build();
        } catch (ProposalNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // ─── Chat stream ──────────────────────────────────────────────────────────

    @POST
    @Blocking
    @Path("/items/{issueKey}/improve-chat")
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

    // ─── Response helpers ─────────────────────────────────────────────────────

    private static Response badRequest(String message) {
        return Response.status(400).entity(Map.of("error", message)).build();
    }

    private static Response notFound(String message) {
        return Response.status(404).entity(Map.of("error", message)).build();
    }

    private static boolean isValidIssueKey(String key) {
        return key != null && (ISSUE_KEY_PATTERN.matcher(key).matches()
                || key.startsWith("VIRTUAL-")
                || key.startsWith("NEW-"));
    }

    private static String strOf(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s ? s.trim() : "";
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
}
