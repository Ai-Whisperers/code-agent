package com.eneve.agent.tools;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RunCommandTool implements ToolExecutor {

    private static final long COMMAND_TIMEOUT_MINUTES = 15;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    @Inject
    GuardrailConfig guardrails;

    @Inject
    SettingsService settings;

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String command = (String) input.get("command");
        if (command == null || command.isBlank()) {
            return "ERROR: 'command' parameter is required";
        }

        if (!isAllowed(command)) {
            return "ERROR: Command not allowed. Allowed prefixes: " + guardrails.getAllowedCommands();
        }

        try {
            String javaHome = settings.get("build.java-home", "");
            String mavenHome = settings.get("build.maven-home", "");
            ProcessBuilder pb = ProcessHelper.cleanBuilderWithMaven(
                            javaHome.isBlank() ? null : javaHome,
                            mavenHome.isBlank() ? null : mavenHome,
                            "sh", "-c", command)
                    .directory(workspace.getRoot().toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                return "ERROR: Command timed out after " + COMMAND_TIMEOUT_MINUTES + " minutes";
            }

            if (output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(0, MAX_OUTPUT_CHARS)
                        + "\n... [output truncated at " + MAX_OUTPUT_CHARS + " chars]";
            }

            return "Exit code: " + proc.exitValue() + "\n" + output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: " + e.getMessage();
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private boolean isAllowed(String command) {
        String trimmed = command.trim();
        return guardrails.getAllowedCommands().stream()
                .anyMatch(allowed -> trimmed.startsWith(allowed));
    }
}
