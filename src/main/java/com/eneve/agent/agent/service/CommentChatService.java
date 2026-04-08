package com.eneve.agent.agent.service;

import com.anthropic.models.messages.MessageParam;
import com.eneve.agent.agent.ClaudeToolUseLoop;
import com.eneve.agent.agent.ToolDefinitions;
import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.CommentChatMessage;
import com.eneve.agent.model.CommentChatRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless streaming chat service for discussing a single review comment.
 *
 * <p>Unlike {@link ChatService}, this service does NOT persist any conversation history
 * to the database. The full message history is sent by the client on every request.
 * Only the AI call logging inside {@link ClaudeToolUseLoop} is retained.
 *
 * <p>Three action tools are available to the AI: resolve_comment, mark_false_positive,
 * and request_fix. These map directly to existing job action logic.
 */
@ApplicationScoped
public class CommentChatService {

    private static final Logger LOG = Logger.getLogger(CommentChatService.class);
    private static final int MAX_ITERATIONS = 20;
    private static final int DIFF_CONTEXT_LINES = 10;

    @Inject ClaudeToolUseLoop toolLoop;
    @Inject CommentStore commentStore;
    @Inject JobStore jobStore;
    @Inject GitPlatformService gitPlatformService;

    /**
     * Streams a single turn of the comment-chat conversation.
     *
     * @param jobId   the parent review/fix job that owns the comment
     * @param request request containing commentId and full message history
     * @return SSE event stream identical in format to {@code POST /chat}
     */
    public Multi<ChatEvent> chatStream(String jobId, CommentChatRequest request) {
        return Multi.createFrom().<ChatEvent>emitter(emitter -> {
            WorkspaceContext workspace = null;
            try {
                var jobOpt = jobStore.get(jobId);
                if (jobOpt.isEmpty()) {
                    emitter.emit(new ChatEvent.Error("Job not found: " + jobId));
                    emitter.complete();
                    return;
                }
                JobRecord job = jobOpt.get();

                // ── Load comment context ──────────────────────────────────
                var commentCtx = commentStore.find(request.commentId());
                if (commentCtx.isEmpty()) {
                    emitter.emit(new ChatEvent.Error("Comment not found: " + request.commentId()));
                    emitter.complete();
                    return;
                }
                var ctx = commentCtx.get();

                // ── Optionally fetch relevant diff hunk ───────────────────
                String diffHunk = fetchDiffHunk(job, ctx.filePath(), ctx.line());

                // ── Build system prompt ───────────────────────────────────
                String systemPrompt = buildSystemPrompt(ctx, diffHunk);

                // ── Create minimal workspace with metadata for tools ──────
                workspace = createWorkspace(jobId, request.commentId());

                // ── Convert message history → MessageParam list ───────────
                List<CommentChatMessage> msgs = request.messages() != null
                        ? request.messages()
                        : List.of();

                String lastUserMessage;
                List<MessageParam> history;

                if (msgs.isEmpty()) {
                    // First turn: auto-greeting (no prior history)
                    lastUserMessage = "Please introduce this finding and explain your reasoning.";
                    history = new ArrayList<>();
                } else {
                    // Subsequent turns: all but last go to history, last is the new user message
                    lastUserMessage = msgs.get(msgs.size() - 1).content();
                    history = toMessageParams(msgs.subList(0, msgs.size() - 1));
                }

                // ── Run the streaming loop (no ConversationRepository) ────
                final WorkspaceContext finalWorkspace = workspace;
                toolLoop.runStreaming(
                        systemPrompt,
                        finalWorkspace,
                        ToolDefinitions.commentChat(),
                        lastUserMessage,
                        history,
                        jobId,
                        "COMMENT_CHAT",
                        MAX_ITERATIONS,
                        event -> {
                            emitter.emit(event);
                            if (event instanceof ChatEvent.Done || event instanceof ChatEvent.Error) {
                                emitter.complete();
                            }
                        });

                emitter.complete();

            } catch (Exception e) {
                LOG.errorf("CommentChatService error: %s", e.getMessage());
                emitter.emit(new ChatEvent.Error(e.getMessage() != null ? e.getMessage() : "Internal error"));
                emitter.complete();
            } finally {
                if (workspace != null) {
                    workspace.close();
                }
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    // ── System prompt ─────────────────────────────────────────────────────────

    private static String buildSystemPrompt(com.eneve.agent.agent.model.CommentContext ctx, String diffHunk) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior software developer and code reviewer. You performed an automated code review ")
          .append("on a pull request and left the following finding. A developer is now discussing this finding ")
          .append("with you directly. Be precise, constructive, and explain your reasoning clearly.\n\n");

        sb.append("**File:** `").append(ctx.filePath() != null ? ctx.filePath() : "unknown").append("`");
        if (ctx.line() > 0) {
            sb.append(" (line ").append(ctx.line()).append(")");
        }
        sb.append("\n");

        if (ctx.severity() != null && !ctx.severity().isBlank()) {
            sb.append("**Severity:** ").append(ctx.severity()).append("\n");
        }
        if (ctx.category() != null && !ctx.category().isBlank()) {
            sb.append("**Category:** ").append(ctx.category()).append("\n");
        }
        sb.append("\n");

        sb.append("**Your review comment:**\n```\n")
          .append(ctx.findingText() != null ? ctx.findingText() : "")
          .append("\n```\n\n");

        if (diffHunk != null && !diffHunk.isBlank()) {
            sb.append("**Relevant code:**\n```diff\n")
              .append(diffHunk)
              .append("\n```\n\n");
        }

        sb.append("**Guidelines:**\n")
          .append("- Explain your reasoning clearly and constructively.\n")
          .append("- If the developer confirms the issue is fixed or agrees to address it, use `resolve_comment`.\n")
          .append("- If the developer convinces you this is not a real issue, use `mark_false_positive`.\n")
          .append("- Only use `request_fix` if the developer explicitly asks you to start an automated fix.\n")
          .append("- Always acknowledge what you are about to do before invoking any action tool.\n");

        return sb.toString();
    }

    // ── Workspace ─────────────────────────────────────────────────────────────

    private WorkspaceContext createWorkspace(String jobId, long commentId) {
        try {
            WorkspaceContext ws = WorkspaceContext.create("comment-chat-" + jobId);
            ws.putMetadata("jobId", jobId);
            ws.putMetadata("commentId", String.valueOf(commentId));
            return ws;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspace for comment chat: " + e.getMessage(), e);
        }
    }

    // ── Message history conversion ────────────────────────────────────────────

    private static List<MessageParam> toMessageParams(List<CommentChatMessage> messages) {
        List<MessageParam> result = new ArrayList<>(messages.size());
        for (var msg : messages) {
            MessageParam.Role role = "assistant".equalsIgnoreCase(msg.role())
                    ? MessageParam.Role.ASSISTANT
                    : MessageParam.Role.USER;
            result.add(MessageParam.builder()
                    .role(role)
                    .content(msg.content() != null ? msg.content() : "")
                    .build());
        }
        return result;
    }

    // ── Diff hunk extraction ──────────────────────────────────────────────────

    private String fetchDiffHunk(JobRecord job, String filePath, int targetLine) {
        if (filePath == null || filePath.isBlank() || targetLine <= 0) {
            return null;
        }
        try {
            RepoCoordinates coords = resolveCoords(job);
            String rawDiff = gitPlatformService.getPullRequestDiff(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());
            return extractHunkForLine(rawDiff, filePath, targetLine);
        } catch (Exception e) {
            LOG.infof("Could not fetch diff hunk for comment chat (non-fatal): %s", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the lines around {@code targetLine} in {@code filePath} from a raw unified diff.
     * Returns up to {@link #DIFF_CONTEXT_LINES} lines before and after the target line.
     */
    private static String extractHunkForLine(String rawDiff, String filePath, int targetLine) {
        if (rawDiff == null || rawDiff.isBlank()) return null;

        boolean inTargetFile = false;
        List<String> collected = new ArrayList<>();
        int newLineNo = 0;

        for (String line : rawDiff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                inTargetFile = false;
                collected.clear();
                newLineNo = 0;
                continue;
            }
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).trim();
                if (path.startsWith("b/")) path = path.substring(2);
                inTargetFile = path.equals(filePath);
                continue;
            }
            if (!inTargetFile) continue;

            if (line.startsWith("@@")) {
                // Parse new-file start line from @@ -old,len +new,len @@ header
                try {
                    int plusIdx = line.indexOf('+');
                    int commaOrSpace = line.indexOf(',', plusIdx);
                    int spaceAfter = line.indexOf(' ', plusIdx);
                    int endIdx = (commaOrSpace > 0 && commaOrSpace < spaceAfter) ? commaOrSpace : spaceAfter;
                    newLineNo = Integer.parseInt(line.substring(plusIdx + 1, endIdx));
                } catch (Exception e) {
                    newLineNo = 0;
                }
                collected.add(line);
                continue;
            }

            if (line.startsWith("-")) {
                collected.add(line);
            } else if (line.startsWith("+")) {
                collected.add(line);
                newLineNo++;
            } else {
                collected.add(line);
                newLineNo++;
            }

            // Once we've passed the target line by DIFF_CONTEXT_LINES, stop collecting
            if (newLineNo > targetLine + DIFF_CONTEXT_LINES) break;
        }

        if (collected.isEmpty()) return null;

        // Return a window of DIFF_CONTEXT_LINES before and after the target
        int approxTargetIdx = -1;
        int currentLine = 0;
        for (int i = 0; i < collected.size(); i++) {
            String l = collected.get(i);
            if (!l.startsWith("@@") && !l.startsWith("-")) currentLine++;
            if (currentLine == targetLine) { approxTargetIdx = i; break; }
        }

        int from = approxTargetIdx >= 0 ? Math.max(0, approxTargetIdx - DIFF_CONTEXT_LINES) : 0;
        int to = Math.min(collected.size(), (approxTargetIdx >= 0 ? approxTargetIdx : collected.size()) + DIFF_CONTEXT_LINES);
        return String.join("\n", collected.subList(from, to));
    }

    private static RepoCoordinates resolveCoords(JobRecord job) {
        String repoUrl = null;
        if (job.getRequest() != null && job.getRequest().repoUrl() != null)
            repoUrl = job.getRequest().repoUrl();
        else if (job.getReviewRequest() != null && job.getReviewRequest().repoUrl() != null)
            repoUrl = job.getReviewRequest().repoUrl();
        else if (job.getFixPrRequest() != null && job.getFixPrRequest().repoUrl() != null)
            repoUrl = job.getFixPrRequest().repoUrl();
        else if (job.getHookRequest() != null && job.getHookRequest().repoUrl() != null)
            repoUrl = job.getHookRequest().repoUrl();

        if (repoUrl != null && !repoUrl.isBlank()) {
            return RepoCoordinates.parse(repoUrl);
        }
        if (job.getPrUrl() != null && !job.getPrUrl().isBlank()) {
            String prUrl = job.getPrUrl()
                    .replaceAll("/pull-requests/.*$", "")
                    .replaceAll("/pulls/.*$", "")
                    .replaceAll("/-/merge_requests/.*$", "");
            return RepoCoordinates.parse(prUrl);
        }
        throw new IllegalStateException("No repository URL available on job " + job.getJobId());
    }
}
