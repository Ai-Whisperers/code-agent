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

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SpotBugsLinter implements LinterRunner {

    private static final Logger LOG = Logger.getLogger(SpotBugsLinter.class);

    @ConfigProperty(name = "build.java-home", defaultValue = "")
    String javaHome;

    private static final String COMPILE_ARGS = " compile -q";
    private static final String SPOTBUGS_ARGS =
            " com.github.spotbugs:spotbugs-maven-plugin:4.8.6:spotbugs -q";

    private static final String REPORT_PATH = "target/spotbugsXml.xml";

    @Override
    public String name() {
        return "spotbugs";
    }

    @Override
    public boolean isApplicable(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("pom.xml"));
    }

    @Override
    public LinterResult run(Path workspaceRoot, long timeoutMinutes) {
        LOG.info("Running SpotBugs analysis (compile + analyze)...");
        String mvn = ProcessHelper.mvn(workspaceRoot);
        String effectiveJavaHome = javaHome != null && !javaHome.isBlank() ? javaHome : null;

        try {
            String compileOutput = runProcess(workspaceRoot, mvn + COMPILE_ARGS, timeoutMinutes);
            if (compileOutput == null) {
                LOG.warn("Compilation failed or timed out, skipping SpotBugs");
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Skipped: compilation failed");
            }
        } catch (CompilationFailedException e) {
            LOG.warnf("SpotBugs skipped — compile failed: %s", e.getMessage());
            return new LinterResult(name(), Collections.emptyList(), false,
                    "Skipped: compilation failed");
        }

        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(effectiveJavaHome, "sh", "-c", mvn + SPOTBUGS_ARGS)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("SpotBugs timed out");
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Timed out after " + timeoutMinutes + " minutes");
            }

            Path reportFile = workspaceRoot.resolve(REPORT_PATH);
            if (!Files.exists(reportFile)) {
                LOG.warnf("SpotBugs report not found at %s (exit %d)", reportFile, proc.exitValue());
                return new LinterResult(name(), Collections.emptyList(), false,
                        "Report file not generated. Output:\n" + LinterUtils.truncate(output));
            }

            List<LinterFinding> findings = parseReport(reportFile, workspaceRoot);
            LOG.infof("SpotBugs found %d issues", findings.size());
            return new LinterResult(name(), findings, true, LinterUtils.truncate(output));

        } catch (IOException | InterruptedException e) {
            LOG.warnf("SpotBugs execution failed: %s", e.getMessage());
            return new LinterResult(name(), Collections.emptyList(), false, e.getMessage());
        }
    }

    private String runProcess(Path workspaceRoot, String command, long timeoutMinutes)
            throws CompilationFailedException {
        String effectiveJavaHome = javaHome != null && !javaHome.isBlank() ? javaHome : null;
        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(effectiveJavaHome, "sh", "-c", command)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                proc.destroyForcibly();
                throw new CompilationFailedException("Timed out");
            }
            if (proc.exitValue() != 0) {
                throw new CompilationFailedException("Exit code " + proc.exitValue());
            }
            return output;
        } catch (IOException | InterruptedException e) {
            throw new CompilationFailedException(e.getMessage());
        }
    }

    private List<LinterFinding> parseReport(Path reportFile, Path workspaceRoot) {
        List<LinterFinding> findings = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(reportFile.toFile());

            NodeList bugInstances = doc.getElementsByTagName("BugInstance");
            for (int i = 0; i < bugInstances.getLength(); i++) {
                Element bug = (Element) bugInstances.item(i);
                String type = bug.getAttribute("type");
                String category = bug.getAttribute("category");
                String severity = mapPriority(bug.getAttribute("priority"));

                String message = "";
                NodeList shortMsgs = bug.getElementsByTagName("ShortMessage");
                if (shortMsgs.getLength() > 0) {
                    message = shortMsgs.item(0).getTextContent().trim();
                }

                NodeList sourceLines = bug.getElementsByTagName("SourceLine");
                String file = "";
                int line = 0;
                for (int j = 0; j < sourceLines.getLength(); j++) {
                    Element sl = (Element) sourceLines.item(j);
                    String sourcePath = sl.getAttribute("sourcepath");
                    if (sourcePath != null && !sourcePath.isEmpty()) {
                        file = "src/main/java/" + sourcePath;
                        line = LinterUtils.parseIntSafe(sl.getAttribute("start"));
                        break;
                    }
                }

                String rule = type + " (" + category + ")";
                findings.add(new LinterFinding(name(), file, line, severity, rule, message));
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse SpotBugs report: %s", e.getMessage());
        }
        return findings;
    }

    private static String mapPriority(String priority) {
        int p = LinterUtils.parseIntSafe(priority);
        if (p <= 1) return LinterFinding.SEVERITY_ERROR;
        if (p <= 2) return LinterFinding.SEVERITY_WARNING;
        return LinterFinding.SEVERITY_INFO;
    }

    private static class CompilationFailedException extends Exception {
        CompilationFailedException(String message) {
            super(message);
        }
    }
}
