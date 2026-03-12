package com.eneve.agent.linter;

import java.nio.file.Path;

/**
 * Contract for a linter/SAST tool that can be run against a workspace.
 * Implementations are CDI beans discovered automatically by LinterService.
 */
public interface LinterRunner {

    String name();

    /**
     * Returns true if this linter can run on the given workspace
     * (e.g. Maven project detected for Checkstyle/PMD/SpotBugs).
     */
    boolean isApplicable(Path workspaceRoot);

    /**
     * Execute the linter and return parsed results.
     * Must not throw -- failures are captured in LinterResult.
     */
    LinterResult run(Path workspaceRoot, long timeoutMinutes);
}
