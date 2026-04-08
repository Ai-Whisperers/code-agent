package com.eneve.agent;

import com.eneve.agent.agent.store.IntegrationFilterStore;
import com.eneve.agent.agent.store.KnowledgeEmbeddingStore;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.model.IntegrationFilter;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * REST API for managing per-project / per-space enable/disable filters for
 * Jira and Confluence integrations.
 *
 * <p>Opt-in model: projects and spaces with no stored row are treated as
 * disabled. A row must be created (enabled=true) before the project/space is used.
 */
@RolesAllowed("app_admin")
@Path("/integration-filters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Integration Filters", description = "Enable/disable Jira projects and Confluence spaces")
public class IntegrationFiltersResource {

    private static final Logger LOG = Logger.getLogger(IntegrationFiltersResource.class);

    @Inject IntegrationFilterStore filterStore;
    @Inject KnowledgeEmbeddingStore embeddingStore;
    @Inject AuditService auditService;

    // ── Request body ──────────────────────────────────────────────────────────

    public record UpsertFilterRequest(
            String name,
            boolean enabled,
            boolean webhookEnabled
    ) {}

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @GET
    @Operation(
            operationId = "listIntegrationFilters",
            summary = "List integration filters",
            description = "Returns all stored filter rows for the given integration type. "
                    + "Projects/spaces with no row are implicitly disabled (opt-in model)."
    )
    @APIResponse(responseCode = "200", description = "List of filters")
    @APIResponse(responseCode = "400", description = "Missing or invalid type parameter")
    public Response list(
            @Parameter(description = "Integration type: 'jira' or 'confluence'", required = true)
            @QueryParam("type") String type) {

        if (type == null || (!type.equals("jira") && !type.equals("confluence"))) {
            return Response.status(400)
                    .entity(Map.of("error", "type must be 'jira' or 'confluence'"))
                    .build();
        }
        List<IntegrationFilter> filters = filterStore.listByType(type);
        return Response.ok(filters).build();
    }

    @PUT
    @Path("/{type}/{key}")
    @Operation(
            operationId = "upsertIntegrationFilter",
            summary = "Create or update an integration filter",
            description = "Stores the enabled/webhookEnabled flags for a Jira project or Confluence space. "
                    + "If enabled transitions from true to false, all existing embeddings for the "
                    + "project/space are immediately purged from the knowledge base."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Filter saved"),
            @APIResponse(responseCode = "400", description = "Invalid type or missing body")
    })
    public Response upsert(
            @Parameter(description = "Integration type: 'jira' or 'confluence'", required = true)
            @PathParam("type") String type,
            @Parameter(description = "Jira project key or Confluence space key", required = true)
            @PathParam("key") String key,
            @RequestBody(required = true) UpsertFilterRequest body) {

        if (!type.equals("jira") && !type.equals("confluence")) {
            return Response.status(400)
                    .entity(Map.of("error", "type must be 'jira' or 'confluence'"))
                    .build();
        }
        if (body == null) {
            return Response.status(400).entity(Map.of("error", "request body is required")).build();
        }

        boolean wasEnabled = filterStore.isEnabled(type, key);
        filterStore.upsert(type, key, body.name(), body.enabled(), body.webhookEnabled());

        if (wasEnabled && !body.enabled()) {
            purgeEmbeddings(type, key);
        }

        auditService.log("INTEGRATION_FILTERS", "FILTER_UPDATED", type, key,
                Map.of("enabled", body.enabled(), "webhookEnabled", body.webhookEnabled()));

        return filterStore.findByTypeAndKey(type, key)
                .map(f -> Response.ok(f).build())
                .orElse(Response.ok().build());
    }

    @DELETE
    @Path("/{type}/{key}")
    @Operation(
            operationId = "resetIntegrationFilter",
            summary = "Reset an integration filter to defaults",
            description = "Removes the stored row, restoring the default behaviour (enabled=true, webhookEnabled=true). "
                    + "Does NOT purge embeddings — the project/space is considered enabled again."
    )
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Filter reset (row deleted)"),
            @APIResponse(responseCode = "400", description = "Invalid type")
    })
    public Response reset(
            @Parameter(description = "Integration type: 'jira' or 'confluence'", required = true)
            @PathParam("type") String type,
            @Parameter(description = "Jira project key or Confluence space key", required = true)
            @PathParam("key") String key) {

        if (!type.equals("jira") && !type.equals("confluence")) {
            return Response.status(400)
                    .entity(Map.of("error", "type must be 'jira' or 'confluence'"))
                    .build();
        }
        filterStore.delete(type, key);
        auditService.log("INTEGRATION_FILTERS", "FILTER_RESET", type, key);
        return Response.noContent().build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void purgeEmbeddings(String type, String key) {
        try {
            if (type.equals("jira")) {
                int deleted = embeddingStore.deleteBySource("jira", key);
                int deletedAttachments = embeddingStore.deleteBySourceIdPrefix("jira-attachment", key + "/attachment/");
                LOG.infof("Purged %d jira + %d jira-attachment chunks for disabled project %s",
                        deleted, deletedAttachments, key);
            } else {
                int deleted = embeddingStore.deleteBySource("confluence", key);
                LOG.infof("Purged %d confluence chunks for disabled space %s", deleted, key);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to purge embeddings for disabled %s/%s: %s", type, key, e.getMessage());
        }
    }
}
