package com.eneve.agent.agent;

import com.eneve.agent.agent.CoverageReporter.CoverageSnapshot;
import com.eneve.agent.agent.CoverageReporter.PackageCoverage;

import java.util.List;

/**
 * Renders {@link CoverageSnapshot} data as Markdown tables.
 *
 * <p>Formatting concerns are kept here so that {@link CoverageSnapshot} stays a
 * pure data record and callers that only need metrics are not coupled to presentation.
 */
public final class CoverageMarkdownFormatter {

    private CoverageMarkdownFormatter() {}

    /**
     * Formats a Markdown before/after comparison table. Pass {@code null} for
     * {@code before} to render a single-column "after" table.
     */
    public static String formatComparison(CoverageSnapshot after, CoverageSnapshot before) {
        if (before == null) {
            return """
                    ## Coverage Report

                    | Metric | Coverage |
                    |--------|----------|
                    | Lines | %.1f%% (%d/%d) |
                    | Branches | %.1f%% (%d/%d) |
                    | Methods | %.1f%% (%d/%d) |
                    | Classes | %.1f%% (%d/%d) |
                    """.formatted(
                    after.lineRate(), after.linesCovered(), after.linesCovered() + after.linesMissed(),
                    after.branchRate(), after.branchesCovered(), after.branchesCovered() + after.branchesMissed(),
                    after.methodRate(), after.methodsCovered(), after.methodsCovered() + after.methodsMissed(),
                    after.classRate(), after.classesCovered(), after.classesCovered() + after.classesMissed());
        }

        return """
                ## Coverage Report

                | Metric | Before | After | Delta |
                |--------|--------|-------|-------|
                | Lines | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %s |
                | Branches | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %s |
                | Methods | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %s |
                | Classes | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %s |
                """.formatted(
                before.lineRate(), before.linesCovered(), before.linesCovered() + before.linesMissed(),
                after.lineRate(), after.linesCovered(), after.linesCovered() + after.linesMissed(),
                formatDelta(after.lineRate() - before.lineRate()),
                before.branchRate(), before.branchesCovered(), before.branchesCovered() + before.branchesMissed(),
                after.branchRate(), after.branchesCovered(), after.branchesCovered() + after.branchesMissed(),
                formatDelta(after.branchRate() - before.branchRate()),
                before.methodRate(), before.methodsCovered(), before.methodsCovered() + before.methodsMissed(),
                after.methodRate(), after.methodsCovered(), after.methodsCovered() + after.methodsMissed(),
                formatDelta(after.methodRate() - before.methodRate()),
                before.classRate(), before.classesCovered(), before.classesCovered() + before.classesMissed(),
                after.classRate(), after.classesCovered(), after.classesCovered() + after.classesMissed(),
                formatDelta(after.classRate() - before.classRate()));
    }

    /**
     * Formats a baseline summary suitable for injection into the agent prompt.
     * Groups packages into three tiers: not tested (0%), low (&lt;50%), moderate (&lt;80%).
     */
    public static String formatForPrompt(CoverageSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Current Coverage Baseline\n\n");
        sb.append("| Metric | Coverage |\n|--------|----------|\n");
        sb.append("| Lines | %.1f%% (%d/%d) |\n".formatted(
                snapshot.lineRate(), snapshot.linesCovered(), snapshot.linesCovered() + snapshot.linesMissed()));
        sb.append("| Branches | %.1f%% (%d/%d) |\n".formatted(
                snapshot.branchRate(), snapshot.branchesCovered(), snapshot.branchesCovered() + snapshot.branchesMissed()));
        sb.append("| Methods | %.1f%% (%d/%d) |\n".formatted(
                snapshot.methodRate(), snapshot.methodsCovered(), snapshot.methodsCovered() + snapshot.methodsMissed()));
        sb.append("| Classes | %.1f%% (%d/%d) |\n".formatted(
                snapshot.classRate(), snapshot.classesCovered(), snapshot.classesCovered() + snapshot.classesMissed()));

        List<PackageCoverage> packages = snapshot.packages();
        if (packages != null && !packages.isEmpty()) {
            List<PackageCoverage> notTested = packages.stream()
                    .filter(p -> p.lineRate() == 0.0)
                    .sorted((a, b) -> Integer.compare(b.linesMissed(), a.linesMissed()))
                    .limit(20)
                    .toList();

            List<PackageCoverage> lowCoverage = packages.stream()
                    .filter(p -> p.lineRate() > 0.0 && p.lineRate() < 50.0)
                    .sorted((a, b) -> Double.compare(a.lineRate(), b.lineRate()))
                    .limit(20)
                    .toList();

            List<PackageCoverage> moderate = packages.stream()
                    .filter(p -> p.lineRate() >= 50.0 && p.lineRate() < 80.0)
                    .sorted((a, b) -> Double.compare(a.lineRate(), b.lineRate()))
                    .limit(10)
                    .toList();

            if (!notTested.isEmpty()) {
                sb.append("\n### Not tested at all — highest impact targets\n\n");
                sb.append("| Package / Namespace | Lines missed |\n|---------------------|-------------|\n");
                for (PackageCoverage pkg : notTested) {
                    sb.append("| `%s` | %d |\n".formatted(
                            pkg.name().replace('/', '.'), pkg.linesMissed()));
                }
            }

            if (!lowCoverage.isEmpty()) {
                sb.append("\n### Low coverage (< 50%) — prioritise these\n\n");
                sb.append("| Package / Namespace | Line % | Covered | Missed |\n|---------------------|--------|---------|--------|\n");
                for (PackageCoverage pkg : lowCoverage) {
                    sb.append("| `%s` | %.1f%% | %d | %d |\n".formatted(
                            pkg.name().replace('/', '.'),
                            pkg.lineRate(),
                            pkg.linesCovered(),
                            pkg.linesMissed()));
                }
            }

            if (!moderate.isEmpty()) {
                sb.append("\n### Moderate coverage (50–80%) — improve if time allows\n\n");
                sb.append("| Package / Namespace | Line % | Covered | Missed |\n|---------------------|--------|---------|--------|\n");
                for (PackageCoverage pkg : moderate) {
                    sb.append("| `%s` | %.1f%% | %d | %d |\n".formatted(
                            pkg.name().replace('/', '.'),
                            pkg.lineRate(),
                            pkg.linesCovered(),
                            pkg.linesMissed()));
                }
            }
        }

        sb.append("\nUse this baseline to prioritise packages and namespaces with the lowest " +
                "coverage. Focus first on untested code, then on packages below 50%, " +
                "then improve packages between 50–80% if time allows.\n");
        return sb.toString();
    }

    private static String formatDelta(double delta) {
        if (delta > 0) return "+%.1f%%".formatted(delta);
        if (delta < 0) return "%.1f%%".formatted(delta);
        return "0.0%";
    }
}
