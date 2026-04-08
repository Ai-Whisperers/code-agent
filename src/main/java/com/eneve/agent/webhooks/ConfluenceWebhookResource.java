package com.eneve.agent.webhooks;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.agent.model.WebhookAuditEntry;
import com.eneve.agent.agent.service.KnowledgeReindexQueue;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.IntegrationFilterStore;
import com.eneve.agent.agent.store.WebhookAuditStore;
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
    @Inject KnowledgeReindexQueue reindexQueue;
    @Inject CustomerRegistryStore registryStore;
    @Inject WebhookAuditStore webhookAuditStore;
    @Inject IntegrationFilterStore integrationFilterStore;

    @Inject ObjectMapper objectMapper;

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
        String eventType = "";
        String spaceKey = "";
        String pageId = "";
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            eventType = payload.path("eventType").asText("");
            LOG.infof("Confluence webhook received: eventType=%s", eventType);

            // Only handle page events
            if (!eventType.equals("page_created") && !eventType.equals("page_updated")) {
                LOG.debugf("Ignoring Confluence webhook event: %s", eventType);
                audit("confluence", eventType, "", "", "ignored", rawPayload);
                return ok("ignored", "Unsupported event type: " + eventType);
            }

            // Extract page information
            JsonNode page = payload.path("page");
            if (page.isMissingNode()) {
                audit("confluence", eventType, "", "", "ignored", rawPayload);
                return ok("ignored", "No page information in payload");
            }

            pageId = page.path("id").asText("");
            String pageTitle = page.path("title").asText("");
            String pageUrl = page.path("_links").path("webui").asText("");

            // Extract space information
            JsonNode space = page.path("space");
            spaceKey = space.path("key").asText("");
            String spaceTitle = space.path("name").asText("");

            // Extract author information
            JsonNode author = page.path("version").path("by");
            String authorName = author.path("displayName").asText(
                    author.path("username").asText("unknown"));

            if (pageId.isBlank() || spaceKey.isBlank()) {
                audit("confluence", eventType, spaceKey, pageId, "ignored", rawPayload);
                return ok("ignored", "Missing required page or space information");
            }

            // Check integration filters — space must be enabled and webhook must be enabled
            if (!integrationFilterStore.isEnabled("confluence", spaceKey)) {
                LOG.infof("Confluence webhook: ignoring event for page %s — space %s is disabled", pageId, spaceKey);
                audit("confluence", eventType, spaceKey, pageId, "ignored", rawPayload);
                return ok("ignored", "Space disabled: " + spaceKey);
            }
            if (!integrationFilterStore.isWebhookEnabled("confluence", spaceKey)) {
                LOG.infof("Confluence webhook: ignoring event for page %s — webhooks disabled for space %s", pageId, spaceKey);
                audit("confluence", eventType, spaceKey, pageId, "ignored", rawPayload);
                return ok("ignored", "Webhook disabled for space: " + spaceKey);
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

            // Reindex this page in the knowledge store if the space is tracked
            boolean reindexQueued = false;
            if (registryStore.findByConfluenceSpace(spaceKey).isPresent()) {
                reindexQueued = reindexQueue.submitConfluencePage(pageId, pageTitle);
                LOG.debugf("Confluence webhook: knowledge reindex for page %s %s",
                        pageId, reindexQueued ? "queued" : "skipped (duplicate or queue full)");
            }

            if (hookJobIds.isEmpty() && !reindexQueued) {
                LOG.debugf("No hooks triggered and no reindex queued for Confluence %s", triggerType);
                audit("confluence", eventType, spaceKey, pageId, "no_action", rawPayload);
                return ok("no_action", "No hooks configured and space not tracked for " + triggerType);
            }

            String action = reindexQueued ? (hookJobIds.isEmpty() ? "reindex_queued" : "processed") : "hooks_evaluated";
            audit("confluence", eventType, spaceKey, pageId, action, rawPayload);
            LOG.infof("Confluence webhook: triggered %d hook jobs for %s, reindex=%s",
                    hookJobIds.size(), triggerType, reindexQueued);
            return Response.ok(Map.of(
                    "action", action,
                    "hooksTriggered", hookJobIds.size(),
                    "jobIds", hookJobIds,
                    "reindexQueued", reindexQueued
            )).build();

        } catch (Exception e) {
            LOG.errorf("Confluence webhook processing error: %s", e.getMessage());
            audit("confluence", eventType, spaceKey, pageId, "error", rawPayload);
            return Response.serverError().entity(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    private void audit(String platform, String eventType, String spaceKey,
                       String pageId, String action, String rawPayload) {
        try {
            webhookAuditStore.save(WebhookAuditEntry.create(
                    platform, eventType, spaceKey, pageId, null, null, action, List.of(), rawPayload));
        } catch (Exception e) {
            LOG.warnf("Failed to save Confluence webhook audit entry (non-fatal): %s", e.getMessage());
        }
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
