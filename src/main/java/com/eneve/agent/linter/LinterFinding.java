package com.eneve.agent.linter;

import java.util.Objects;

public record LinterFinding(
        String linterName,
        String file,
        int line,
        String severity,
        String rule,
        String message
) {
    public static final String SEVERITY_ERROR = "ERROR";
    public static final String SEVERITY_WARNING = "WARNING";
    public static final String SEVERITY_INFO = "INFO";

    /**
     * Two findings match if they share the same file, line, rule, and linter.
     * Used for baseline comparison (message text may vary slightly between runs).
     */
    public boolean matches(LinterFinding other) {
        return Objects.equals(linterName, other.linterName)
                && Objects.equals(file, other.file)
                && line == other.line
                && Objects.equals(rule, other.rule);
    }
}
