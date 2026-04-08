package com.eneve.agent.notifications;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jboss.logging.Logger;

import com.eneve.agent.model.RunResult;
import com.eneve.agent.settings.SettingsService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Notification channel that posts to the AIW Hermes gateway.
 *
 * <p>Hermes is the AIW multi-platform notification bus that fans messages out
 * to Telegram, Discord, WhatsApp, Signal, Slack, Matrix, Mattermost, email,
 * and SMS. By going through Hermes instead of each platform's native SDK,
 * the agent avoids carrying platform-specific credentials and benefits from
 * Hermes' retry, rate-limiting, and delivery-target routing.
 *
 * <h2>Config</h2>
 * <ul>
 *   <li>{@code hermes.gateway.url} — base URL of the gateway (e.g.
 *       {@code http://72.61.44.159:8765}). Empty disables this notifier.</li>
 *   <li>{@code hermes.default.target} — delivery target identifier. Examples:
 *       {@code telegram}, {@code telegram:@vete-alerts},
 *       {@code discord:#engineering}, {@code whatsapp:+595981234567}.</li>
 *   <li>{@code hermes.api.key} — optional bearer token if the gateway
 *       requires auth.</li>
 * </ul>
 *
 * <h2>Wire format</h2>
 * POSTs a plain JSON payload to {@code POST /send}:
 * <pre>{@code
 * {
 *   "target": "telegram:@vete-alerts",
 *   "kind":   "code-agent.job",
 *   "status": "SUCCESS",
 *   "title":  "Fix SUCCESS: acme/my-repo",
 *   "body":   "<markdown body>",
 *   "url":    "https://github.com/acme/my-repo/pull/123",
 *   "meta":   { "jobId": "...", "jobType": "FIX", ... }
 * }
 * }</pre>
 *
 * <p>Hermes is responsible for format conversion (Telegram MarkdownV2, Discord
 * embeds, WhatsApp text, etc.). We send a neutral structured payload and let
 * the gateway decide how it renders on each platform.
 */
@ApplicationScoped
public class HermesGatewayNotifier implements Notifier {

    private static final Logger LOG = Logger.getLogger(HermesGatewayNotifier.class);

    @Inject
    SettingsService settingsService;

    @Inject
    HttpClient httpClient;

    @Override
    public String channel() {
        return "hermes";
    }

    @Override
    public void sendNotification(RunResult result) {
        String gatewayUrl = settingsService.get("hermes.gateway.url", "");
        if (gatewayUrl.isBlank()) {
            LOG.debug("Hermes gateway URL not configured, skipping notification");
            return;
        }
        String target = settingsService.get("hermes.default.target", "telegram");
        String apiKey = settingsService.getSecret("hermes.api.key");

        String status = result.status() != null ? result.status() : "UNKNOWN";
        String jobLabel = jobTypeLabel(result.jobType());
        String repoLabel = repoSlug(result.repoUrl());
        String title = "Code Agent " + jobLabel + " " + status + ": " + repoLabel;

        StringBuilder body = new StringBuilder();
        if (result.prUrl() != null && !result.prUrl().isBlank()) {
            body.append("PR: ").append(result.prUrl()).append("\n\n");
        }
        switch (status) {
            case "FAILED":
                body.append("**Error:** ").append(nz(result.errorMessage()));
                break;
            case "AWAITING_APPROVAL":
                body.append(nz(result.summary()))
                    .append("\n\n_Please review and approve the pull request._");
                break;
            case "STARTED":
                body.append("Started: ").append(nz(result.summary()));
                break;
            default:
                body.append("**Summary:** ").append(nz(result.summary()));
                boolean hasStats = result.filesChanged() > 0 || result.linesChanged() > 0;
                if (hasStats) {
                    body.append("\n\nFiles changed: ").append(result.filesChanged())
                        .append(" · Lines changed: ").append(result.linesChanged());
                }
        }

        StringBuilder meta = new StringBuilder();
        meta.append("{");
        meta.append("\"jobId\":\"").append(escape(result.jobId())).append("\"");
        meta.append(",\"jobType\":\"").append(escape(result.jobType())).append("\"");
        meta.append(",\"repo\":\"").append(escape(repoLabel)).append("\"");
        if (result.jiraKey() != null && !result.jiraKey().isBlank()) {
            // Kept as jiraKey for now; renaming to issueKey will follow the
            // integration:linear swap.
            meta.append(",\"issueKey\":\"").append(escape(result.jiraKey())).append("\"");
        }
        if (result.branchName() != null && !result.branchName().isBlank()) {
            meta.append(",\"branch\":\"").append(escape(result.branchName())).append("\"");
        }
        meta.append("}");

        String payload = "{"
                + "\"target\":\"" + escape(target) + "\","
                + "\"kind\":\"code-agent.job\","
                + "\"status\":\"" + escape(status) + "\","
                + "\"title\":\"" + escape(title) + "\","
                + "\"body\":\"" + escape(body.toString()) + "\","
                + "\"url\":\"" + escape(nz(result.prUrl())) + "\","
                + "\"meta\":" + meta
                + "}";

        String sendUrl = gatewayUrl.replaceAll("/+$", "") + "/send";

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(15))
                    .uri(URI.create(sendUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("Hermes notification sent (HTTP %d) target=%s", response.statusCode(), target);
            } else {
                LOG.warnf("Hermes notification got HTTP %d: %s", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            LOG.errorf("Hermes notification failed: %s", e.getMessage());
        }
    }

    private static String jobTypeLabel(String jobType) {
        if (jobType == null) return "Job";
        return switch (jobType) {
            case "FIX"             -> "Fix";
            case "REVIEW"          -> "Review";
            case "FIX_PR"          -> "Fix PR";
            case "REPLY"           -> "Reply";
            case "FIX_COMMENT"     -> "Fix Comment";
            case "HOOK"            -> "Hook";
            case "GENERATE_TESTS"  -> "Generate Tests";
            case "GENERATE_DOCS"   -> "Generate Docs";
            case "UPGRADE"         -> "Upgrade";
            case "PROMOTE"         -> "Security Promotion";
            case "AIKIDO_TRIAGE"   -> "Security Triage";
            default                -> jobType;
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "");
    }

    /**
     * Extracts a human-readable "workspace/repo" slug from a clone URL.
     */
    private static String repoSlug(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return "";
        String s = repoUrl.stripTrailing().replaceAll("\\.git$", "");
        int slash = s.lastIndexOf('/');
        if (slash <= 0) return s;
        int prevSlash = s.lastIndexOf('/', slash - 1);
        return prevSlash >= 0 ? s.substring(prevSlash + 1) : s.substring(slash + 1);
    }
}
