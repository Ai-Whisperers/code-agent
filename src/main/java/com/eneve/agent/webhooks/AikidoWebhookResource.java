package com.eneve.agent.webhooks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.aikido.AikidoTriageService;
import com.eneve.agent.model.RunResult;
import com.eneve.agent.notifications.TeamsNotifier;
import com.eneve.agent.upgrade.UpgradeService;
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
 * Handles incoming Aikido Security webhooks.
 * When Aikido reports a vulnerability for a known repository, automatically triggers
 * an upgrade check for that repository via {@link UpgradeService}.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class AikidoWebhookResource {

    private static final Logger LOG = Logger.getLogger(AikidoWebhookResource.class);

    private static final Set<String> ACTIONABLE_SEVERITIES = Set.of("critical", "high");

    @Inject UpgradeService upgradeService;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject AikidoService aikidoService;
    @Inject AikidoTriageService aikidoTriageService;
    @Inject HookEvaluator hookEvaluator;
    @Inject TeamsNotifier teamsNotifier;

    ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService upgradeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aikido-webhook-upgrade");
        t.setDaemon(true);
        return t;
    });

    @POST
    @Path("/aikido")
    @Operation(
            operationId = "aikidoWebhook",
            summary = "Handle Aikido Security webhook events",
            description = "Receives Aikido Security webhook payloads for vulnerability notifications. "
                    + "When a known repository is identified in the payload, automatically triggers "
                    + "an upgrade check for that repository. Signature verification and replay-attack "
                    + "protection are enforced by WebhookSignatureFilter before this handler is called."
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Upgrade check triggered in background",
                    content = @Content(schema = @Schema(example = "{\"action\": \"upgrade_check_started\", \"workspace\": \"...\", \"repoSlug\": \"...\"}"))),
            @APIResponse(responseCode = "200", description = "Webhook processed — event ignored or repo not found")
    })
    public Response handleAikidoWebhook(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String eventType = root.path("event_type").asText("unknown");
            LOG.infof("Aikido webhook received: event_type=%s", eventType);

            // The actual event data lives inside the nested "payload" node when present
            JsonNode payloadNode = root.has("payload") && !root.path("payload").isNull()
                    ? root.path("payload")
                    : root;

            Optional<RepoSettings> repoOpt = resolveRepo(payloadNode);
            if (repoOpt.isEmpty()) {
                LOG.infof("Aikido webhook: no matching repository found for event_type=%s — skipping", eventType);
                return ok("skipped", "No matching repository found in settings");
            }

            RepoSettings repo = repoOpt.get();
            LOG.infof("Aikido webhook: matched repo %s/%s for event_type=%s",
                    repo.workspace(), repo.repoSlug(), eventType);

            // Extract vulnerability context from payload
            String severity = payloadNode.path("severity").asText(
                    payloadNode.path("risk_level").asText("unknown")).toLowerCase();
            String issueType = extractIssueType(payloadNode);
            Integer groupId = extractGroupId(payloadNode);

            // Only new vulnerability events trigger remediation (not "fixed" or "resolved")
            boolean isNewVulnerability = eventType != null
                    && !eventType.toLowerCase().contains("fixed")
                    && !eventType.toLowerCase().contains("resolved");

            // ── Remediation triage for critical/high actionable issues ────────
            if (isNewVulnerability && ACTIONABLE_SEVERITIES.contains(severity)
                    && aikidoService.isActionableType(issueType) && groupId != null) {

                // Check for unmapped container (repoUrl null after resolution)
                String repoUrl = repo.gitPlatformUrl();
                if (repoUrl == null || repoUrl.isBlank()) {
                    String containerImage = extractContainerImage(payloadNode);
                    if (containerImage != null) {
                        String mapped = aikidoService.findCodeRepoUrlForContainer(containerImage);
                        if (mapped == null) {
                            LOG.warnf("Aikido webhook: container image '%s' has no repo mapping — alerting team",
                                    containerImage);
                            teamsNotifier.sendNotification(new RunResult(
                                    null, "AIKIDO_TRIAGE", "FAILED", null, null, null, null,
                                    null,
                                    "Unmapped container: " + containerImage
                                    + " — update container-repo-mapping.json to enable auto-remediation.",
                                    0, 0));
                            return ok("skipped", "Unmapped container: " + containerImage);
                        }
                        repoUrl = mapped;
                    }
                }

                final int finalGroupId = groupId;
                final String finalRepoUrl = repoUrl;
                final String finalSeverity = severity;
                final String finalIssueType = issueType;

                upgradeExecutor.submit(() -> {
                    try {
                        AikidoTriageService.TriageResult result = aikidoTriageService.handleNewIssue(
                                finalGroupId, finalRepoUrl, finalSeverity, finalIssueType);
                        if (result.skipped()) {
                            LOG.infof("Aikido triage skipped for group %d: %s", finalGroupId, result.skipReason());
                        } else {
                            LOG.infof("Aikido triage dispatched job %s for JIRA %s (group=%d)",
                                    result.jobId(), result.jiraKey(), finalGroupId);
                        }
                    } catch (Exception e) {
                        LOG.errorf("Aikido triage failed for group %d: %s", finalGroupId, e.getMessage());
                    }
                });

                // Also run upgrade check and hooks in background
                upgradeExecutor.submit(() -> {
                    try {
                        upgradeService.checkAndUpgradeOne(repo.workspace(), repo.repoSlug());
                    } catch (Exception e) {
                        LOG.warnf("Upgrade check failed: %s", e.getMessage());
                    }
                });
                evaluateAikidoHooks(eventType, repo, payloadNode);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("action", "triage_started");
                body.put("workspace", repo.workspace());
                body.put("repoSlug", repo.repoSlug());
                body.put("eventType", eventType);
                body.put("severity", severity);
                body.put("groupId", groupId);
                return Response.accepted(body).build();

            } else {
                // Not a remediation-eligible event: still run upgrade check and hooks
                if (!isNewVulnerability) {
                    LOG.infof("Aikido webhook: event_type=%s is not a new vulnerability — skipping triage", eventType);
                } else if (!ACTIONABLE_SEVERITIES.contains(severity)) {
                    LOG.infof("Aikido webhook: severity=%s is below threshold — skipping triage", severity);
                } else if (!aikidoService.isActionableType(issueType)) {
                    LOG.infof("Aikido webhook: issueType=%s is not actionable — skipping triage", issueType);
                }

                upgradeExecutor.submit(() -> {
                    try {
                        upgradeService.checkAndUpgradeOne(repo.workspace(), repo.repoSlug());
                    } catch (Exception e) {
                        LOG.warnf("Upgrade check failed: %s", e.getMessage());
                    }
                });
                evaluateAikidoHooks(eventType, repo, payloadNode);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("action", "upgrade_check_started");
                body.put("workspace", repo.workspace());
                body.put("repoSlug", repo.repoSlug());
                body.put("eventType", eventType);
                return Response.accepted(body).build();
            }

        } catch (Exception e) {
            LOG.errorf("Aikido webhook processing error: %s", e.getMessage());
            return ok("error", e.getMessage());
        }
    }

    // ─── Repository resolution ──────────────────────────────────────────────────

    /**
     * Attempts to resolve a known {@link RepoSettings} from the Aikido event payload.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Repo name / slug extracted directly from the payload fields.</li>
     *   <li>Last path segment of any repo URL present in the payload.</li>
     *   <li>Container image name looked up via the container-repo mapping, then the
     *       resulting URL parsed for workspace + slug.</li>
     * </ol>
     */
    Optional<RepoSettings> resolveRepo(JsonNode payload) {
        List<RepoSettings> all = repoSettingsStore.listAll();

        // 1. Direct repo name / slug candidates
        for (String field : new String[]{"repo_name", "repository_name"}) {
            String name = payload.path(field).asText("");
            if (!name.isBlank()) {
                Optional<RepoSettings> match = matchBySlug(all, name);
                if (match.isPresent()) return match;
            }
        }
        JsonNode codeRepo = payload.path("code_repo");
        if (!codeRepo.isMissingNode() && !codeRepo.isNull()) {
            String name = codeRepo.path("name").asText(codeRepo.path("repo_name").asText(""));
            if (!name.isBlank()) {
                Optional<RepoSettings> match = matchBySlug(all, name);
                if (match.isPresent()) return match;
            }
        }

        // 2. Last path segment of repo URL fields
        for (String field : new String[]{"repo_url", "repository_url", "clone_url"}) {
            String url = payload.path(field).asText("");
            if (url.isBlank() && !codeRepo.isMissingNode()) {
                url = codeRepo.path("url").asText(codeRepo.path("clone_url").asText(""));
            }
            if (!url.isBlank()) {
                String slug = lastPathSegment(url);
                if (!slug.isBlank()) {
                    Optional<RepoSettings> match = matchBySlug(all, slug);
                    if (match.isPresent()) return match;
                }
            }
        }

        // 3. Container image → repo URL mapping
        String containerImage = extractContainerImage(payload);
        if (containerImage != null && !containerImage.isBlank() && aikidoService.isEnabled()) {
            String codeRepoUrl = aikidoService.findCodeRepoUrlForContainer(containerImage);
            if (codeRepoUrl != null) {
                String[] parts = parseWorkspaceAndSlug(codeRepoUrl);
                if (parts != null) {
                    return repoSettingsStore.find(parts[0], parts[1]);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Finds the first repo in {@code all} whose slug (case-insensitive) contains
     * {@code candidate}, or where {@code candidate} contains the slug.
     */
    private static Optional<RepoSettings> matchBySlug(List<RepoSettings> all, String candidate) {
        String lower = candidate.toLowerCase();
        return all.stream()
                .filter(r -> {
                    String slug = r.repoSlug().toLowerCase();
                    return lower.contains(slug) || slug.contains(lower);
                })
                .findFirst();
    }

    /**
     * Extracts workspace and repo slug from a clone URL.
     * E.g. {@code https://bitbucket.org/csarenergy/my-repo.git} → {@code ["csarenergy", "my-repo"]}.
     * Returns {@code null} if the URL does not have at least two real path segments after the host.
     */
    static String[] parseWorkspaceAndSlug(String url) {
        if (url == null || url.isBlank()) return null;
        String work = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
        if (work.endsWith("/")) work = work.substring(0, work.length() - 1);

        // Isolate the path component: strip protocol + authority
        String path;
        int schemeEnd = work.indexOf("://");
        if (schemeEnd >= 0) {
            int pathStart = work.indexOf('/', schemeEnd + 3);
            if (pathStart < 0) return null; // only host, no path
            path = work.substring(pathStart + 1); // e.g. "csarenergy/my-repo"
        } else if (work.contains("@") && work.contains(":")) {
            // SSH: git@host:workspace/repo
            int colon = work.indexOf(':');
            path = work.substring(colon + 1);
        } else {
            path = work;
        }

        // path must contain at least one slash → workspace/slug
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) return null;
        String workspace = path.substring(0, slash);
        // Take only the first two segments (ignore sub-groups)
        String rest = path.substring(slash + 1);
        String slug = rest.contains("/") ? rest.substring(rest.lastIndexOf('/') + 1) : rest;
        if (workspace.isBlank() || slug.isBlank()) return null;
        return new String[]{workspace, slug};
    }

    private static String lastPathSegment(String url) {
        if (url == null || url.isBlank()) return "";
        String stripped = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
        int idx = stripped.lastIndexOf('/');
        return idx >= 0 ? stripped.substring(idx + 1) : stripped;
    }

    private static String extractContainerImage(JsonNode root) {
        for (String field : new String[]{"container_image", "image_name", "docker_image",
                "affected_container", "container_name"}) {
            String val = root.path(field).asText("");
            if (!val.isBlank()) return val;
        }
        JsonNode container = root.path("container");
        if (!container.isMissingNode() && !container.isNull()) {
            if (container.isTextual()) return container.asText("");
            String img = container.path("image").asText(container.path("name").asText(""));
            if (!img.isBlank()) return img;
        }
        return null;
    }

    private static String extractIssueType(JsonNode payload) {
        for (String field : new String[]{"type", "issue_type", "category", "scanner",
                "rule_type", "detection_type", "scan_type", "finding_type"}) {
            String val = payload.path(field).asText("");
            if (!val.isBlank()) return val.toLowerCase();
        }
        return "unknown";
    }

    private static Integer extractGroupId(JsonNode payload) {
        for (String field : new String[]{"group_id", "issue_group_id", "id", "group"}) {
            JsonNode node = payload.path(field);
            if (!node.isMissingNode() && node.isNumber()) return node.asInt();
            if (!node.isMissingNode() && node.isTextual()) {
                try { return Integer.parseInt(node.asText()); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private void evaluateAikidoHooks(String eventType, RepoSettings repo, JsonNode payload) {
        try {
            // Determine trigger type based on event
            String triggerType;
            if (eventType.toLowerCase().contains("vulnerability")) {
                if (eventType.toLowerCase().contains("fixed") || eventType.toLowerCase().contains("resolved")) {
                    triggerType = "aikido.vulnerability_fixed";
                } else {
                    triggerType = "aikido.vulnerability_new";
                }
            } else {
                triggerType = "aikido.vulnerability_new"; // fallback
            }

            // Extract context from payload
            String severity = payload.path("severity").asText(
                    payload.path("risk_level").asText("unknown"));
            String packageName = payload.path("package").path("name").asText(
                    payload.path("dependency").path("name").asText("unknown"));
            String cveId = payload.path("cve").path("id").asText(
                    payload.path("vulnerability").path("cve_id").asText("unknown"));

            // Extract issue type using same field strategy as AikidoService
            String issueType = "unknown";
            for (String field : new String[]{"type", "issue_type", "category", "scanner",
                    "rule_type", "detection_type", "scan_type", "finding_type"}) {
                JsonNode n = payload.path(field);
                if (!n.isMissingNode() && !n.isNull() && !n.asText("").isBlank()) {
                    issueType = n.asText("").toLowerCase();
                    break;
                }
            }

            var context = Map.of(
                    "eventType", eventType,
                    "repoSlug", repo.repoSlug(),
                    "severity", severity,
                    "packageName", packageName,
                    "cveId", cveId,
                    "issueType", issueType
            );

            // Build repo URL for this repo
            String repoUrl = null;
            if (repo.gitPlatformUrl() != null && !repo.gitPlatformUrl().isBlank()) {
                repoUrl = repo.gitPlatformUrl();
            } else {
                // Try to construct a reasonable repo URL
                repoUrl = "https://bitbucket.org/" + repo.workspace() + "/" + repo.repoSlug() + ".git";
            }

            // Evaluate hooks
            var hookJobIds = hookEvaluator.evaluateByTrigger(
                    triggerType, repo.workspace(), repo.repoSlug(), repoUrl, context);

            if (!hookJobIds.isEmpty()) {
                LOG.infof("Aikido webhook: triggered %d hook jobs for %s", hookJobIds.size(), triggerType);
            }

        } catch (Exception e) {
            LOG.warnf("Failed to evaluate Aikido hooks for %s/%s: %s", 
                    repo.workspace(), repo.repoSlug(), e.getMessage());
        }
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
