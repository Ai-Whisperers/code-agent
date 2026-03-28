package com.eneve.agent.agent.model;

import java.time.Instant;
import java.util.List;
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
        ReviewSection reviewQuality,
        TestPresenceSection testPresence
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
            int classesMissed,
            List<PackageLineCoverage> packages
    ) {}

    /**
     * Per-package (Java) or per-namespace (.NET) line coverage summary.
     * Stored inline within {@link CoverageSection} in the JSONB report.
     */
    public record PackageLineCoverage(String name, int linesCovered, int linesMissed) {
        public double lineRate() {
            int total = linesCovered + linesMissed;
            return total > 0 ? 100.0 * linesCovered / total : 0.0;
        }
    }

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
            int lowCount,
            int sastCount,
            int dependencyCount,
            int secretCount,
            int containerCount,
            int otherCount
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

    /**
     * Language-agnostic test presence snapshot.
     * {@code testRatio} = {@code testFiles / max(1, sourceFiles)}, capped at 1.0.
     */
    public record TestPresenceSection(
            int sourceFiles,
            int testFiles,
            double testRatio,
            java.util.List<String> detectedLanguages
    ) {}

    // ─── Score computation ────────────────────────────────────────────────

    /**
     * Computes an aggregate quality score in [0.0, 1.0]. Higher is better.
     *
     * <p>Section weights (normalised to their sum when all four are present):
     * <ul>
     *   <li><b>Complexity   (50)</b> — 1 − methodsAboveThreshold / max(1, totalMethods)</li>
     *   <li><b>Aikido       (30)</b> — 1 − min(1, critical×0.5 + high×0.2 + medium×0.05 + low×0.01)</li>
     *   <li><b>Linter       (20)</b> — 1 − min(1, errors×0.1 + warnings×0.01)</li>
     *   <li><b>Coverage (10)</b> — JaCoCo line rate / 100 when available; otherwise
     *       testFiles / max(1, sourceFiles); absent = 0 (<em>penalised, not redistributed</em>)</li>
     * </ul>
     *
     * <p>Coverage / test-presence absence is treated as 0 so that repositories with no
     * measurable coverage are penalised. Aikido and linter absence (service not configured /
     * no applicable linter) have their weight redistributed to the remaining present sections.
     * Review quality is collected but excluded from the score.
     */
    public static double computeScore(CoverageSection coverage, TestPresenceSection tests,
                                      LinterSection lint, AikidoSection aik,
                                      ComplexitySection cplx, ReviewSection rev) {
        // Coverage: real JaCoCo line rate preferred; fall back to test presence ratio; absent = 0
        double covScore;
        if (coverage != null) {
            covScore = Math.max(0.0, Math.min(1.0, coverage.lineRate() / 100.0));
        } else if (tests != null) {
            covScore = Math.max(0.0, Math.min(1.0, tests.testRatio()));
        } else {
            covScore = 0.0;
        }

        // Complexity
        double cplxScore = 0.0;
        if (cplx != null) {
            double fraction = (double) cplx.methodsAboveThreshold() / Math.max(1, cplx.totalMethods());
            cplxScore = Math.max(0.0, 1.0 - Math.min(1.0, fraction));
        }

        // Aikido
        double aikScore = 0.0;
        if (aik != null) {
            double penalty = aik.criticalCount() * 0.5 + aik.highCount() * 0.2
                    + aik.mediumCount() * 0.05 + aik.lowCount() * 0.01;
            aikScore = Math.max(0.0, 1.0 - Math.min(1.0, penalty));
        }

        // Linter
        double lintScore = 0.0;
        if (lint != null) {
            double penalty = lint.errorCount() * 0.1 + lint.warningCount() * 0.01;
            lintScore = Math.max(0.0, 1.0 - Math.min(1.0, penalty));
        }

        // Coverage weight is always present (absence = 0, not excluded).
        // Absent optional sections have their weight redistributed.
        double totalWeight = 10.0;                     // coverage always in denominator
        if (cplx != null) totalWeight += 50.0;
        if (aik  != null) totalWeight += 30.0;
        if (lint != null) totalWeight += 20.0;

        double weighted = covScore  * 10.0
                + cplxScore * (cplx != null ? 50.0 : 0.0)
                + aikScore  * (aik  != null ? 30.0 : 0.0)
                + lintScore * (lint != null ? 20.0 : 0.0);

        return Math.round(weighted / totalWeight * 10000.0) / 10000.0;
    }
}
