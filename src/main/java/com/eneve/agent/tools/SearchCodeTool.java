package com.eneve.agent.tools;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Provides grep-based code search over the cloned repository.
 * Allows the review agent to find callers, usages, and related code
 * beyond what the diff alone shows, reducing false positives.
 */
@ApplicationScoped
public class SearchCodeTool implements ToolExecutor {

    private static final long COMMAND_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 30_000;

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String pattern = (String) input.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "ERROR: 'pattern' parameter is required";
        }

        String searchPath = (String) input.getOrDefault("path", ".");
        String include = (String) input.get("include");

        try {
            workspace.resolve(searchPath);
        } catch (SecurityException e) {
            return "ERROR: " + e.getMessage();
        }

        StringBuilder cmd = new StringBuilder("grep -rn");
        if (include != null && !include.isBlank()) {
            cmd.append(" --include=").append(shellQuote(include));
        }
        cmd.append(" ").append(shellQuote(pattern));
        cmd.append(" ").append(shellQuote(searchPath));

        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder("sh", "-c", cmd.toString())
                    .directory(workspace.getRoot().toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                proc.destroyForcibly();
                return "ERROR: search_code timed out after " + COMMAND_TIMEOUT_SECONDS + " seconds";
            }

            int exitCode = proc.exitValue();
            if (exitCode == 1 && output.isBlank()) {
                return "No matches found for pattern: " + pattern;
            }
            if (exitCode > 1) {
                return "ERROR: grep exited with code " + exitCode + "\n" + output;
            }

            if (output.length() > MAX_OUTPUT_CHARS) {
                long lineCount = output.lines().count();
                output = output.substring(0, MAX_OUTPUT_CHARS);
                // Trim to the last complete line
                int lastNewline = output.lastIndexOf('\n');
                if (lastNewline > 0) {
                    output = output.substring(0, lastNewline);
                }
                output += "\n... [output truncated — " + lineCount + " total matches, showing first "
                        + output.lines().count() + "]";
            }

            return output;
        } catch (IOException | InterruptedException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
