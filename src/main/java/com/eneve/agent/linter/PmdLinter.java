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

import com.eneve.agent.util.ProcessHelper;

import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@ApplicationScoped
public class PmdLinter implements LinterRunner {

    private static final Logger LOG = Logger.getLogger(PmdLinter.class);

    @Inject
    SettingsService settings;

    private static final String PMD_ARGS =
            " org.apache.maven.plugins:maven-pmd-plugin:3.26.0:pmd -Dformat=xml -q";

    private static final String REPORT_PATH = "target/pmd.xml";

    @Override
    public String name() {
        return "pmd";
    }

    @Override
    public boolean isApplicable(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("pom.xml"));
    }

    @Override
    public LinterResult run(Path workspaceRoot, long timeoutMinutes) {
        LOG.info("Running PMD analysis...");
        String command = ProcessHelper.mvn(workspaceRoot) + PMD_ARGS;
        String javaHomeVal = settings.get("build.java-home", "");
        String effectiveJavaHome = javaHomeVal.isBlank() ? null : javaHomeVal;
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(effectiveJavaHome, "sh", "-c", command)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("PMD timed out");
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Timed out after " + timeoutMinutes + " minutes");
            }

            Path reportFile = workspaceRoot.resolve(REPORT_PATH);
            if (!Files.exists(reportFile)) {
                LOG.warnf("PMD report not found at %s (exit %d)", reportFile, proc.exitValue());
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Report file not generated. Output:\n" + LinterUtils.truncate(output));
            }

            List<LinterFinding> findings = parseReport(reportFile, workspaceRoot);
            LOG.infof("PMD found %d issues", findings.size());
            return new LinterResult(name(), findings, true, LinterUtils.truncate(output));

        } catch (IOException | InterruptedException e) {
            LOG.warnf("PMD execution failed: %s", e.getMessage());
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
                String relativePath = LinterUtils.toRelativePath(absolutePath, workspaceRoot);

                NodeList violations = fileEl.getElementsByTagName("violation");
                for (int j = 0; j < violations.getLength(); j++) {
                    Element v = (Element) violations.item(j);
                    int line = LinterUtils.parseIntSafe(v.getAttribute("beginline"));
                    String rule = v.getAttribute("rule");
                    String message = v.getTextContent().trim();
                    String severity = mapPriority(v.getAttribute("priority"));

                    findings.add(new LinterFinding(name(), relativePath, line, severity, rule, message));
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse PMD report: %s", e.getMessage());
        }
        return findings;
    }

    private static String mapPriority(String priority) {
        int p = LinterUtils.parseIntSafe(priority);
        if (p <= 2) return LinterFinding.SEVERITY_ERROR;
        if (p <= 3) return LinterFinding.SEVERITY_WARNING;
        return LinterFinding.SEVERITY_INFO;
    }
}
