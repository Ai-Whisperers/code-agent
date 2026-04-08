package com.eneve.agent.linter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Orchestrates all registered LinterRunner instances, compares baseline vs. current
 * scans to detect regressions, and formats results for the agent prompt.
 */
@ApplicationScoped
public class LinterService {

    private static final Logger LOG = Logger.getLogger(LinterService.class);

    @Inject
    Instance<LinterRunner> runners;

    @Inject
    LinterConfig config;

    /**
     * Run all enabled and applicable linters against the workspace.
     */
    public List<LinterResult> runAll(Path workspaceRoot) {
        if (!config.isEnabled()) {
            LOG.info("Linting is globally disabled");
            return List.of();
        }

        List<LinterResult> results = new ArrayList<>();
        for (LinterRunner runner : runners) {
            if (!config.isLinterEnabled(runner.name())) {
                LOG.debugf("Linter %s is disabled, skipping", runner.name());
                continue;
            }
            if (!runner.isApplicable(workspaceRoot)) {
                LOG.debugf("Linter %s is not applicable to this workspace, skipping", runner.name());
                continue;
            }

            try {
                LinterResult result = runner.run(workspaceRoot, config.getTimeoutMinutes());
                results.add(result);
            } catch (Exception e) {
                LOG.warnf("Linter %s threw unexpected exception: %s", runner.name(), e.getMessage());
                results.add(new LinterResult(runner.name(), List.of(), false, e.getMessage()));
            }
        }
        return results;
    }

    /**
     * Compare current scan results against a baseline and return only findings
     * that are new (not present in the baseline). Uses fuzzy line matching so
     * that pre-existing issues shifted by nearby edits are not falsely flagged.
     */
    public List<LinterFinding> findNewIssues(List<LinterResult> baseline, List<LinterResult> current) {
        int tolerance = config.getLineTolerance();
        List<LinterFinding> baselineFindings = baseline.stream()
                .flatMap(r -> r.findings().stream())
                .toList();

        return current.stream()
                .flatMap(r -> r.findings().stream())
                .filter(finding -> baselineFindings.stream()
                        .noneMatch(b -> finding.matchesLoose(b, tolerance)))
                .toList();
    }

    /**
     * Symmetric to {@link #findNewIssues}: returns findings present in the baseline
     * that are absent from the current results (i.e., issues the agent resolved).
     */
    public List<LinterFinding> findResolvedIssues(List<LinterResult> baseline, List<LinterResult> current) {
        int tolerance = config.getLineTolerance();
        List<LinterFinding> currentFindings = current.stream()
                .flatMap(r -> r.findings().stream())
                .toList();

        return baseline.stream()
                .flatMap(r -> r.findings().stream())
                .filter(finding -> currentFindings.stream()
                        .noneMatch(c -> finding.matchesLoose(c, tolerance)))
                .toList();
    }

    /**
     * Build a {@link StaticAnalysisDiffReport} comparing the baseline and current scans.
     * When {@code linter.scope-to-changed-files} is enabled and {@code changedFiles} is
     * non-empty, the new/resolved issue lists are further filtered to only include
     * findings in those files.
     */
    public StaticAnalysisDiffReport buildDiffReport(
            List<LinterResult> baseline,
            List<LinterResult> current,
            Set<String> changedFiles) {

        List<LinterFinding> newIssues = findNewIssues(baseline, current);
        List<LinterFinding> resolvedIssues = findResolvedIssues(baseline, current);

        boolean scoped = config.isScopeToChangedFiles() && !changedFiles.isEmpty();
        if (scoped) {
            newIssues = newIssues.stream()
                    .filter(f -> changedFiles.contains(f.file()))
                    .toList();
            resolvedIssues = resolvedIssues.stream()
                    .filter(f -> changedFiles.contains(f.file()))
                    .toList();
        }

        return new StaticAnalysisDiffReport(baseline, current, newIssues, resolvedIssues, changedFiles, scoped);
    }

    /**
     * Build a one-line-per-linter summary suitable for inclusion in the system prompt.
     */
    public String formatSummary(List<LinterResult> results) {
        if (results.isEmpty()) {
            return "No linters were run.";
        }

        StringBuilder sb = new StringBuilder();
        for (LinterResult r : results) {
            if (!r.success()) {
                sb.append(r.linterName()).append(": skipped (").append(truncate(r.rawOutput(), 80)).append(")\n");
            } else {
                sb.append(r.linterName()).append(": ")
                        .append(r.findings().size()).append(" issues")
                        .append(" (").append(r.errorCount()).append(" errors, ")
                        .append(r.warningCount()).append(" warnings)\n");
            }
        }
        return sb.toString().strip();
    }

    /**
     * Build a focused prompt that tells Claude exactly which linter issues to fix.
     * Groups findings by file for clarity.
     */
    public String buildFixPrompt(List<LinterFinding> newIssues) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following NEW linter/SAST issues were introduced by your changes. ");
        sb.append("Fix ALL of them. Do not introduce any other issues.\n\n");

        var byFile = newIssues.stream()
                .collect(Collectors.groupingBy(LinterFinding::file));

        for (var entry : byFile.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n");
            for (LinterFinding f : entry.getValue()) {
                sb.append("- Line ").append(f.line())
                        .append(" [").append(f.linterName()).append("/").append(f.rule()).append("] ")
                        .append(f.severity()).append(": ").append(f.message()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public LinterConfig getConfig() {
        return config;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() > maxLen ? oneLine.substring(0, maxLen) + "..." : oneLine;
    }
}
