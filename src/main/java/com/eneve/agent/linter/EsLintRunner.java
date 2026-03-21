package com.eneve.agent.linter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.eneve.agent.util.ProcessHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EsLintRunner implements LinterRunner {

    private static final Logger LOG = Logger.getLogger(EsLintRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "eslint";
    }

    @Override
    public boolean isApplicable(Path workspaceRoot) {
        if (!Files.exists(workspaceRoot.resolve("package.json"))) {
            return false;
        }
        return hasEslintConfig(workspaceRoot);
    }

    @Override
    public LinterResult run(Path workspaceRoot, long timeoutMinutes) {
        LOG.info("Running ESLint analysis...");
        try {
            String installOutput = installDependencies(workspaceRoot, timeoutMinutes);
            if (installOutput == null) {
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Skipped: npm install failed");
            }

            String eslintCommand = "npx eslint . --format json --no-error-on-unmatched-pattern 2>/dev/null; true";

            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", eslintCommand)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(false);

            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes());
            String stderr = new String(proc.getErrorStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("ESLint timed out");
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Timed out after " + timeoutMinutes + " minutes");
            }

            String jsonOutput = stdout.trim();
            if (jsonOutput.isEmpty() || !jsonOutput.startsWith("[")) {
                LOG.warnf("ESLint produced no JSON output (exit %d). stderr: %s",
                        proc.exitValue(), LinterUtils.truncate(stderr));
                return new LinterResult(name(), Collections.emptyList(), false,
                        "No JSON output. stderr: " + LinterUtils.truncate(stderr));
            }

            List<LinterFinding> findings = parseJsonOutput(jsonOutput, workspaceRoot);
            LOG.infof("ESLint found %d issues", findings.size());
            return new LinterResult(name(), findings, true, LinterUtils.truncate(jsonOutput));

        } catch (IOException | InterruptedException e) {
            LOG.warnf("ESLint execution failed: %s", e.getMessage());
            return new LinterResult(name(), Collections.emptyList(), false, e.getMessage());
        }
    }

    private String installDependencies(Path workspaceRoot, long timeoutMinutes) {
        String command = Files.exists(workspaceRoot.resolve("package-lock.json"))
                ? "npm ci --ignore-scripts"
                : "npm install --ignore-scripts";
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", command)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("npm install timed out");
                return null;
            }
            if (proc.exitValue() != 0) {
                LOG.warnf("npm install failed (exit %d): %s", proc.exitValue(), LinterUtils.truncate(output));
                return null;
            }
            return output;
        } catch (IOException | InterruptedException e) {
            LOG.warnf("npm install failed: %s", e.getMessage());
            return null;
        }
    }

    private List<LinterFinding> parseJsonOutput(String jsonOutput, Path workspaceRoot) {
        List<LinterFinding> findings = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(jsonOutput);
            if (!root.isArray()) return findings;

            for (JsonNode fileNode : root) {
                String filePath = fileNode.path("filePath").asText("");
                String relativePath = LinterUtils.toRelativePath(filePath, workspaceRoot);

                JsonNode messages = fileNode.path("messages");
                if (!messages.isArray()) continue;

                for (JsonNode msg : messages) {
                    int line = msg.path("line").asInt(0);
                    int eslintSeverity = msg.path("severity").asInt(0);
                    String ruleId = msg.path("ruleId").asText("unknown");
                    String message = msg.path("message").asText("");

                    String severity = switch (eslintSeverity) {
                        case 2 -> LinterFinding.SEVERITY_ERROR;
                        case 1 -> LinterFinding.SEVERITY_WARNING;
                        default -> LinterFinding.SEVERITY_INFO;
                    };

                    findings.add(new LinterFinding(name(), relativePath, line, severity, ruleId, message));
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse ESLint JSON output: %s", e.getMessage());
        }
        return findings;
    }

    private static boolean hasEslintConfig(Path workspaceRoot) {
        String[] configFiles = {
                "eslint.config.js", "eslint.config.mjs", "eslint.config.cjs", "eslint.config.ts",
                ".eslintrc.js", ".eslintrc.cjs", ".eslintrc.yaml", ".eslintrc.yml", ".eslintrc.json"
        };
        for (String name : configFiles) {
            if (Files.exists(workspaceRoot.resolve(name))) return true;
        }

        try {
            String packageJson = Files.readString(workspaceRoot.resolve("package.json"));
            if (packageJson.contains("\"eslintConfig\"")) return true;
        } catch (IOException ignored) { }

        return false;
    }
}
