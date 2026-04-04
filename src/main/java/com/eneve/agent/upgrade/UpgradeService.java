package com.eneve.agent.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.model.RunResult;
import com.eneve.agent.notifications.TeamsNotifier;
import com.eneve.agent.planner.ExecutionPlan;
import com.eneve.agent.planner.PlanCompletedEvent;
import com.eneve.agent.planner.PlanData;
import com.eneve.agent.planner.PlanOrchestratorService;
import com.eneve.agent.planner.PlanPhase;
import com.eneve.agent.planner.PlanStatus;
import com.eneve.agent.planner.PlanStep;
import com.eneve.agent.planner.PlanStore;
import com.eneve.agent.planner.PlannerService;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.util.UrlUtils;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

/**
 * Orchestrates automated framework version upgrade checks across all registered repositories.
 *
 * <p>Supported archetypes: {@code quarkus}, {@code dotnet}, {@code wildfly}, {@code angular},
 * {@code react}, {@code typescript}, {@code laravel}, {@code symfony}, {@code php}.
 *
 * <p>Flow for each supported archetype:
 * <ol>
 *   <li>Fetch the latest version from the appropriate release API.</li>
 *   <li>Find all repos in {@code repo_settings} whose detected archetype matches.</li>
 *   <li>For each outdated repo: generate an AI execution plan (via {@link PlannerService}),
 *       auto-approve and execute it, then send a Teams notification.</li>
 *   <li>On plan completion, update the repo's stored {@code archetype_version}.</li>
 * </ol>
 */
@ApplicationScoped
public class UpgradeService {

    private static final Logger LOG = Logger.getLogger(UpgradeService.class);

    /** Archetypes for which automated upgrade plans are created. */
    static final List<String> SUPPORTED_ARCHETYPES = List.of(
            "quarkus", "dotnet", "wildfly", "angular", "react", "typescript", "laravel", "symfony", "php");

    @Inject RepoSettingsStore repoSettingsStore;
    @Inject MavenCentralClient mavenCentralClient;
    @Inject DotnetReleaseClient dotnetReleaseClient;
    @Inject WildflyReleaseClient wildflyReleaseClient;
    @Inject NpmRegistryClient npmRegistryClient;
    @Inject PackagistClient packagistClient;
    @Inject PhpReleaseClient phpReleaseClient;
    @Inject PostgresJdbcClient postgresJdbcClient;
    @Inject QuarkusMigrationFetcher migrationFetcher;
    @Inject PlannerService plannerService;
    @Inject PlanStore planStore;
    @Inject PlanOrchestratorService orchestratorService;
    @Inject TeamsNotifier teamsNotifier;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject AikidoService aikidoService;
    @Inject SettingsService settings;

    private String defaultBranch() { return settings.get("upgrade.scheduler.default-branch", "develop"); }

    /** Tracks plans started by this service: planId → upgrade context needed on completion. */
    private final ConcurrentHashMap<String, UpgradeContext> activePlans = new ConcurrentHashMap<>();

