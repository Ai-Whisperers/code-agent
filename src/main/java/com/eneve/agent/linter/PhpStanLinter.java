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

/**
 * Runs PHPStan static analysis against PHP projects.
 *
 * <p>Applicable when a {@code composer.json} is present and either a PHPStan
 * configuration file ({@code phpstan.neon} / {@code phpstan.neon.dist}) exists,
 * or {@code phpstan/phpstan} is listed as a dependency in {@code composer.json}.
 *
 * <p>Process:
 * <ol>
 *   <li>Run {@code composer install --no-interaction --no-scripts} to ensure vendor is populated.</li>
 *   <li>Run {@code vendor/bin/phpstan analyse --error-format=json --no-progress}.</li>
 *   <li>Parse the JSON output into {@link LinterFinding} objects.</li>
 * </ol>
 */
@ApplicationScoped
public class PhpStanLinter implements LinterRunner {

    private static final Logger LOG = Logger.getLogger(PhpStanLinter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "phpstan";
    }

    @Override
    public boolean isApplicable(Path workspaceRoot) {
        if (!Files.exists(workspaceRoot.resolve("composer.json"))) {
            return false;
        }
        return hasPhpStanConfig(workspaceRoot) || hasPhpStanDependency(workspaceRoot);
    }

    @Override
    public LinterResult run(Path workspaceRoot, long timeoutMinutes) {
        LOG.info("Running PHPStan analysis...");
        try {
            if (!installDependencies(workspaceRoot, timeoutMinutes)) {
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Skipped: composer install failed");
            }

            String phpstanBin = resolvePhpStanBin(workspaceRoot);
            String command = phpstanBin + " analyse --error-format=json --no-progress 2>/dev/null; true";

            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", command)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(false);

            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes());
            String stderr = new String(proc.getErrorStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("PHPStan timed out");
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Timed out after " + timeoutMinutes + " minutes");
            }

            String jsonOutput = stdout.trim();
            if (jsonOutput.isEmpty() || !jsonOutput.startsWith("{")) {
                LOG.warnf("PHPStan produced no JSON output (exit %d). stderr: %s",
                        proc.exitValue(), LinterUtils.truncate(stderr));
                return new LinterResult(name(), Collections.emptyList(), false,
                        "No JSON output. stderr: " + LinterUtils.truncate(stderr));
            }

            List<LinterFinding> findings = parseJsonOutput(jsonOutput, workspaceRoot);
            LOG.infof("PHPStan found %d issues", findings.size());
            return new LinterResult(name(), findings, true, LinterUtils.truncate(jsonOutput));

        } catch (IOException | InterruptedException e) {
            LOG.warnf("PHPStan execution failed: %s", e.getMessage());
            return new LinterResult(name(), Collections.emptyList(), false, e.getMessage());
        }
    }

    private boolean installDependencies(Path workspaceRoot, long timeoutMinutes) {
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(
                            "sh", "-c", "composer install --no-interaction --no-scripts -q")
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("composer install timed out");
                return false;
            }
            if (proc.exitValue() != 0) {
                LOG.warnf("composer install failed (exit %d): %s",
                        proc.exitValue(), LinterUtils.truncate(output));
                return false;
            }
            return true;
        } catch (IOException | InterruptedException e) {
            LOG.warnf("composer install failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * PHPStan JSON output format:
     * <pre>
     * {
     *   "totals": { "errors": N, "file_errors": N },
     *   "files": {
     *     "/abs/path/to/File.php": {
     *       "errors": N,
     *       "messages": [
     *         { "message": "...", "line": 42, "ignorable": true }
     *       ]
     *     }
     *   },
     *   "errors": ["...global errors..."]
     * }
     * </pre>
     */
    private List<LinterFinding> parseJsonOutput(String jsonOutput, Path workspaceRoot) {
        List<LinterFinding> findings = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(jsonOutput);
            JsonNode files = root.path("files");
            if (!files.isObject()) return findings;

            files.fields().forEachRemaining(entry -> {
                String absolutePath = entry.getKey();
                String relativePath = LinterUtils.toRelativePath(absolutePath, workspaceRoot);
                JsonNode fileNode = entry.getValue();
                JsonNode messages = fileNode.path("messages");
                if (!messages.isArray()) return;

                for (JsonNode msg : messages) {
                    int line = msg.path("line").asInt(0);
                    String message = msg.path("message").asText("");
                    // PHPStan does not provide rule IDs in basic JSON output;
                    // use a generic identifier
                    String ruleId = "phpstan";

                    findings.add(new LinterFinding(name(), relativePath, line,
                            LinterFinding.SEVERITY_ERROR, ruleId, message));
                }
            });

            // Global (non-file) errors
            JsonNode globalErrors = root.path("errors");
            if (globalErrors.isArray()) {
                for (JsonNode err : globalErrors) {
                    String message = err.isTextual() ? err.asText() : err.path("message").asText("");
                    if (!message.isBlank()) {
                        findings.add(new LinterFinding(name(), "", 0,
                                LinterFinding.SEVERITY_ERROR, "phpstan", message));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse PHPStan JSON output: %s", e.getMessage());
        }
        return findings;
    }

    private static String resolvePhpStanBin(Path workspaceRoot) {
        Path vendorBin = workspaceRoot.resolve("vendor/bin/phpstan");
        if (Files.exists(vendorBin)) {
            return vendorBin.toAbsolutePath().toString();
        }
        return "phpstan";
    }

    private static boolean hasPhpStanConfig(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("phpstan.neon"))
                || Files.exists(workspaceRoot.resolve("phpstan.neon.dist"))
                || Files.exists(workspaceRoot.resolve("phpstan.dist.neon"));
    }

    private static boolean hasPhpStanDependency(Path workspaceRoot) {
        try {
            String composerJson = Files.readString(workspaceRoot.resolve("composer.json"));
            return composerJson.contains("\"phpstan/phpstan\"")
                    || composerJson.contains("\"phpstan/phpstan-strict-rules\"");
        } catch (IOException ignored) {
            return false;
        }
    }
}
