package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.knowledge.AuthorIdentityResolver;
import com.eneve.agent.knowledge.KnowledgeGraphStore;
import com.eneve.agent.knowledge.KnowledgeGraphStore.*;
import com.eneve.agent.model.*;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Handles {@link JobType#KNOWLEDGE_GRAPH} jobs.
 *
 * <p>Repo source: {@link RepoSettingsStore#listAll()} — the same registry used by the
 * quality-report scheduler. Each {@link RepoSettings#gitPlatformUrl()} is the authenticated
 * clone URL; {@link RepoSettings#workspace()} and {@link RepoSettings#repoSlug()} provide
 * the human-readable identifiers.
 *
 * <p>Flow per repo:
 * <ol>
 *   <li>Full-history clone (no {@code --depth} limit).</li>
 *   <li>{@code git log --numstat} to collect per-author per-file commit signals.</li>
 *   <li>{@code git blame --line-porcelain} on the top 20% most-churned files.</li>
 *   <li>Compute weighted score with time-decay.</li>
 *   <li>Persist scores and bus-factor flags via {@link KnowledgeGraphStore}.</li>
 *   <li>Delete snapshots older than 90 days.</li>
 * </ol>
 */
@ApplicationScoped
public class KnowledgeGraphHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(KnowledgeGraphHandler.class);

    /** Files sampled for blame: top N% by churn (lines added + deleted). */
    private static final double BLAME_SAMPLE_FRACTION = 0.20;
    /** Hard cap on blame targets per repo (guard against huge repos). */
    private static final int BLAME_MAX_FILES = 200;
    /** Retention: delete snapshots older than this many days. */
    private static final int RETENTION_DAYS = 90;

    @Inject KnowledgeGraphStore store;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject JobStore jobStore;
    @Inject SettingsService settings;
    @Inject AuthorIdentityResolver identityResolver;

    @Override
    public JobType jobType() {
        return JobType.KNOWLEDGE_GRAPH;
    }

    @Override
    public void handle(JobRecord job) {
        KnowledgeGraphRequest request = (KnowledgeGraphRequest) job.getPayload();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("KnowledgeGraph job %s starting (productId=%s, lookbackDays=%d)",
                job.getJobId(), request.productId(), request.lookbackDays());

        long snapshotId = store.createSnapshot(request.productId(), request.lookbackDays());
        if (snapshotId < 0) {
            failJob(job, "Failed to create snapshot row in database");
            return;
        }

        // Collect repos to analyse — non-archived repos with quality reports enabled
        // (same opt-in flag as the quality report scheduler)
        List<RepoSettings> repos = repoSettingsStore.listAll().stream()
                .filter(r -> !r.archived())
                .filter(RepoSettings::qualityReportEnabled)
                .toList();

        if (repos.isEmpty()) {
            LOG.warnf("KnowledgeGraph job %s: no repos with quality reports enabled found", job.getJobId());
            store.updateSnapshotStats(snapshotId, 0, 0, 0);
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary("No repos configured.");
            jobStore.archive(job);
            return;
        }

        LOG.infof("KnowledgeGraph job %s: analysing %d repos", job.getJobId(), repos.size());

        int totalRepos = 0;
        Set<String> allAuthors = new HashSet<>();
        int totalFiles = 0;
        List<KnowledgeScore> allScores = new ArrayList<>();
        List<BusFactorRow> allBusFactor = new ArrayList<>();

        String defaultBranch = settings.get("knowledge-graph.default-branch", "develop");

        // Load author identity resolution state — shared across all repos in this run
        // so that the local-part heuristic is consistent (first-seen canonical email wins)
        Map<String, String> aliasMap = identityResolver.loadAliasMap();
        Map<String, String> localPartIndex = new LinkedHashMap<>();  // localPart → canonical email
        Map<String, String> nameIndex = new LinkedHashMap<>();        // canonical email → display name

        LOG.infof("KnowledgeGraph job %s: loaded %d explicit author aliases", job.getJobId(), aliasMap.size());

        for (RepoSettings repo : repos) {
            String repoSlug = repo.repoSlug();

            // Build clone URL: prefer stored gitPlatformUrl, fall back to platform service
            String cloneUrl = repo.gitPlatformUrl();
            if (cloneUrl == null || cloneUrl.isBlank()) {
                try {
                    GitPlatformService svc = platformRegistry.resolve(
                            "https://bitbucket.org/" + repo.workspace() + "/" + repoSlug + ".git");
                    cloneUrl = svc.buildCloneUrl(repo.workspace(), repoSlug);
                } catch (Exception e) {
                    LOG.warnf("KnowledgeGraph job %s: cannot build clone URL for %s/%s — skipping: %s",
                            job.getJobId(), repo.workspace(), repoSlug, e.getMessage());
                    continue;
                }
            }

            LOG.infof("KnowledgeGraph job %s: analysing %s/%s", job.getJobId(), repo.workspace(), repoSlug);

            final String finalCloneUrl = cloneUrl;
            try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId() + "-" + repoSlug)) {
                workspace.cloneRepoFull(finalCloneUrl, defaultBranch, 60L);

                RepoAnalysis analysis = analyseRepo(workspace, repoSlug, request.lookbackDays(),
                        aliasMap, localPartIndex, nameIndex);

                allScores.addAll(analysis.scores());
                allBusFactor.addAll(analysis.busFactorRows());
                allAuthors.addAll(analysis.scores().stream()
                        .map(KnowledgeScore::authorEmail).collect(Collectors.toSet()));
                totalFiles += analysis.fileCount();
                totalRepos++;

            } catch (Exception e) {
                LOG.errorf("KnowledgeGraph job %s: failed to analyse repo %s/%s: %s",
                        job.getJobId(), repo.workspace(), repoSlug, e.getMessage());
            }
        }

        if (!allScores.isEmpty()) {
            store.insertScores(snapshotId, allScores);
        }
        if (!allBusFactor.isEmpty()) {
            store.insertBusFactorRows(snapshotId, allBusFactor);
        }
        store.updateSnapshotStats(snapshotId, totalRepos, allAuthors.size(), totalFiles);

        int deleted = store.deleteOldSnapshots(RETENTION_DAYS);
        if (deleted > 0) {
            LOG.infof("KnowledgeGraph job %s: deleted %d old snapshot(s) (retention=%d days)",
                    job.getJobId(), deleted, RETENTION_DAYS);
        }

        String summary = "Snapshot #%d: %d repos, %d authors, %d files analysed. Bus-factor flags: %d."
                .formatted(snapshotId, totalRepos, allAuthors.size(), totalFiles,
                        allBusFactor.stream().filter(BusFactorRow::busFactorFlag).count());

        job.setStatus(JobStatus.SUCCESS);
        job.setSummary(summary);
        jobStore.archive(job);

        LOG.infof("KnowledgeGraph job %s complete: %s", job.getJobId(), summary);
    }

    // ── Per-repo analysis ─────────────────────────────────────────────────────

    private RepoAnalysis analyseRepo(WorkspaceContext workspace, String repoSlug, int lookbackDays,
                                     Map<String, String> aliasMap,
                                     Map<String, String> localPartIndex,
                                     Map<String, String> nameIndex)
            throws IOException, InterruptedException {

        // ── Step 1: git log --numstat ─────────────────────────────────────────
        // Output format per commit block:
        //   <author-email>|<date-YYYY-MM-DD>
        //   <added>\t<deleted>\t<file>
        //   ...
        //   (blank line)
        String since = LocalDate.now().minusDays(lookbackDays).toString();
        String logOutput = runCommand(workspace.getRoot(), 30,
                "git", "log",
                "--format=%ae|%ad",
                "--date=short",
                "--since=" + since,
                "--diff-filter=ACDMRT",
                "--numstat",
                "--no-merges");

        // author → file → signals
        Map<String, Map<String, FileSignals>> authorFileMap = new LinkedHashMap<>();
        // file → total churn (for blame sampling)
        Map<String, Integer> fileChurn = new LinkedHashMap<>();

        String currentEmail = null;   // canonical email for current commit
        LocalDate currentDate = null;
        boolean recentCommit = false;

        for (String line : logOutput.lines().toList()) {
            // Commit header: email|YYYY-MM-DD (no tabs, second part is a date)
            if (!line.startsWith("\t") && line.contains("|")) {
                String[] parts = line.split("\\|", 2);
                String possibleDate = parts.length > 1 ? parts[1].trim() : "";
                if (possibleDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    // Resolve raw email → canonical identity immediately
                    String rawEmail = parts[0].trim().toLowerCase();
                    currentEmail = identityResolver.resolve(rawEmail, aliasMap, localPartIndex);
                    try {
                        currentDate = LocalDate.parse(possibleDate);
                        recentCommit = currentDate.isAfter(LocalDate.now().minusDays(90));
                    } catch (Exception e) {
                        currentDate = null;
                        recentCommit = false;
                    }
                    continue;
                }
            }
            if (!line.isBlank() && currentEmail != null) {
                // Numstat line: added\tdeleted\tfile
                String[] cols = line.split("\t", 3);
                if (cols.length < 3) continue;
                int added = parseIntSafe(cols[0]);
                int deleted = parseIntSafe(cols[1]);
                String filePath = cols[2].trim();
                if (filePath.isBlank()) continue;

                authorFileMap
                        .computeIfAbsent(currentEmail, k -> new LinkedHashMap<>())
                        .computeIfAbsent(filePath, k -> new FileSignals())
                        .record(added, deleted, recentCommit, currentDate);

                fileChurn.merge(filePath, added + deleted, Integer::sum);
            }
        }

        // ── Step 2: git blame on top-churned files ────────────────────────────
        List<String> blameTargets = fileChurn.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(Math.max(1, (int) (fileChurn.size() * BLAME_SAMPLE_FRACTION)))
                .limit(BLAME_MAX_FILES)
                .map(Map.Entry::getKey)
                .filter(f -> Files.exists(workspace.getRoot().resolve(f)))
                .toList();

        // file → author → blame lines owned
        Map<String, Map<String, Integer>> blameMap = new LinkedHashMap<>();
        // file → total lines
        Map<String, Integer> fileTotalLines = new LinkedHashMap<>();

        for (String filePath : blameTargets) {
            try {
                String blameOutput = runCommand(workspace.getRoot(), 5,
                        "git", "blame", "-e", "--line-porcelain", "--", filePath);
                Map<String, Integer> authorLines = new LinkedHashMap<>();
                int total = 0;
                for (String bline : blameOutput.lines().toList()) {
                    if (bline.startsWith("author-mail ")) {
                        String rawEmail = bline.substring("author-mail ".length())
                                .trim().replaceAll("[<>]", "").toLowerCase();
                        // Resolve to canonical identity
                        String canonical = identityResolver.resolve(rawEmail, aliasMap, localPartIndex);
                        authorLines.merge(canonical, 1, Integer::sum);
                        total++;
                    }
                }
                blameMap.put(filePath, authorLines);
                fileTotalLines.put(filePath, total);
            } catch (Exception e) {
                LOG.debugf("git blame failed for %s/%s: %s", repoSlug, filePath, e.getMessage());
            }
        }

        // ── Step 3: build author-name map from git log ────────────────────────
        // Populate the shared nameIndex so display names are consistent across repos
        try {
            String nameOutput = runCommand(workspace.getRoot(), 5,
                    "git", "log", "--format=%ae|%an", "--since=" + since, "--no-merges");
            for (String nline : nameOutput.lines().toList()) {
                String[] p = nline.split("\\|", 2);
                if (p.length == 2) {
                    String rawEmail = p[0].trim().toLowerCase();
                    String canonical = identityResolver.resolve(rawEmail, aliasMap, localPartIndex);
                    identityResolver.resolveName(canonical, p[1].trim(), nameIndex);
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not fetch author names for %s: %s", repoSlug, e.getMessage());
        }

        // ── Step 4: compute scores ────────────────────────────────────────────
        List<KnowledgeScore> scores = new ArrayList<>();
        for (Map.Entry<String, Map<String, FileSignals>> authorEntry : authorFileMap.entrySet()) {
            String email = authorEntry.getKey(); // already canonical
            String name = identityResolver.resolveName(email, null, nameIndex);

            for (Map.Entry<String, FileSignals> fileEntry : authorEntry.getValue().entrySet()) {
                String filePath = fileEntry.getKey();
                FileSignals sig = fileEntry.getValue();

                int blameLines = blameMap.getOrDefault(filePath, Map.of())
                        .getOrDefault(email, 0);
                int totalLines = fileTotalLines.getOrDefault(filePath, 0);

                // Score formula:
                // commit_count*3 + lines_added*0.5 + lines_deleted*0.3 + blame_lines*2
                // Decay: recent commits (< 90 days) weighted 1×, older 0.5×
                double raw = sig.recentCommits * 3.0
                        + sig.olderCommits * 3.0 * 0.5
                        + sig.linesAdded * 0.5
                        + sig.linesDeleted * 0.3
                        + blameLines * 2.0;

                BigDecimal score = BigDecimal.valueOf(raw).setScale(4, RoundingMode.HALF_UP);

                scores.add(new KnowledgeScore(
                        0L,
                        email,
                        name,
                        repoSlug,
                        filePath,
                        sig.recentCommits + sig.olderCommits,
                        sig.linesAdded,
                        sig.linesDeleted,
                        blameLines,
                        totalLines,
                        sig.lastCommitDate,
                        score,
                        BigDecimal.ZERO
                ));
            }
        }

        // ── Step 5: bus-factor flags ──────────────────────────────────────────
        List<BusFactorRow> busFactorRows = new ArrayList<>();
        Map<String, List<KnowledgeScore>> byFile = scores.stream()
                .collect(Collectors.groupingBy(KnowledgeScore::filePath));

        for (Map.Entry<String, List<KnowledgeScore>> entry : byFile.entrySet()) {
            String filePath = entry.getKey();
            List<KnowledgeScore> fileScores = entry.getValue().stream()
                    .sorted(Comparator.comparing(KnowledgeScore::score).reversed())
                    .toList();
            if (fileScores.isEmpty()) continue;

            KnowledgeScore top = fileScores.get(0);
            KnowledgeScore second = fileScores.size() > 1 ? fileScores.get(1) : null;

            int totalFileLines = fileTotalLines.getOrDefault(filePath, 0);
            int topBlame = blameMap.getOrDefault(filePath, Map.of())
                    .getOrDefault(top.authorEmail(), 0);
            double ownershipPct = totalFileLines > 0
                    ? (topBlame * 100.0 / totalFileLines) : 0.0;

            boolean flag = ownershipPct >= 60.0;
            String risk;
            if (ownershipPct >= 80.0) risk = "critical";
            else if (ownershipPct >= 60.0) risk = "warning";
            else risk = "none";

            busFactorRows.add(new BusFactorRow(
                    0L,
                    repoSlug,
                    filePath,
                    top.authorEmail(),
                    top.authorName(),
                    top.score(),
                    BigDecimal.valueOf(ownershipPct).setScale(2, RoundingMode.HALF_UP),
                    second != null ? second.authorEmail() : null,
                    second != null ? second.score() : BigDecimal.ZERO,
                    flag,
                    risk
            ));
        }

        return new RepoAnalysis(scores, busFactorRows, scores.stream()
                .map(KnowledgeScore::filePath).collect(Collectors.toSet()).size());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String runCommand(Path workDir, long timeoutMinutes, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", cmd));
        }
        return output;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void failJob(JobRecord job, String reason) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(reason);
        jobStore.update(job);
        LOG.errorf("KnowledgeGraph job %s failed: %s", job.getJobId(), reason);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record RepoAnalysis(
            List<KnowledgeScore> scores,
            List<BusFactorRow> busFactorRows,
            int fileCount
    ) {}

    private static class FileSignals {
        int recentCommits;
        int olderCommits;
        int linesAdded;
        int linesDeleted;
        LocalDate lastCommitDate;

        void record(int added, int deleted, boolean recent, LocalDate date) {
            if (recent) recentCommits++;
            else olderCommits++;
            linesAdded += added;
            linesDeleted += deleted;
            if (date != null && (lastCommitDate == null || date.isAfter(lastCommitDate))) {
                lastCommitDate = date;
            }
        }
    }
}
