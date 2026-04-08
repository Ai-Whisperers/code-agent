package com.eneve.agent.agent.detector;

import com.eneve.agent.agent.ArchetypeDetector.ArchetypeInfo;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Detects shell-script projects from {@code *.sh} files present directly at the
 * project root (depth 1 only, to avoid false positives from build tooling scripts).
 */
public class ShellDetector implements Detector {

    private static final Logger LOG = Logger.getLogger(ShellDetector.class);

    @Override
    public ArchetypeInfo detect(Path projectRoot) {
        try (Stream<Path> stream = Files.list(projectRoot)) {
            boolean hasShell = stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().endsWith(".sh"));
            if (hasShell) {
                LOG.debugf("Detected shell-script project");
                return new ArchetypeInfo("shell", "unknown");
            }
        } catch (IOException e) {
            LOG.debugf("ShellDetector: cannot list root dir: %s", e.getMessage());
        }
        return null;
    }
}
