package com.eneve.agent.agent;

import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.util.JdkResolver;
import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class BuildValidator {

    private static final Logger LOG = Logger.getLogger(BuildValidator.class);

    @Inject
    SettingsService settings;

    public void validate(WorkspaceContext workspace) throws Exception {
        long timeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        String configuredJavaHome = settings.get("build.java-home", "");
        String effectiveMavenHome = settings.get("build.maven-home", "").isBlank() ? null : settings.get("build.maven-home", "");
        // If build.java-home is not explicitly set, try to resolve the JDK required by the project.
        String effectiveJavaHome = configuredJavaHome.isBlank()
                ? JdkResolver.resolveForWorkspace(workspace.getRoot())
                : configuredJavaHome;
        String command = detectTestCommand(workspace.getRoot(), effectiveMavenHome);
        if (command == null) {
            LOG.info("No recognized test command found, skipping build validation");
            return;
        }

        LOG.infof("Build validation using: %s", command);
        ProcessBuilder pb = ProcessHelper.cleanBuilderWithMaven(effectiveJavaHome, effectiveMavenHome, "sh", "-c", command)
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
            String excerpt = buildErrorExcerpt(output);
            throw new RuntimeException("Build validation failed (exit " + proc.exitValue() + "):\n" + excerpt);
        }
        LOG.info("Build validation passed");
    }

    /**
     * Extracts a useful excerpt from build output for feeding back to the agent.
     * Prefers lines containing [ERROR] or [WARN], then falls back to head+tail.
     * This avoids the common problem of classpath dumps burying the actual failure.
     */
    static String buildErrorExcerpt(String output) {
        if (output == null) return "";
        final int MAX = 3000;
        if (output.length() <= MAX) return output;

        // Extract lines that contain actual errors/warnings — these are the most useful
        String errorLines = output.lines()
                .filter(l -> l.contains("[ERROR]") || l.contains("[FATAL]")
                        || l.contains("FAILED") || l.contains("BUILD FAILURE"))
                .collect(java.util.stream.Collectors.joining("\n"));

        if (!errorLines.isBlank() && errorLines.length() <= MAX) {
            return errorLines;
        }

        // Fall back to head + tail so the agent sees both the failure reason and context
        int headLen = MAX * 2 / 3;
        int tailLen = MAX - headLen;
        String head = output.substring(0, Math.min(headLen, output.length()));
        String tail = output.length() > tailLen ? output.substring(output.length() - tailLen) : "";
        return tail.isBlank() ? head : head + "\n...\n" + tail;
    }

    private String detectTestCommand(Path root, String mavenHome) {
        if (Files.exists(root.resolve("pom.xml"))) {
            return ProcessHelper.mvn(root, mavenHome) + " test";
        }
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
            return "gradle test";
        }
        if (hasDotnetProject(root)) {
            return "dotnet test";
        }
        if (Files.exists(root.resolve("package.json")) && hasNpmTestScript(root)) {
            String installCmd = Files.exists(root.resolve("package-lock.json")) ? "npm ci" : "npm install";
            return installCmd + " --ignore-scripts && npm test";
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
