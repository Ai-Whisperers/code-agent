package com.eneve.agent.linter;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.util.ProcessHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DotnetFormatLinter implements LinterRunner {

    private static final Logger LOG = Logger.getLogger(DotnetFormatLinter.class);
    @Inject ObjectMapper mapper;

    /** MSBuild diagnostic pattern: path(line,col): severity CODE: message */
    private static final Pattern MSBUILD_DIAG = Pattern.compile(
            "^(.+?)\\((\\d+),\\d+\\):\\s+(warning|error)\\s+([A-Z]+\\d+):\\s+(.+)$",
            Pattern.MULTILINE);

    @Override
    public String name() {
        return "dotnet-format";
    }

    @Override
    public boolean isApplicable(Path workspaceRoot) {
        return hasDotnetProject(workspaceRoot);
    }

    @Override
    public LinterResult run(Path workspaceRoot, long timeoutMinutes) {
        LOG.info("Running dotnet format analysis...");
        try {
            if (!restore(workspaceRoot, timeoutMinutes)) {
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Skipped: dotnet restore failed");
            }

            Path reportDir = Files.createTempDirectory("dotnet-format-report-");
            String formatCommand = "dotnet format --verify-no-changes --report "
                    + reportDir.toAbsolutePath()
                    + " --no-restore -v diag 2>&1; true";

            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", formatCommand)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("dotnet format timed out");
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Timed out after " + timeoutMinutes + " minutes");
            }

            List<LinterFinding> findings = parseReport(reportDir, workspaceRoot);

            if (findings.isEmpty()) {
                findings = parseBuildDiagnostics(workspaceRoot, timeoutMinutes);
            }

            cleanupReportDir(reportDir);

            LOG.infof("dotnet-format found %d issues", findings.size());
            return new LinterResult(name(), findings, true, LinterUtils.truncate(output));

        } catch (IOException | InterruptedException e) {
            LOG.warnf("dotnet format execution failed: %s", e.getMessage());
            return new LinterResult(name(), Collections.emptyList(), false, e.getMessage());
        }
    }

    private boolean restore(Path workspaceRoot, long timeoutMinutes) {
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", "dotnet restore -q")
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("dotnet restore timed out");
                return false;
            }
            if (proc.exitValue() != 0) {
                LOG.warnf("dotnet restore failed (exit %d): %s",
                        proc.exitValue(), LinterUtils.truncate(output));
                return false;
            }
            return true;
        } catch (IOException | InterruptedException e) {
            LOG.warnf("dotnet restore failed: %s", e.getMessage());
            return false;
        }
    }

    private List<LinterFinding> parseReport(Path reportDir, Path workspaceRoot) {
        List<LinterFinding> findings = new ArrayList<>();
        try {
            Path reportFile = reportDir.resolve("format-report.json");
            if (!Files.exists(reportFile)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportDir, "*.json")) {
                    for (Path p : stream) {
                        reportFile = p;
                        break;
                    }
                }
            }

            if (!Files.exists(reportFile)) {
                LOG.debug("No dotnet format report file found");
                return findings;
            }

            String json = Files.readString(reportFile);
            if (json.isBlank()) return findings;

            JsonNode root = mapper.readTree(json);
            if (!root.isArray()) return findings;

            for (JsonNode entry : root) {
                String filePath = entry.path("FilePath").asText(
                        entry.path("filePath").asText(""));
                String relativePath = LinterUtils.toRelativePath(filePath, workspaceRoot);

                JsonNode changes = entry.path("FileChanges");
                if (!changes.isArray()) {
                    changes = entry.path("Changes");
                }
                if (!changes.isArray()) continue;

                for (JsonNode change : changes) {
                    int line = change.path("LineNumber").asInt(
                            change.path("lineNumber").asInt(0));
                    String diagId = change.path("DiagnosticId").asText(
                            change.path("diagnosticId").asText("format"));
                    String desc = change.path("FormatDescription").asText(
                            change.path("formatDescription").asText("Formatting issue"));

                    findings.add(new LinterFinding(name(), relativePath, line,
                            LinterFinding.SEVERITY_WARNING, diagId, desc));
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse dotnet format report: %s", e.getMessage());
        }
        return findings;
    }

    /**
     * Fallback: run {@code dotnet build} and parse MSBuild warning/error diagnostics from stdout.
     */
    private List<LinterFinding> parseBuildDiagnostics(Path workspaceRoot, long timeoutMinutes) {
        List<LinterFinding> findings = new ArrayList<>();
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", "dotnet build --no-restore -v q 2>&1; true")
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                return findings;
            }

            Matcher m = MSBUILD_DIAG.matcher(output);
            while (m.find()) {
                String file = LinterUtils.toRelativePath(m.group(1).trim(), workspaceRoot);
                int line = LinterUtils.parseIntSafe(m.group(2));
                String severity = "error".equals(m.group(3))
                        ? LinterFinding.SEVERITY_ERROR
                        : LinterFinding.SEVERITY_WARNING;
                String rule = m.group(4);
                String message = m.group(5).trim();

                findings.add(new LinterFinding(name(), file, line, severity, rule, message));
            }
        } catch (IOException | InterruptedException e) {
            LOG.warnf("dotnet build fallback failed: %s", e.getMessage());
        }
        return findings;
    }

    private static boolean hasDotnetProject(Path workspaceRoot) {
        if (Files.exists(workspaceRoot.resolve("Directory.Build.props"))) return true;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspaceRoot, "*.{sln,csproj}")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private static void cleanupReportDir(Path reportDir) {
        try {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportDir)) {
                for (Path p : stream) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(reportDir);
        } catch (IOException ignored) { }
    }
}
