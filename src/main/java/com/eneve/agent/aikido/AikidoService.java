package com.eneve.agent.aikido;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Aikido Security REST API client.
 * Handles OAuth2 authentication, issue lookup, CVE enrichment, changelog retrieval,
 * and CI scan triggering.
 */
@ApplicationScoped
public class AikidoService {

    /**
     * Issue types that the agent can actually fix (SAST findings and open-source dependency
     * vulnerabilities). Secrets and container issues are excluded from automated fix flows
     * but are still included in quality reports.
     */
    private static final java.util.Set<String> ACTIONABLE_ISSUE_TYPES = java.util.Set.of(
            "sca", "dependency", "dependencies", "open_source", "software_composition_analysis",
            "sast", "code", "static_analysis", "code_security"
    );

    private static final Logger LOG = Logger.getLogger(AikidoService.class);

    @Inject SettingsService settings;
    @Inject AikidoGroupDetailStore groupDetailStore;

    private String baseUrl() { return settings.get("aikido.base.url", "https://app.aikido.dev"); }
    private String clientId() { return settings.get("aikido.client.id", ""); }
    private String clientSecret() { return settings.getSecret("aikido.client.secret"); }
    private String ciApiSecret() { return settings.getSecret("aikido.ci.api.secret"); }

    /** Minimum delay between consecutive detail API calls to stay under 20 req/min. */
    private static final long DETAIL_CALL_DELAY_MS = 3_100L;

    /**
     * Loaded once from {@code container-repo-mapping.json} on first use.
     * Key: full container image name (e.g. "julesenergy/julesclick-files").
     * Value: the {@code codeRepo} slug that owns it (e.g. "FileService").
     */
    private volatile java.util.Map<String, String> containerRepoMapping = null;

    private java.util.Map<String, String> getContainerRepoMapping() {
        if (containerRepoMapping != null) return containerRepoMapping;
        synchronized (this) {
            if (containerRepoMapping != null) return containerRepoMapping;
            java.util.Map<String, String> map = new java.util.HashMap<>();
            try (var stream = getClass().getClassLoader()
                    .getResourceAsStream("container-repo-mapping.json")) {
                if (stream != null) {
                    JsonNode root = objectMapper.readTree(stream);
                    JsonNode mappings = root.path("mappings");
                    var it = mappings.fieldNames();
                    while (it.hasNext()) {
                        String imgName = it.next();
                        String codeRepo = mappings.path(imgName).path("codeRepo").asText("");
                        // Store all entries — blank/null codeRepo stored as "" to mark the image
                        // as "known but untracked" so Pass 2 drops it instead of misrouting it.
                        map.put(imgName.toLowerCase(), codeRepo.toLowerCase());
                    }
                    LOG.infof("Loaded %d container→repo mappings from container-repo-mapping.json", map.size());
                } else {
                    LOG.warn("container-repo-mapping.json not found on classpath");
                }
            } catch (Exception ex) {
                LOG.warnf("Failed to load container-repo-mapping.json: %s", ex.getMessage());
            }
            containerRepoMapping = java.util.Collections.unmodifiableMap(map);
        }
        return containerRepoMapping;
    }

    @Inject HttpClient httpClient;
    @Inject ObjectMapper objectMapper;

    private String accessToken;
    private Instant tokenExpiry = Instant.EPOCH;
    private final ReentrantLock tokenLock = new ReentrantLock();

    public boolean isEnabled() {
        String id = clientId();
        String secret = clientSecret();
        return id != null && !id.isBlank() && secret != null && !secret.isBlank();
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
                    .encodeToString((clientId() + ":" + clientSecret())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("grant_type", "client_credentials"));

            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(baseUrl() + "/api/oauth/token"))
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

