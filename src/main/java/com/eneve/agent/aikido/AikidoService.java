package com.eneve.agent.aikido;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Aikido Security REST API client.
 * Handles OAuth2 authentication, issue lookup, CVE enrichment, changelog retrieval,
 * and CI scan triggering.
 */
@ApplicationScoped
public class AikidoService {

    private static final Logger LOG = Logger.getLogger(AikidoService.class);

    @ConfigProperty(name = "aikido.base.url", defaultValue = "https://app.aikido.dev")
    String baseUrl;

    @ConfigProperty(name = "aikido.client.id", defaultValue = "")
    String clientId;

    @ConfigProperty(name = "aikido.client.secret", defaultValue = "")
    String clientSecret;

    @ConfigProperty(name = "aikido.ci.api.secret", defaultValue = "")
    String ciApiSecret;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String accessToken;
    private Instant tokenExpiry = Instant.EPOCH;
    private final ReentrantLock tokenLock = new ReentrantLock();

    public boolean isEnabled() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    // =========================================================================
    // OAuth2 token management
    // =========================================================================

    private String getAccessToken() {
        tokenLock.lock();
        try {
            if (accessToken != null && Instant.now().isBefore(tokenExpiry)) {
                return accessToken;
            }
            refreshToken();
            return accessToken;
        } finally {
            tokenLock.unlock();
        }
    }

