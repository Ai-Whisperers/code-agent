package com.eneve.agent.webhooks;

import java.util.Map;

import com.eneve.agent.agent.HookEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Handles incoming Confluence Cloud webhooks for page events.
 * Triggers automation hooks based on page creation/update events.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class ConfluenceWebhookResource {

    private static final Logger LOG = Logger.getLogger(ConfluenceWebhookResource.class);

    @Inject HookEvaluator hookEvaluator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/confluence")
    @Operation(
            operationId = "confluenceWebhook",
            summary = "Handle Confluence Cloud webhook events",
            description = "Receives Confluence Cloud webhook payloads for page_created and page_updated events. "
                    + "Triggers automation hooks based on the page event and space context. "
                    + "Signature verification is enforced by WebhookSignatureFilter before this handler is called."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed successfully",
                    content = @Content(schema = @Schema(example = "{\"action\": \"hooks_evaluated\", \"hooksTriggered\": 2}"))),
            @APIResponse(responseCode = "202", description = "Webhook received but no action taken")
    })
    public Response handleConfluenceWebhook(String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String eventType = payload.path("eventType").asText("");
            LOG.infof("Confluence webhook received: eventType=%s", eventType);

            // Only handle page events
            if (!eventType.equals("page_created") && !eventType.equals("page_updated")) {
                LOG.debugf("Ignoring Confluence webhook event: %s", eventType);
                return ok("ignored", "Unsupported event type: " + eventType);
            }

            // Extract page information
            JsonNode page = payload.path("page");
            if (page.isMissingNode()) {
                return ok("ignored", "No page information in payload");
            }

            String pageId = page.path("id").asText("");
            String pageTitle = page.path("title").asText("");
            String pageUrl = page.path("_links").path("webui").asText("");

            // Extract space information
            JsonNode space = page.path("space");
            String spaceKey = space.path("key").asText("");
            String spaceTitle = space.path("name").asText("");

            // Extract author information
            JsonNode author = page.path("version").path("by");
            String authorName = author.path("displayName").asText(
                    author.path("username").asText("unknown"));

            if (pageId.isBlank() || spaceKey.isBlank()) {
                return ok("ignored", "Missing required page or space information");
            }

            LOG.infof("Confluence webhook: %s for page '%s' in space '%s'", eventType, pageTitle, spaceKey);

            // Determine trigger type
            String triggerType = eventType.equals("page_created") ? "confluence.page_created" : "confluence.page_updated";

            // Build context
            var context = Map.of(
                    "pageId", pageId,
                    "pageTitle", pageTitle,
                    "spaceKey", spaceKey,
                    "spaceTitle", spaceTitle,
                    "author", authorName,
                    "pageUrl", pageUrl
            );

            // Evaluate hooks - use space key as both workspace and repo slug
            var hookJobIds = hookEvaluator.evaluateByTrigger(
                    triggerType, spaceKey, "confluence", null, context);

            if (hookJobIds.isEmpty()) {
                LOG.debugf("No hooks triggered for Confluence %s", triggerType);
                return ok("no_hooks", "No hooks configured for " + triggerType);
            }

            LOG.infof("Confluence webhook: triggered %d hook jobs for %s", hookJobIds.size(), triggerType);
            return Response.ok(Map.of(
                    "action", "hooks_evaluated",
                    "hooksTriggered", hookJobIds.size(),
                    "jobIds", hookJobIds
            )).build();

        } catch (Exception e) {
            LOG.errorf("Confluence webhook processing error: %s", e.getMessage());
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
