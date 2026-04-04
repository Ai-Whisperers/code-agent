package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;

import com.eneve.agent.util.XmlParserFactory;

import com.eneve.agent.util.JdkResolver;
import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.eneve.agent.settings.SettingsService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Measures JaCoCo code coverage before and after unit test generation.
 * If JaCoCo is not configured in pom.xml, all methods return null gracefully.
 */
@ApplicationScoped
public class CoverageReporter {

    private static final Logger LOG = Logger.getLogger(CoverageReporter.class);
    private static final String JACOCO_REPORT_PATH = "target/site/jacoco/jacoco.xml";
    private static final String JACOCO_VERSION_SETTING = "quality-report.jacoco.version";
    private static final String JACOCO_VERSION_DEFAULT = "0.8.12";

    @Inject SettingsService settings;

    private long timeoutMinutes() {
        return Long.parseLong(settings.get("generate-tests.job-timeout-minutes", "60"));
    }

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * Snapshot of aggregated JaCoCo coverage metrics, with optional per-package detail.
     */
    public record CoverageSnapshot(
            int linesCovered, int linesMissed,
            int branchesCovered, int branchesMissed,
            int methodsCovered, int methodsMissed,
            int classesCovered, int classesMissed,
            List<PackageCoverage> packages) {

        public double lineRate() {
            int total = linesCovered + linesMissed;
            return total > 0 ? 100.0 * linesCovered / total : 0.0;
        }

        public double branchRate() {
            int total = branchesCovered + branchesMissed;
            return total > 0 ? 100.0 * branchesCovered / total : 0.0;
        }

        public double methodRate() {
            int total = methodsCovered + methodsMissed;
            return total > 0 ? 100.0 * methodsCovered / total : 0.0;
        }

        public double classRate() {
            int total = classesCovered + classesMissed;
            return total > 0 ? 100.0 * classesCovered / total : 0.0;
        }

        /**
         * Formats a Markdown before/after comparison table. Pass null for {@code before}
         * to render a single-column "after" table.
         *
         * @see CoverageMarkdownFormatter#formatComparison(CoverageSnapshot, CoverageSnapshot)
         */
        public String formatMarkdownComparison(CoverageSnapshot before) {
            return CoverageMarkdownFormatter.formatComparison(this, before);
        }

        /**
         * Formats a baseline summary suitable for injection into the agent prompt.
         * Groups packages into three tiers: not tested (0%), low (&lt;50%), moderate (&lt;80%).
         *
         * @see CoverageMarkdownFormatter#formatForPrompt(CoverageSnapshot)
         */
        public String formatForPrompt() {
            return CoverageMarkdownFormatter.formatForPrompt(this);
        }
    }

    /**
     * Per-package line coverage summary.
     */
    public record PackageCoverage(String name, int linesCovered, int linesMissed) {
        public double lineRate() {
            int total = linesCovered + linesMissed;
            return total > 0 ? 100.0 * linesCovered / total : 0.0;
        }
    }

    // ─── Measurement ─────────────────────────────────────────────────────

    /**
     * Controls how a non-zero Maven exit code is handled by {@link #runMavenJacoco}.
     */
    private enum OnBuildFailure { LOG_AND_RETURN_NULL, THROW }

    /**
     * Measures JaCoCo coverage for quality report purposes.
     * If JaCoCo is already declared in {@code pom.xml}, the existing plugin configuration
     * is used. If not, the plugin configuration is injected into {@code pom.xml} so that
     * coverage is recorded and the project retains it going forward.
     *
     * <p>Returns {@code null} if the project is not Maven-based or if measurement fails.
     * Never throws — all errors are logged as warnings.
     *
     * @param workspace      the cloned workspace
     * @param timeoutMinutes the maximum time to wait for the Maven process
     */
    public CoverageSnapshot measureCoverageWithFallback(WorkspaceContext workspace, long timeoutMinutes) {
        Path pom = workspace.getRoot().resolve("pom.xml");
        if (!Files.exists(pom)) {
            LOG.debugf("CoverageReporter: no pom.xml found — skipping coverage measurement");
            return null;
        }

        boolean jacocoPresent = isJacocoPresent(workspace);
        if (!jacocoPresent) {
            String version = settings.get(JACOCO_VERSION_SETTING, JACOCO_VERSION_DEFAULT);
            try {
                injectJacocoPlugin(pom, version);
            } catch (IOException e) {
                LOG.warnf("CoverageReporter: failed to inject JaCoCo plugin into pom.xml: %s", e.getMessage());
                return null;
            }
        }

        LOG.infof("CoverageReporter: running coverage (%s JaCoCo)",
                jacocoPresent ? "configured" : "injected into pom.xml");
        CoverageSnapshot result = runMavenJacoco(workspace, timeoutMinutes, OnBuildFailure.LOG_AND_RETURN_NULL);
        if (result != null) return result;

        // If the run returned null it may be due to a JDK incompatibility.
        // Try again with the JDK version declared in pom.xml.
        String alternateJavaHome = JdkResolver.resolveForWorkspace(workspace.getRoot());
        if (alternateJavaHome != null && !alternateJavaHome.equals(System.getenv("JAVA_HOME"))) {
            LOG.infof("CoverageReporter: retrying coverage with alternate JDK: %s", alternateJavaHome);
            return runMavenJacocoWithJavaHome(workspace, timeoutMinutes, alternateJavaHome,
                    OnBuildFailure.LOG_AND_RETURN_NULL);
        }
        return null;
    }

