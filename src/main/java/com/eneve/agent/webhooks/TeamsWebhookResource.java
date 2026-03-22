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
 * Handles incoming Microsoft Teams webhooks for message/mention events.
 * Triggers automation hooks based on Teams channel activity.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class TeamsWebhookResource {

    private static final Logger LOG = Logger.getLogger(TeamsWebhookResource.class);

    @Inject HookEvaluator hookEvaluator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/teams")
    @Operation(
            operationId = "teamsWebhook",
            summary = "Handle Microsoft Teams webhook events",
            description = "Receives Microsoft Teams webhook payloads for message and activity events. "
                    + "Triggers automation hooks based on team activity, mentions, and channel messages. "
                    + "Supports both bot framework webhooks and incoming webhook connectors."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed successfully",
                    content = @Content(schema = @Schema(example = "{\"action\": \"hooks_evaluated\", \"hooksTriggered\": 1}"))),
            @APIResponse(responseCode = "202", description = "Webhook received but no action taken")
    })
    public Response handleTeamsWebhook(String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            LOG.debugf("Teams webhook payload: %s", payload.toString());

            // Handle different Teams webhook formats
            String eventType = determineEventType(payload);
            if (eventType == null) {
                return ok("ignored", "Unsupported Teams webhook format");
            }

            LOG.infof("Teams webhook received: eventType=%s", eventType);

            // Extract common fields
            String messageText = extractMessageText(payload);
            String author = extractAuthor(payload);
            String channelId = extractChannelId(payload);
            String teamId = extractTeamId(payload);
            String conversationType = extractConversationType(payload);

            if (messageText.isBlank() && !eventType.equals("teams.activity")) {
                return ok("ignored", "Empty message content");
            }

            // Build context
            var context = Map.of(
                    "eventType", eventType,
                    "messageText", messageText,
                    "author", author,
                    "channelId", channelId != null ? channelId : "",
                    "teamId", teamId != null ? teamId : "",
                    "conversationType", conversationType != null ? conversationType : ""
            );

            // Evaluate hooks using team ID as workspace, channel ID as repo
            String workspace = teamId != null && !teamId.isBlank() ? teamId : "teams";
            String repoSlug = channelId != null && !channelId.isBlank() ? channelId : "general";

            var hookJobIds = hookEvaluator.evaluateByTrigger(
                    "teams.message", workspace, repoSlug, null, context);

            if (hookJobIds.isEmpty()) {
                LOG.debugf("No hooks triggered for Teams event %s", eventType);
                return ok("no_hooks", "No hooks configured for teams.message");
            }

            LOG.infof("Teams webhook: triggered %d hook jobs for %s", hookJobIds.size(), eventType);
            return Response.ok(Map.of(
                    "action", "hooks_evaluated",
                    "hooksTriggered", hookJobIds.size(),
                    "jobIds", hookJobIds
            )).build();

        } catch (Exception e) {
            LOG.errorf("Teams webhook processing error: %s", e.getMessage());
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    private String determineEventType(JsonNode payload) {
        // Bot Framework Activity format
        if (payload.has("type")) {
            String activityType = payload.path("type").asText("");
            if ("message".equals(activityType)) {
                return "teams.message";
            }
            return "teams.activity";
        }

        // Incoming Webhook Connector format
        if (payload.has("text") || payload.has("title")) {
            return "teams.message";
        }

        // Unknown format
        return null;
    }

    private String extractMessageText(JsonNode payload) {
        // Bot Framework format
        String text = payload.path("text").asText("");
        if (!text.isBlank()) {
            return text;
        }

        // Webhook connector format
        String title = payload.path("title").asText("");
        String summary = payload.path("summary").asText("");
        
        if (!title.isBlank() && !summary.isBlank()) {
            return title + ": " + summary;
        }
        return !title.isBlank() ? title : summary;
    }

    private String extractAuthor(JsonNode payload) {
        // Bot Framework format
        JsonNode from = payload.path("from");
        if (!from.isMissingNode()) {
            String name = from.path("name").asText("");
            if (!name.isBlank()) {
                return name;
            }
        }

        // Webhook connector format - might be in themeColor or other fields
        return "unknown";
    }

    private String extractChannelId(JsonNode payload) {
        // Bot Framework format
        JsonNode channelData = payload.path("channelData");
        if (!channelData.isMissingNode()) {
            String channelId = channelData.path("channel").path("id").asText("");
            if (!channelId.isBlank()) {
                return channelId;
            }
        }

        // Conversation format
        JsonNode conversation = payload.path("conversation");
        return conversation.path("id").asText(null);
    }

    private String extractTeamId(JsonNode payload) {
        // Bot Framework format
        JsonNode channelData = payload.path("channelData");
        if (!channelData.isMissingNode()) {
            String teamId = channelData.path("team").path("id").asText("");
            if (!teamId.isBlank()) {
                return teamId;
            }
        }

        return null;
    }

    private String extractConversationType(JsonNode payload) {
        return payload.path("conversation").path("conversationType").asText(null);
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
