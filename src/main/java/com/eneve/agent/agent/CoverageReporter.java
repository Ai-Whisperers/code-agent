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
        String command = buildJacocoCommand(workspace, effectiveMavenHome, isJacocoPresent(workspace));
        LOG.infof("CoverageReporter: running command: %s", command);
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
                String errorLines = extractErrorLines(output);
                if (onFailure == OnBuildFailure.THROW) {
                    throw new RuntimeException("Build error during coverage measurement (exit "
                            + proc.exitValue() + "):\n" + errorLines);
                }
                LOG.warnf("CoverageReporter: coverage run failed (build error, exit %d, total output %d chars):\n%s",
                        proc.exitValue(), output.length(), errorLines);
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
     * Builds the Maven JaCoCo coverage command.
     *
     * <p>When JaCoCo is already declared in the POM ({@code jacocoAlreadyInPom=true}), the
     * POM's own {@code prepare-agent} execution fires automatically during the {@code test}
     * lifecycle. Invoking {@code prepare-agent} again on the CLI would attach a <em>second</em>
     * JaCoCo agent to the forked test JVM — two agents writing to the same {@code jacoco.exec}
     * file cause a JVM crash (exit 134 / SIGABRT). In this case we only need to run
     * {@code test} (the POM handles agent injection) and then {@code jacoco:report} explicitly
     * to ensure the XML report is generated regardless of which report phase is configured.
     *
     * <p>When JaCoCo was injected by us ({@code jacocoAlreadyInPom=false}), we must invoke
     * {@code prepare-agent} explicitly because the injected plugin has no bound lifecycle
     * execution for that goal.
     */
    private String buildJacocoCommand(WorkspaceContext workspace, String effectiveMavenHome,
                                      boolean jacocoAlreadyInPom) {
        String jacocoVersion = settings.get(JACOCO_VERSION_SETTING, JACOCO_VERSION_DEFAULT);
        String jacocoGoal = "org.jacoco:jacoco-maven-plugin:" + jacocoVersion;
        String excludes = buildQuarkusTestExcludes(workspace.getRoot());
        String goals = jacocoAlreadyInPom
                // POM already binds prepare-agent — just run tests then generate the report.
                ? "test " + jacocoGoal + ":report"
                // We injected JaCoCo — must invoke prepare-agent explicitly before tests.
                : jacocoGoal + ":prepare-agent test " + jacocoGoal + ":report";
        return ProcessHelper.mvn(workspace.getRoot(), effectiveMavenHome)
                + " " + goals
                // --no-transfer-progress suppresses download noise without hiding error output.
                // Intentionally NOT using -q (quiet): quiet mode suppresses application startup
                // errors (logged at INFO) making it impossible to detect and classify failures.
                + " --no-transfer-progress -Dmaven.test.failure.ignore=true"
                + excludes;
    }

    /**
     * Scans the workspace for test classes annotated with {@code @QuarkusTest} and returns
     * a Surefire {@code -Dexcludes} argument that skips them.
     *
     * <p>Quarkus integration tests boot the full application and require live infrastructure
     * (database, message broker, etc.) that is not available in the quality-report sandbox.
     * Excluding them lets the plain unit tests run and contribute to coverage without the
     * build crashing on application startup.
     *
     * <p>Returns an empty string when no {@code @QuarkusTest} classes are found (non-Quarkus
     * projects are unaffected).
     */
    private static String buildQuarkusTestExcludes(Path projectRoot) {
        Path testSrc = projectRoot.resolve("src/test/java");
        if (!Files.exists(testSrc)) return "";
        try {
            String patterns = Files.walk(testSrc)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("@QuarkusTest");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> {
                        // Convert absolute path to a **/<ClassName>.class glob for Surefire
                        String name = p.getFileName().toString().replace(".java", ".class");
                        return "**/" + name;
                    })
                    .collect(java.util.stream.Collectors.joining(","));

            if (patterns.isEmpty()) return "";
            LOG.infof("CoverageReporter: excluding @QuarkusTest classes from coverage run: %s", patterns);
            return " -Dexcludes='" + patterns + "'";
        } catch (IOException e) {
            LOG.debugf("CoverageReporter: could not scan for @QuarkusTest classes: %s", e.getMessage());
            return "";
        }
    }

    /**
     * Extracts all {@code [ERROR]} and {@code [WARNING]} lines from Maven output, plus the
     * last 20 lines for stack-trace context. This gives a compact, signal-rich view of the
     * failure regardless of total output size — far more useful than a head/tail character
     * slice which may miss the actual root-cause message buried in the middle.
     */
    private static String extractErrorLines(String output) {
        if (output == null) return "(no output)";
        String[] lines = output.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("[ERROR]") || line.startsWith("[WARNING]")) {
                sb.append(line).append('\n');
            }
        }
        // Always append the last 20 lines for Maven lifecycle context
        int start = Math.max(0, lines.length - 20);
        sb.append("--- last ").append(lines.length - start).append(" lines ---\n");
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /**
     * Returns true when the Maven output indicates a permanent, non-transient build failure
     * that will recur on every run in the sandbox — JDK/compiler incompatibilities or
     * Quarkus application startup failures caused by missing infrastructure (DB, broker).
     * These are logged at DEBUG rather than WARN to avoid flooding logs on every quality run.
     */
    private static boolean isJdkIncompatibilityError(String output) {
        if (output == null) return false;
        // JDK / compiler version mismatches — match only error-context phrases, not POM content.
        // "release version" and "source release" appear in javac error messages.
        // "--release N" as a bare compiler flag error (not inside a POM <release> tag).
        if (output.contains("NoSuchFieldError")
                || output.contains("NoSuchMethodError")
                || output.contains("UnsupportedClassVersionError")
                || output.contains("Fatal error compiling")
                || output.contains("release version ")
                || output.contains("source release ")
                || output.contains("error: --release")) {
            return true;
        }
        return false;
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
