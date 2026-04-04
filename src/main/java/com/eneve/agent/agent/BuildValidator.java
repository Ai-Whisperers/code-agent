package com.eneve.agent.agent;

import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.util.DotnetWorkspaceProbe;
import com.eneve.agent.util.JdkResolver;
import com.eneve.agent.util.NodeProjectHelper;
import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
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
     * Collects lines that match Maven or MSBuild/dotnet CLI error patterns first,
     * then falls back to head+tail. This avoids classpath dumps burying the actual
     * failure for both Java and .NET projects.
     *
     * <p>Maven patterns: {@code [ERROR]}, {@code [FATAL]}, {@code FAILED},
     * {@code BUILD FAILURE}.
     *
     * <p>MSBuild / dotnet CLI patterns: {@code : error CS}, {@code : error MSB},
     * {@code : error NU}, {@code : error NETSDK}, {@code Build FAILED},
     * {@code Error(s)}, {@code Unhandled exception}.
     */
    static String buildErrorExcerpt(String output) {
        if (output == null) return "";
        final int MAX = 3000;
        if (output.length() <= MAX) return output;

        // Collect lines matching Maven or MSBuild/dotnet CLI error indicators
        String errorLines = output.lines()
                .filter(l -> l.contains("[ERROR]") || l.contains("[FATAL]")
                        || l.contains("FAILED") || l.contains("BUILD FAILURE")
                        // MSBuild / dotnet CLI patterns
                        || l.contains(": error CS") || l.contains(": error MSB")
                        || l.contains(": error NU") || l.contains(": error NETSDK")
                        || l.contains("Build FAILED") || l.contains("Error(s)")
                        || l.contains("Unhandled exception"))
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
        if (DotnetWorkspaceProbe.hasDotnetAtRoot(root)) {
            return DotnetWorkspaceProbe.resolveDotnetTestCommand(root);
        }
        if (Files.exists(root.resolve("package.json")) && hasNodeTestableProject(root)) {
            return NodeProjectHelper.installAndTestCommand(root);
        }
        return null;
    }

    /**
     * True when the repo has a {@code test} script, or Vitest/Jest/Angular can run tests without one.
     */
    private static boolean hasNodeTestableProject(Path root) {
        if (!Files.exists(root.resolve("package.json"))) {
            return false;
        }
        if (NodeProjectHelper.hasRunnableTestScript(root)) {
            return true;
        }
        if (NodeProjectHelper.isAngularWorkspace(root)) {
            return true;
        }
        if (hasVitestConfig(root)) {
            return true;
        }
        try {
            return Files.readString(root.resolve("package.json")).contains("\"jest\"");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasVitestConfig(Path root) {
        for (String name : new String[] {
                "vitest.config.ts", "vitest.config.mts", "vitest.config.cts",
                "vitest.config.js", "vitest.config.mjs", "vitest.config.cjs"
        }) {
            if (Files.exists(root.resolve(name))) {
                return true;
            }
        }
        return false;
    }

}
