package com.eneve.agent.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.eneve.agent.agent.RepoSettings;
import com.eneve.agent.agent.RepoSettingsStore;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.model.RunResult;
import com.eneve.agent.notifications.TeamsNotifier;
import com.eneve.agent.planner.ExecutionPlan;
import com.eneve.agent.planner.PlanCompletedEvent;
import com.eneve.agent.planner.PlanOrchestratorService;
import com.eneve.agent.planner.PlanStatus;
import com.eneve.agent.planner.PlanStore;
import com.eneve.agent.planner.PlannerService;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.util.UrlUtils;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

/**
 * Orchestrates automated Quarkus version upgrade checks across all registered repositories.
 *
 * <p>Flow:
 * <ol>
 *   <li>Fetch the latest Quarkus version from Maven Central.</li>
 *   <li>Pre-fetch the official Quarkus migration guide once for the run.</li>
 *   <li>Find all repos in {@code repo_settings} whose detected archetype is {@code "quarkus"}
 *       and whose stored version is older than the latest.</li>
 *   <li>For each outdated repo: generate an AI execution plan (via {@link PlannerService}),
 *       auto-approve and execute it, then send a Teams notification.</li>
 * </ol>
 */
@ApplicationScoped
public class UpgradeService {

    private static final Logger LOG = Logger.getLogger(UpgradeService.class);

    @Inject RepoSettingsStore repoSettingsStore;
    @Inject MavenCentralClient mavenCentralClient;
    @Inject QuarkusMigrationFetcher migrationFetcher;
    @Inject PlannerService plannerService;
    @Inject PlanStore planStore;
    @Inject PlanOrchestratorService orchestratorService;
    @Inject TeamsNotifier teamsNotifier;
    @Inject GitPlatformService platformService;
    @Inject AikidoService aikidoService;

    @ConfigProperty(name = "upgrade.scheduler.default-branch", defaultValue = "develop")
    String defaultBranch;

    /** Tracks plans started by this service: planId → upgrade context needed to update repo settings on completion. */
    private final ConcurrentHashMap<String, UpgradeContext> activePlans = new ConcurrentHashMap<>();

    private record UpgradeContext(String workspace, String repoSlug, String targetVersion) {}

    public record UpgradeResult(int checked, int outdated, int plansCreated, List<String> planIds) {}

    // ─── Public API ──────────────────────────────────────────────────────────────

    /**
     * Checks all Quarkus repos and starts upgrade plans for any that are outdated.
     */
    public UpgradeResult checkAndUpgradeAll() {
        Optional<String> latestOpt = mavenCentralClient.getLatestQuarkusVersion();
        if (latestOpt.isEmpty()) {
            LOG.warn("UpgradeService: could not determine latest Quarkus version — skipping run");
            return new UpgradeResult(0, 0, 0, List.of());
        }
        String latestVersion = latestOpt.get();

        Optional<String> migrationNotes = migrationFetcher.fetchMigrationNotes(latestVersion);

        List<RepoSettings> repos = repoSettingsStore.listByArchetype("quarkus");
        LOG.infof("UpgradeService: %d Quarkus repo(s) registered, latest version is %s",
                repos.size(), latestVersion);

        int plansCreated = 0;
        List<String> planIds = new ArrayList<>();

        // Collect repos that actually need upgrading
        List<RepoSettings> outdatedRepos = new ArrayList<>();
        for (RepoSettings repo : repos) {
            if (!repo.upgradeEnabled()) {
                LOG.debugf("UpgradeService: %s/%s has auto-upgrade disabled — skipping",
                        repo.workspace(), repo.repoSlug());
                continue;
            }
            if (!isOlderVersion(repo.archetypeVersion(), latestVersion)) {
                LOG.debugf("UpgradeService: %s/%s is already at %s — skipping",
                        repo.workspace(), repo.repoSlug(), repo.archetypeVersion());
                continue;
            }
            LOG.infof("UpgradeService: %s/%s is on Quarkus %s, upgrading to %s",
                    repo.workspace(), repo.repoSlug(), repo.archetypeVersion(), latestVersion);
            outdatedRepos.add(repo);
        }

        int outdated = outdatedRepos.size();

        // Generate upgrade plans in parallel (each involves an Aikido HTTP call + a Claude API call)
        if (!outdatedRepos.isEmpty()) {
            String migrationNotesStr = migrationNotes.orElse(null);
            int poolSize = Math.min(outdatedRepos.size(), 3);
            ExecutorService upgradeExec = Executors.newFixedThreadPool(poolSize);
            try {
                List<CompletableFuture<String>> futures = outdatedRepos.stream()
                        .map(repo -> CompletableFuture.supplyAsync(
                                () -> startUpgrade(repo, latestVersion, migrationNotesStr),
                                upgradeExec))
                        .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                for (CompletableFuture<String> f : futures) {
                    String planId = f.join();
                    if (planId != null) {
                        plansCreated++;
                        planIds.add(planId);
                    }
                }
            } finally {
                upgradeExec.shutdown();
            }
        }

        LOG.infof("UpgradeService: run complete — %d checked, %d outdated, %d plans created",
                repos.size(), outdated, plansCreated);
        return new UpgradeResult(repos.size(), outdated, plansCreated, planIds);
    }