    /**
     * Looks up all open issue groups and returns the linked JIRA issue key for the specified
     * group, or {@code null} if no JIRA link is recorded in Aikido.
     * Used by AikidoTriageService to avoid creating duplicate JIRA tickets.
     */
    public String findLinkedJiraKeyForGroup(int groupId) {
        String json = get("/api/public/v1/open-issue-groups", "list open issues (jira key lookup)");
        if (json == null) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode groups = root.isArray() ? root : root.path("data");
            if (!groups.isArray()) groups = root.path("groups");

            for (JsonNode group : groups) {
                if (group.path("id").asInt(-1) != groupId) continue;

                // Primary: external_ticket_id / jira_issue_key on the group
                String ext = group.path("external_ticket_id").asText(
                        group.path("jira_issue_key").asText(""));
                if (!ext.isBlank()) return ext;

                // Secondary: tasks array
                JsonNode tasks = group.path("tasks");
                if (tasks.isArray()) {
                    for (JsonNode task : tasks) {
                        String key = task.path("key").asText(task.path("external_id").asText(""));
                        if (!key.isBlank()) return key;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("findLinkedJiraKeyForGroup(%d) failed: %s", groupId, e.getMessage());
        }
        return null;
    }

    private boolean matchesJiraKey(JsonNode group, String jiraKey) {
        String title = group.path("title").asText("");
        if (title.contains(jiraKey)) return true;

        String externalId = group.path("external_ticket_id").asText(
                group.path("jira_issue_key").asText(""));
        return jiraKey.equalsIgnoreCase(externalId);
    }

    /**
     * Fetch all open issue groups and return enriched details for those belonging to the given repo.
     * Matching is lenient: a group matches if its repo name contains {@code repoSlug} (case-insensitive),
     * or if the last path segment of the repo URL matches {@code repoSlug}.
     *
     * <p><b>Note:</b> this method calls the list endpoint once and then one detail endpoint per
     * matching group. For scanning multiple repos in one pass use
     * {@link #findOpenIssuesForAllRepos(java.util.Collection)} to avoid redundant list calls and
     * stay within Aikido's rate limit (20 req/min).
     */
    public List<AikidoIssueInfo> findOpenIssuesForRepo(String repoSlug) {
        if (repoSlug == null || repoSlug.isBlank()) return List.of();

        String json = get("/api/public/v1/open-issue-groups", "list open issues for repo");
        if (json == null) return List.of();

        List<AikidoIssueInfo> results = new ArrayList<>();
        try {
            JsonNode groups = parseGroups(json);
            if (groups == null) return List.of();

            String slugLower = repoSlug.toLowerCase();
            for (JsonNode group : groups) {
                if (!groupMatchesRepo(group, slugLower)) continue;
                // Skip low/medium — only critical/high are actionable for the snapshot
                String sev = extractSeverity(group);
                if (!"critical".equals(sev) && !"high".equals(sev)) continue;
                int groupId = group.path("id").asInt(-1);
                if (groupId < 0) continue;
                AikidoIssueInfo info = getIssueGroupDetail(groupId);
                if (info != null) results.add(info);
            }
            LOG.infof("Aikido: found %d open critical/high issue(s) for repo '%s'", results.size(), repoSlug);
        } catch (Exception e) {
            LOG.errorf("Aikido: failed to parse open issues for repo '%s': %s", repoSlug, e.getMessage());
        }
        return results;
    }

    /**
     * Uses the {@code /api/public/v1/issues/export} endpoint to fetch <b>all</b> issues
     * (open, task_closed, snoozed, ignored, etc.) across all severities, then returns a map of
     * {@code repoSlug → List<AikidoIssueInfo>} filtered to critical and high severity only.
     *
     * <p>Advantages over the old open-issue-groups + N detail calls approach:
     * <ul>
     *   <li>Single API call — no N+1 detail fetches, well within the 20 req/min rate limit.</li>
     *   <li>Includes issues with {@code group_status=task_closed} (Jira ticket created) which
     *       the open-issue-groups endpoint silently omits.</li>
     *   <li>Full detail fields (description, how_to_fix, related_cve_ids, etc.) are inline.</li>
     * </ul>
     */
    public java.util.Map<String, List<AikidoIssueInfo>> findOpenIssuesForAllRepos(
            java.util.Collection<String> repoSlugs) {

        java.util.Map<String, List<AikidoIssueInfo>> result = new java.util.LinkedHashMap<>();
        for (String slug : repoSlugs) {
            result.put(slug, new ArrayList<>());
        }

        String json = get("/api/public/v1/issues/export", "export all issues (bulk)");
        if (json == null) return result;

        try {
            JsonNode root = objectMapper.readTree(json);
            // The export endpoint returns { "data": [...] } or a bare array
            JsonNode issues = root.isArray() ? root : root.path("data");
            if (!issues.isArray()) {
                LOG.warnf("Aikido export: unexpected response shape. Raw (first 500 chars): %s",
                        json.length() > 500 ? json.substring(0, 500) : json);
                return result;
            }

            LOG.infof("Aikido export: received %d total issue(s)", issues.size());

            // Pre-lowercase all slugs once
            java.util.Map<String, String> slugMap = new java.util.LinkedHashMap<>();
            for (String slug : repoSlugs) {
                slugMap.put(slug, slug.toLowerCase());
            }

            int skippedSeverity = 0;
            int matched = 0;

            // Global dedup: each group_id is assigned to exactly one repo slug.
            //
            // Routing rules:
            //   Container issues (attack_surface = docker_container / container / …):
            //     1. Try container_repo_name last-segment first (e.g. "julesclick-files" from
            //        "julesenergy/julesclick-files") — this keeps the issue under its own
            //        container repo if that slug is tracked.
            //     2. Fall back to code_repo_name if the container slug is not tracked.
            //
            //   Software issues (all other attack_surfaces):
            //     Match by code_repo_name only.
            //
            // This prevents a container CVE that was built from the "fit" source repo from
            // appearing under "fit" when the container image has its own tracked slug.
            java.util.Map<Integer, String> groupToSlug = new java.util.LinkedHashMap<>();

            java.util.Map<String, String> ctrMapping = getContainerRepoMapping();
            LOG.infof("Aikido routing: mapping has %d container entries, tracking %d repo slugs: %s",
                    ctrMapping.size(), slugMap.size(), slugMap.keySet());

            // Pass 1a: any issue with a container_repo_name → resolve via mapping first,
            // then last-segment match. Attack_surface is NOT checked here — a container image
            // name in the mapping is authoritative regardless of how Aikido classifies the surface.
            for (JsonNode issue : issues) {
                String severity = extractSeverity(issue);
                if (!"critical".equals(severity) && !"high".equals(severity)) {
                    skippedSeverity++;
                    continue;
                }
                if (isInactiveStatus(issue)) continue;
                int groupId = issue.path("group_id").asInt(issue.path("id").asInt(-1));
                if (groupId < 0 || groupToSlug.containsKey(groupId)) continue;

                String containerRepoName = issue.path("container_repo_name").asText("").toLowerCase();
                if (containerRepoName.isBlank()) continue;

                String mappedCodeRepo = ctrMapping.get(containerRepoName);
                if (mappedCodeRepo != null) {
                    // Mapping is authoritative — find the tracked slug or drop in Pass 2
                    boolean mappingMatched = false;
                    for (java.util.Map.Entry<String, String> entry : slugMap.entrySet()) {
                        if (entry.getValue().equals(mappedCodeRepo)) {
                            groupToSlug.put(groupId, entry.getKey());
                            LOG.infof("Aikido routing [1a-map]: group %d container '%s' → mapped codeRepo '%s' → tracked slug '%s'",
                                    groupId, containerRepoName, mappedCodeRepo, entry.getKey());
                            mappingMatched = true;
                            break;
                        }
                    }
                    if (!mappingMatched) {
                        LOG.infof("Aikido routing [1a-map]: group %d container '%s' → mapped codeRepo '%s' NOT in tracked slugs — will drop",
                                groupId, containerRepoName, mappedCodeRepo);
                    }
                    continue; // mapping is authoritative — don't fall through to last-seg
                }

                // No mapping entry — try last-segment match against tracked slugs
                String lastSeg = containerRepoName.substring(containerRepoName.lastIndexOf('/') + 1);
                boolean segMatched = false;
                for (java.util.Map.Entry<String, String> entry : slugMap.entrySet()) {
                    String slugLower = entry.getValue();
                    if (containerRepoName.equals(slugLower) || lastSeg.equals(slugLower)) {
                        groupToSlug.put(groupId, entry.getKey());
                        LOG.infof("Aikido routing [1a-seg]: group %d container '%s' → slug '%s'",
                                groupId, containerRepoName, entry.getKey());
                        segMatched = true;
                        break;
                    }
                }
                if (!segMatched) {
                    LOG.infof("Aikido routing [1a-seg]: group %d container '%s' — no slug match, no mapping — will try Pass 2",
                            groupId, containerRepoName);
                }
            }

            // Pass 1b: software issues (no container_repo_name) → match by code_repo_name
            for (JsonNode issue : issues) {
                String severity = extractSeverity(issue);
                if (!"critical".equals(severity) && !"high".equals(severity)) continue;
                if (isInactiveStatus(issue)) continue;
                int groupId = issue.path("group_id").asInt(issue.path("id").asInt(-1));
                if (groupId < 0 || groupToSlug.containsKey(groupId)) continue;

                String containerRepoName = issue.path("container_repo_name").asText("").toLowerCase();
                if (!containerRepoName.isBlank()) continue; // handled in Pass 1a

                String codeRepoName = issue.path("code_repo_name").asText("").toLowerCase();
                if (!codeRepoName.isBlank()) {
                    String lastSeg = codeRepoName.substring(codeRepoName.lastIndexOf('/') + 1);
                    for (java.util.Map.Entry<String, String> entry : slugMap.entrySet()) {
                        String slugLower = entry.getValue();
                        if (codeRepoName.equals(slugLower) || lastSeg.equals(slugLower)) {
                            groupToSlug.put(groupId, entry.getKey());
                            LOG.infof("Aikido routing [1b]: group %d code_repo '%s' → slug '%s'",
                                    groupId, codeRepoName, entry.getKey());
                            break;
                        }
                    }
                }
            }

            // Pass 2: anything still unassigned — fall back to code_repo_name only if the
            // container_repo_name is NOT in the mapping at all (truly unknown container).
            // If container_repo_name IS in the mapping, the issue belongs to a known but
            // untracked repo — drop it to avoid misrouting.
            for (JsonNode issue : issues) {
                String severity = extractSeverity(issue);
                if (!"critical".equals(severity) && !"high".equals(severity)) continue;
                if (isInactiveStatus(issue)) continue;
                int groupId = issue.path("group_id").asInt(issue.path("id").asInt(-1));
                if (groupId < 0 || groupToSlug.containsKey(groupId)) continue;

                String containerRepoName = issue.path("container_repo_name").asText("").toLowerCase();
                if (!containerRepoName.isBlank() && ctrMapping.containsKey(containerRepoName)) {
                    LOG.infof("Aikido routing [drop]: group %d container '%s' in mapping but codeRepo not tracked — dropping",
                            groupId, containerRepoName);
                    continue;
                }

                String codeRepoName = issue.path("code_repo_name").asText("").toLowerCase();
                if (!codeRepoName.isBlank()) {
                    String lastSeg = codeRepoName.substring(codeRepoName.lastIndexOf('/') + 1);
                    for (java.util.Map.Entry<String, String> entry : slugMap.entrySet()) {
                        String slugLower = entry.getValue();
                        if (codeRepoName.equals(slugLower) || lastSeg.equals(slugLower)) {
                            groupToSlug.put(groupId, entry.getKey());
                            LOG.infof("Aikido routing [2-fallback]: group %d container '%s' code_repo '%s' → slug '%s'",
                                    groupId, containerRepoName, codeRepoName, entry.getKey());
                            break;
                        }
                    }
                }
            }

            // Collect one representative export row per group_id.
            // Prefer the row whose container_repo_name (lowercased last-segment) matches the
            // assigned slug — this ensures containerImage on the final issue reflects the
            // correct container, not a sibling container that happens to share the same group_id.
            java.util.Map<Integer, JsonNode> groupRepresentative = new java.util.LinkedHashMap<>();
            for (JsonNode issue : issues) {
                String severity = extractSeverity(issue);
                if (!"critical".equals(severity) && !"high".equals(severity)) continue;
                if (isInactiveStatus(issue)) continue;
                int groupId = issue.path("group_id").asInt(issue.path("id").asInt(-1));
                String assignedSlug = groupToSlug.get(groupId);
                if (groupId < 0 || assignedSlug == null) continue;

                if (!groupRepresentative.containsKey(groupId)) {
                    groupRepresentative.put(groupId, issue);
                } else {
                    // Upgrade to this row if its container_repo_name maps to the assigned slug
                    String ctr = issue.path("container_repo_name").asText("").toLowerCase();
                    if (!ctr.isBlank()) {
                        String mappedRepo = ctrMapping.get(ctr);
                        String slugLower = assignedSlug.toLowerCase();
                        boolean isCanonical = (mappedRepo != null && mappedRepo.equals(slugLower))
                                || ctr.equals(slugLower)
                                || ctr.substring(ctr.lastIndexOf('/') + 1).equals(slugLower);
                        if (isCanonical) {
                            groupRepresentative.put(groupId, issue);
                        }
                    }
                }
            }

            int totalGroups = groupRepresentative.size();
            LOG.infof("Aikido export: %d distinct critical/high group(s) to enrich", totalGroups);

            // Determine which groups are already in the DB cache
            java.util.Set<Integer> cachedIds = groupDetailStore.findCachedIds(groupRepresentative.keySet());
            int cacheMisses = totalGroups - cachedIds.size();
            LOG.infof("Aikido detail cache: %d hit(s), %d miss(es) — will fetch misses at ~%dms intervals",
                    cachedIds.size(), cacheMisses, DETAIL_CALL_DELAY_MS);

            long lastDetailCallMs = 0;

            for (java.util.Map.Entry<Integer, JsonNode> entry : groupRepresentative.entrySet()) {
                int groupId = entry.getKey();
                JsonNode exportRow = entry.getValue();
                String slug = groupToSlug.get(groupId);

                AikidoIssueInfo exportInfo = parseExportIssue(exportRow, groupId, slug);
                if (exportInfo == null) continue;

                // Try DB cache first; fall back to API (rate-limited) on miss
                AikidoIssueInfo detailInfo = groupDetailStore.find(groupId).orElse(null);
                if (detailInfo == null) {
                    // Rate-limit: ensure at least DETAIL_CALL_DELAY_MS between API calls
                    long now = System.currentTimeMillis();
                    long elapsed = now - lastDetailCallMs;
                    if (lastDetailCallMs > 0 && elapsed < DETAIL_CALL_DELAY_MS) {
                        try { Thread.sleep(DETAIL_CALL_DELAY_MS - elapsed); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    detailInfo = getIssueGroupDetail(groupId);
                    lastDetailCallMs = System.currentTimeMillis();
                    if (detailInfo != null) {
                        groupDetailStore.upsert(detailInfo);
                    }
                }

                AikidoIssueInfo merged;
                if (detailInfo != null) {
                    // Prefer detail fields for rich content; keep export fields for status/SLA/versions.
                    // Always keep exportInfo.issueType() when it is "container" — the detail endpoint
                    // returns the vulnerability class (e.g. "open_source") not the attack surface.
                    String mergedIssueType = "container".equals(exportInfo.issueType())
                            ? "container"
                            : (detailInfo.issueType() != null && !"unknown".equals(detailInfo.issueType())
                                    ? detailInfo.issueType() : exportInfo.issueType());
                    merged = new AikidoIssueInfo(
                            groupId,
                            mergedIssueType,
                            detailInfo.title() != null && !detailInfo.title().isBlank()
                                    ? detailInfo.title() : exportInfo.title(),
                            detailInfo.description(),
                            exportInfo.severity(),
                            exportInfo.severityScore() != null ? exportInfo.severityScore() : detailInfo.severityScore(),
                            exportInfo.packageName() != null && !"unknown".equals(exportInfo.packageName())
                                    ? exportInfo.packageName() : detailInfo.packageName(),
                            exportInfo.currentVersion() != null && !exportInfo.currentVersion().isBlank()
                                    ? exportInfo.currentVersion() : detailInfo.currentVersion(),
                            exportInfo.fixedVersion() != null && !exportInfo.fixedVersion().isBlank()
                                    ? exportInfo.fixedVersion() : detailInfo.fixedVersion(),
                            exportInfo.cveId() != null && !exportInfo.cveId().isBlank()
                                    ? exportInfo.cveId() : detailInfo.cveId(),
                            detailInfo.cveDescription(),
                            detailInfo.cvssScore(),
                            slug,
                            detailInfo.repoUrl(),
                            exportInfo.containerImage() != null
                                    ? exportInfo.containerImage() : detailInfo.containerImage(),
                            detailInfo.changelogSummary(),
                            detailInfo.howToFix(),
                            detailInfo.relatedCveIds() != null && !detailInfo.relatedCveIds().isEmpty()
                                    ? detailInfo.relatedCveIds() : exportInfo.relatedCveIds(),
                            exportInfo.groupStatus(),
                            exportInfo.timeToFixMinutes() != null
                                    ? exportInfo.timeToFixMinutes() : detailInfo.timeToFixMinutes(),
                            exportInfo.firstDetectedAt(),
                            exportInfo.slaRemediateBy()
                    );
                } else {
                    merged = exportInfo;
                }

                result.get(slug).add(merged);
                matched++;
            }

            LOG.infof("Aikido export: %d critical/high issue(s) matched across %d repo(s) (skipped %d lower-severity)",
                    matched, repoSlugs.size(), skippedSeverity);
            for (java.util.Map.Entry<String, List<AikidoIssueInfo>> entry : result.entrySet()) {
                LOG.infof("Aikido: found %d critical/high issue(s) for repo '%s'",
                        entry.getValue().size(), entry.getKey());
            }

        } catch (Exception e) {
            LOG.errorf("Aikido: failed to parse export issues (bulk): %s", e.getMessage());
        }
        return result;
    }

    /**
     * Parses a single row from the {@code /issues/export} response into an {@link AikidoIssueInfo}.
     *
     * <p>Export row field mapping (confirmed from live JSON):
     * <ul>
     *   <li>{@code type}              → issueType</li>
     *   <li>{@code severity}          → severity</li>
     *   <li>{@code severity_score}    → severityScore</li>
     *   <li>{@code status}            → groupStatus  (open / closed / ignored / snoozed)</li>
     *   <li>{@code affected_package}  → packageName</li>
     *   <li>{@code installed_version} → currentVersion</li>
     *   <li>{@code patched_versions}  → fixedVersion  (array — take first element)</li>
     *   <li>{@code cve_id}            → cveId</li>
     *   <li>{@code code_repo_name}    → repoName (plain slug)</li>
     *   <li>{@code container_repo_name} → containerImage</li>
     *   <li>{@code sla_days}          → timeToFixMinutes (days × 1440)</li>
     * </ul>
     */
    // attack_surface values that indicate the issue lives in a container image, not source code
    private static final java.util.Set<String> CONTAINER_ATTACK_SURFACES = java.util.Set.of(
            "docker_container", "container", "container_image", "container_registry"
    );

    private AikidoIssueInfo parseExportIssue(JsonNode issue, int groupId, String repoSlug) {
        try {
            String issueType  = extractIssueType(issue);
            String severity   = extractSeverity(issue);
            String groupStatus = extractText(issue, "status", "");
            Integer severityScore = issue.path("severity_score").isNumber()
                    ? issue.path("severity_score").asInt() : null;

            // sla_days is in days — convert to minutes for the shared model field
            Integer timeToFixMinutes = null;
            if (issue.path("sla_days").isNumber()) {
                timeToFixMinutes = issue.path("sla_days").asInt() * 1440;
            }

            String packageName = extractText(issue, "affected_package",
                    extractText(issue, "package_name",
                            extractText(issue, "dependency_name", "unknown")));

            String currentVersion = extractText(issue, "installed_version",
                    extractText(issue, "current_version", ""));

            // patched_versions is an array — take the first element as the target fix version
            String fixedVersion = "";
            JsonNode patchedArr = issue.path("patched_versions");
            if (patchedArr.isArray() && patchedArr.size() > 0) {
                fixedVersion = patchedArr.get(0).asText("");
            }
            if (fixedVersion.isBlank()) {
                fixedVersion = extractText(issue, "fix_version",
                        extractText(issue, "fixed_in_version", ""));
            }

            String cveId = extractText(issue, "cve_id", extractText(issue, "cve", ""));

            // container_repo_name is the container image identifier (e.g. "julesenergy/jtp")
            String containerImage = extractText(issue, "container_repo_name", null);
            if (containerImage != null && containerImage.isBlank()) containerImage = null;

            // attack_surface tells us WHERE the vulnerability was found.
            // A CVE in a container image has type="open_source" but attack_surface="docker_container".
            // Override issueType to "container" so the UI can distinguish it from a source-code fix.
            String attackSurface = issue.path("attack_surface").asText("").toLowerCase();
            if (CONTAINER_ATTACK_SURFACES.contains(attackSurface)) {
                issueType = "container";
            }

            // first_detected_at and sla_remediate_by are epoch-seconds integers in the export
            java.time.Instant firstDetectedAt = null;
            if (issue.path("first_detected_at").isNumber()) {
                firstDetectedAt = java.time.Instant.ofEpochSecond(issue.path("first_detected_at").asLong());
            }
            java.time.Instant slaRemediateBy = null;
            if (issue.path("sla_remediate_by").isNumber()) {
                slaRemediateBy = java.time.Instant.ofEpochSecond(issue.path("sla_remediate_by").asLong());
            }

            // Build a human-readable title from package + CVE
            String title = packageName.equals("unknown") ? cveId
                    : (cveId.isBlank() ? packageName : packageName + " (" + cveId + ")");

            return new AikidoIssueInfo(
                    groupId, issueType, title,
                    null,           // description — not in export; available via detail endpoint if needed
                    severity, severityScore,
                    packageName, currentVersion, fixedVersion,
                    cveId, null, null,
                    repoSlug, null, containerImage, null,
                    null,           // howToFix — not in export
                    java.util.List.of(), groupStatus, timeToFixMinutes,
                    firstDetectedAt, slaRemediateBy
            );
        } catch (Exception e) {
            LOG.warnf("Aikido export: failed to parse issue group %d: %s", groupId, e.getMessage());
            return null;
        }
    }

    /** Parses the open-issue-groups JSON response and returns the groups array, or null on error. */
    private JsonNode parseGroups(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode groups = root.isArray() ? root : root.path("data");
            if (!groups.isArray()) groups = root.path("groups");
            if (!groups.isArray()) {
                LOG.warnf("Aikido: unexpected response shape when listing open issues. Raw (first 2000 chars): %s",
                        json.length() > 2000 ? json.substring(0, 2000) : json);
                return null;
            }
            // Log the first group entry so we can inspect the actual field names
            if (groups.size() > 0) {
                LOG.infof("Aikido open-issue-groups first entry fields: %s | raw: %s",
                        fieldNames(groups.get(0)),
                        groups.get(0).toString());
            }
            return groups;
        } catch (Exception e) {
            LOG.errorf("Aikido: failed to parse open-issue-groups response: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Returns only actionable Aikido issues for the given repo: SAST findings and open-source
     * dependency vulnerabilities. Secrets and container issues are excluded because they cannot
     * be reliably auto-remediated by the agent.
     *
     * <p>Applies a best-effort two-level filter:
     * <ol>
     *   <li>Group level: skips the detail API call if the group JSON already has a non-actionable type.</li>
     *   <li>Detail level: drops the issue after {@link #getIssueGroupDetail} if the parsed type is non-actionable.</li>
     * </ol>
     */
    public List<AikidoIssueInfo> findActionableIssuesForRepo(String repoSlug) {
        if (repoSlug == null || repoSlug.isBlank()) return List.of();

        String json = get("/api/public/v1/open-issue-groups", "list open issues for repo (actionable)");
        if (json == null) return List.of();

        List<AikidoIssueInfo> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode groups = root.isArray() ? root : root.path("data");
            if (!groups.isArray()) {
                groups = root.path("groups");
            }
            if (!groups.isArray()) {
                LOG.warnf("Aikido: unexpected response shape when listing open issues");
                return List.of();
            }

            String slugLower = repoSlug.toLowerCase();
            for (JsonNode group : groups) {
                if (!groupMatchesRepo(group, slugLower)) continue;

                // Skip low/medium at the group level to avoid unnecessary detail calls
                String groupSev = extractSeverity(group);
                if (!"critical".equals(groupSev) && !"high".equals(groupSev)) continue;

                // Group-level type filter: avoid the detail API call for non-actionable types
                String groupType = extractIssueType(group);
                if (!"unknown".equals(groupType) && !isActionableType(groupType)) {
                    LOG.debugf("Aikido: skipping non-actionable group (type=%s) for repo '%s'", groupType, repoSlug);
                    continue;
                }

                int groupId = group.path("id").asInt(-1);
                if (groupId < 0) continue;

                AikidoIssueInfo info = getIssueGroupDetail(groupId);
                if (info == null) continue;

                // Detail-level type filter: drop if type became known and is not actionable
                if (!isActionableType(info.issueType())) {
                    LOG.debugf("Aikido: dropping non-actionable issue group %d (type=%s) for repo '%s'",
                            groupId, info.issueType(), repoSlug);
                    continue;
                }

                results.add(info);
            }
            LOG.infof("Aikido: found %d actionable issue(s) (SAST/dependency) for repo '%s'",
                    results.size(), repoSlug);
        } catch (Exception e) {
            LOG.errorf("Aikido: failed to parse actionable issues for repo '%s': %s", repoSlug, e.getMessage());
        }
        return results;
    }

    public boolean isActionableType(String issueType) {
        if (issueType == null || issueType.isBlank() || "unknown".equals(issueType)) return true;
        return ACTIONABLE_ISSUE_TYPES.contains(issueType.toLowerCase());
    }

    private boolean groupMatchesRepo(JsonNode group, String slugLower) {
        // ── Export endpoint fields (flat structure, no locations array) ──────────
        // code_repo_name: plain slug e.g. "jtp"
        // container_repo_name: org/slug e.g. "julesenergy/jtp"
        for (String field : new String[]{"code_repo_name", "container_repo_name",
                "repo_name", "repository_name", "name"}) {
            String name = group.path(field).asText("").toLowerCase();
            if (name.isBlank()) continue;
            String lastSegment = name.substring(name.lastIndexOf('/') + 1);
            if (name.equals(slugLower) || lastSegment.equals(slugLower)) return true;
        }

        // ── Open-issue-groups endpoint: locations array ───────────────────────
        JsonNode locations = group.path("locations");
        if (locations.isArray()) {
            for (JsonNode loc : locations) {
                String locName = loc.path("name").asText("").toLowerCase();
                if (locName.isBlank()) continue;
                String lastSegment = locName.substring(locName.lastIndexOf('/') + 1);
                if (locName.equals(slugLower) || lastSegment.equals(slugLower)) return true;
            }
        }

        // ── Nested code_repo object (some API versions) ───────────────────────
        JsonNode codeRepo = group.path("code_repo");
        if (!codeRepo.isMissingNode()) {
            String name = codeRepo.path("name").asText(codeRepo.path("repo_name").asText("")).toLowerCase();
            if (!name.isBlank()) {
                String lastSegment = name.substring(name.lastIndexOf('/') + 1);
                if (name.equals(slugLower) || lastSegment.equals(slugLower)) return true;
            }
        }

        // ── URL-based fallback ────────────────────────────────────────────────
        for (String field : new String[]{"repo_url", "repository_url", "clone_url"}) {
            String url = group.path(field).asText("");
            if (url.isBlank() && !codeRepo.isMissingNode()) {
                url = codeRepo.path("url").asText(codeRepo.path("clone_url").asText(""));
            }
            if (!url.isBlank()) {
                String stripped = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
                String lastSegment = stripped.substring(stripped.lastIndexOf('/') + 1).toLowerCase();
                if (lastSegment.equals(slugLower)) return true;
            }
        }
        return false;
    }

    /**
     * Get detailed info for a specific issue group.
     */
    public AikidoIssueInfo getIssueGroupDetail(int issueGroupId) {
        // Serve from DB cache when available — avoids burning rate-limit quota
        var cached = groupDetailStore.find(issueGroupId);
        if (cached.isPresent()) {
            LOG.debugf("Aikido detail cache hit for group %d", issueGroupId);
            return cached.get();
        }

        String json = get("/api/public/v1/issues/groups/" + issueGroupId, "issue group detail");
        if (json == null) return null;

        try {
            JsonNode root = objectMapper.readTree(json);

            String issueType = extractIssueType(root);
            String severity = extractSeverity(root);

            String title       = extractText(root, "title", "");
            String description = extractText(root, "description", "");
            String groupStatus = extractText(root, "group_status", "");
            Integer severityScore = root.path("severity_score").isNumber()
                    ? root.path("severity_score").asInt() : null;
            Integer timeToFixMinutes = root.path("time_to_fix_minutes").isNumber()
                    ? root.path("time_to_fix_minutes").asInt() : null;
            String howToFix = extractText(root, "how_to_fix", "");

            // related_cve_ids is an array of strings
            java.util.List<String> relatedCveIds = new java.util.ArrayList<>();
            JsonNode cveArray = root.path("related_cve_ids");
            if (cveArray.isArray()) {
                cveArray.forEach(n -> { if (!n.asText("").isBlank()) relatedCveIds.add(n.asText()); });
            }

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
            String repoName    = extractRepoName(root);
            String repoUrl     = extractRepoUrl(root);
            String containerImage = extractContainerImage(root);

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

            AikidoIssueInfo result = new AikidoIssueInfo(
                    issueGroupId, issueType, title, description, severity, severityScore,
                    packageName, currentVersion, fixedVersion,
                    cveId, cveDescription, cvssScore, repoName, repoUrl, containerImage, changelogSummary,
                    howToFix, relatedCveIds, groupStatus, timeToFixMinutes,
                    null, null  // firstDetectedAt / slaRemediateBy come from the export, not the detail endpoint
            );
            // Persist to DB cache so subsequent calls don't need an API round-trip
            groupDetailStore.upsert(result);
            return result;
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
        if (ciApiSecret() == null || ciApiSecret().isBlank()) {
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
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(baseUrl() + "/api/integrations/continuous_integration/scan/repository"))
                    .header("X-AIK-API-SECRET", ciApiSecret())
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
        if (ciApiSecret() == null || ciApiSecret().isBlank()) return null;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(baseUrl() + "/api/integrations/continuous_integration/scan/repository/" + scanId))
                    .header("X-AIK-API-SECRET", ciApiSecret())
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
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(baseUrl() + path))
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

    /**
     * Returns {@code true} if the issue row's {@code status} field indicates it is no longer
     * active — i.e. it has been closed, ignored, or snoozed in Aikido.
     * These issues should not appear in the security dashboard.
     */
    private static boolean isInactiveStatus(JsonNode issue) {
        String status = issue.path("status").asText("").toLowerCase();
        return "closed".equals(status) || "ignored".equals(status) || "snoozed".equals(status);
    }

    /** Returns a comma-separated list of top-level field names present in the node. */
    private static String fieldNames(JsonNode node) {
        if (!node.isObject()) return "(not an object)";
        var names = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names);
    }

    /**
     * Extracts the severity from an Aikido issue group node.
     * Aikido's public API uses {@code "severity"} as the field name.
     * Falls back to {@code "risk_level"} and {@code "criticality"} for webhook shapes.
     */
    private static String extractSeverity(JsonNode node) {
        for (String field : new String[]{"severity", "risk_level", "criticality"}) {
            JsonNode n = node.path(field);
            if (!n.isMissingNode() && !n.isNull() && !n.asText("").isBlank()) {
                return n.asText("").toLowerCase();
            }
        }
        return "medium";
    }

    private static String extractIssueType(JsonNode node) {
        for (String field : new String[]{
                "type", "issue_type", "category", "scanner", "rule_type",
                "detection_type", "scan_type", "finding_type"}) {
            JsonNode n = node.path(field);
            if (!n.isMissingNode() && !n.isNull() && !n.asText("").isBlank()) {
                return n.asText("").toLowerCase();
            }
        }
        return "unknown";
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
        // Fall back to locations array — pick the first code_repository entry's name
        JsonNode locations = root.path("locations");
        if (locations.isArray()) {
            for (JsonNode loc : locations) {
                String type = loc.path("type").asText("");
                if ("code_repository".equals(type) || type.isBlank()) {
                    String name = loc.path("name").asText("");
                    if (!name.isBlank()) {
                        // Strip org prefix if present (e.g. "julesenergy/fit" → "fit")
                        return name.contains("/")
                                ? name.substring(name.lastIndexOf('/') + 1)
                                : name;
                    }
                }
            }
        }
        return "";
    }

    /**
     * Look up the code repository URL for a container image using the static mapping
     * in container-repo-mapping.json (loaded once, cached). Also tries matching
     * by base name (e.g., "julestender" matches "julesenergy/julestender").
     *
     * @return the code repo's clone URL, or null if no mapping exists
     */
    public String findCodeRepoUrlForContainer(String containerImage) {
        if (containerImage == null || containerImage.isBlank()) return null;

        LOG.infof("Looking up code repo for container '%s'", containerImage);

        if (containerMappingCache == null) {
            containerMappingCache = loadContainerMappings();
        }

        String url = containerMappingCache.get(containerImage);
        if (url != null) {
            LOG.infof("Container mapping hit: '%s' → %s", containerImage, url);
            return url;
        }

        String baseName = containerImage.contains("/")
                ? containerImage.substring(containerImage.lastIndexOf('/') + 1)
                : containerImage;
        for (var entry : containerMappingCache.entrySet()) {
            String key = entry.getKey();
            String keyBase = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
            if (keyBase.equalsIgnoreCase(baseName)) {
                LOG.infof("Container mapping hit (base name '%s'): '%s' → %s",
                        baseName, key, entry.getValue());
                return entry.getValue();
            }
        }

        LOG.warnf("No container-to-repo mapping found for '%s'. "
                + "Add it to container-repo-mapping.json.", containerImage);
        return null;
    }

    private java.util.Map<String, String> containerMappingCache;

    private java.util.Map<String, String> loadContainerMappings() {
        var map = new java.util.HashMap<String, String>();
        try (var is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("container-repo-mapping.json")) {
            if (is == null) {
                LOG.warn("container-repo-mapping.json not found on classpath");
                return map;
            }
            JsonNode root = objectMapper.readTree(is);
            JsonNode mappings = root.path("mappings");
            var it = mappings.fields();
            while (it.hasNext()) {
                var entry = it.next();
                JsonNode repoUrlNode = entry.getValue().path("repoUrl");
                if (!repoUrlNode.isMissingNode() && !repoUrlNode.isNull()) {
                    String repoUrl = repoUrlNode.asText("");
                    if (!repoUrl.isBlank()) {
                        map.put(entry.getKey(), repoUrl);
                    }
                }
            }
            LOG.infof("Loaded %d container-to-repo mappings", map.size());
        } catch (Exception e) {
            LOG.warnf("Failed to load container-repo-mapping.json: %s", e.getMessage());
        }
        return map;
    }

    private static String extractContainerImage(JsonNode root) {
        for (String field : new String[]{
                "container_image", "image_name", "docker_image",
                "affected_container", "container_name"}) {
            JsonNode n = root.path(field);
            if (!n.isMissingNode() && !n.isNull() && !n.asText("").isBlank()) {
                return n.asText("");
            }
        }
        JsonNode container = root.path("container");
        if (!container.isMissingNode() && !container.isNull()) {
            if (container.isTextual()) return container.asText("");
            String img = container.path("image").asText(container.path("name").asText(""));
            if (!img.isBlank()) return img;
        }
        return null;
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
