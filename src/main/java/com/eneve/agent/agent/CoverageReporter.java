package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.eneve.agent.workspace.WorkspaceContext;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Measures JaCoCo code coverage before and after unit test generation.
 * If JaCoCo is not configured in pom.xml, all methods return null gracefully.
 */
@ApplicationScoped
public class CoverageReporter {

    private static final Logger LOG = Logger.getLogger(CoverageReporter.class);
    private static final String JACOCO_REPORT_PATH = "target/site/jacoco/jacoco.xml";

    @ConfigProperty(name = "generate-tests.job-timeout-minutes", defaultValue = "60")
    long timeoutMinutes;

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
         * Formats a compact baseline summary suitable for injection into the agent prompt.
         */
        public String formatForPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Current Coverage Baseline (JaCoCo)\n\n");
            sb.append("| Metric | Coverage |\n|--------|----------|\n");
            sb.append("| Lines | %.1f%% (%d/%d) |\n".formatted(
                    lineRate(), linesCovered, linesCovered + linesMissed));
            sb.append("| Branches | %.1f%% (%d/%d) |\n".formatted(
                    branchRate(), branchesCovered, branchesCovered + branchesMissed));
            sb.append("| Methods | %.1f%% (%d/%d) |\n".formatted(
                    methodRate(), methodsCovered, methodsCovered + methodsMissed));
            sb.append("| Classes | %.1f%% (%d/%d) |\n".formatted(
                    classRate(), classesCovered, classesCovered + classesMissed));

            List<PackageCoverage> uncovered = packages.stream()
                    .filter(p -> p.lineRate() < 20.0)
                    .sorted((a, b) -> Double.compare(a.lineRate(), b.lineRate()))
                    .limit(15)
                    .toList();

            if (!uncovered.isEmpty()) {
                sb.append("\n### Packages with lowest line coverage (prioritize these)\n\n");
                for (PackageCoverage pkg : uncovered) {
                    sb.append("- `%s` — %.1f%% lines covered (%d/%d)\n".formatted(
                            pkg.name().replace('/', '.'),
                            pkg.lineRate(),
                            pkg.linesCovered(),
                            pkg.linesCovered() + pkg.linesMissed()));
                }
            }

            sb.append("\nUse this baseline to prioritise classes and packages that currently have the " +
                    "lowest coverage. Focus on increasing line and branch coverage substantially.\n");
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
        String command = "mvn jacoco:prepare-agent test jacoco:report -q";
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
                    .directory(workspace.getRoot().toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                throw new RuntimeException("JaCoCo coverage run timed out after " + timeoutMinutes + " minutes");
            }
            if (proc.exitValue() != 0) {
                String tail = output.length() > 3000 ? output.substring(output.length() - 3000) : output;
                throw new RuntimeException("Tests failed during coverage measurement (exit "
                        + proc.exitValue() + "):\n" + tail);
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

    // ─── Parsing ─────────────────────────────────────────────────────────

    private CoverageSnapshot parseReport(Path reportFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Harden against XXE attacks — JaCoCo reports reference an external DTD
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://xml.org/sax/features/validation", false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            // Suppress all external entity resolution (DTD, schemas)
            return new org.xml.sax.InputSource(new java.io.StringReader(""));
        });

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
