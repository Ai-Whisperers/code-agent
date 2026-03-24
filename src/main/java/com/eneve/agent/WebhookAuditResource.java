package com.eneve.agent;

import com.eneve.agent.agent.store.WebhookAuditStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/webhook-audit")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhook Audit", description = "Query the webhook event audit log")
public class WebhookAuditResource {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    @Inject
    WebhookAuditStore webhookAuditStore;

    @GET
    @Operation(
            operationId = "listWebhookAuditLog",
            summary = "List recent webhook audit entries",
            description = "Returns the most recent webhook events received by the agent, ordered by received time descending."
    )
    @APIResponse(responseCode = "200", description = "List of audit entries")
    public Response listRecent(
            @Parameter(description = "Maximum number of entries to return (default 50, max 500)")
            @QueryParam("limit") Integer limit) {

        int effectiveLimit = clamp(limit, DEFAULT_LIMIT);
        return Response.ok(webhookAuditStore.listRecent(effectiveLimit)).build();
    }

    @GET
    @Path("/{workspace}/{repoSlug}")
    @Operation(
            operationId = "listWebhookAuditLogByRepo",
            summary = "List webhook audit entries for a specific repository",
            description = "Returns recent webhook events for the given workspace and repository, ordered by received time descending."
    )
    @APIResponse(responseCode = "200", description = "List of audit entries for the repository")
    public Response listByRepo(
            @Parameter(description = "Workspace or organisation slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,
            @Parameter(description = "Maximum number of entries to return (default 50, max 500)")
            @QueryParam("limit") Integer limit) {

        int effectiveLimit = clamp(limit, DEFAULT_LIMIT);
        return Response.ok(webhookAuditStore.listByRepo(workspace, repoSlug, effectiveLimit)).build();
    }

    private static int clamp(Integer requested, int defaultValue) {
        if (requested == null || requested <= 0) return defaultValue;
        return Math.min(requested, MAX_LIMIT);
    }
}
