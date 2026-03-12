package com.eneve.agent.linter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CheckstyleLinter implements LinterRunner {

    private static final Logger LOG = Logger.getLogger(CheckstyleLinter.class);

    private static final String MAVEN_COMMAND =
            "mvn org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:checkstyle"
                    + " -Dcheckstyle.output.format=xml -q";

    private static final String REPORT_PATH = "target/checkstyle-result.xml";

    @Override
    public String name() {
        return "checkstyle";
    }

    @Override
    public boolean isApplicable(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("pom.xml"));
    }

    @Override
    public LinterResult run(Path workspaceRoot, long timeoutMinutes) {
        LOG.info("Running Checkstyle analysis...");
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", MAVEN_COMMAND)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("Checkstyle timed out");
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Timed out after " + timeoutMinutes + " minutes");
            }

            Path reportFile = workspaceRoot.resolve(REPORT_PATH);
            if (!Files.exists(reportFile)) {
                LOG.warnf("Checkstyle report not found at %s (exit %d)", reportFile, proc.exitValue());
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Report file not generated. Output:\n" + truncate(output));
            }

            List<LinterFinding> findings = parseReport(reportFile, workspaceRoot);
            LOG.infof("Checkstyle found %d issues", findings.size());
            return new LinterResult(name(), findings, true, truncate(output));

        } catch (IOException | InterruptedException e) {
            LOG.warnf("Checkstyle execution failed: %s", e.getMessage());
            return new LinterResult(name(), Collections.emptyList(), false, e.getMessage());
        }
    }

    private List<LinterFinding> parseReport(Path reportFile, Path workspaceRoot) {
        List<LinterFinding> findings = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(reportFile.toFile());

            NodeList fileNodes = doc.getElementsByTagName("file");
            for (int i = 0; i < fileNodes.getLength(); i++) {
                Element fileEl = (Element) fileNodes.item(i);
                String absolutePath = fileEl.getAttribute("name");
                String relativePath = toRelativePath(absolutePath, workspaceRoot);

                NodeList errors = fileEl.getElementsByTagName("error");
                for (int j = 0; j < errors.getLength(); j++) {
                    Element err = (Element) errors.item(j);
                    int line = parseIntSafe(err.getAttribute("line"));
                    String severity = mapSeverity(err.getAttribute("severity"));
                    String source = err.getAttribute("source");
                    String rule = source.contains(".") ? source.substring(source.lastIndexOf('.') + 1) : source;
                    String message = err.getAttribute("message");

                    findings.add(new LinterFinding(name(), relativePath, line, severity, rule, message));
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse Checkstyle report: %s", e.getMessage());
        }
        return findings;
    }

    private static String mapSeverity(String checkstyleSeverity) {
        if (checkstyleSeverity == null) return LinterFinding.SEVERITY_WARNING;
        return switch (checkstyleSeverity.toLowerCase()) {
            case "error" -> LinterFinding.SEVERITY_ERROR;
            case "info" -> LinterFinding.SEVERITY_INFO;
            default -> LinterFinding.SEVERITY_WARNING;
        };
    }

    static String toRelativePath(String absolutePath, Path workspaceRoot) {
        try {
            Path abs = Path.of(absolutePath);
            if (abs.startsWith(workspaceRoot)) {
                return workspaceRoot.relativize(abs).toString();
            }
        } catch (Exception ignored) { }
        return absolutePath;
    }

    static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 2000 ? text.substring(0, 2000) + "..." : text;
    }
}