    private void refreshToken() {
        try {
            String basicAuth = java.util.Base64.getEncoder()
                    .encodeToString((clientId + ":" + clientSecret)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("grant_type", "client_credentials"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/oauth/token"))
                    .header("Authorization", "Basic " + basicAuth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Aikido auth failed (HTTP " + response.statusCode() + "): " + response.body());
            }

            JsonNode node = objectMapper.readTree(response.body());
            accessToken = node.path("access_token").asText();
            int expiresIn = node.path("expires_in").asInt(3600);
            tokenExpiry = Instant.now().plusSeconds(expiresIn - 60);
            LOG.info("Aikido access token refreshed");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Aikido token refresh error: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Issue lookup
    // =========================================================================

    /**
     * List all open issue groups and find one linked to the given JIRA key.
     * Returns the issue group ID, or null if not found.
     */
    public Integer findIssueGroupByJiraKey(String jiraKey) {
        String json = get("/api/public/v1/open-issue-groups", "list open issues");
        if (json == null) return null;

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode groups = root.isArray() ? root : root.path("data");
            if (!groups.isArray()) {
                groups = root.path("groups");
            }

            for (JsonNode group : groups) {
                if (matchesJiraKey(group, jiraKey)) {
                    return group.path("id").asInt();
                }
                JsonNode tasks = group.path("tasks");
                if (tasks.isArray()) {
                    for (JsonNode task : tasks) {
                        String taskKey = task.path("key").asText(task.path("external_id").asText(""));
                        if (jiraKey.equalsIgnoreCase(taskKey)) {
                            return group.path("id").asInt();
                        }
                    }
                }
            }

            LOG.warnf("No Aikido issue group found linked to JIRA key: %s", jiraKey);
            return null;
        } catch (Exception e) {
            LOG.errorf("Failed to parse Aikido open issues: %s", e.getMessage());
            return null;
        }
    }

    private boolean matchesJiraKey(JsonNode group, String jiraKey) {
        String title = group.path("title").asText("");
        if (title.contains(jiraKey)) return true;

        String externalId = group.path("external_ticket_id").asText(
                group.path("jira_issue_key").asText(""));
        return jiraKey.equalsIgnoreCase(externalId);
    }

    /**
     * Get detailed info for a specific issue group.
     */
    public AikidoIssueInfo getIssueGroupDetail(int issueGroupId) {
        String json = get("/api/public/v1/issues/groups/" + issueGroupId, "issue group detail");
        if (json == null) return null;

        try {
            JsonNode root = objectMapper.readTree(json);

            String severity = extractText(root, "severity", "medium");
            String packageName = extractText(root, "affected_package",
                    extractText(root, "package_name",
                            extractText(root, "dependency_name", "unknown")));
            String currentVersion = extractText(root, "current_version",
                    extractText(root, "installed_version", ""));
            String fixedVersion = extractText(root, "fix_version",
                    extractText(root, "fixed_in_version",
                            extractText(root, "patched_version", "")));
            String cveId = extractText(root, "cve_id",
                    extractText(root, "cve", ""));
            String repoName = extractRepoName(root);

            String repoUrl = extractRepoUrl(root);

            String cveDescription = null;
            Double cvssScore = null;
            if (cveId != null && !cveId.isBlank()) {
                try {
                    var cveInfo = fetchCveDetails(cveId);
                    if (cveInfo != null) {
                        cveDescription = cveInfo[0];
                        cvssScore = cveInfo[1] != null ? Double.parseDouble(cveInfo[1]) : null;
                    }
                } catch (Exception e) {
                    LOG.warnf("Could not fetch CVE details for %s: %s", cveId, e.getMessage());
                }
            }

            String changelogSummary = null;
            if (packageName != null && currentVersion != null && fixedVersion != null
                    && !currentVersion.isBlank() && !fixedVersion.isBlank()) {
                changelogSummary = fetchChangelogSummary(packageName, currentVersion, fixedVersion);
            }

            return new AikidoIssueInfo(
                    issueGroupId, severity, packageName, currentVersion, fixedVersion,
                    cveId, cveDescription, cvssScore, repoName, repoUrl, changelogSummary
            );
        } catch (Exception e) {
            LOG.errorf("Failed to parse Aikido issue group %d: %s", issueGroupId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // CVE and changelog enrichment
    // =========================================================================

    /**
     * Returns [description, cvssScore] or null.
     */
    private String[] fetchCveDetails(String cveId) {
        String json = get("/api/public/v1/cve/" + cveId, "CVE " + cveId);
        if (json == null) return null;

        try {
            JsonNode root = objectMapper.readTree(json);
            String description = extractText(root, "description", "");
            String cvss = root.has("cvss_score") ? root.path("cvss_score").asText(null)
                    : root.has("cvss") ? root.path("cvss").asText(null) : null;
            return new String[]{description, cvss};
        } catch (Exception e) {
            LOG.warnf("Failed to parse CVE details for %s: %s", cveId, e.getMessage());
            return null;
        }
    }

    private String fetchChangelogSummary(String packageName, String fromVersion, String toVersion) {
        String url = "/api/public/v1/changelog-summary?package=" + encode(packageName)
                + "&from=" + encode(fromVersion) + "&to=" + encode(toVersion);
        String json = get(url, "changelog " + packageName);
        if (json == null) return null;

        try {
            JsonNode root = objectMapper.readTree(json);
            return extractText(root, "summary",
                    extractText(root, "changelog", root.asText("")));
        } catch (Exception e) {
            LOG.warnf("Failed to parse changelog for %s: %s", packageName, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // CI scan trigger (post-PR verification)
    // =========================================================================

    /**
     * Trigger an Aikido CI scan on a branch. Returns the scan_id, or -1 on failure.
     */
    public int triggerCiScan(String repositoryId, String baseCommitId, String headCommitId,
                             String branchName) {
        if (ciApiSecret == null || ciApiSecret.isBlank()) {
            LOG.warn("Aikido CI API secret not configured, skipping scan trigger");
            return -1;
        }

        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "repository_id", repositoryId,
                    "base_commit_id", baseCommitId,
                    "head_commit_id", headCommitId,
                    "branch_name", branchName,
                    "version", "1.0.5"
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/integrations/continuous_integration/scan/repository"))
                    .header("X-AIK-API-SECRET", ciApiSecret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode node = objectMapper.readTree(response.body());
                int scanId = node.path("scan_id").asInt(-1);
                LOG.infof("Aikido CI scan triggered: scan_id=%d", scanId);
                return scanId;
            } else {
                LOG.warnf("Aikido CI scan trigger failed (HTTP %d): %s", response.statusCode(), response.body());
                return -1;
            }
        } catch (Exception e) {
            LOG.errorf("Aikido CI scan trigger error: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Poll CI scan status. Returns "passed", "failed", "running", or null on error.
     */
    public String pollCiScanStatus(int scanId) {
        if (ciApiSecret == null || ciApiSecret.isBlank()) return null;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/integrations/continuous_integration/scan/repository/" + scanId))
                    .header("X-AIK-API-SECRET", ciApiSecret)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode node = objectMapper.readTree(response.body());
                boolean gatePassed = node.path("gate_passed").asBoolean(false);
                boolean isRunning = node.path("all_scans_completed").isMissingNode()
                        || !node.path("all_scans_completed").asBoolean(true);
                if (isRunning) return "running";
                return gatePassed ? "passed" : "failed";
            }
            return null;
        } catch (Exception e) {
            LOG.warnf("Aikido CI scan poll error: %s", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    private String get(String path, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.debugf("Aikido %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.warnf("Aikido %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("Aikido %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    private static String extractText(JsonNode node, String field, String fallback) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) return fallback;
        return child.asText(fallback);
    }

    private static String extractRepoName(JsonNode root) {
        for (String field : new String[]{"repo_name", "repository_name", "repository"}) {
            JsonNode n = root.path(field);
            if (!n.isMissingNode() && !n.isNull()) {
                return n.isObject() ? n.path("name").asText("") : n.asText("");
            }
        }
        JsonNode repo = root.path("code_repo");
        if (!repo.isMissingNode()) {
            return repo.path("name").asText(repo.path("repo_name").asText(""));
        }
        return "";
    }

    private static String extractRepoUrl(JsonNode root) {
        for (String field : new String[]{"repo_url", "repository_url", "clone_url"}) {
            JsonNode n = root.path(field);
            if (!n.isMissingNode() && !n.isNull() && !n.asText("").isBlank()) {
                return n.asText("");
            }
        }
        JsonNode repo = root.path("code_repo");
        if (!repo.isMissingNode()) {
            for (String field : new String[]{"url", "clone_url", "html_url"}) {
                JsonNode n = repo.path(field);
                if (!n.isMissingNode() && !n.isNull() && !n.asText("").isBlank()) {
                    return n.asText("");
                }
            }
        }
        return null;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
