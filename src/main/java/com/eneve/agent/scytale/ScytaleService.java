package com.eneve.agent.scytale;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.eneve.agent.model.JobRecord;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Uploads SOC II compliance evidence to Scytale's external evidence API.
 *
 * <p>Configuration (via System Settings → Compliance tab):
 * <ul>
 *   <li>{@code scytale.api.key}      – Bearer token for the Scytale API</li>
 *   <li>{@code scytale.base.url}     – Base URL, e.g. {@code https://api.scytale.ai}</li>
 *   <li>{@code scytale.cc8-control-id} – The CC8 control identifier to attach evidence to</li>
 * </ul>
 *
 * <p>The service is intentionally kept synchronous so callers can act on the result
 * immediately (log success/failure, update job record).
 */
@ApplicationScoped
public class ScytaleService {

    private static final Logger LOG = Logger.getLogger(ScytaleService.class);

    @Inject
    SettingsService settings;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Uploads a structured evidence payload for {@code job} to Scytale.
     *
     * @return a {@link ScytaleUploadResult} describing success/failure and the remote reference.
     */
    public ScytaleUploadResult upload(JobRecord job, List<Map<String, Object>> complianceChecks,
                                      List<Map<String, Object>> auditTrailEntries) {
        String apiKey   = settings.get("scytale.api.key",        "");
        String baseUrl  = settings.get("scytale.base.url",       "https://api.scytale.ai");
        String controlId = settings.get("scytale.cc8-control-id", "CC8");

        if (apiKey.isBlank()) {
            return ScytaleUploadResult.failure("Scytale API key not configured");
        }

        // Extract jiraKey from the job request payload
        String jiraKey = null;
        if (job.getRequest() != null)        jiraKey = job.getRequest().jiraKey();
        else if (job.getFixPrRequest() != null) jiraKey = job.getFixPrRequest().jiraKey();

        // Build evidence payload
        Map<String, Object> payload = Map.of(
                "controlId",       controlId,
                "evidenceDate",    Instant.now().toString(),
                "title",           "Code Agent SOC II Evidence – " + (jiraKey != null ? jiraKey : job.getJobId()),
                "description",     buildDescription(job, jiraKey),
                "jobId",           job.getJobId(),
                "jiraKey",         jiraKey != null ? jiraKey : "",
                "prUrl",           job.getPrUrl() != null ? job.getPrUrl() : "",
                "complianceChecks", complianceChecks,
                "auditTrail",      auditTrailEntries
        );

        try {
            String body = objectMapper.writeValueAsString(payload);
            String uploadUrl = baseUrl.stripTrailing() + "/v1/evidence";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // Try to extract the remote reference from the response body
                String ref = extractRef(response.body(), job.getJobId());
                LOG.infof("Scytale upload succeeded for job %s → ref=%s", job.getJobId(), ref);
                return ScytaleUploadResult.success(ref);
            } else {
                String detail = "HTTP " + response.statusCode() + ": " + truncate(response.body(), 200);
                LOG.warnf("Scytale upload failed for job %s: %s", job.getJobId(), detail);
                return ScytaleUploadResult.failure(detail);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ScytaleUploadResult.failure("Upload interrupted");
        } catch (Exception e) {
            LOG.errorf("Scytale upload error for job %s: %s", job.getJobId(), e.getMessage());
            return ScytaleUploadResult.failure(e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildDescription(JobRecord job, String jiraKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("Automated code-fix and review evidence generated by Code Agent.\n\n");
        if (jiraKey != null) sb.append("Jira Issue: ").append(jiraKey).append("\n");
        if (job.getPrUrl() != null) sb.append("Pull Request: ").append(job.getPrUrl()).append("\n");
        sb.append("Job Status: ").append(job.getStatus()).append("\n");
        if (job.getJiraPriority() != null) sb.append("Priority: ").append(job.getJiraPriority()).append("\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractRef(String responseBody, String jobId) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
            Object id = parsed.get("id");
            if (id != null) return id.toString();
            Object ref = parsed.get("ref");
            if (ref != null) return ref.toString();
            Object evidenceId = parsed.get("evidenceId");
            if (evidenceId != null) return evidenceId.toString();
        } catch (Exception ignored) {
            // fall through
        }
        // Fallback: generate a deterministic-looking reference
        return "scytale-" + jobId.substring(0, 8);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public record ScytaleUploadResult(boolean success, String ref, String errorMessage) {
        public static ScytaleUploadResult success(String ref) {
            return new ScytaleUploadResult(true, ref, null);
        }
        public static ScytaleUploadResult failure(String error) {
            return new ScytaleUploadResult(false, null, error);
        }
    }
}
