package com.eneve.agent.notifications;

import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.ReviewCommentEntry;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sends a single HTML digest email via AWS SES when a PR review job completes.
 * Consolidates all inline agent comments into one email, replacing the per-comment
 * Bitbucket email notifications that were disabled.
 *
 * Config keys (via SettingsService / env vars):
 *   review.email.recipient  — to-address (REVIEW_EMAIL_RECIPIENT)
 *   review.email.cc         — comma-separated CC addresses, optional (REVIEW_EMAIL_CC)
 *   review.email.from       — SES-verified from-address (REVIEW_EMAIL_FROM)
 *   review.email.aws.region — AWS region, default eu-west-1 (REVIEW_EMAIL_AWS_REGION)
 */
@ApplicationScoped
public class ReviewEmailNotifier {

    private static final Logger LOG = Logger.getLogger(ReviewEmailNotifier.class);

    @Inject
    SettingsService settingsService;

    @Inject
    GitPlatformRegistry platformRegistry;

    @Inject
    CommentStore commentStore;

    public void sendReviewDigest(ReviewPrRequest request, JobRecord job, RepoCoordinates coords) {
        String recipient = settingsService.get("review.email.recipient", "");
        String from = settingsService.get("review.email.from", "");

        if (recipient.isBlank() || from.isBlank()) {
            LOG.debug("Review email not configured (review.email.recipient / review.email.from), skipping");
            return;
        }

        try {
            List<ReviewCommentEntry> comments = fetchComments(coords, request.prId());
            String prTitle = job.getSummary() != null ? derivePrTitle(job) : "PR #" + request.prId();
            String reviewSummary = job.getSummary() != null ? job.getSummary() : "";
            String prUrl = job.getPrUrl() != null ? job.getPrUrl() : "";
            String authorName = request.prAuthor() != null ? request.prAuthor() : "";

            String subject = "Code Review: " + prTitle + " (#" + request.prId() + ")";
            String htmlBody = buildHtmlBody(authorName, request.prId(), prTitle,
                    coords.organization(), coords.repository(),
                    reviewSummary, comments, prUrl);

            String regionStr = settingsService.get("review.email.aws.region", "eu-west-1");
            Region region = Region.of(regionStr);

            String ccRaw = settingsService.get("review.email.cc", "");
            List<String> ccAddresses = java.util.Arrays.stream(ccRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();

            Destination.Builder destinationBuilder = Destination.builder().toAddresses(recipient);
            if (!ccAddresses.isEmpty()) {
                destinationBuilder.ccAddresses(ccAddresses);
            }

            try (SesClient ses = SesClient.builder().region(region).build()) {
                SendEmailRequest emailRequest = SendEmailRequest.builder()
                        .destination(destinationBuilder.build())
                        .source(from)
                        .message(Message.builder()
                                .subject(Content.builder().data(subject).charset("UTF-8").build())
                                .body(Body.builder()
                                        .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                                        .build())
                                .build())
                        .build();

                ses.sendEmail(emailRequest);
                LOG.infof("Review digest email sent to %s%s for PR #%s (%s/%s)",
                        recipient,
                        ccAddresses.isEmpty() ? "" : " (cc: " + String.join(", ", ccAddresses) + ")",
                        request.prId(), coords.organization(), coords.repository());
            }
        } catch (Exception e) {
            LOG.errorf("Failed to send review digest email for PR #%s: %s", request.prId(), e.getMessage());
        }
    }

    private List<ReviewCommentEntry> fetchComments(RepoCoordinates coords, String prId) {
        List<ReviewCommentEntry> result = new ArrayList<>();
        try {
            GitPlatformService platform = platformRegistry.defaultPlatform();
            List<AgentComment> agentComments = platform.getAgentPrComments(
                    coords.organization(), coords.project(), coords.repository(), prId);

            List<Long> ids = agentComments.stream().map(AgentComment::id).toList();
            Map<Long, CommentStore.ResolvedInfo> resolvedInfoMap = commentStore.getResolvedInfoBatch(ids);

            for (AgentComment c : agentComments) {
                if (c.content() != null && c.content().trim().startsWith("<!-- agent-reviewed-up-to:")) {
                    continue;
                }
                CommentStore.ResolvedInfo ri = resolvedInfoMap.getOrDefault(c.id(), CommentStore.ResolvedInfo.OPEN);
                result.add(new ReviewCommentEntry(
                        c.id(), c.filePath(), c.line(), c.content(),
                        ri.resolved(),
                        ri.resolvedAt() != null ? ri.resolvedAt().toString() : null,
                        ri.resolvedBy(),
                        c.parentId()));
            }
        } catch (Exception e) {
            LOG.warnf("Could not fetch PR comments for email digest (%s/%s #%s): %s",
                    coords.organization(), coords.repository(), prId, e.getMessage());
        }
        return result;
    }

    private String buildHtmlBody(String authorName, String prId, String prTitle,
                                 String workspace, String repo,
                                 String reviewSummary, List<ReviewCommentEntry> comments,
                                 String prUrl) {
        long openCount = comments.stream().filter(c -> !c.resolved()).count();
        long resolvedCount = comments.stream().filter(ReviewCommentEntry::resolved).count();

        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <style>
                  body { font-family: Lato, Arial, sans-serif; font-size: 14px; color: #141414; margin: 0; padding: 0; background: #F9F9F8; }
                  .wrapper { max-width: 680px; margin: 24px auto; background: #ffffff; border-radius: 8px; border: 1px solid #E8E4E1; overflow: hidden; }
                  .header { background: #002433; padding: 20px 28px; }
                  .header-title { color: #ffffff; font-size: 18px; font-weight: 700; margin: 0; }
                  .header-sub { color: #66D1FF; font-size: 13px; margin: 4px 0 0; }
                  .body { padding: 24px 28px; }
                  p { margin: 0 0 12px; line-height: 1.6; }
                  .summary { background: #E5F7FF; border-left: 4px solid #00B4FF; padding: 12px 16px; margin: 16px 0; border-radius: 4px; color: #002433; font-size: 13px; line-height: 1.6; }
                  .stats { margin: 12px 0 20px; color: #525252; font-size: 13px; }
                  h3 { color: #004766; font-size: 14px; font-weight: 700; margin: 0 0 10px; text-transform: uppercase; letter-spacing: 0.04em; }
                  table { border-collapse: collapse; width: 100%%; margin-top: 4px; font-size: 13px; }
                  th { background: #004766; color: #ffffff; text-align: left; padding: 8px 12px; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.05em; }
                  td { padding: 8px 12px; border-bottom: 1px solid #E8E4E1; vertical-align: top; color: #141414; }
                  tr:nth-child(even) td { background: #F9F9F8; }
                  .badge-open { background: #FFEEEE; color: #890002; padding: 2px 8px; border-radius: 2px; font-size: 11px; font-weight: 600; white-space: nowrap; }
                  .badge-resolved { background: #E6FDF4; color: #09573A; padding: 2px 8px; border-radius: 2px; font-size: 11px; font-weight: 600; white-space: nowrap; }
                  .file { font-family: 'Courier New', monospace; font-size: 12px; color: #525252; }
                  .pr-link { margin-top: 24px; }
                  .pr-link a { display: inline-block; background: #00B4FF; color: #ffffff; text-decoration: none; padding: 8px 20px; border-radius: 22px; font-size: 13px; font-weight: 600; }
                  .footer { margin: 24px 28px 0; padding: 16px 0; border-top: 1px solid #E8E4E1; font-size: 12px; color: #8F8F8F; }
                </style>
                </head>
                <body>
                <div class="wrapper">
                <div class="header">
                  <p class="header-title">Code Agent &mdash; PR Review</p>
                  <p class="header-sub">Automated review complete</p>
                </div>
                <div class="body">
                """);

        if (!authorName.isBlank()) {
            html.append("<p>Hi ").append(escapeHtml(authorName)).append(",</p>\n");
        }

        html.append("<p>The AI code agent has completed its review of <strong>")
                .append(escapeHtml(prTitle))
                .append("</strong> (#").append(escapeHtml(prId))
                .append(") in <strong>").append(escapeHtml(workspace)).append("/").append(escapeHtml(repo))
                .append("</strong>.</p>\n");

        if (!reviewSummary.isBlank()) {
            html.append("<div class=\"summary\">").append(escapeHtml(reviewSummary)).append("</div>\n");
        }

        html.append("<p class=\"stats\">")
                .append("<strong>").append(comments.size()).append("</strong> finding(s) total &mdash; ")
                .append("<strong>").append(openCount).append("</strong> open, ")
                .append("<strong>").append(resolvedCount).append("</strong> resolved.")
                .append("</p>\n");

        if (!comments.isEmpty()) {
            html.append("<h3>Inline Comments</h3>\n");
            html.append("<table>\n");
            html.append("<tr><th>File</th><th>Line</th><th>Comment</th><th>Status</th></tr>\n");
            for (ReviewCommentEntry c : comments) {
                if (c.parentId() != 0) {
                    continue;
                }
                String filePath = c.filePath() != null && !c.filePath().isBlank() ? c.filePath() : "(general)";
                String lineStr = c.line() > 0 ? String.valueOf(c.line()) : "&mdash;";
                String badge = c.resolved()
                        ? "<span class=\"badge-resolved\">Resolved</span>"
                        : "<span class=\"badge-open\">Open</span>";
                html.append("<tr>")
                        .append("<td class=\"file\">").append(escapeHtml(filePath)).append("</td>")
                        .append("<td>").append(lineStr).append("</td>")
                        .append("<td>").append(escapeHtml(c.content())).append("</td>")
                        .append("<td>").append(badge).append("</td>")
                        .append("</tr>\n");
            }
            html.append("</table>\n");
        }

        if (!prUrl.isBlank()) {
            html.append("<p class=\"pr-link\"><a href=\"").append(escapeHtml(prUrl))
                    .append("\">View Pull Request</a></p>\n");
        }

        html.append("""
                </div>
                <div class="footer">Sent by AI Code Agent &mdash; Bitbucket per-comment emails are disabled.</div>
                </div>
                </body>
                </html>
                """);

        return html.toString();
    }

    /**
     * Derives a short PR title from the job summary (first sentence / line).
     * Falls back to the full summary if no sentence boundary is found.
     */
    private static String derivePrTitle(JobRecord job) {
        String summary = job.getSummary();
        if (summary == null || summary.isBlank()) return "";
        int dot = summary.indexOf('.');
        int newline = summary.indexOf('\n');
        int cut = -1;
        if (dot > 0) cut = dot;
        if (newline > 0 && (cut < 0 || newline < cut)) cut = newline;
        String title = cut > 0 ? summary.substring(0, cut).trim() : summary.trim();
        return title.length() > 80 ? title.substring(0, 77) + "..." : title;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
