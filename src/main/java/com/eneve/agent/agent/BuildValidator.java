package com.eneve.agent.agent;

import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class BuildValidator {

    private static final Logger LOG = Logger.getLogger(BuildValidator.class);

    @ConfigProperty(name = "run-fix.job-timeout-minutes", defaultValue = "30")
    long timeoutMinutes;

    @ConfigProperty(name = "build.java-home", defaultValue = "")
    String javaHome;

    public void validate(WorkspaceContext workspace) throws Exception {
        String command = detectTestCommand(workspace.getRoot());
        if (command == null) {
            LOG.info("No recognized test command found, skipping build validation");
            return;
        }

        LOG.infof("Build validation using: %s", command);
        String effectiveJavaHome = javaHome != null && !javaHome.isBlank() ? javaHome : null;
        ProcessBuilder pb = ProcessHelper.cleanBuilder(effectiveJavaHome, "sh", "-c", command)
                .directory(workspace.getRoot().toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);

        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("Build validation timed out after " + timeoutMinutes + " minutes");
        }
        if (proc.exitValue() != 0) {
            String tail = output.length() > 2000 ? output.substring(output.length() - 2000) : output;
            throw new RuntimeException("Build validation failed (exit " + proc.exitValue() + "):\n" + tail);
        }
        LOG.info("Build validation passed");
    }

    private String detectTestCommand(Path root) {
        if (Files.exists(root.resolve("pom.xml"))) {
            return ProcessHelper.mvn(root) + " test";
        }
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
            return "gradle test";
        }
        if (hasDotnetProject(root)) {
            return "dotnet test";
        }
        if (Files.exists(root.resolve("package.json")) && hasNpmTestScript(root)) {
            return "npm test";
        }
        return null;
    }

    private static boolean hasDotnetProject(Path root) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.{sln,csproj}")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasNpmTestScript(Path root) {
        try {
            String content = Files.readString(root.resolve("package.json"));
            if (!content.contains("\"test\"")) return false;
            return !content.contains("\"test\": \"echo \\\"Error: no test specified\\\"");
        } catch (IOException e) {
            return false;
        }
    }
}