    /**
     * Checks and upgrades a single repository if it is outdated.
     */
    public UpgradeResult checkAndUpgradeOne(String workspace, String repoSlug) {
        Optional<String> latestOpt = mavenCentralClient.getLatestQuarkusVersion();
        if (latestOpt.isEmpty()) {
            LOG.warn("UpgradeService: could not determine latest Quarkus version");
            return new UpgradeResult(1, 0, 0, List.of());
        }
        String latestVersion = latestOpt.get();

        Optional<RepoSettings> repoOpt = repoSettingsStore.find(workspace, repoSlug);
        if (repoOpt.isEmpty()) {
            LOG.warnf("UpgradeService: repo %s/%s not found in settings", workspace, repoSlug);
            return new UpgradeResult(0, 0, 0, List.of());
        }

        RepoSettings repo = repoOpt.get();
        if (!repo.upgradeEnabled()) {
            LOG.infof("UpgradeService: %s/%s has auto-upgrade disabled — skipping", workspace, repoSlug);
            return new UpgradeResult(1, 0, 0, List.of());
        }
        if (repo.archetypeVersion() == null) {
            LOG.infof("UpgradeService: %s/%s has no detected archetype version — skipping", workspace, repoSlug);
            return new UpgradeResult(1, 0, 0, List.of());
        }
        if (!isOlderVersion(repo.archetypeVersion(), latestVersion)) {
            LOG.infof("UpgradeService: %s/%s is already at %s — nothing to do", workspace, repoSlug, repo.archetypeVersion());
            return new UpgradeResult(1, 0, 0, List.of());
        }

        Optional<String> migrationNotes = migrationFetcher.fetchMigrationNotes(latestVersion);
        String planId = startUpgrade(repo, latestVersion, migrationNotes.orElse(null));
        if (planId != null) {
            return new UpgradeResult(1, 1, 1, List.of(planId));
        }
        return new UpgradeResult(1, 1, 0, List.of());
    }

    // ─── Internal helpers ────────────────────────────────────────────────────────

    private String startUpgrade(RepoSettings repo, String latestVersion, String migrationNotes) {
        String currentVersion = repo.archetypeVersion();
        String cloneUrl = platformService.buildCloneUrl(repo.workspace(), repo.repoSlug());
        if (cloneUrl == null) {
            LOG.warnf("UpgradeService: cannot build clone URL for %s/%s — skipping",
                    repo.workspace(), repo.repoSlug());
            return null;
        }

        // Clean URL used in notifications and the AI prompt — never contains credentials.
        String cleanUrl = UrlUtils.stripCredentials(cloneUrl);

        String branchName = "agent/upgrade-quarkus-" + latestVersion;

        List<AikidoIssueInfo> aikidoFindings = List.of();
        if (aikidoService.isEnabled()) {
            aikidoFindings = aikidoService.findActionableIssuesForRepo(repo.repoSlug());
            if (!aikidoFindings.isEmpty()) {
                LOG.infof("UpgradeService: %d actionable Aikido finding(s) (SAST/dependency) found for %s/%s — appending to upgrade spec",
                        aikidoFindings.size(), repo.workspace(), repo.repoSlug());
            }
        }

        String specText = buildSpecText(currentVersion, latestVersion, branchName, migrationNotes, aikidoFindings);

        // Pass the authenticated clone URL so the orchestrator can clone/push, but the
        // prompt and any stored plan metadata use the clean URL.
        ExecutionPlan plan = plannerService.generatePlan(
                specText, cloneUrl, defaultBranch, "UPGRADE", "quarkus-" + latestVersion);

        if (plan == null) {
            LOG.errorf("UpgradeService: plan generation failed for %s/%s", repo.workspace(), repo.repoSlug());
            teamsNotifier.sendNotification(new RunResult(
                    "upgrade-" + repo.repoSlug(), "UPGRADE", "FAILED",
                    null, cleanUrl, branchName, null,
                    null, "Plan generation failed for Quarkus upgrade to " + latestVersion,
                    0, 0));
            return null;
        }

        planStore.create(plan);
        planStore.approve(plan.planId());

        activePlans.put(plan.planId(), new UpgradeContext(repo.workspace(), repo.repoSlug(), latestVersion));

        try {
            orchestratorService.startExecution(plan.planId());
            LOG.infof("UpgradeService: plan %s started for %s/%s (Quarkus %s → %s)",
                    plan.planId(), repo.workspace(), repo.repoSlug(), currentVersion, latestVersion);

            teamsNotifier.sendNotification(new RunResult(
                    plan.planId(), "UPGRADE", "STARTED",
                    null, cleanUrl, branchName, null,
                    "Quarkus upgrade from " + currentVersion + " to " + latestVersion
                            + " started. Plan: " + plan.planId(),
                    null, 0, 0));

            return plan.planId();

        } catch (Exception e) {
            LOG.errorf("UpgradeService: failed to start execution for plan %s (%s/%s): %s",
                    plan.planId(), repo.workspace(), repo.repoSlug(), e.getMessage());
            activePlans.remove(plan.planId());

            teamsNotifier.sendNotification(new RunResult(
                    plan.planId(), "UPGRADE", "FAILED",
                    null, cleanUrl, branchName, null,
                    null, "Failed to start Quarkus upgrade plan: " + e.getMessage(),
                    0, 0));
            return null;
        }
    }

