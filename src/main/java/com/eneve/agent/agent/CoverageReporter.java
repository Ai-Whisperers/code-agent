package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;

import com.eneve.agent.util.XmlParserFactory;

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
         */
        public String formatMarkdownComparison(CoverageSnapshot before) {
            if (before == null) {
                return """
                        ## Coverage Report

                        | Metric | Coverage |
                        |--------|----------|
                        | Lines | %.1f%% (%d/%d) |
                        | Branches | %.1f%% (%d/%d) |
                        | Methods | %.1f%% (%d/%d) |
                        | Classes | %.1f%% (%d/%d) |
                        """.formatted(
                        lineRate(), linesCovered, linesCovered + linesMissed,
                        branchRate(), branchesCovered, branchesCovered + branchesMissed,
                        methodRate(), methodsCovered, methodsCovered + methodsMissed,
                        classRate(), classesCovered, classesCovered + classesMissed);
            }

            return """
                    ## Coverage Report

                    | Metric | Before | After | Delta |
                    |--------|--------|-------|-------|
                    | Lines | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %s |
                    | Branches | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %s |
                    | Methods | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %s |
                    | Classes | %.1f%% (%d/%d) | %.1f%% (%d/%d) | %s |
                    """.formatted(
                    before.lineRate(), before.linesCovered, before.linesCovered + before.linesMissed,
                    lineRate(), linesCovered, linesCovered + linesMissed,
                    formatDelta(lineRate() - before.lineRate()),
                    before.branchRate(), before.branchesCovered, before.branchesCovered + before.branchesMissed,
                    branchRate(), branchesCovered, branchesCovered + branchesMissed,
                    formatDelta(branchRate() - before.branchRate()),
                    before.methodRate(), before.methodsCovered, before.methodsCovered + before.methodsMissed,
                    methodRate(), methodsCovered, methodsCovered + methodsMissed,
                    formatDelta(methodRate() - before.methodRate()),
                    before.classRate(), before.classesCovered, before.classesCovered + before.classesMissed,
                    classRate(), classesCovered, classesCovered + classesMissed,
                    formatDelta(classRate() - before.classRate()));
        }

        /**
         * Formats a baseline summary suitable for injection into the agent prompt.
         * Groups packages into three tiers: not tested (0%), low (&lt;50%), moderate (&lt;80%).
         */
        public String formatForPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Current Coverage Baseline\n\n");
            sb.append("| Metric | Coverage |\n|--------|----------|\n");
            sb.append("| Lines | %.1f%% (%d/%d) |\n".formatted(
                    lineRate(), linesCovered, linesCovered + linesMissed));
            sb.append("| Branches | %.1f%% (%d/%d) |\n".formatted(
                    branchRate(), branchesCovered, branchesCovered + branchesMissed));
            sb.append("| Methods | %.1f%% (%d/%d) |\n".formatted(
                    methodRate(), methodsCovered, methodsCovered + methodsMissed));
            sb.append("| Classes | %.1f%% (%d/%d) |\n".formatted(
                    classRate(), classesCovered, classesCovered + classesMissed));

            if (packages != null && !packages.isEmpty()) {
                List<PackageCoverage> notTested = packages.stream()
                        .filter(p -> p.lineRate() == 0.0)
                        .sorted((a, b) -> Integer.compare(b.linesMissed(), a.linesMissed()))
                        .limit(20)
                        .toList();

                List<PackageCoverage> lowCoverage = packages.stream()
                        .filter(p -> p.lineRate() > 0.0 && p.lineRate() < 50.0)
                        .sorted((a, b) -> Double.compare(a.lineRate(), b.lineRate()))
                        .limit(20)
                        .toList();

                List<PackageCoverage> moderate = packages.stream()
                        .filter(p -> p.lineRate() >= 50.0 && p.lineRate() < 80.0)
                        .sorted((a, b) -> Double.compare(a.lineRate(), b.lineRate()))
                        .limit(10)
                        .toList();

                if (!notTested.isEmpty()) {
                    sb.append("\n### Not tested at all — highest impact targets\n\n");
                    sb.append("| Package / Namespace | Lines missed |\n|---------------------|-------------|\n");
                    for (PackageCoverage pkg : notTested) {
                        sb.append("| `%s` | %d |\n".formatted(
                                pkg.name().replace('/', '.'), pkg.linesMissed()));
                    }
                }

                if (!lowCoverage.isEmpty()) {
                    sb.append("\n### Low coverage (< 50%) — prioritise these\n\n");
                    sb.append("| Package / Namespace | Line % | Covered | Missed |\n|---------------------|--------|---------|--------|\n");
                    for (PackageCoverage pkg : lowCoverage) {
                        sb.append("| `%s` | %.1f%% | %d | %d |\n".formatted(
                                pkg.name().replace('/', '.'),
                                pkg.lineRate(),
                                pkg.linesCovered(),
                                pkg.linesMissed()));
                    }
                }

                if (!moderate.isEmpty()) {
                    sb.append("\n### Moderate coverage (50–80%) — improve if time allows\n\n");
                    sb.append("| Package / Namespace | Line % | Covered | Missed |\n|---------------------|--------|---------|--------|\n");
                    for (PackageCoverage pkg : moderate) {
                        sb.append("| `%s` | %.1f%% | %d | %d |\n".formatted(
                                pkg.name().replace('/', '.'),
                                pkg.lineRate(),
                                pkg.linesCovered(),
                                pkg.linesMissed()));
                    }
                }
            }

            sb.append("\nUse this baseline to prioritise packages and namespaces with the lowest " +
                    "coverage. Focus first on untested code, then on packages below 50%, " +
                    "then improve packages between 50–80% if time allows.\n");
            return sb.toString();
        }

        private static String formatDelta(double delta) {
            if (delta > 0) return "+%.1f%%".formatted(delta);
            if (delta < 0) return "%.1f%%".formatted(delta);
            return "0.0%";
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

        String effectiveMavenHome = resolveMavenHome();
        String command = buildJacocoCommand(workspace, effectiveMavenHome);

        LOG.infof("CoverageReporter: running coverage (%s JaCoCo): %s",
                jacocoPresent ? "configured" : "injected into pom.xml", command);
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilderWithMaven(null, effectiveMavenHome, "sh", "-c", command)
                    .directory(workspace.getRoot().toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warnf("CoverageReporter: coverage run timed out after %d minutes", timeoutMinutes);
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
                } else {
                    String tail = output.length() > 2000 ? output.substring(output.length() - 2000) : output;
                    LOG.warnf("CoverageReporter: coverage run failed (build error, exit %d): %s",
                            proc.exitValue(), tail);
                }
                return null;
            }
            // Warn if any tests failed so callers can see it in the logs, but proceed.
            if (output.contains("[ERROR] Tests run:") || output.contains("BUILD FAILURE")) {
                LOG.warnf("CoverageReporter: some tests failed during coverage run — coverage reflects passing tests only");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("CoverageReporter: coverage measurement interrupted");
            return null;
        } catch (IOException e) {
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

    /**
     * Returns true if JaCoCo is declared in the project's {@code pom.xml}.
     */
    public boolean isJacocoPresent(WorkspaceContext workspace) {
        Path pom = workspace.getRoot().resolve("pom.xml");
        if (!Files.exists(pom)) return false;
        try {
            return Files.readString(pom).contains("jacoco");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Runs {@code mvn jacoco:prepare-agent test jacoco:report} and parses the resulting
     * XML report. Returns null if JaCoCo is not present in {@code pom.xml} or if the
     * report cannot be generated. Throws {@link RuntimeException} if tests fail (exit != 0),
     * so this doubles as a build validator when JaCoCo is available.
     */
    public CoverageSnapshot measureCoverage(WorkspaceContext workspace) {
        if (!isJacocoPresent(workspace)) {
            return null;
        }

        LOG.info("Running JaCoCo coverage measurement...");
        String effectiveMavenHome = resolveMavenHome();
        String command = buildJacocoCommand(workspace, effectiveMavenHome);
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilderWithMaven(null, effectiveMavenHome, "sh", "-c", command)
                    .directory(workspace.getRoot().toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes(), TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                throw new RuntimeException("JaCoCo coverage run timed out after " + timeoutMinutes() + " minutes");
            }
            if (proc.exitValue() != 0) {
                // Non-zero even with test.failure.ignore means a build/compile error.
                if (isJdkIncompatibilityError(output)) {
                    LOG.debugf("CoverageReporter: skipping coverage — JDK/compiler incompatibility");
                    return null;
                }
                String tail = output.length() > 3000 ? output.substring(output.length() - 3000) : output;
                throw new RuntimeException("Build error during coverage measurement (exit "
                        + proc.exitValue() + "):\n" + tail);
            }
            if (output.contains("[ERROR] Tests run:") || output.contains("BUILD FAILURE")) {
                LOG.warnf("CoverageReporter: some tests failed — coverage reflects passing tests only");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Coverage measurement interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start coverage process: " + e.getMessage(), e);
        }

        Path report = workspace.getRoot().resolve(JACOCO_REPORT_PATH);
        if (!Files.exists(report)) {
            LOG.warnf("JaCoCo report not found at %s despite successful test run", report);
            return null;
        }

        try {
            return parseReport(report);
        } catch (Exception e) {
            LOG.warnf("Failed to parse JaCoCo report: %s", e.getMessage());
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
