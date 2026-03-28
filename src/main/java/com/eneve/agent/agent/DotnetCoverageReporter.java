package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.eneve.agent.agent.CoverageReporter.CoverageSnapshot;
import com.eneve.agent.agent.CoverageReporter.PackageCoverage;
import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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

        Path reportFile = findCoberturaReport(resultsDir);
        if (reportFile == null) {
            LOG.warnf("DotnetCoverageReporter: no coverage.cobertura.xml found under %s", resultsDir);
            return null;
        }

        try {
            return parseCoberturaReport(reportFile);
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

    // ─── Report discovery ─────────────────────────────────────────────────

    /**
     * Recursively searches {@code dir} for the first {@code coverage.cobertura.xml} file.
     */
    private Path findCoberturaReport(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    Path found = findCoberturaReport(entry);
                    if (found != null) return found;
                } else if (entry.getFileName().toString().equals("coverage.cobertura.xml")) {
                    return entry;
                }
            }
        } catch (IOException e) {
            LOG.warnf("DotnetCoverageReporter: error searching for Cobertura report: %s", e.getMessage());
        }
        return null;
    }

    // ─── Parsing ─────────────────────────────────────────────────────────

    /**
     * Parses a Cobertura XML report into a {@link CoverageSnapshot}.
     *
     * <p>Cobertura XML root attributes supply aggregate rates; per-line and per-branch counts
     * are accumulated from {@code <package>} → {@code <class>} → {@code <line>} elements.
     */
    private CoverageSnapshot parseCoberturaReport(Path reportFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://xml.org/sax/features/validation", false);
        // Cobertura XML does not use external DTDs — disallow DOCTYPE entirely
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader("")));

        Document doc = builder.parse(reportFile.toFile());
        Element root = doc.getDocumentElement();

        // Aggregate line / branch counts by walking <package> → <class> → <line> elements
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

                // Count covered/missed classes by checking if any line has hits > 0
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

                    // Branch coverage: "condition-coverage" attribute looks like "50% (1/2)"
                    String condCoverage = line.getAttribute("condition-coverage");
                    if (!condCoverage.isEmpty()) {
                        int[] bc = parseBranchCoverage(condCoverage);
                        branchesCovered += bc[0];
                        branchesMissed  += bc[1] - bc[0];
                    }
                }

                // Count methods from <method> elements within the class
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

    /**
     * Parses a Cobertura branch-coverage attribute like {@code "50% (1/2)"} into
     * {@code [covered, total]}.
     */
    private static int[] parseBranchCoverage(String condCoverage) {
        // Format: "50% (1/2)"
        int parenOpen = condCoverage.indexOf('(');
        int slash = condCoverage.indexOf('/');
        int parenClose = condCoverage.indexOf(')');
        if (parenOpen < 0 || slash < 0 || parenClose < 0) return new int[]{0, 0};
        try {
            int covered = Integer.parseInt(condCoverage.substring(parenOpen + 1, slash).trim());
            int total = Integer.parseInt(condCoverage.substring(slash + 1, parenClose).trim());
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

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static boolean hasDotnetProject(Path workspaceRoot) {
        if (Files.exists(workspaceRoot.resolve("Directory.Build.props"))) return true;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspaceRoot, "*.{sln,csproj}")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
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