    /** Runs plan execution in the background after plan creation returns to the caller. */
    private final ExecutorService executionExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "upgrade-exec");
        t.setDaemon(true);
        return t;
    });

    private record UpgradeContext(String workspace, String repoSlug, String archetype, String targetVersion) {}

    public record UpgradeResult(int checked, int outdated, int plansCreated, List<String> planIds) {}

    // ─── Public API ──────────────────────────────────────────────────────────────

    /**
     * Checks all repos for all supported archetypes and starts upgrade plans for any that are
     * outdated relative to the latest published version.
     */
    public UpgradeResult checkAndUpgradeAll() {
        int totalChecked = 0;
        int totalOutdated = 0;
        int totalPlans = 0;
        List<String> allPlanIds = new ArrayList<>();

        for (String archetype : SUPPORTED_ARCHETYPES) {
            Optional<String> latestOpt = getLatestVersionForArchetype(archetype);
            if (latestOpt.isEmpty()) {
                LOG.warnf("UpgradeService: could not determine latest %s version — skipping archetype",
                        archetype);
                continue;
            }
            String latestVersion = latestOpt.get();

            String migrationNotes = "quarkus".equals(archetype)
                    ? migrationFetcher.fetchMigrationNotes(latestVersion).orElse(null)
                    : null;

            List<RepoSettings> repos = repoSettingsStore.listByArchetype(archetype);
            LOG.infof("UpgradeService: %d %s repo(s) registered, latest version is %s",
                    repos.size(), archetype, latestVersion);

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
                LOG.infof("UpgradeService: %s/%s is on %s %s, upgrading to %s",
                        repo.workspace(), repo.repoSlug(), archetype,
                        repo.archetypeVersion(), latestVersion);
                outdatedRepos.add(repo);
            }

            totalChecked += repos.size();
            totalOutdated += outdatedRepos.size();

            if (!outdatedRepos.isEmpty()) {
                String migrationStr = migrationNotes;
                int poolSize = Math.min(outdatedRepos.size(), 3);
                ExecutorService upgradeExec = Executors.newFixedThreadPool(poolSize);
                try {
                    List<CompletableFuture<String>> futures = outdatedRepos.stream()
                            .map(repo -> CompletableFuture.supplyAsync(
                                    () -> startUpgrade(repo, archetype, latestVersion, migrationStr),
                                    upgradeExec))
                            .toList();
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    for (CompletableFuture<String> f : futures) {
                        String planId = f.join();
                        if (planId != null) {
                            totalPlans++;
                            allPlanIds.add(planId);
                        }
                    }
                } finally {
                    upgradeExec.shutdown();
                }
            }
        }

        LOG.infof("UpgradeService: run complete — %d checked, %d outdated, %d plans created",
                totalChecked, totalOutdated, totalPlans);
        return new UpgradeResult(totalChecked, totalOutdated, totalPlans, allPlanIds);
    }

    /**
     * Checks and upgrades a single repository based on its detected archetype.
     */
    public UpgradeResult checkAndUpgradeOne(String workspace, String repoSlug) {
        Optional<RepoSettings> repoOpt = repoSettingsStore.find(workspace, repoSlug);
        if (repoOpt.isEmpty()) {
            LOG.warnf("UpgradeService: repo %s/%s not found in settings", workspace, repoSlug);
            return new UpgradeResult(0, 0, 0, List.of());
        }

        RepoSettings repo = repoOpt.get();
        String archetype = repo.archetype();

        if (archetype == null || !SUPPORTED_ARCHETYPES.contains(archetype)) {
            LOG.infof("UpgradeService: %s/%s has unsupported archetype '%s' — skipping",
                    workspace, repoSlug, archetype);
            return new UpgradeResult(1, 0, 0, List.of());
        }

        if (!repo.upgradeEnabled()) {
            LOG.infof("UpgradeService: %s/%s has auto-upgrade disabled — skipping", workspace, repoSlug);
            return new UpgradeResult(1, 0, 0, List.of());
        }
        if (repo.archetypeVersion() == null) {
            LOG.infof("UpgradeService: %s/%s has no detected archetype version — skipping",
                    workspace, repoSlug);
            return new UpgradeResult(1, 0, 0, List.of());
        }

        Optional<String> latestOpt = getLatestVersionForArchetype(archetype);
        if (latestOpt.isEmpty()) {
            LOG.warnf("UpgradeService: could not determine latest %s version", archetype);
            return new UpgradeResult(1, 0, 0, List.of());
        }
        String latestVersion = latestOpt.get();

        if (!isOlderVersion(repo.archetypeVersion(), latestVersion)) {
            LOG.infof("UpgradeService: %s/%s is already at %s — nothing to do",
                    workspace, repoSlug, repo.archetypeVersion());
            return new UpgradeResult(1, 0, 0, List.of());
        }

        String migrationNotes = "quarkus".equals(archetype)
                ? migrationFetcher.fetchMigrationNotes(latestVersion).orElse(null)
                : null;

        String planId = startUpgrade(repo, archetype, latestVersion, migrationNotes);
        if (planId != null) {
            return new UpgradeResult(1, 1, 1, List.of(planId));
        }
        return new UpgradeResult(1, 1, 0, List.of());
    }

    // ─── Internal helpers ────────────────────────────────────────────────────────

    private Optional<String> getLatestVersionForArchetype(String archetype) {
        return switch (archetype) {
            case "quarkus"  -> mavenCentralClient.getLatestQuarkusVersion();
            case "dotnet"   -> dotnetReleaseClient.getLatestDotnetVersion();
            case "wildfly"  -> wildflyReleaseClient.getLatestWildflyVersion();
            case "angular"  -> npmRegistryClient.getLatestVersion("@angular/core");
            case "react"    -> npmRegistryClient.getLatestVersion("react");
            case "typescript" -> npmRegistryClient.getLatestVersion("typescript");
            case "laravel"  -> packagistClient.getLatestVersion("laravel", "framework");
            case "symfony"  -> packagistClient.getLatestVersion("symfony", "framework-bundle");
            case "php"      -> phpReleaseClient.getLatestPhpVersion();
            default         -> Optional.empty();
        };
    }

    private String startUpgrade(RepoSettings repo, String archetype,
                                 String latestVersion, String migrationNotes) {
        String currentVersion = repo.archetypeVersion();
        String cloneUrl = platformRegistry.defaultPlatform().buildCloneUrl(repo.workspace(), repo.repoSlug());
        if (cloneUrl == null) {
            LOG.warnf("UpgradeService: cannot build clone URL for %s/%s — skipping",
                    repo.workspace(), repo.repoSlug());
            return null;
        }

        String cleanUrl = UrlUtils.stripCredentials(cloneUrl);
        String branchName = "agent/upgrade-" + archetype + "-" + latestVersion;

        List<AikidoIssueInfo> aikidoFindings = List.of();
        if (aikidoService.isEnabled()) {
            aikidoFindings = aikidoService.findActionableIssuesForRepo(repo.repoSlug());
            if (!aikidoFindings.isEmpty()) {
                LOG.infof("UpgradeService: %d actionable Aikido finding(s) found for %s/%s — appending to upgrade spec",
                        aikidoFindings.size(), repo.workspace(), repo.repoSlug());
            }
        }

        String latestJdbcVersion  = null;
        String currentJdbcVersion = null;
        String jdbcSource         = null;
        if ("wildfly".equals(archetype)) {
            latestJdbcVersion  = postgresJdbcClient.getLatestPostgresJdbcVersion().orElse(null);
            currentJdbcVersion = repo.dependencyVersions().get("postgresql-jdbc");
            jdbcSource         = repo.dependencyVersions().get("postgresql-jdbc-source");
        }

        String specText = buildSpecText(archetype, currentVersion, latestVersion, branchName,
                migrationNotes, aikidoFindings, latestJdbcVersion, currentJdbcVersion, jdbcSource);

        ExecutionPlan plan = plannerService.generatePlan(
                specText, cleanUrl, defaultBranch(), "UPGRADE", archetype + "-" + latestVersion);

        if (plan == null) {
            LOG.errorf("UpgradeService: plan generation failed for %s/%s", repo.workspace(), repo.repoSlug());
            teamsNotifier.sendNotification(new RunResult(
                    "upgrade-" + repo.repoSlug(), "UPGRADE", "FAILED",
                    null, cleanUrl, branchName, null,
                    null, "Plan generation failed for " + archetype + " upgrade to " + latestVersion,
                    0, 0));
            return null;
        }

        // Convert the AI-generated markdown checklist into structured PlanData.
        // The planner prompt produces "- [ ] task" items; parse each into a FIX step
        // so the orchestrator has concrete jobs to submit.
        PlanData planData = markdownToPlanData(plan.markdownContent(), specText, branchName);
        plan = new ExecutionPlan(
                plan.planId(), plan.status(), plan.sourceType(), plan.sourceRef(),
                plan.repoUrl(), null, plan.targetBranch(), plan.title(),
                planData,
                plan.createdAt(), plan.updatedAt(),
                null, null, null, null, null,
                plan.markdownContent(), null, false, null);

        LOG.infof("UpgradeService: plan %s has %d step(s) for %s/%s",
                plan.planId(), planData.phases().stream().mapToLong(p -> p.steps().size()).sum(),
                repo.workspace(), repo.repoSlug());

        planStore.create(plan);
        planStore.approve(plan.planId());

        activePlans.put(plan.planId(),
                new UpgradeContext(repo.workspace(), repo.repoSlug(), archetype, latestVersion));

        final String planId = plan.planId();
        executionExecutor.submit(() -> {
            try {
                orchestratorService.startExecution(planId);
                LOG.infof("UpgradeService: plan %s started for %s/%s (%s %s → %s)",
                        planId, repo.workspace(), repo.repoSlug(),
                        archetype, currentVersion, latestVersion);

                teamsNotifier.sendNotification(new RunResult(
                        planId, "UPGRADE", "STARTED",
                        null, cleanUrl, branchName, null,
                        archetype + " upgrade from " + currentVersion + " to " + latestVersion
                                + " started. Plan: " + planId,
                        null, 0, 0));

            } catch (Exception e) {
                LOG.errorf("UpgradeService: failed to start execution for plan %s (%s/%s): %s",
                        planId, repo.workspace(), repo.repoSlug(), e.getMessage());
                activePlans.remove(planId);

                teamsNotifier.sendNotification(new RunResult(
                        planId, "UPGRADE", "FAILED",
                        null, cleanUrl, branchName, null,
                        null, "Failed to start " + archetype + " upgrade plan: " + e.getMessage(),
                        0, 0));
            }
        });

        return planId;
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
        repoSettingsStore.updateArchetype(
                ctx.workspace(), ctx.repoSlug(), ctx.archetype(), ctx.targetVersion(), Map.of());
    }

    // ─── Spec text builders ──────────────────────────────────────────────────────

    private String buildSpecText(String archetype, String currentVersion, String latestVersion,
                                  String branchName, String migrationNotes,
                                  List<AikidoIssueInfo> aikidoFindings, String latestJdbcVersion,
                                  String currentJdbcVersion, String jdbcSource) {
        String base = switch (archetype) {
            case "quarkus" -> buildQuarkusSpec(currentVersion, latestVersion, branchName, migrationNotes);
            case "dotnet"  -> buildDotnetSpec(currentVersion, latestVersion, branchName);
            case "wildfly" -> buildWildflySpec(currentVersion, latestVersion, branchName,
                                               latestJdbcVersion, currentJdbcVersion, jdbcSource);
            case "angular" -> buildAngularSpec(currentVersion, latestVersion, branchName);
            case "react"   -> buildReactSpec(currentVersion, latestVersion, branchName);
            case "typescript" -> buildTypeScriptCompilerSpec(currentVersion, latestVersion, branchName);
            case "laravel" -> buildLaravelSpec(currentVersion, latestVersion, branchName);
            case "symfony" -> buildSymfonySpec(currentVersion, latestVersion, branchName);
            case "php"     -> buildPhpSpec(currentVersion, latestVersion, branchName);
            default        -> buildGenericSpec(archetype, currentVersion, latestVersion, branchName);
        };

        if (aikidoFindings != null && !aikidoFindings.isEmpty()) {
            StringBuilder sb = new StringBuilder(base);
            sb.append("\n## Aikido Security Findings\n");
            sb.append("The following open vulnerabilities were found for this repository. ");
            sb.append("Include steps to resolve each of them as part of this upgrade plan.\n\n");
            for (AikidoIssueInfo finding : aikidoFindings) {
                sb.append(finding.toPromptSection());
                sb.append("\n");
            }
            return sb.toString();
        }

        return base;
    }

    private String buildQuarkusSpec(String currentVersion, String latestVersion,
                                     String branchName, String migrationNotes) {
        String migrationSection = migrationNotes != null && !migrationNotes.isBlank()
                ? migrationNotes
                : "No migration guide was available. Check https://quarkus.io/guides/migration-guide-"
                        + QuarkusMigrationFetcher.extractMajorMinor(latestVersion) + " manually.";

        String javaNote = FrameworkJavaRequirements.javaVersionNote("quarkus", latestVersion, currentVersion);

        return """
                Upgrade Quarkus from %s to %s in this repository.

                Steps:
                1. Update the Quarkus BOM/platform version in pom.xml (check parent pom, \
                properties, dependencyManagement).
                2. Run `./mvnw quarkus:update` (or `mvn quarkus:update` if no wrapper) if the Quarkus Maven plugin is present.
                3. Apply any breaking changes listed in the Migration Guide section below.
                4. Java version check: %s
                5. If a Dockerfile exists, update any base image FROM lines to use a pinned \
                version tag matching the new Java requirement — never use :latest.
                6. Ensure the project compiles: run `./mvnw compile` if ./mvnw exists in the project root, otherwise `mvn compile`
                7. Run tests: run `./mvnw test` if ./mvnw exists in the project root, otherwise `mvn test`

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s

                ## Migration Guide
                %s
                """.formatted(
                currentVersion, latestVersion,
                javaNote,
                currentVersion, latestVersion,
                branchName, defaultBranch(),
                migrationSection);
    }

    private String buildDotnetSpec(String currentVersion, String latestVersion, String branchName) {
        return """
                Upgrade .NET from %s to %s in this repository.

                Steps:
                1. Update the TargetFramework in all .csproj/.fsproj/.vbproj files \
                from the current version to net%s (e.g. <TargetFramework>net%s</TargetFramework>).
                2. Update the SDK version in global.json (if present) to a version compatible with .NET %s.
                3. Update NuGet packages to versions compatible with the new .NET target framework \
                (`dotnet outdated` or `dotnet list package --outdated`).
                4. Review the .NET %s release notes for breaking changes and deprecated APIs: \
                https://learn.microsoft.com/en-us/dotnet/core/whats-new/
                5. If a Dockerfile exists, update any base image FROM lines to use a pinned \
                version tag for .NET %s (e.g. FROM mcr.microsoft.com/dotnet/aspnet:%s) — never use :latest.
                6. Ensure the project builds successfully: run `dotnet build`
                7. Run the test suite: run `dotnet test`

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s
                """.formatted(
                currentVersion, latestVersion,
                latestVersion, latestVersion, latestVersion, latestVersion,
                latestVersion, latestVersion,
                currentVersion, latestVersion,
                branchName, defaultBranch());
    }

    private String buildWildflySpec(String currentVersion, String latestVersion, String branchName,
                                     String latestJdbcVersion, String currentJdbcVersion, String jdbcSource) {
        String javaNote = FrameworkJavaRequirements.javaVersionNote("wildfly", latestVersion, currentVersion);

        String jdbcStep = buildJdbcUpgradeStep(latestJdbcVersion, currentJdbcVersion, jdbcSource);

        String compileStep = jdbcStep.isEmpty() ? "8" : "9";
        String testStep    = jdbcStep.isEmpty() ? "9" : "10";

        return """
                Upgrade WildFly from %s to %s in this repository.

                Steps:
                1. Update the WildFly BOM/platform version in pom.xml (check parent pom, \
                dependencyManagement, or properties such as wildfly.version / version.wildfly).
                2. Update the wildfly-maven-plugin version in pom.xml if present.
                3. If a Dockerfile exists that references a WildFly base image \
                (FROM quay.io/wildfly/wildfly:... or FROM jboss/wildfly:...), update the image tag to %s.
                4. Java version check: %s
                5. Review the WildFly %s release notes for breaking changes or deprecated subsystems: \
                https://www.wildfly.org/news/
                6. Update any WildFly-specific server configuration files (standalone.xml, domain.xml) if needed.
                7. Update other explicitly versioned dependencies to versions compatible with WildFly %s.
                %s\
                %s. Ensure the project compiles: run `./mvnw compile` if ./mvnw exists, otherwise `mvn compile`
                %s. Run tests: run `./mvnw test` if ./mvnw exists, otherwise `mvn test`

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s
                """.formatted(
                currentVersion, latestVersion,
                latestVersion,
                javaNote, latestVersion, latestVersion,
                jdbcStep,
                compileStep, testStep,
                currentVersion, latestVersion,
                branchName, defaultBranch());
    }

    /**
     * Builds a numbered spec step for upgrading the PostgreSQL JDBC driver, tailored to
     * where the driver was detected in the repository.
     *
     * <ul>
     *   <li><b>pom.xml</b>: instructs updating the {@code org.postgresql:postgresql} version
     *       (or its property) in {@code pom.xml}.</li>
     *   <li><b>module.xml</b>: instructs replacing the JAR and updating the
     *       {@code <resource-root>} path in the WildFly module descriptor under
     *       {@code config/}.</li>
     *   <li><b>unknown source / not detected</b>: falls back to a conditional hint covering
     *       both patterns.</li>
     * </ul>
     *
     * @return a non-null step string (may be empty if no latest JDBC version is available)
     */
    private String buildJdbcUpgradeStep(String latestJdbcVersion, String currentJdbcVersion,
                                         String jdbcSource) {
        if (latestJdbcVersion == null) {
            return "";
        }

        String from = currentJdbcVersion != null
                ? "from " + currentJdbcVersion + " "
                : "";

        if ("pom".equals(jdbcSource)) {
            return "8. Update the PostgreSQL JDBC driver (`org.postgresql:postgresql`) "
                    + from + "to " + latestJdbcVersion
                    + " in pom.xml (update the version directly or its property reference).\n";
        }

        if ("module.xml".equals(jdbcSource)) {
            return "8. Update the PostgreSQL JDBC driver " + from + "to " + latestJdbcVersion
                    + " in the WildFly server module: replace the JAR file under config/ "
                    + "(e.g. config/org.postgresql/main/postgresql-" + latestJdbcVersion + ".jar) "
                    + "and update the <resource-root path=\\\"postgresql-" + latestJdbcVersion + ".jar\\\"/> "
                    + "attribute in the corresponding module.xml.\n";
        }

        // Source unknown — driver may be configured via pom.xml or as a WildFly module
        return "8. If the project uses the PostgreSQL JDBC driver (`org.postgresql:postgresql`), "
                + "update it " + from + "to " + latestJdbcVersion
                + ". Check pom.xml for a direct dependency or config/ for a WildFly module.xml "
                + "with a <resource-root> referencing postgresql-*.jar.\n";
    }

    private String buildAngularSpec(String currentVersion, String latestVersion, String branchName) {
        String latestMajor = majorVersion(latestVersion);
        return """
                Upgrade Angular from %s to %s in this repository.

                Steps:
                1. Consult the Angular Update Guide for all required changes: https://update.angular.io/
                2. Run `npx @angular/cli@%s update @angular/core@%s @angular/cli@%s` \
                to update the core Angular packages.
                3. Run `ng update` to apply any remaining migrations.
                4. Review and address all breaking changes and deprecations reported by `ng update`.
                5. Update third-party Angular-compatible libraries (Angular Material, NgRx, etc.) \
                to versions compatible with Angular %s.
                6. Ensure the project builds: run `npm run build`
                7. Run the test suite: run `npm test`

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s
                """.formatted(
                currentVersion, latestVersion,
                latestMajor, latestMajor, latestMajor, latestMajor,
                currentVersion, latestVersion,
                branchName, defaultBranch());
    }

    private String buildReactSpec(String currentVersion, String latestVersion, String branchName) {
        return """
                Upgrade React from %s to %s in this repository.

                Steps:
                1. Update the `react` and `react-dom` packages in package.json to version %s.
                2. If using TypeScript, update `@types/react` and `@types/react-dom` \
                to compatible versions.
                3. Review the React %s changelog for breaking changes: \
                https://github.com/facebook/react/blob/main/CHANGELOG.md
                4. Update React-dependent libraries (react-router, redux, react-redux, etc.) \
                to versions compatible with React %s.
                5. Run `npm install` (or `yarn install`) to install the updated packages.
                6. Fix any breaking changes identified (e.g. removed APIs, changed prop types).
                7. Ensure the project builds: run `npm run build`
                8. Run the test suite: run `npm test`

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s
                """.formatted(
                currentVersion, latestVersion,
                latestVersion, latestVersion, latestVersion,
                currentVersion, latestVersion,
                branchName, defaultBranch());
    }

    private String buildTypeScriptCompilerSpec(String currentVersion, String latestVersion, String branchName) {
        return """
                Upgrade the TypeScript compiler (`typescript` package) from %s to %s in this repository.

                Steps:
                1. Update the `typescript` devDependency (or dependency) in package.json to %s.
                2. Read the TypeScript release notes for breaking changes: \
                https://github.com/microsoft/TypeScript/wiki/Breaking-Changes
                3. Run the install command for your package manager (`npm install`, `pnpm install`, or `yarn install`).
                4. Run `npx tsc --noEmit` (or the project's `npm run build` / `typecheck` script) and fix new type errors.
                5. Update `@types/*` packages if the compiler flags incompatible DefinitelyTyped versions.
                6. Run the full test suite.

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s
                """.formatted(
                currentVersion, latestVersion,
                latestVersion,
                currentVersion, latestVersion,
                branchName, defaultBranch());
    }

    private String buildLaravelSpec(String currentVersion, String latestVersion, String branchName) {
        String latestMajor = majorVersion(latestVersion);
        return """
                Upgrade Laravel from %s to %s in this repository.

                Steps:
                1. Update the `laravel/framework` constraint in composer.json to `^%s.0`.
                2. Run `composer update laravel/framework --with-dependencies` \
                to update the framework and its dependencies.
                3. Follow the official Laravel upgrade guide: \
                https://laravel.com/docs/%s.x/upgrade
                4. Update config stubs, service providers, and middleware as described \
                in the upgrade guide.
                5. Run `php artisan config:clear && php artisan cache:clear && php artisan route:clear` \
                to clear all caches.
                6. Run the test suite: run `php artisan test` or `./vendor/bin/phpunit`

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s
                """.formatted(
                currentVersion, latestVersion,
                latestMajor, latestMajor,
                currentVersion, latestVersion,
                branchName, defaultBranch());
    }

    private String buildSymfonySpec(String currentVersion, String latestVersion, String branchName) {
        String latestMajor = majorVersion(latestVersion);
        return """
                Upgrade Symfony from %s to %s in this repository.

                Steps:
                1. Update all `symfony/*` constraints in composer.json to `^%s.0`.
                2. Run `composer update "symfony/*" --with-dependencies` \
                to update all Symfony components.
                3. Follow the official Symfony upgrade guide: \
                https://symfony.com/doc/%s.x/setup/upgrade_major.html
                4. Run `php bin/console debug:container --deprecations` \
                to identify deprecation warnings.
                5. Address all deprecation warnings before and after the upgrade.
                6. Run `php bin/console cache:clear` to clear the cache.
                7. Run the test suite: run `./bin/phpunit` or `php bin/phpunit`

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s
                """.formatted(
                currentVersion, latestVersion,
                latestMajor, latestMajor,
                currentVersion, latestVersion,
                branchName, defaultBranch());
    }

    private String buildPhpSpec(String currentVersion, String latestVersion, String branchName) {
        String majorMinor = majorMinorVersion(latestVersion);
        String noDotsVersion = majorMinor.replace(".", "");
        return """
                Upgrade the PHP runtime requirement from %s to %s in this repository.

                Steps:
                1. Update the `php` version constraint in composer.json to `^%s`.
                2. Update the PHP version in any CI/CD pipeline configuration files \
                (.github/workflows/*.yml, .gitlab-ci.yml, bitbucket-pipelines.yml, etc.).
                3. Update the PHP version in Dockerfile or docker-compose.yml if present. \
                Use a pinned version tag (e.g. FROM php:%s-fpm) — never use :latest.
                4. Review the PHP %s migration guide for breaking changes: \
                https://www.php.net/manual/en/migration%s.php
                5. Fix any deprecated or removed functions, extensions, or behaviors \
                flagged by the new PHP version.
                6. Run `composer install` to verify all dependencies are compatible.
                7. Run the test suite: run `./vendor/bin/phpunit` \
                or `php artisan test` (for Laravel projects).

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s
                """.formatted(
                currentVersion, latestVersion,
                majorMinor, majorMinor, majorMinor, noDotsVersion,
                currentVersion, latestVersion,
                branchName, defaultBranch());
    }

    private String buildGenericSpec(String archetype, String currentVersion,
                                     String latestVersion, String branchName) {
        return """
                Upgrade %s from %s to %s in this repository.

                Steps:
                1. Identify all version references for %s in the project \
                (configuration files, build files, dependency manifests).
                2. Update all version references to the target version %s.
                3. Review the official release notes or changelog for breaking changes.
                4. Ensure the project builds successfully.
                5. Run the test suite.

                Current version: %s
                Target version: %s
                Use branch name: %s
                Target branch (PR base): %s
                """.formatted(
                archetype, currentVersion, latestVersion,
                archetype, latestVersion,
                currentVersion, latestVersion,
                branchName, defaultBranch());
    }

    // ─── Version comparison helpers ──────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code current} is old enough to warrant an upgrade.
     * Both versions are normalized before comparison (leading non-numeric prefixes like
     * {@code "net"} or {@code "v"} are stripped).
     *
     * <p>Tolerance rules (being behind by this amount or less is acceptable):
     * <ul>
     *   <li><b>Major</b> — any difference always triggers an upgrade.</li>
     *   <li><b>Minor</b> — up to 3 minor versions behind is tolerated; 4+ triggers.</li>
     *   <li><b>Patch</b> — up to 3 patch versions behind is tolerated; 4+ triggers.</li>
     * </ul>
     *
     * Returns {@code false} if either version is null/blank or cannot be parsed.
     */
    static boolean isOlderVersion(String current, String latest) {
        String normalizedCurrent = normalizeVersion(current);
        String normalizedLatest  = normalizeVersion(latest);
        if (normalizedCurrent == null || normalizedCurrent.isBlank()
                || normalizedLatest == null || normalizedLatest.isBlank()) {
            return false;
        }
        String[] currentParts = normalizedCurrent.trim().split("\\.");
        String[] latestParts  = normalizedLatest.trim().split("\\.");

        int currentMajor = currentParts.length > 0 ? parseSegment(currentParts[0]) : 0;
        int latestMajor  = latestParts.length  > 0 ? parseSegment(latestParts[0])  : 0;
        if (latestMajor > currentMajor) return true;
        if (latestMajor < currentMajor) return false;

        int currentMinor = currentParts.length > 1 ? parseSegment(currentParts[1]) : 0;
        int latestMinor  = latestParts.length  > 1 ? parseSegment(latestParts[1])  : 0;
        if (latestMinor > currentMinor + 3) return true;
        if (latestMinor != currentMinor) return false;

        int currentPatch = currentParts.length > 2 ? parseSegment(currentParts[2]) : 0;
        int latestPatch  = latestParts.length  > 2 ? parseSegment(latestParts[2])  : 0;
        return latestPatch > currentPatch + 3;
    }

    /**
     * Strips leading non-numeric characters from a version string.
     *
     * <p>This handles common version prefixes used by various frameworks:
     * <ul>
     *   <li>{@code "net8.0"} → {@code "8.0"} (.NET TargetFramework)</li>
     *   <li>{@code "v11.30.0"} → {@code "11.30.0"} (npm/Packagist {@code v}-prefix)</li>
     *   <li>{@code "32.0.1.Final"} → {@code "32.0.1.Final"} (WildFly — no change needed)</li>
     *   <li>{@code "unknown"} or {@code null} → returned as-is (handled by caller)</li>
     * </ul>
     */
    static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) return version;
        return version.trim().replaceFirst("^[^0-9]+", "");
    }

    private static int parseSegment(String segment) {
        try {
            // Strip non-numeric suffixes like ".Final", "-CR1"
            String numeric = segment.replaceAll("[^0-9].*", "");
            return numeric.isEmpty() ? 0 : Integer.parseInt(numeric);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Extracts the major version number from a version string (e.g. {@code "18.2.0"} → {@code "18"}). */
    private static String majorVersion(String version) {
        if (version == null || version.isBlank()) return version;
        String normalized = normalizeVersion(version);
        return normalized.split("\\.")[0];
    }

    /** Extracts major.minor from a version string (e.g. {@code "8.3.11"} → {@code "8.3"}). */
    private static String majorMinorVersion(String version) {
        if (version == null || version.isBlank()) return version;
        String normalized = normalizeVersion(version);
        String[] parts = normalized.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : normalized;
    }

    /**
     * Parses the AI-generated markdown plan into a structured {@link PlanData}.
     *
     * <p>The planner prompt produces a checklist of {@code - [ ] task} items.
     * Each item becomes its own sequential phase (one FIX step per phase, all sharing
     * {@code branchName}) so that changes accumulate on the branch in order rather than
     * running in parallel. Each phase is gated on success so a failure stops the upgrade.
     *
     * <p>The planner prompt now produces {@code ### Phase N: <name>} headings, each followed
     * by a {@code - [ ] task} checklist. Each phase heading becomes a {@link PlanPhase} whose
     * single FIX step prompt contains all the tasks for that phase, so the agent executes the
     * whole phase in one job session. Falls back to one-phase-per-item if no headings are found,
     * and to a single catch-all phase if no checklist items exist.
     */
    static PlanData markdownToPlanData(String markdown, String fallbackSpec, String branchName) {
        if (markdown == null || markdown.isBlank()) {
            PlanStep step = new PlanStep("upgrade-fix", "FIX", "Apply upgrade", fallbackSpec,
                    "PENDING", null, Map.of("branchName", branchName), null);
            return new PlanData(List.of(new PlanPhase(1, "Upgrade", true, List.of(step))));
        }

        List<PlanPhase> phases = new ArrayList<>();
        List<String> lines = markdown.lines().toList();

        // Try phase-grouped format: ### Phase N: <name>
        String currentPhaseName = null;
        List<String> currentTasks = new ArrayList<>();
        int phaseOrder = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("###\\s+Phase\\s+\\d+.*")) {
                if (currentPhaseName != null && !currentTasks.isEmpty()) {
                    phases.add(buildPhase(++phaseOrder, currentPhaseName, currentTasks, branchName));
                }
                currentPhaseName = trimmed.replaceFirst("###\\s+Phase\\s+\\d+[:\\s]*", "").trim();
                if (currentPhaseName.isBlank()) currentPhaseName = "Phase " + (phaseOrder + 1);
                currentTasks = new ArrayList<>();
            } else if (currentPhaseName != null
                    && (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] "))) {
                currentTasks.add(trimmed.substring(6).trim());
            }
        }
        if (currentPhaseName != null && !currentTasks.isEmpty()) {
            phases.add(buildPhase(++phaseOrder, currentPhaseName, currentTasks, branchName));
        }

        // Fall back to flat checklist: one phase per checklist item
        if (phases.isEmpty()) {
            int order = 1;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ")) {
                    String title = trimmed.substring(6).trim();
                    PlanStep step = new PlanStep("upgrade-step-" + order, "FIX", title, title,
                            "PENDING", null, Map.of("branchName", branchName), null);
                    phases.add(new PlanPhase(order, title, true, List.of(step)));
                    order++;
                }
            }
        }

        // Last resort: single phase with the full markdown as the prompt
        if (phases.isEmpty()) {
            PlanStep step = new PlanStep("upgrade-fix", "FIX", "Apply upgrade", markdown,
                    "PENDING", null, Map.of("branchName", branchName), null);
            phases.add(new PlanPhase(1, "Upgrade", true, List.of(step)));
        }

        return new PlanData(phases);
    }

    /**
     * Builds a single {@link PlanPhase} from a list of task strings.
     * The phase has one FIX step whose prompt is all tasks joined as a numbered list,
     * giving the agent the full context for the phase in one job.
     */
    private static PlanPhase buildPhase(int order, String name, List<String> tasks, String branchName) {
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < tasks.size(); i++) {
            prompt.append(i + 1).append(". ").append(tasks.get(i)).append("\n");
        }
        PlanStep step = new PlanStep("upgrade-phase-" + order, "FIX", name, prompt.toString().trim(),
                "PENDING", null, Map.of("branchName", branchName), null);
        return new PlanPhase(order, name, true, List.of(step));
    }
}
