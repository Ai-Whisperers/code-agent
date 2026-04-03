package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.model.QualityReport;
import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.QualityReportStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.knowledge.KnowledgeGraphStore;
import com.eneve.agent.knowledge.KnowledgeGraphStore.KnowledgeScore;
import com.eneve.agent.knowledge.KnowledgeGraphStore.KnowledgeSnapshot;
import com.eneve.agent.model.*;
import com.eneve.agent.techdebt.TechDebtStore;
import com.eneve.agent.techdebt.TechDebtStore.TechDebtFileRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles {@link JobType#TECH_DEBT} jobs.
 *
 * <p>Flow:
 * <ol>
 *   <li>Load all non-archived repos from {@link RepoSettingsStore} (same source as
 *       {@code KnowledgeGraphHandler}).</li>
 *   <li>Resolve the most recent {@link KnowledgeSnapshot} for churn + staleness signals.</li>
 *   <li>For each repo, load per-file {@link KnowledgeScore} rows from the KG snapshot.</li>
 *   <li>Load the latest {@link QualityReport} for each repo (complexity + coverage signals).</li>
 *   <li>Compute a composite debt score per file with weight redistribution when quality
 *       data is absent:
 *       <ul>
 *         <li>Complexity  30% (redistributed when no quality report)</li>
 *         <li>Coverage gap 25% (redistributed when no coverage section)</li>
 *         <li>Churn        25%</li>
 *         <li>Staleness    20%</li>
 *       </ul>
 *   </li>
 *   <li>Persist results via {@link TechDebtStore}.</li>
 *   <li>Delete snapshots older than 90 days (retention policy).</li>
 * </ol>
 *
 * <p>No repository cloning is performed — all signals are derived from existing DB data.
 */
@ApplicationScoped
public class TechDebtHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(TechDebtHandler.class);

    /** Staleness cap: files not touched within this many days score 1.0 for staleness. */
    private static final int STALENESS_CAP_DAYS = 365;
    /** Retention: delete snapshots older than this many days. */
    private static final int RETENTION_DAYS = 90;
    /** Default branch to look up quality reports on. */
    private static final String DEFAULT_BRANCH = "develop";

    @Inject KnowledgeGraphStore knowledgeStore;
    @Inject QualityReportStore  qualityReportStore;
    @Inject TechDebtStore       techDebtStore;
    @Inject RepoSettingsStore   repoSettingsStore;
    @Inject JobStore            jobStore;

    @Override
    public JobType jobType() {
        return JobType.TECH_DEBT;
    }

    @Override
    public void handle(JobRecord job) {
        TechDebtRequest request = (TechDebtRequest) job.getPayload();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("TechDebt job %s starting (lookbackDays=%d)",
                job.getJobId(), request.lookbackDays());

        long snapshotId = techDebtStore.createSnapshot(request.productId(), request.lookbackDays());
        if (snapshotId < 0) {
            failJob(job, "Failed to create tech-debt snapshot row in database");
            return;
        }

        // Resolve the latest knowledge-graph snapshot to source churn + staleness data from.
        Optional<KnowledgeSnapshot> kgSnapshot = knowledgeStore.findLatestSnapshot();
        if (kgSnapshot.isEmpty()) {
            failJob(job, "No knowledge-graph snapshot found — run a KNOWLEDGE_GRAPH job first");
            return;
        }
        long kgSnapshotId = kgSnapshot.get().id();

        // Use the same repo list as KnowledgeGraphHandler: all non-archived repos.
        List<RepoSettings> repos = repoSettingsStore.listAll().stream()
                .filter(r -> !r.archived())
                .toList();

        if (repos.isEmpty()) {
            LOG.warnf("TechDebt job %s: no repos found", job.getJobId());
            techDebtStore.updateSnapshotStats(snapshotId, 0);
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary("No repos configured.");
            jobStore.archive(job);
            return;
        }

        LOG.infof("TechDebt job %s: analysing %d repos", job.getJobId(), repos.size());

        List<TechDebtFileRow> allRows = new ArrayList<>();

        for (RepoSettings repo : repos) {
            try {
                List<TechDebtFileRow> repoRows = analyseRepo(
                        kgSnapshotId, repo.workspace(), repo.repoSlug());
                allRows.addAll(repoRows);
                LOG.debugf("TechDebt job %s: %s/%s → %d files scored",
                        job.getJobId(), repo.workspace(), repo.repoSlug(), repoRows.size());
            } catch (Exception e) {
                LOG.warnf("TechDebt job %s: failed to analyse %s/%s: %s",
                        job.getJobId(), repo.workspace(), repo.repoSlug(), e.getMessage());
            }
        }

        if (!allRows.isEmpty()) {
            techDebtStore.insertFiles(snapshotId, allRows);
        }
        techDebtStore.updateSnapshotStats(snapshotId, allRows.size());

        int deleted = techDebtStore.deleteOldSnapshots(RETENTION_DAYS);
        if (deleted > 0) {
            LOG.infof("TechDebt job %s: deleted %d old snapshot(s) (retention=%d days)",
                    job.getJobId(), deleted, RETENTION_DAYS);
        }

        long highDebt = allRows.stream()
                .filter(r -> r.debtScore().compareTo(BigDecimal.valueOf(0.6)) >= 0)
                .count();

        String summary = "Snapshot #%d: %d repos, %d files scored, %d with debt > 0.6."
                .formatted(snapshotId, repos.size(), allRows.size(), highDebt);

        job.setStatus(JobStatus.SUCCESS);
        job.setSummary(summary);
        jobStore.archive(job);

        LOG.infof("TechDebt job %s complete: %s", job.getJobId(), summary);
    }

    // ── Per-repo analysis ─────────────────────────────────────────────────────

    private List<TechDebtFileRow> analyseRepo(long kgSnapshotId,
                                               String workspace,
                                               String repoSlug) {

        // ── Load knowledge-graph scores (churn + staleness) ───────────────────
        List<KnowledgeScore> kgScores = knowledgeStore.findScores(kgSnapshotId, repoSlug, null);
        if (kgScores.isEmpty()) {
            LOG.debugf("No knowledge-graph scores for %s/%s in KG snapshot %d — skipping",
                    workspace, repoSlug, kgSnapshotId);
            return List.of();
        }

        // Compute 95th-percentile churn cap across all files in this repo.
        List<Integer> churns = kgScores.stream()
                .map(s -> s.linesAdded() + s.linesDeleted())
                .sorted()
                .toList();
        int p95Index = Math.max(0, (int) Math.ceil(churns.size() * 0.95) - 1);
        int churnCap = Math.max(1, churns.get(p95Index));

        // ── Load quality report (complexity + coverage) ───────────────────────
        // Try main branch first; fall back to develop.
        Optional<QualityReport> reportOpt = qualityReportStore.findLatest(workspace, repoSlug, DEFAULT_BRANCH);
        if (reportOpt.isEmpty()) {
            reportOpt = qualityReportStore.findLatest(workspace, repoSlug, "develop");
        }
        QualityReport report = reportOpt.orElse(null);

        if (report == null) {
            LOG.debugf("No quality report for %s/%s — scoring on churn/staleness only", workspace, repoSlug);
        }

        // Build package → coverage-gap lookup from the quality report.
        Map<String, Double> packageCoverageGap = buildPackageCoverageGap(report);

        // Repo-level complexity fraction (used when per-file data is unavailable).
        double repoComplexityFraction = repoComplexityFraction(report);

        // ── Determine available signals and redistribute weights ──────────────
        // When quality data is absent, its weight is redistributed to churn + staleness,
        // matching the same principle used in QualityReport.computeScore().
        boolean hasCoverage   = report != null && report.coverage() != null
                && report.coverage().packages() != null
                && !report.coverage().packages().isEmpty();
        boolean hasComplexity = report != null && report.complexity() != null;

        double wComplexity = hasComplexity ? 0.30 : 0.0;
        double wCoverage   = hasCoverage   ? 0.25 : 0.0;
        double wChurn      = 0.25;
        double wStaleness  = 0.20;
        double totalWeight = wComplexity + wCoverage + wChurn + wStaleness;

        // ── Score each file ───────────────────────────────────────────────────
        // Aggregate knowledge scores per file (multiple authors → keep highest-churn row).
        Map<String, KnowledgeScore> byFile = kgScores.stream()
                .collect(Collectors.toMap(
                        KnowledgeScore::filePath,
                        s -> s,
                        (a, b) -> (a.linesAdded() + a.linesDeleted()) >= (b.linesAdded() + b.linesDeleted()) ? a : b
                ));

        List<TechDebtFileRow> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (Map.Entry<String, KnowledgeScore> entry : byFile.entrySet()) {
            String filePath = entry.getKey();
            KnowledgeScore ks = entry.getValue();

            // Churn score: normalised to 95th-percentile cap.
            double churn = Math.min(1.0,
                    (double) (ks.linesAdded() + ks.linesDeleted()) / churnCap);

            // Staleness score: days since last commit / cap.
            double staleness = 1.0;
            if (ks.lastCommitAt() != null) {
                long days = ChronoUnit.DAYS.between(ks.lastCommitAt(), today);
                staleness = Math.min(1.0, (double) days / STALENESS_CAP_DAYS);
            }

            // Complexity: repo-level fraction; 0 when absent (weight redistributed).
            double complexity = hasComplexity ? repoComplexityFraction : 0.0;

            // Coverage gap: matched by package path; 0 when absent (weight redistributed).
            double coverageGap = hasCoverage ? matchCoverageGap(filePath, packageCoverageGap) : 0.0;

            // Composite debt score with redistributed weights.
            double debt = (wComplexity * complexity
                    + wCoverage   * coverageGap
                    + wChurn      * churn
                    + wStaleness  * staleness) / totalWeight;

            rows.add(new TechDebtFileRow(
                    0L,
                    repoSlug,
                    filePath,
                    bd(complexity),
                    bd(coverageGap),
                    bd(churn),
                    bd(staleness),
                    bd(debt),
                    ks.lastCommitAt()
            ));
        }

        return rows;
    }

    // ── Signal helpers ────────────────────────────────────────────────────────

    /**
     * Returns the repo-level complexity fraction:
     * {@code methodsAboveThreshold / max(1, totalMethods)}.
     * Returns 0.0 when no quality report is available.
     */
    private static double repoComplexityFraction(QualityReport report) {
        if (report == null || report.complexity() == null) return 0.0;
        QualityReport.ComplexitySection c = report.complexity();
        return Math.min(1.0,
                (double) c.methodsAboveThreshold() / Math.max(1, c.totalMethods()));
    }

    /**
     * Builds a map of package-name-prefix → coverage-gap (1 − lineRate) from the
     * {@link QualityReport.CoverageSection}.
     * Returns an empty map when coverage data is unavailable.
     */
    private static Map<String, Double> buildPackageCoverageGap(QualityReport report) {
        if (report == null || report.coverage() == null
                || report.coverage().packages() == null) {
            return Map.of();
        }
        Map<String, Double> map = new LinkedHashMap<>();
        for (QualityReport.PackageLineCoverage pkg : report.coverage().packages()) {
            if (pkg.name() == null) continue;
            double gap = 1.0 - Math.min(1.0, Math.max(0.0, pkg.lineRate() / 100.0));
            // Normalise package name to a path-like prefix (replace dots with slashes).
            map.put(pkg.name().replace('.', '/'), gap);
        }
        return map;
    }

    /**
     * Matches a file path to the most specific package prefix in the coverage map.
     * Returns 1.0 (full coverage-gap penalty) when no package prefix matches the file.
     */
    private static double matchCoverageGap(String filePath,
                                            Map<String, Double> packageCoverageGap) {
        if (packageCoverageGap.isEmpty()) return 1.0;
        String best = null;
        for (String prefix : packageCoverageGap.keySet()) {
            if (filePath.contains(prefix)) {
                if (best == null || prefix.length() > best.length()) {
                    best = prefix;
                }
            }
        }
        return best != null ? packageCoverageGap.get(best) : 1.0;
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, value)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private void failJob(JobRecord job, String reason) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(reason);
        jobStore.update(job);
        LOG.errorf("TechDebt job %s failed: %s", job.getJobId(), reason);
    }
}