    /**
     * Listens for plan completion events. When an upgrade plan completes successfully,
     * updates the repo's stored {@code archetype_version} so that the next upgrade check
     * sees the correct baseline and does not re-trigger an already-applied upgrade.
     */
    public void onPlanCompleted(@ObservesAsync PlanCompletedEvent event) {
        UpgradeContext ctx = activePlans.remove(event.planId());
        if (ctx == null) {
            return; // not an upgrade plan managed by this service
        }
        if (!PlanStatus.COMPLETED.name().equals(event.status())) {
            LOG.infof("UpgradeService: upgrade plan %s ended with %s for %s/%s — archetype_version not updated",
                    event.planId(), event.status(), ctx.workspace(), ctx.repoSlug());
            return;
        }
        LOG.infof("UpgradeService: upgrade plan %s completed — updating %s/%s archetype_version to %s",
                event.planId(), ctx.workspace(), ctx.repoSlug(), ctx.targetVersion());
        repoSettingsStore.updateArchetype(ctx.workspace(), ctx.repoSlug(), "quarkus", ctx.targetVersion());
    }

    private String buildSpecText(String currentVersion, String latestVersion,
                                  String branchName, String migrationNotes,
                                  List<AikidoIssueInfo> aikidoFindings) {
        String migrationSection = migrationNotes != null && !migrationNotes.isBlank()
                ? migrationNotes
                : "No migration guide was available. Check https://quarkus.io/guides/migration-guide-"
                        + QuarkusMigrationFetcher.extractMajorMinor(latestVersion) + " manually.";

        StringBuilder sb = new StringBuilder("""
                Upgrade Quarkus from %s to %s in this repository.

                Steps:
                1. Update the Quarkus BOM/platform version in pom.xml (check parent pom, \
                properties, dependencyManagement).
                2. Run `./mvnw quarkus:update` (or `mvn quarkus:update` if no wrapper) if the Quarkus Maven plugin is present.
                3. Apply any breaking changes listed in the Migration Guide section below.
                4. Ensure the project compiles: run `./mvnw compile` if ./mvnw exists in the project root, otherwise `mvn compile`
                5. Run tests: run `./mvnw test` if ./mvnw exists in the project root, otherwise `mvn test`

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s

                ## Migration Guide
                %s
                """.formatted(
                currentVersion, latestVersion,
                currentVersion, latestVersion,
                branchName, defaultBranch,
                migrationSection));

        if (aikidoFindings != null && !aikidoFindings.isEmpty()) {
            sb.append("\n## Aikido Security Findings\n");
            sb.append("The following open vulnerabilities were found for this repository. ");
            sb.append("Include steps to resolve each of them as part of this upgrade plan.\n\n");
            for (AikidoIssueInfo finding : aikidoFindings) {
                sb.append(finding.toPromptSection());
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Returns true if {@code current} is strictly older than {@code latest}.
     * Compares numeric segments of semantic version strings (e.g. "3.8.1" < "3.17.0").
     * Returns false if either version is null/blank or cannot be parsed.
     */
    static boolean isOlderVersion(String current, String latest) {
        if (current == null || current.isBlank() || latest == null || latest.isBlank()) {
            return false;
        }
        String[] currentParts = current.trim().split("\\.");
        String[] latestParts = latest.trim().split("\\.");
        int len = Math.max(currentParts.length, latestParts.length);
        for (int i = 0; i < len; i++) {
            int c = i < currentParts.length ? parseSegment(currentParts[i]) : 0;
            int l = i < latestParts.length ? parseSegment(latestParts[i]) : 0;
            if (c < l) return true;
            if (c > l) return false;
        }
        return false; // equal
    }

    private static int parseSegment(String segment) {
        try {
            // Strip non-numeric suffixes like "-Final", "-CR1"
            String numeric = segment.replaceAll("[^0-9].*", "");
            return numeric.isEmpty() ? 0 : Integer.parseInt(numeric);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
