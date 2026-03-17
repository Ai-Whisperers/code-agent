package com.eneve.agent.agent;

import java.time.Instant;
import java.util.Map;

/**
 * A point-in-time quality snapshot for a single repository branch.
 * All metric sections are nullable — if a data source is unavailable (e.g. no JaCoCo,
 * Aikido not configured) its section is {@code null} and its weight is redistributed to
 * the remaining sections when computing the aggregate {@link #score()}.
 *
 * <p>Serialised as JSONB in the {@code quality_reports} table.
 */
public record QualityReport(
        String reportId,
        String workspace,
        String repoSlug,
        String branch,
        Instant measuredAt,
        double score,
        CoverageSection coverage,
        LinterSection linter,
        AikidoSection aikido,
        ComplexitySection complexity,
        ReviewSection reviewQuality
) {

    // ─── Nested section records ───────────────────────────────────────────

    public record CoverageSection(
            double lineRate,
            double branchRate,
            double methodRate,
            double classRate,
            int linesCovered,
            int linesMissed,
            int branchesCovered,
            int branchesMissed,
            int methodsCovered,
            int methodsMissed,
            int classesCovered,
            int classesMissed
    ) {}

    public record LinterSection(
            int totalFindings,
            int errorCount,
            int warningCount,
            int infoCount,
            Map<String, Integer> findingsByLinter,
            Map<String, Integer> findingsBySeverity
    ) {}

    public record AikidoSection(
            int totalIssues,
            int criticalCount,
            int highCount,
            int mediumCount,
            int lowCount
    ) {}

    public record ComplexitySection(
            int totalMethods,
            int methodsAboveThreshold,
            double avgComplexity,
            int maxComplexity,
            int threshold
    ) {}

    public record ReviewSection(
            long totalFindings,
            long resolvedFindings,
            double resolutionRate,
            long falsePositives,
            double fpRate
    ) {}

    // ─── Score computation ────────────────────────────────────────────────

    /**
     * Computes an aggregate quality score in [0.0, 1.0] from the available metric sections.
     * Each present section contributes an equal weight; missing (null) sections have their
     * weight redistributed to the remaining sections.
     *
     * <ul>
     *   <li><b>Coverage score</b> = lineRate / 100</li>
     *   <li><b>Linter score</b> = 1 − min(1, errors×0.1 + warnings×0.01)</li>
     *   <li><b>Aikido score</b> = 1 − min(1, critical×0.5 + high×0.2 + medium×0.05 + low×0.01)</li>
     *   <li><b>Complexity score</b> = 1 − methodsAboveThreshold / max(1, totalMethods)</li>
     *   <li><b>Review score</b> = resolutionRate</li>
     * </ul>
     */
    public static double computeScore(CoverageSection cov, LinterSection lint,
                                      AikidoSection aik, ComplexitySection cplx,
                                      ReviewSection rev) {
        double[] scores = new double[5];
        boolean[] present = new boolean[5];

        if (cov != null) {
            scores[0] = Math.max(0.0, Math.min(1.0, cov.lineRate() / 100.0));
            present[0] = true;
        }
        if (lint != null) {
            double penalty = lint.errorCount() * 0.1 + lint.warningCount() * 0.01;
            scores[1] = Math.max(0.0, 1.0 - Math.min(1.0, penalty));
            present[1] = true;
        }
        if (aik != null) {
            double penalty = aik.criticalCount() * 0.5 + aik.highCount() * 0.2
                    + aik.mediumCount() * 0.05 + aik.lowCount() * 0.01;
            scores[2] = Math.max(0.0, 1.0 - Math.min(1.0, penalty));
            present[2] = true;
        }
        if (cplx != null) {
            double fraction = (double) cplx.methodsAboveThreshold() / Math.max(1, cplx.totalMethods());
            scores[3] = Math.max(0.0, 1.0 - Math.min(1.0, fraction));
            present[3] = true;
        }
        if (rev != null) {
            scores[4] = Math.max(0.0, Math.min(1.0, rev.resolutionRate()));
            present[4] = true;
        }

        int presentCount = 0;
        double total = 0.0;
        for (int i = 0; i < 5; i++) {
            if (present[i]) {
                total += scores[i];
                presentCount++;
            }
        }

        if (presentCount == 0) return 0.0;
        return Math.round(total / presentCount * 10000.0) / 10000.0;
    }
}
