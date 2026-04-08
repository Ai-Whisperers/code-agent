package com.eneve.agent.linter;

import java.util.List;

public record LinterResult(
        String linterName,
        List<LinterFinding> findings,
        boolean success,
        String rawOutput
) {
    public long errorCount() {
        return findings.stream()
                .filter(f -> LinterFinding.SEVERITY_ERROR.equals(f.severity()))
                .count();
    }

    public long warningCount() {
        return findings.stream()
                .filter(f -> LinterFinding.SEVERITY_WARNING.equals(f.severity()))
                .count();
    }
}
