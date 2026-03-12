package com.eneve.agent.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.eneve.agent.workspace.WorkspaceContext;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BuildValidator {

    private static final Logger LOG = Logger.getLogger(BuildValidator.class);

    @ConfigProperty(name = "run-fix.job-timeout-minutes", defaultValue = "30")
    long timeoutMinutes;

    public void validate(WorkspaceContext workspace) throws Exception {
        Path gradleFile = workspace.getRoot().resolve("build.gradle");
        String command = Files.exists(gradleFile) ? "gradle test" : "mvn test";

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
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
}
