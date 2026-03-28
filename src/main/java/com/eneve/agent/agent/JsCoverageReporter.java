package com.eneve.agent.agent;

import com.eneve.agent.agent.CoverageReporter.CoverageSnapshot;
import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Measures code coverage for JavaScript / TypeScript projects using Jest or Vitest.
 *
 * <p>Both runners can emit a Cobertura XML report which is parsed by
 * {@link CoberturaXmlParser} into a {@link CoverageSnapshot} — the same format
 * used by the Java (JaCoCo) and .NET (Coverlet) reporters.
 *
 * <p><b>Supported configurations:</b>
 * <ul>
 *   <li>Jest — detected via {@code jest.config.*} files or {@code jest} in {@code devDependencies}</li>
 *   <li>Vitest — detected via {@code vitest.config.*} files or {@code vitest} in {@code devDependencies}</li>
 * </ul>
 *
 * <p><b>Package managers:</b> npm (package-lock.json), Yarn (yarn.lock), pnpm (pnpm-lock.yaml).
 *
 * <p><b>Limitations:</b> Karma/Jasmine (classic Angular), Mocha, and Playwright component
 * tests are not supported — they use LCOV format which requires a separate parser.
 * Monorepo roots (Nx, Turborepo) are not automatically detected; run from the relevant
 * sub-package directory.
 */
@ApplicationScoped
public class JsCoverageReporter {

    private static final Logger LOG = Logger.getLogger(JsCoverageReporter.class);

    /** Maximum minutes reserved for the package-manager install step. */
    private static final long INSTALL_TIMEOUT_MINUTES = 10L;

    /**
     * Returns {@code true} when the workspace looks like a Jest or Vitest project.
     * Detection prefers explicit config files ({@code jest.config.*},
     * {@code vitest.config.*}) and falls back to checking {@code devDependencies} /
     * {@code dependencies} in {@code package.json}.
     */
    public boolean isApplicable(WorkspaceContext workspace) {
        Path root = workspace.getRoot();
        if (!Files.exists(root.resolve("package.json"))) return false;
        return detectRunner(root) != null;
    }

    /**
     * Installs dependencies, runs the test suite with coverage, and parses the
     * resulting Cobertura XML into a {@link CoverageSnapshot}.
     *
     * <p>A non-zero test exit code is treated as a soft failure: if a
     * {@code cobertura-coverage.xml} was still produced (some tests passed), the
     * partial report is accepted. This mirrors the behaviour of the Maven reporter
     * with {@code -Dmaven.test.failure.ignore=true}.
     *
     * @param workspace      the cloned workspace
     * @param timeoutMinutes maximum time for the full measurement (install + test run)
     * @return snapshot, or {@code null} if the project is not applicable or measurement fails
     */
    public CoverageSnapshot measureCoverage(WorkspaceContext workspace, long timeoutMinutes) {
        Path root = workspace.getRoot();
        if (!Files.exists(root.resolve("package.json"))) return null;

        String runner = detectRunner(root);
        if (runner == null) {
            LOG.debugf("JsCoverageReporter: no Jest/Vitest config found — skipping");
            return null;
        }

        String pkgManager = detectPackageManager(root);
        if (!install(root, pkgManager)) return null;

        // Vitest requires a coverage provider. Auto-inject @vitest/coverage-v8 when missing
        // so projects that have vitest but no coverage plugin still work.
        // The workspace is an ephemeral clone so modifying node_modules is safe.
        if ("vitest".equals(runner) && !hasVitestCoverageProvider(root)) {
            LOG.infof("JsCoverageReporter: Vitest coverage provider not found — injecting @vitest/coverage-v8");
            if (!injectVitestCoverageProvider(root, pkgManager)) {
                LOG.warnf("JsCoverageReporter: could not install @vitest/coverage-v8 — coverage skipped");
                return null;
            }
        }

        long testTimeout = Math.max(1L, timeoutMinutes - INSTALL_TIMEOUT_MINUTES);
        return runCoverage(root, runner, testTimeout);
    }

    // ── Runner detection ─────────────────────────────────────────────────

    /**
     * Returns {@code "jest"}, {@code "vitest"}, or {@code null}.
     * Config files take priority over package.json dependency entries.
     */
    private String detectRunner(Path root) {
        // Prefer explicit config files — strongest signal
        if (hasConfigFile(root, "vitest.config")) return "vitest";
        if (hasConfigFile(root, "jest.config"))   return "jest";

        // Fall back to package.json dependency names
        try {
            String pkg = Files.readString(root.resolve("package.json"));
            // Check scripts first (e.g. "test": "vitest")
            if (pkg.contains("\"vitest\"")) return "vitest";
            if (pkg.contains("\"jest\""))   return "jest";
        } catch (IOException e) {
            LOG.debugf("JsCoverageReporter: could not read package.json: %s", e.getMessage());
        }
        return null;
    }

    private static boolean hasConfigFile(Path root, String baseName) {
        for (String ext : new String[]{"ts", "mts", "cts", "js", "mjs", "cjs"}) {
            if (Files.exists(root.resolve(baseName + "." + ext))) return true;
        }
        return false;
    }

    // ── Package manager detection ─────────────────────────────────────────

