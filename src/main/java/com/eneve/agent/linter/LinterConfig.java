package com.eneve.agent.linter;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class LinterConfig {

    @ConfigProperty(name = "linter.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "linter.checkstyle.enabled", defaultValue = "true")
    boolean checkstyleEnabled;

    @ConfigProperty(name = "linter.pmd.enabled", defaultValue = "true")
    boolean pmdEnabled;

    @ConfigProperty(name = "linter.spotbugs.enabled", defaultValue = "true")
    boolean spotbugsEnabled;

    @ConfigProperty(name = "linter.eslint.enabled", defaultValue = "true")
    boolean eslintEnabled;

    @ConfigProperty(name = "linter.dotnet-format.enabled", defaultValue = "true")
    boolean dotnetFormatEnabled;

    @ConfigProperty(name = "linter.max-fix-iterations", defaultValue = "2")
    int maxFixIterations;

    @ConfigProperty(name = "linter.fail-on-new-issues", defaultValue = "false")
    boolean failOnNewIssues;

    @ConfigProperty(name = "linter.timeout-minutes", defaultValue = "10")
    long timeoutMinutes;

    @ConfigProperty(name = "linter.report-on-pr", defaultValue = "true")
    boolean reportOnPr;

    @ConfigProperty(name = "linter.scope-to-changed-files", defaultValue = "true")
    boolean scopeToChangedFiles;

    @ConfigProperty(name = "linter.line-tolerance", defaultValue = "5")
    int lineTolerance;

    public boolean isEnabled() { return enabled; }
    public boolean isCheckstyleEnabled() { return checkstyleEnabled; }
    public boolean isPmdEnabled() { return pmdEnabled; }
    public boolean isSpotbugsEnabled() { return spotbugsEnabled; }
    public boolean isEslintEnabled() { return eslintEnabled; }
    public boolean isDotnetFormatEnabled() { return dotnetFormatEnabled; }
    public int getMaxFixIterations() { return maxFixIterations; }
    public boolean isFailOnNewIssues() { return failOnNewIssues; }
    public long getTimeoutMinutes() { return timeoutMinutes; }
    public boolean isReportOnPr() { return reportOnPr; }
    public boolean isScopeToChangedFiles() { return scopeToChangedFiles; }
    public int getLineTolerance() { return lineTolerance; }

    public boolean isLinterEnabled(String name) {
        return switch (name) {
            case "checkstyle" -> checkstyleEnabled;
            case "pmd" -> pmdEnabled;
            case "spotbugs" -> spotbugsEnabled;
            case "eslint" -> eslintEnabled;
            case "dotnet-format" -> dotnetFormatEnabled;
            default -> false;
        };
    }
}
