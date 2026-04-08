package com.eneve.agent.webhooks;

import java.util.Locale;
import java.util.Map;

import com.eneve.agent.agent.model.CommentContext;
import com.eneve.agent.agent.model.CommentIntent;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.model.JobType;
import com.fasterxml.jackson.databind.JsonNode;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Handles incoming GitLab webhook notifications for merge request note events.
 * When a developer replies to one of the agent's review notes, triggers a
 * REPLY job so the agent can respond conversationally in-thread.
 * <p>
 * GitLab sends all note events via the Note Hook event, regardless of whether
 * the note is a standalone comment or a reply inside a discussion. We identify
 * replies by checking if the note's discussion already has a note tracked in
 * {@link CommentStore} (i.e., the first note in the discussion is from the agent).
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class GitLabCommentWebhookResource extends AbstractCommentWebhookHandler {

    private static final Logger LOG = Logger.getLogger(GitLabCommentWebhookResource.class);

    @Override
    protected String agentUserSettingKey() { return "gitlab.agent.user"; }

    @POST
    @Path("/gitlab/merge-request-comment")
    @Operation(
            operationId = "gitlabMrNoteWebhook",
            summary = "Handle GitLab MR note webhook events",
            description = "Receives GitLab webhook payloads for Note Hook events on merge requests. "
                    + "When a developer replies to one of the agent's review notes, triggers an AI-powered "
                    + "conversational reply in the same discussion thread."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handleNoteWebhook(
            @HeaderParam("X-Gitlab-Event") String eventHeader,
            String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            String event = eventHeader != null ? eventHeader : "";
            LOG.infof("GitLab note webhook received: %s", event);

            if (!event.equalsIgnoreCase("Note Hook")) {
                return ok("ignored", "Unsupported event: " + event);
            }

            // Only handle notes on merge requests
            String noteableType = payload.path("object_attributes").path("noteable_type").asText("");
            if (!noteableType.equals("MergeRequest")) {
                return ok("ignored", "Not a merge request note: " + noteableType);
            }

            JsonNode noteAttrs = payload.path("object_attributes");
            long noteId = noteAttrs.path("id").asLong(0);
            String noteBody = noteAttrs.path("note").asText("").trim();
            String discussionId = noteAttrs.path("discussion_id").asText("");
            String filePath = noteAttrs.path("position").path("new_path").asText(null);
            int line = noteAttrs.path("position").path("new_line").asInt(0);

            JsonNode authorNode = payload.path("user");
            String noteAuthor = authorNode.path("username").asText("");

            JsonNode mrNode = payload.path("merge_request");
            String mrIid = String.valueOf(mrNode.path("iid").asInt(0));

            JsonNode projectNode = payload.path("project");
            String projectWebUrl = projectNode.path("web_url").asText("");
            String projectPath = projectNode.path("path_with_namespace").asText("");
            String repoUrl = projectWebUrl.isBlank() ? "" : projectWebUrl + ".git";

            if (noteId == 0 || mrIid.equals("0") || projectPath.isBlank()) {
                return ok("ignored", "Missing note ID, MR IID, or project path in payload");
            }

            // Derive org (namespace) and repoSlug from path_with_namespace
            String[] pathParts = projectPath.split("/", 2);
            String namespace = pathParts.length == 2 ? pathParts[0] : projectPath;
            String repoSlug = pathParts.length == 2 ? pathParts[1] : projectPath;

            if (!repoSettingsStore.isReviewEnabled(namespace, repoSlug)) {
                LOG.infof("GitLab note webhook: skipping — review disabled for %s", projectPath);
                return ok("skipped", "Review disabled for " + projectPath);
            }

            // Guard: ignore notes from the agent itself (prevent infinite loops)
            if (!agentUser().isEmpty() && noteAuthor.equalsIgnoreCase(agentUser())) {
                LOG.debugf("Note webhook: ignoring note %d by agent user '%s'", noteId, agentUser());
                return ok("ignored", "Note is from the agent itself");
            }

            // Guard: only process replies inside discussions that the agent started.
            // In GitLab, all notes in a discussion share the same discussion_id.
            // We look for any note in this discussion that is tracked in CommentStore.
            if (discussionId.isBlank()) {
                return ok("ignored", "No discussion_id — standalone note, not a reply");
            }

            // Find the agent's root note id for this discussion via CommentStore.
            // The agent stores note IDs when posting; we check if any note in this
            // discussion is tracked. Since we only have the discussion_id here, we
            // resolve by looking for any tracked note in the discussion.
            // The simplest approach: check if noteId itself is tracked (it won't be for replies),
            // so instead we rely on the fact that we don't have the parent note id directly.
            // GitLab Note Hook does not provide a parentNoteId field.
            // We must check by discussion_id: store maps discussion_id -> rootNoteId via CommentStore.
            //
            // For now: check if any note ID in CommentStore is part of this discussion.
            // This requires a helper method on CommentStore. As a pragmatic approach, we
            // check if the discussion_id is registered as a comment ID (GitLab stores
            // discussion IDs as opaque strings, not longs). We use a convention:
            // the agent's note ID (long) is used as the parentCommentId in our system.
            //
            // Since GitLab Note Hook does not expose parentNoteId, we cannot use the same
            // mechanism as Bitbucket/ADO. Instead, we check if there is a tracked note
            // whose discussion_id matches. We store discussion_id hashed as a long in CommentStore
            // during review posting - but that's not how CommentStore currently works.
            //
            // Practical solution: use the first note in the payload's `merge_request.discussion_id`
            // and look for it indirectly. Since GitLab sends the `author` of the note being created
            // but NOT the parent note id, we check commentStore for the note id that the webhook
            // payload provides in `object_attributes.in_reply_to_id` (only present for direct replies).
            long inReplyToId = noteAttrs.path("in_reply_to_id").asLong(0);
            if (inReplyToId == 0) {
                return ok("ignored", "Not a reply — in_reply_to_id is absent or zero");
            }

            if (!commentStore.contains(inReplyToId)) {
                LOG.debugf("Note webhook: parent note %d is not from the agent, ignoring", inReplyToId);
                return ok("ignored", "Parent note is not from the agent");
            }

            LOG.infof("Note webhook: developer replied (note %d) to agent note %d on MR !%s (%s)",
                    noteId, inReplyToId, mrIid, projectPath);

            // Fast path: /learn command stores a team preference directly
            if (noteBody.toLowerCase(Locale.ROOT).startsWith("/learn ")) {
                return handleLearnCommand(noteBody, namespace, repoSlug, repoUrl, mrIid,
                        inReplyToId, noteAuthor);
            }

            // Fast path: /fp or /false-positive marks the finding as a false positive
            String lowerNote = noteBody.toLowerCase(Locale.ROOT);
            if (lowerNote.equals("/fp") || lowerNote.equals("/false-positive")
                    || lowerNote.startsWith("/fp ") || lowerNote.startsWith("/false-positive ")) {
                return handleFalsePositiveCommand(namespace, repoSlug, mrIid,
                        inReplyToId, noteAuthor);
            }

            // Fast path: /generate-tests triggers a unit test generation job for this MR
            if (lowerNote.equals("/generate-tests")) {
                return handleGenerateTestsCommand(repoUrl, mrIid, inReplyToId,
                        namespace, repoSlug);
            }

            // Classify intent: is this a fix request or a discussion?
            String originalFinding = commentStore.find(inReplyToId)
                    .map(CommentContext::findingText).orElse(null);
            CommentIntent intent = intentClassifier.classify(noteBody, originalFinding);
            JobType jobType = (intent == CommentIntent.FIX) ? JobType.FIX_COMMENT : JobType.REPLY;

            LOG.infof("Note webhook: classified intent as %s for note %d", jobType, noteId);

            return submitJob(repoUrl, mrIid, inReplyToId, noteBody, filePath, line, jobType);

        } catch (Exception e) {
            LOG.errorf("GitLab note webhook processing error: %s", e.getMessage());
            return Response.serverError().entity(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }






}
