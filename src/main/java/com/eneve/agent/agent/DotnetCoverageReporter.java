package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.eneve.agent.agent.CoverageReporter.CoverageSnapshot;
import com.eneve.agent.util.DotnetWorkspaceProbe;
import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

/**
 * Measures code coverage for .NET projects using {@code dotnet test} with the
 * XPlat Code Coverage collector (Coverlet). Produces a Cobertura XML report
 * which is parsed into a {@link CoverageSnapshot}.
 *
 * <p>This is the .NET counterpart of {@link CoverageReporter} (Maven/JaCoCo).
 * Returns {@code null} gracefully when the project is not .NET-based or when
 * coverage measurement fails.
 */
@ApplicationScoped
public class DotnetCoverageReporter {

    private static final Logger LOG = Logger.getLogger(DotnetCoverageReporter.class);

    /**
     * Returns {@code true} when the workspace contains a {@code .sln}, {@code .csproj},
     * or {@code Directory.Build.props} file at the root level.
     */
    public boolean isApplicable(WorkspaceContext workspace) {
        return hasDotnetProject(workspace.getRoot());
    }

    /**
     * Runs {@code dotnet restore} followed by {@code dotnet test --collect:"XPlat Code Coverage"}
     * and parses the resulting Cobertura XML into a {@link CoverageSnapshot}.
     *
     * <p>Returns {@code null} if:
     * <ul>
     *   <li>The workspace has no .NET project files</li>
     *   <li>{@code dotnet restore} fails</li>
     *   <li>{@code dotnet test} fails or times out</li>
     *   <li>No {@code coverage.cobertura.xml} is produced</li>
     * </ul>
     * Never throws — all errors are logged as warnings.
     *
     * @param workspace      the cloned workspace
     * @param timeoutMinutes the maximum time to wait for {@code dotnet test}
     */
    public CoverageSnapshot measureCoverage(WorkspaceContext workspace, long timeoutMinutes) {
        if (!hasDotnetProject(workspace.getRoot())) {
            LOG.debugf("DotnetCoverageReporter: no .NET project found — skipping coverage measurement");
            return null;
        }

        if (!restore(workspace.getRoot(), timeoutMinutes)) {
            return null;
        }

        Path resultsDir;
        try {
            resultsDir = Files.createTempDirectory("dotnet-coverage-");
        } catch (IOException e) {
            LOG.warnf("DotnetCoverageReporter: failed to create temp results directory: %s", e.getMessage());
            return null;
        }

        String testCommand = "dotnet test --no-restore"
                + " --collect:\"XPlat Code Coverage\""
                + " --results-directory " + resultsDir.toAbsolutePath()
                + " -- DataCollectionRunSettings.DataCollectors.DataCollector.Configuration.Format=cobertura";

        LOG.infof("DotnetCoverageReporter: running coverage: %s", testCommand);
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", testCommand)
                    .directory(workspace.getRoot().toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warnf("DotnetCoverageReporter: coverage run timed out after %d minutes", timeoutMinutes);
                return null;
            }
            if (proc.exitValue() != 0) {
                String tail = output.length() > 2000 ? output.substring(output.length() - 2000) : output;
                LOG.warnf("DotnetCoverageReporter: coverage run failed (exit %d): %s", proc.exitValue(), tail);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("DotnetCoverageReporter: coverage measurement interrupted");
            return null;
        } catch (IOException e) {
            LOG.warnf("DotnetCoverageReporter: failed to start coverage process: %s", e.getMessage());
            return null;
        }

        Path reportFile = CoberturaXmlParser.findReport(resultsDir);
        if (reportFile == null) {
            LOG.warnf("DotnetCoverageReporter: no coverage.cobertura.xml found under %s", resultsDir);
            return null;
        }

        try {
            return CoberturaXmlParser.parse(reportFile);
        } catch (Exception e) {
            LOG.warnf("DotnetCoverageReporter: failed to parse Cobertura report: %s", e.getMessage());
            return null;
        } finally {
            deleteTempDir(resultsDir);
        }
    }

    // ─── dotnet restore ───────────────────────────────────────────────────

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
                LOG.warn("DotnetCoverageReporter: dotnet restore timed out");
                return false;
            }
            if (proc.exitValue() != 0) {
                String tail = output.length() > 1000 ? output.substring(output.length() - 1000) : output;
                LOG.warnf("DotnetCoverageReporter: dotnet restore failed (exit %d): %s",
                        proc.exitValue(), tail);
                return false;
            }
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warnf("DotnetCoverageReporter: dotnet restore error: %s", e.getMessage());
            return false;
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static boolean hasDotnetProject(Path workspaceRoot) {
        return DotnetWorkspaceProbe.hasDotnetAtRoot(workspaceRoot);
    }

    private static void deleteTempDir(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