    /**
     * Returns true if the JaCoCo Maven plugin is declared in the project's {@code pom.xml}.
     *
     * <p>Checks for {@code <artifactId>jacoco-maven-plugin</artifactId>} in the parsed DOM
     * rather than a plain string search, so comments and unrelated text are not matched.
     * Falls back to {@code false} on any parse error.
     */
    public boolean isJacocoPresent(WorkspaceContext workspace) {
        Path pom = workspace.getRoot().resolve("pom.xml");
        if (!Files.exists(pom)) return false;
        try {
            DocumentBuilder builder = XmlParserFactory.createSecureBuilder();
            Document doc = builder.parse(pom.toFile());
            NodeList artifactIds = doc.getElementsByTagName("artifactId");
            for (int i = 0; i < artifactIds.getLength(); i++) {
                if ("jacoco-maven-plugin".equals(artifactIds.item(i).getTextContent().trim())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            LOG.debugf("CoverageReporter: could not parse pom.xml to check for JaCoCo: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Runs {@code mvn jacoco:prepare-agent test jacoco:report} and parses the resulting
     * XML report. Returns null if JaCoCo is not present in {@code pom.xml} or if the
     * report cannot be generated. Throws {@link RuntimeException} if the build fails
     * (exit != 0), so this doubles as a build validator when JaCoCo is available.
     */
    public CoverageSnapshot measureCoverage(WorkspaceContext workspace) {
        if (!isJacocoPresent(workspace)) {
            return null;
        }
        LOG.info("Running JaCoCo coverage measurement...");
        return runMavenJacoco(workspace, timeoutMinutes(), OnBuildFailure.THROW);
    }

    /**
     * Shared Maven/JaCoCo runner used by both public measurement methods.
     *
     * <p>Builds the command, starts the process, waits for completion, checks the exit
     * code according to {@code onFailure}, and finally parses the XML report.
     *
     * @param onFailure {@link OnBuildFailure#LOG_AND_RETURN_NULL} to swallow build errors
     *                  (quality-report path), or {@link OnBuildFailure#THROW} to surface
     *                  them as a {@link RuntimeException} (test-generation path).
     */
    private CoverageSnapshot runMavenJacoco(WorkspaceContext workspace, long timeoutMinutes,
                                             OnBuildFailure onFailure) {
        return runMavenJacocoWithJavaHome(workspace, timeoutMinutes, null, onFailure);
    }

    /**
     * Like {@link #runMavenJacoco} but overrides {@code JAVA_HOME} for the child process.
     * Pass {@code null} to use the agent's default JDK.
     */
    private CoverageSnapshot runMavenJacocoWithJavaHome(WorkspaceContext workspace, long timeoutMinutes,
                                                         String javaHome, OnBuildFailure onFailure) {
        String effectiveMavenHome = resolveMavenHome();
        String command = buildJacocoCommand(workspace, effectiveMavenHome);
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilderWithMaven(javaHome, effectiveMavenHome, "sh", "-c", command)
                    .directory(workspace.getRoot().toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                String msg = "JaCoCo coverage run timed out after " + timeoutMinutes + " minutes";
                if (onFailure == OnBuildFailure.THROW) throw new RuntimeException(msg);
                LOG.warnf("CoverageReporter: %s", msg);
                return null;
            }

            if (proc.exitValue() != 0) {
                // Non-zero even with test.failure.ignore means a build/compile error — no report possible.
                // Detect known permanent incompatibilities and log them concisely at DEBUG to avoid
                // flooding the logs on every quality report run for projects that will never compile.
                if (isJdkIncompatibilityError(output)) {
                    LOG.debugf("CoverageReporter: skipping coverage for %s — JDK/compiler incompatibility " +
                            "(project requires a different Java version than the one running the agent)",
                            workspace.getRoot().getFileName());
                    return null;
                }
                int tailLen = onFailure == OnBuildFailure.THROW ? 3000 : 2000;
                String tail = output.length() > tailLen ? output.substring(output.length() - tailLen) : output;
                if (onFailure == OnBuildFailure.THROW) {
                    throw new RuntimeException("Build error during coverage measurement (exit "
                            + proc.exitValue() + "):\n" + tail);
                }
                LOG.warnf("CoverageReporter: coverage run failed (build error, exit %d): %s",
                        proc.exitValue(), tail);
                return null;
            }

            if (output.contains("[ERROR] Tests run:") || output.contains("BUILD FAILURE")) {
                LOG.warnf("CoverageReporter: some tests failed during coverage run — coverage reflects passing tests only");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (onFailure == OnBuildFailure.THROW) throw new RuntimeException("Coverage measurement interrupted", e);
            LOG.warnf("CoverageReporter: coverage measurement interrupted");
            return null;
        } catch (IOException e) {
            if (onFailure == OnBuildFailure.THROW) throw new RuntimeException("Failed to start coverage process: " + e.getMessage(), e);
            LOG.warnf("CoverageReporter: failed to start coverage process: %s", e.getMessage());
            return null;
        }

        Path report = workspace.getRoot().resolve(JACOCO_REPORT_PATH);
        if (!Files.exists(report)) {
            LOG.warnf("CoverageReporter: JaCoCo report not found at %s despite successful test run", report);
            return null;
        }

        try {
            return parseReport(report);
        } catch (Exception e) {
            LOG.warnf("CoverageReporter: failed to parse JaCoCo report: %s", e.getMessage());
            return null;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private String resolveMavenHome() {
        String val = settings.get("build.maven-home", "");
        return val.isBlank() ? null : val;
    }

    /**
     * Builds the {@code mvn jacoco:prepare-agent test jacoco:report} shell command.
     * Uses the fully-qualified goal form so Maven does not need to resolve the "jacoco"
     * prefix from plugin groups — works even when the plugin is not yet in the local repo.
     * {@code -Dmaven.test.failure.ignore=true} lets Maven reach jacoco:report even when
     * environment-sensitive integration tests fail in the sandbox.
     */
    private String buildJacocoCommand(WorkspaceContext workspace, String effectiveMavenHome) {
        String jacocoVersion = settings.get(JACOCO_VERSION_SETTING, JACOCO_VERSION_DEFAULT);
        String jacocoGoal = "org.jacoco:jacoco-maven-plugin:" + jacocoVersion;
        return ProcessHelper.mvn(workspace.getRoot(), effectiveMavenHome)
                + " " + jacocoGoal + ":prepare-agent test " + jacocoGoal + ":report"
                + " -q -Dmaven.test.failure.ignore=true";
    }

    /**
     * Returns true when the Maven output indicates a permanent JDK/compiler version
     * incompatibility — i.e. the project cannot be compiled by the JDK running the agent.
     * These failures are not transient and should not produce a WARN on every run.
     */
    private static boolean isJdkIncompatibilityError(String output) {
        if (output == null) return false;
        return output.contains("NoSuchFieldError")
                || output.contains("NoSuchMethodError")
                || output.contains("UnsupportedClassVersionError")
                || output.contains("Fatal error compiling")
                || output.contains("release version")
                || output.contains("source release")
                || output.contains("--release");
    }

    // ─── POM manipulation ────────────────────────────────────────────────

    /**
     * Injects the JaCoCo Maven plugin into {@code pom.xml} when it is not already declared.
     * Handles three cases:
     * <ol>
     *   <li>A {@code <plugins>} block already exists → plugin is appended inside it.</li>
     *   <li>A {@code <build>} block exists but no {@code <plugins>} → a {@code <plugins>}
     *       wrapper is added inside {@code <build>}.</li>
     *   <li>Neither exists → a full {@code <build><plugins>…</plugins></build>} section is
     *       inserted before the closing {@code </project>} tag.</li>
     * </ol>
     */
    private void injectJacocoPlugin(Path pom, String version) throws IOException {
        String content = Files.readString(pom);

        String pluginXml = """
                        <plugin>
                            <groupId>org.jacoco</groupId>
                            <artifactId>jacoco-maven-plugin</artifactId>
                            <version>%s</version>
                            <executions>
                                <execution>
                                    <goals>
                                        <goal>prepare-agent</goal>
                                    </goals>
                                </execution>
                                <execution>
                                    <id>report</id>
                                    <phase>test</phase>
                                    <goals>
                                        <goal>report</goal>
                                    </goals>
                                </execution>
                            </executions>
                        </plugin>
                """.formatted(version);

        String updated;
        if (content.contains("</plugins>")) {
            updated = content.replace("</plugins>", pluginXml + "    </plugins>");
        } else if (content.contains("</build>")) {
            String pluginsBlock = "    <plugins>\n" + pluginXml + "    </plugins>\n    ";
            updated = content.replace("</build>", pluginsBlock + "</build>");
        } else {
            String buildBlock = "\n    <build>\n        <plugins>\n" + pluginXml + "        </plugins>\n    </build>\n";
            updated = content.replace("</project>", buildBlock + "</project>");
        }

        Files.writeString(pom, updated);
        LOG.infof("CoverageReporter: injected JaCoCo plugin %s into pom.xml", version);
    }

    // ─── Parsing ─────────────────────────────────────────────────────────

    private CoverageSnapshot parseReport(Path reportFile) throws Exception {
        // createSecureBuilder() already disables all external entity resolution (DTD, schemas).
        DocumentBuilder builder = XmlParserFactory.createSecureBuilder();

        Document doc = builder.parse(reportFile.toFile());
        Element root = doc.getDocumentElement();

        int linesCovered = 0, linesMissed = 0;
        int branchesCovered = 0, branchesMissed = 0;
        int methodsCovered = 0, methodsMissed = 0;
        int classesCovered = 0, classesMissed = 0;

        // Root-level <counter> elements give the aggregate totals
        NodeList rootCounters = root.getChildNodes();
        for (int i = 0; i < rootCounters.getLength(); i++) {
            if (!(rootCounters.item(i) instanceof Element el)) continue;
            if (!"counter".equals(el.getTagName())) continue;

            int covered = Integer.parseInt(el.getAttribute("covered"));
            int missed = Integer.parseInt(el.getAttribute("missed"));
            switch (el.getAttribute("type")) {
                case "LINE" -> { linesCovered = covered; linesMissed = missed; }
                case "BRANCH" -> { branchesCovered = covered; branchesMissed = missed; }
                case "METHOD" -> { methodsCovered = covered; methodsMissed = missed; }
                case "CLASS" -> { classesCovered = covered; classesMissed = missed; }
                default -> { /* INSTRUCTION, COMPLEXITY — not used */ }
            }
        }

        // Per-package <counter type="LINE"> for low-coverage identification
        List<PackageCoverage> packages = new ArrayList<>();
        NodeList packageNodes = root.getElementsByTagName("package");
        for (int i = 0; i < packageNodes.getLength(); i++) {
            Element pkg = (Element) packageNodes.item(i);
            String name = pkg.getAttribute("name");
            int pkgLinesCovered = 0, pkgLinesMissed = 0;

            NodeList pkgCounters = pkg.getChildNodes();
            for (int j = 0; j < pkgCounters.getLength(); j++) {
                if (!(pkgCounters.item(j) instanceof Element counter)) continue;
                if (!"counter".equals(counter.getTagName())) continue;
                if ("LINE".equals(counter.getAttribute("type"))) {
                    pkgLinesCovered = Integer.parseInt(counter.getAttribute("covered"));
                    pkgLinesMissed = Integer.parseInt(counter.getAttribute("missed"));
                }
            }
            if (pkgLinesCovered + pkgLinesMissed > 0) {
                packages.add(new PackageCoverage(name, pkgLinesCovered, pkgLinesMissed));
            }
        }

        return new CoverageSnapshot(
                linesCovered, linesMissed,
                branchesCovered, branchesMissed,
                methodsCovered, methodsMissed,
                classesCovered, classesMissed,
                packages);
    }
}
