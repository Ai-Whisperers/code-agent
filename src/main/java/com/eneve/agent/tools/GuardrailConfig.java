package com.eneve.agent.tools;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GuardrailConfig {

    @ConfigProperty(name = "run-fix.blocked-paths")
    List<String> blockedPaths;

    @ConfigProperty(name = "run-fix.allowed-commands")
    List<String> allowedCommands;

    @ConfigProperty(name = "run-fix.max-files-changed", defaultValue = "10")
    int maxFilesChanged;

    @ConfigProperty(name = "run-fix.max-lines-changed", defaultValue = "500")
    int maxLinesChanged;

    @ConfigProperty(name = "run-fix.max-loop-iterations", defaultValue = "50")
    int maxLoopIterations;

    public List<String> getBlockedPaths() { return blockedPaths; }
    public List<String> getAllowedCommands() { return allowedCommands; }
    public int getMaxFilesChanged() { return maxFilesChanged; }
    public int getMaxLinesChanged() { return maxLinesChanged; }
    public int getMaxLoopIterations() { return maxLoopIterations; }
}
