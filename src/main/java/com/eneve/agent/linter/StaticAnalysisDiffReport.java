package com.eneve.agent.linter;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Captures the before/after static analysis state for a single agent change,
 * scoped to files touched by the agent. Produces a human-readable Markdown
 * report suitable for posting as a PR comment.
 */
public record StaticAnalysisDiffReport(
        List<LinterResult> baselineResults,
        List<LinterResult> currentResults,
        List<LinterFinding> newIssues,
        List<LinterFinding> resolvedIssues,
        Set<String> changedFiles,
        boolean scopedToChangedFiles
) {

    public enum Verdict {
        PASS,      // no new issues introduced
        IMPROVED,  // issues resolved > issues introduced (net positive)
        DEGRADED   // net new issues remain
    }

    public Verdict verdict() {
        if (newIssues.isEmpty()) {
            return Verdict.PASS;
        }
        return resolvedIssues.size() > newIssues.size() ? Verdict.IMPROVED : Verdict.DEGRADED;
    }

    /**
     * Total finding count across all baseline results.
     */
    public int baselineTotal() {
        return baselineResults.stream().mapToInt(r -> r.findings().size()).sum();
    }

    /**
     * Total finding count across all current results.
     */
    public int currentTotal() {
        return currentResults.stream().mapToInt(r -> r.findings().size()).sum();
    }

    /**
     * Produces a Markdown PR comment with:
     *   - verdict badge header
     *   - per-linter before/after table
     *   - new issues section (if any)
     *   - resolved issues section (if any)
     */
    public String formatMarkdown() {
        StringBuilder sb = new StringBuilder();

        Verdict v = verdict();
        sb.append("## Static Analysis Diff\n\n");
        sb.append(verdictBadge(v)).append("\n\n");

        if (scopedToChangedFiles && !changedFiles.isEmpty()) {
            sb.append("_Report scoped to ").append(changedFiles.size())
              .append(" file(s) changed by this PR._\n\n");
        }

        appendPerLinterTable(sb);

        if (!newIssues.isEmpty()) {
            sb.append("\n### New Issues Introduced\n\n");
            appendFindingsGroupedByFile(sb, newIssues);
        }

        if (!resolvedIssues.isEmpty()) {
            sb.append("\n### Issues Resolved\n\n");
            appendFindingsGroupedByFile(sb, resolvedIssues);
        }

        if (newIssues.isEmpty() && resolvedIssues.isEmpty()) {
            sb.append("No changes to static analysis findings in the files modified by this PR.\n");
        }

        return sb.toString();
    }

    // ─── Private helpers ─────────────────────────────────────────────────

    private static String verdictBadge(Verdict v) {
        return switch (v) {
            case PASS     -> "> **PASS** — No new static analysis issues introduced.";
            case IMPROVED -> "> **IMPROVED** — Net reduction in static analysis issues.";
            case DEGRADED -> "> **DEGRADED** — New static analysis issues were introduced.";
        };
    }

    private void appendPerLinterTable(StringBuilder sb) {
        // Collect all linter names that appear in either baseline or current
        Set<String> linterNames = Stream.concat(
                baselineResults.stream().map(LinterResult::linterName),
                currentResults.stream().map(LinterResult::linterName))
                .collect(Collectors.toCollection(java.util.TreeSet::new));

        if (linterNames.isEmpty()) {
            return;
        }

        Map<String, Integer> baselineCounts = baselineResults.stream()
                .collect(Collectors.toMap(LinterResult::linterName,
                        r -> r.findings().size(), Integer::sum));
        Map<String, Integer> currentCounts = currentResults.stream()
                .collect(Collectors.toMap(LinterResult::linterName,
                        r -> r.findings().size(), Integer::sum));

        sb.append("### Per-Linter Summary\n\n");
        sb.append("| Linter | Before | After | Delta |\n");
        sb.append("|--------|-------:|------:|------:|\n");

        for (String name : linterNames) {
            int before = baselineCounts.getOrDefault(name, 0);
            int after  = currentCounts.getOrDefault(name, 0);
            int delta  = after - before;
            String deltaStr = delta == 0 ? "0" : (delta > 0 ? "+" + delta : String.valueOf(delta));
            sb.append("| ").append(name)
              .append(" | ").append(before)
              .append(" | ").append(after)
              .append(" | ").append(deltaStr)
              .append(" |\n");
        }

        // Total row
        int totalBefore = baselineCounts.values().stream().mapToInt(i -> i).sum();
        int totalAfter  = currentCounts.values().stream().mapToInt(i -> i).sum();
        int totalDelta  = totalAfter - totalBefore;
        String totalDeltaStr = totalDelta == 0 ? "0"
                : (totalDelta > 0 ? "+" + totalDelta : String.valueOf(totalDelta));
        sb.append("| **Total** | **").append(totalBefore)
          .append("** | **").append(totalAfter)
          .append("** | **").append(totalDeltaStr)
          .append("** |\n");
    }

    private static void appendFindingsGroupedByFile(StringBuilder sb, List<LinterFinding> findings) {
        Map<String, List<LinterFinding>> byFile = findings.stream()
                .collect(Collectors.groupingBy(LinterFinding::file));

        byFile.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    sb.append("**").append(entry.getKey()).append("**\n");
                    entry.getValue().stream()
                            .sorted(Comparator.comparingInt(LinterFinding::line))
                            .forEach(f -> sb.append("- Line ").append(f.line())
                                    .append(" `[").append(f.linterName()).append("/").append(f.rule()).append("]`")
                                    .append(" ").append(f.severity())
                                    .append(": ").append(f.message()).append("\n"));
                    sb.append("\n");
                });
    }
}