    /**
     * Returns {@code "pnpm"}, {@code "yarn"}, or {@code "npm"} based on lockfile presence.
     */
    private String detectPackageManager(Path root) {
        if (Files.exists(root.resolve("pnpm-lock.yaml"))) return "pnpm";
        if (Files.exists(root.resolve("yarn.lock")))      return "yarn";
        return "npm";
    }

    // ── Install ──────────────────────────────────────────────────────────

    /**
     * Installs project dependencies using the detected package manager.
     */
    private boolean install(Path root, String pkgManager) {
        String installCmd = switch (pkgManager) {
            case "pnpm" -> "pnpm install --frozen-lockfile --ignore-scripts";
            case "yarn" -> "yarn install --frozen-lockfile --ignore-scripts";
            default     -> Files.exists(root.resolve("package-lock.json"))
                    ? "npm ci --ignore-scripts"
                    : "npm install --ignore-scripts";
        };

        LOG.infof("JsCoverageReporter: installing dependencies (%s): %s", pkgManager, installCmd);
        return runInstallCommand(root, installCmd, INSTALL_TIMEOUT_MINUTES);
    }

    /**
     * Installs {@code @vitest/coverage-v8} into the project's local {@code node_modules}
     * without publishing the change back to {@code package.json} when possible.
     * Since the workspace is an ephemeral clone the side-effects are irrelevant.
     */
    private boolean injectVitestCoverageProvider(Path root, String pkgManager) {
        String addCmd = switch (pkgManager) {
            case "pnpm" -> "pnpm add @vitest/coverage-v8 --save-dev";
            case "yarn" -> "yarn add @vitest/coverage-v8 --dev";
            default     -> "npm install --no-save @vitest/coverage-v8";
        };
        LOG.infof("JsCoverageReporter: injecting coverage provider: %s", addCmd);
        return runInstallCommand(root, addCmd, INSTALL_TIMEOUT_MINUTES);
    }

    private boolean runInstallCommand(Path root, String cmd, long timeoutMinutes) {
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", cmd)
                    .directory(root.toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warnf("JsCoverageReporter: command timed out after %d minutes: %s", timeoutMinutes, cmd);
                return false;
            }
            if (proc.exitValue() != 0) {
                String tail = output.length() > 1500 ? output.substring(output.length() - 1500) : output;
                LOG.warnf("JsCoverageReporter: command failed (exit %d) [%s]: %s",
                        proc.exitValue(), cmd, tail);
                return false;
            }
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warnf("JsCoverageReporter: command error [%s]: %s", cmd, e.getMessage());
            return false;
        }
    }

    // ── Test run ─────────────────────────────────────────────────────────

    private CoverageSnapshot runCoverage(Path root, String runner, long testTimeoutMinutes) {
        String cmd = buildCoverageCommand(runner);
        LOG.infof("JsCoverageReporter: running coverage (%s): %s", runner, cmd);

        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", cmd)
                    .directory(root.toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(testTimeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warnf("JsCoverageReporter: coverage run timed out after %d minutes", testTimeoutMinutes);
                return null;
            }
            if (proc.exitValue() != 0) {
                LOG.warnf("JsCoverageReporter: test run exited with %d — checking for partial report",
                        proc.exitValue());
                // Don't return null yet; a report may still have been written for passing tests.
            }

            // Debug: log last 500 chars of output on non-zero exit
            if (proc.exitValue() != 0 && LOG.isDebugEnabled()) {
                String tail = output.length() > 500 ? output.substring(output.length() - 500) : output;
                LOG.debugf("JsCoverageReporter: test output tail: %s", tail);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warnf("JsCoverageReporter: coverage run error: %s", e.getMessage());
            return null;
        }

        Path reportFile = CoberturaXmlParser.findReport(root);
        if (reportFile == null) {
            LOG.warnf("JsCoverageReporter: no cobertura-coverage.xml found under %s", root);
            return null;
        }

        try {
            CoverageSnapshot snap = CoberturaXmlParser.parse(reportFile);
            LOG.infof("JsCoverageReporter: parsed report — lines %.1f%%, branches %.1f%%",
                    snap.lineRate(), snap.branchRate());
            return snap;
        } catch (Exception e) {
            LOG.warnf("JsCoverageReporter: failed to parse Cobertura report: %s", e.getMessage());
            return null;
        }
    }

    private String buildCoverageCommand(String runner) {
        if ("vitest".equals(runner)) {
            // By this point the coverage provider is guaranteed to be present
            return "npx vitest run --coverage --coverage.provider=v8 --coverage.reporter=cobertura --passWithNoTests";
        }
        return "npx jest --coverage --coverageReporters=cobertura --passWithNoTests --forceExit";
    }

    /**
     * Returns {@code true} if {@code package.json} lists a known Vitest coverage provider.
     * Used to decide whether to auto-inject one before running.
     */
    private boolean hasVitestCoverageProvider(Path root) {
        try {
            String pkg = Files.readString(root.resolve("package.json"));
            return pkg.contains("@vitest/coverage-v8") || pkg.contains("@vitest/coverage-istanbul");
        } catch (IOException e) {
            return false;
        }
    }
}
