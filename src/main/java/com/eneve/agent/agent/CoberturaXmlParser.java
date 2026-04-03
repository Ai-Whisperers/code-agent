package com.eneve.agent.agent;

import com.eneve.agent.agent.CoverageReporter.CoverageSnapshot;
import com.eneve.agent.agent.CoverageReporter.PackageCoverage;
import com.eneve.agent.util.XmlParserFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared parser for Cobertura XML coverage reports.
 *
 * <p>Used by both {@link DotnetCoverageReporter} (.NET / Coverlet) and
 * {@link JsCoverageReporter} (Jest / Vitest). Hardened against XXE attacks.
 */
public final class CoberturaXmlParser {

    private CoberturaXmlParser() {}

    /**
     * Parses a Cobertura XML report into a {@link CoverageSnapshot}.
     *
     * <p>Aggregate line / branch / method / class counts are accumulated by walking
     * {@code <package>} → {@code <class>} → {@code <line>} elements. Branch coverage
     * is derived from {@code condition-coverage} attributes on {@code <line>} elements.
     *
     * @param reportFile path to the {@code cobertura-coverage.xml} file
     * @return populated snapshot; never {@code null}
     * @throws Exception if the file cannot be parsed
     */
    public static CoverageSnapshot parse(Path reportFile) throws Exception {
        // createSecureBuilder() disables all external entity resolution while allowing
        // DOCTYPE declarations — Vitest/Jest/Istanbul reports include one.
        DocumentBuilder builder = XmlParserFactory.createSecureBuilder();
        Document doc = builder.parse(reportFile.toFile());
        Element root = doc.getDocumentElement();

        int linesCovered = 0, linesMissed = 0;
        int branchesCovered = 0, branchesMissed = 0;
        int methodsCovered = 0, methodsMissed = 0;
        int classesCovered = 0, classesMissed = 0;

        List<PackageCoverage> packages = new ArrayList<>();

        NodeList packageNodes = root.getElementsByTagName("package");
        for (int p = 0; p < packageNodes.getLength(); p++) {
            Element pkg = (Element) packageNodes.item(p);
            String pkgName = pkg.getAttribute("name");
            int pkgLinesCovered = 0, pkgLinesMissed = 0;

            NodeList classNodes = pkg.getElementsByTagName("class");
            for (int c = 0; c < classNodes.getLength(); c++) {
                Element cls = (Element) classNodes.item(c);
                boolean hasAnyHit = false;

                NodeList lineNodes = cls.getElementsByTagName("line");
                for (int l = 0; l < lineNodes.getLength(); l++) {
                    Element line = (Element) lineNodes.item(l);
                    int hits = parseIntAttr(line, "hits");

                    if (hits > 0) {
                        linesCovered++;
                        pkgLinesCovered++;
                        hasAnyHit = true;
                    } else {
                        linesMissed++;
                        pkgLinesMissed++;
                    }

                    String condCoverage = line.getAttribute("condition-coverage");
                    if (!condCoverage.isEmpty()) {
                        int[] bc = parseBranchCoverage(condCoverage);
                        branchesCovered += bc[0];
                        branchesMissed  += bc[1] - bc[0];
                    }
                }

                NodeList methodNodes = cls.getElementsByTagName("method");
                for (int m = 0; m < methodNodes.getLength(); m++) {
                    Element method = (Element) methodNodes.item(m);
                    boolean methodHit = false;
                    NodeList mLines = method.getElementsByTagName("line");
                    for (int ml = 0; ml < mLines.getLength(); ml++) {
                        if (parseIntAttr((Element) mLines.item(ml), "hits") > 0) {
                            methodHit = true;
                            break;
                        }
                    }
                    if (methodHit) methodsCovered++;
                    else methodsMissed++;
                }

                if (hasAnyHit) classesCovered++;
                else classesMissed++;
            }

            if (pkgLinesCovered + pkgLinesMissed > 0) {
                packages.add(new PackageCoverage(pkgName, pkgLinesCovered, pkgLinesMissed));
            }
        }

        return new CoverageSnapshot(
                linesCovered, linesMissed,
                branchesCovered, branchesMissed,
                methodsCovered, methodsMissed,
                classesCovered, classesMissed,
                packages);
    }

    private static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(
            "node_modules", ".git", ".svn", "target", "bin", "obj", ".cache", "dist", "build");

    /**
     * Recursively searches {@code dir} for a Cobertura XML coverage report.
     *
     * <p>Accepts both common naming conventions:
     * <ul>
     *   <li>{@code cobertura-coverage.xml} — Jest / Vitest / Istanbul</li>
     *   <li>{@code coverage.cobertura.xml} — .NET Coverlet</li>
     * </ul>
     * Skips {@code node_modules}, {@code .git}, {@code target}, {@code bin},
     * {@code obj}, and other large build-artefact directories for speed.
     *
     * @return the path if found, or {@code null}
     */
    public static Path findReport(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    if (SKIP_DIRS.contains(entry.getFileName().toString())) continue;
                    Path found = findReport(entry);
                    if (found != null) return found;
                } else {
                    String name = entry.getFileName().toString();
                    if (name.equals("cobertura-coverage.xml") || name.equals("coverage.cobertura.xml")) {
                        return entry;
                    }
                }
            }
        } catch (IOException e) {
            // ignore — caller will handle null return
        }
        return null;
    }

    // ── private helpers ───────────────────────────────────────────────────

    /**
     * Parses a Cobertura branch-coverage string like {@code "50% (1/2)"}
     * into {@code [covered, total]}.
     */
    private static int[] parseBranchCoverage(String condCoverage) {
        int parenOpen  = condCoverage.indexOf('(');
        int slash      = condCoverage.indexOf('/');
        int parenClose = condCoverage.indexOf(')');
        if (parenOpen < 0 || slash < 0 || parenClose < 0) return new int[]{0, 0};
        try {
            int covered = Integer.parseInt(condCoverage.substring(parenOpen + 1, slash).trim());
            int total   = Integer.parseInt(condCoverage.substring(slash + 1, parenClose).trim());
            return new int[]{covered, total};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    private static int parseIntAttr(Element el, String attr) {
        String val = el.getAttribute(attr);
        if (val == null || val.isEmpty()) return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
