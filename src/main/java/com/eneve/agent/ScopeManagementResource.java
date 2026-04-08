package com.eneve.agent;

import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.ScopeRecord;
import com.eneve.agent.scope.ScopeExceptions.*;
import com.eneve.agent.scope.ScopeManagementService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoints for scope lifecycle management: CRUD, sync, product links, and attachments.
 * All URLs are under {@code /scope} — no path prefix change from the original {@code ScopeResource}.
 */
@Path("/scope")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
public class ScopeManagementResource {

    private static final Logger LOG = Logger.getLogger(ScopeManagementResource.class);

    @Inject ScopeManagementService managementService;

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    @GET
    public Response listScopes(@QueryParam("type") String type) {
        List<Map<String, Object>> result = managementService.listScopesByType(type).stream()
                .map(s -> scopeResponse(s, null))
                .collect(Collectors.toList());
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

        String scopeType = strOf(body, "scopeType");
        if (scopeType.isBlank()) scopeType = "po";

        CreateScopeResult result = managementService.createScope(
                name, labels,
                strOf(body, "epicIssuetype"),
                strOf(body, "featureIssuetype"),
                strOf(body, "userstoryIssuetype"),
                scopeType,
                strOf(body, "etrProjectKey"));

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
            Object updated = managementService.updateScope(id, name, labels,
                    strOf(body, "epicIssuetype"),
                    strOf(body, "featureIssuetype"),
                    strOf(body, "userstoryIssuetype"),
                    strOf(body, "etrProjectKey"));
            return Response.ok(updated).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteScope(@PathParam("id") String id) {
        try {
            managementService.deleteScope(id);
            return Response.noContent().build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    @GET
    @Path("/{id}")
    public Response getScope(@PathParam("id") String id) {
        try {
            return Response.ok(scopeResponse(managementService.getScope(id), null)).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Preview labels ───────────────────────────────────────────────────────

    @GET
    @Path("/preview-labels")
    public Response previewLabels(@QueryParam("labels") List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return badRequest("at least one label is required");
        }
        return Response.ok(managementService.previewLabels(labels)).build();
    }

    // ─── Token stats ──────────────────────────────────────────────────────────

    @GET
    @Path("/review-token-stats")
    public Response getReviewTokenStats() {
        return Response.ok(managementService.getReviewTokenStats()).build();
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/sync")
    public Response syncScope(@PathParam("id") String scopeId) {
        try {
            return Response.ok(Map.of("itemsSynced", managementService.syncScope(scopeId))).build();
        } catch (ScopeNotFoundException e) {
            return notFound("Scope not found");
        }
    }

    // ─── Product links ────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/products")
    public Response listLinkedProducts(@PathParam("id") String scopeId) {
        try {
            return Response.ok(managementService.listLinkedProducts(scopeId)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @PUT
    @Path("/{id}/products/{productId}")
    public Response linkProduct(@PathParam("id") String scopeId,
                                @PathParam("productId") String productId) {
        try {
            managementService.linkProduct(scopeId, productId);
            return Response.ok(managementService.listLinkedProducts(scopeId)).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}/products/{productId}")
    public Response unlinkProduct(@PathParam("id") String scopeId,
                                  @PathParam("productId") String productId) {
        try {
            managementService.unlinkProduct(scopeId, productId);
            return Response.noContent().build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // ─── Attachments ──────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/items/{issueKey}/attachments")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response proxyAttachment(@PathParam("id") String scopeId,
                                     @PathParam("issueKey") String issueKey,
                                     @QueryParam("url") String contentUrl) {
        if (contentUrl == null || contentUrl.isBlank()) return badRequest("url query parameter is required");
        try {
            byte[] bytes = managementService.fetchJiraAttachmentBytes(contentUrl);
            if (bytes == null) return Response.status(502).entity("Failed to fetch attachment from Jira").build();
            return Response.ok(bytes, MediaType.APPLICATION_OCTET_STREAM).build();
        } catch (ScopeNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @GET
    @Path("/{id}/items/{issueKey}/attachments-list")
    public Response listAttachments(@PathParam("id") String scopeId,
                                    @PathParam("issueKey") String issueKey) {
        if (!isValidIssueKey(issueKey)) return badRequest("Invalid issue key format");
        if (issueKey.startsWith("NEW-") || issueKey.startsWith("VIRTUAL-"))
            return Response.ok(List.of()).build();
        try {
            List<JiraService.JiraAttachment> atts = managementService.fetchAttachmentsForIssue(issueKey);
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

    // ─── Response helpers ─────────────────────────────────────────────────────

    private static Response badRequest(String message) {
        return Response.status(400).entity(Map.of("error", message)).build();
    }

    private static Response notFound(String message) {
        return Response.status(404).entity(Map.of("error", message)).build();
    }

    private static boolean isValidIssueKey(String key) {
        return key != null && (java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]+-[0-9]+$").matcher(key).matches()
                || key.startsWith("VIRTUAL-")
                || key.startsWith("NEW-"));
    }

    private static boolean hasInvalidChars(String s) {
        return s.contains("\"") || s.contains("'") || s.contains(";") || s.contains("\\");
    }

    private static String strOf(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s ? s.trim() : "";
    }

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
        Object single = body.get("label");
        if (single instanceof String s && !s.isBlank()) return List.of(s.trim());
        return List.of();
    }

    private static Map<String, Object> scopeResponse(ScopeRecord scope, Integer itemsSynced) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id",                 scope.id());
        r.put("name",               scope.name());
        r.put("labels",             scope.labels());
        r.put("label",              scope.primaryLabel());
        r.put("epicIssuetype",      scope.epicIssuetype());
        r.put("featureIssuetype",   scope.featureIssuetype());
        r.put("userstoryIssuetype", scope.userstoryIssuetype());
        r.put("createdAt",          scope.createdAt());
        r.put("scopeType",          scope.scopeType() != null ? scope.scopeType() : "po");
        r.put("etrProjectKey",      scope.etrProjectKey());
        if (itemsSynced != null) r.put("itemsSynced", itemsSynced);
        return r;
    }
}
