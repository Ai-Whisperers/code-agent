package com.eneve.agent.agent.detector;

import com.eneve.agent.agent.ArchetypeDetector.ArchetypeInfo;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects Docker-based project archetypes.
 *
 * <p>Two detection modes:
 * <ul>
 *   <li>{@link #detectWildFly(Path)} — scans the full tree for WildFly/EAP {@code FROM} lines
 *       (used as a fallback by {@link MavenDetector}).</li>
 *   <li>{@link #detect(Path)} — detects a generic Docker project from a root-level
 *       {@code Dockerfile} or {@code docker-compose.yml}.</li>
 * </ul>
 */
public class DockerfileDetector implements Detector {

    private static final Logger LOG = Logger.getLogger(DockerfileDetector.class);

    /**
     * Maximum directory depth searched when scanning for Dockerfiles in a multi-module project.
     */
    static final int DOCKERFILE_SCAN_MAX_DEPTH = 8;

    /**
     * Directory names that are never descended into during the Dockerfile scan.
     */
    static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "node_modules", ".mvn", ".idea", ".gradle", "build"
    );

    /**
     * Matches WildFly/EAP {@code FROM} lines and captures the server version.
     */
    private static final Pattern WILDFLY_FROM_PATTERN = Pattern.compile(
            "^\\s*FROM\\s+(?:quay\\.io/wildfly/wildfly|jboss/wildfly|registry\\.redhat\\.io/jboss-eap-[^/]+/[^:]+)" +
            ":(\\d+\\.\\d+\\.\\d+\\.\\w+)(?:-jdk\\d+)?(?:\\s|$)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches the first {@code FROM} instruction in a Dockerfile and captures the image tag.
     */
    private static final Pattern DOCKER_FROM_TAG_PATTERN = Pattern.compile(
            "^\\s*FROM\\s+[^:\\s]+:([^\\s]+)(?:\\s+AS\\s+\\S+)?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Scans the entire project tree for any file whose name starts with {@code Dockerfile}
     * and contains a WildFly {@code FROM} instruction.
     *
     * @return detected WildFly archetype info, or {@code null} if not found
     */
    public ArchetypeInfo detectWildFly(Path projectRoot) {
        try (Stream<Path> stream = Files.walk(projectRoot, DOCKERFILE_SCAN_MAX_DEPTH)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("Dockerfile"))
                    .filter(p -> !isInSkippedDir(projectRoot, p))
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .map(p -> probeForWildFly(p, projectRoot.relativize(p).toString()))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOG.debugf("Failed to walk project tree for Dockerfiles at %s: %s", projectRoot, e.getMessage());
            return null;
        }
    }

    /** Detects a generic Docker project from a root-level Dockerfile or docker-compose file. */
    @Override
    public ArchetypeInfo detect(Path projectRoot) {
        for (String name : List.of("Dockerfile", "Dockerfile.jvm", "Dockerfile.native")) {
            Path candidate = projectRoot.resolve(name);
            if (Files.isRegularFile(candidate)) {
                String version = extractDockerFromTag(candidate);
                LOG.debugf("Detected Docker project via %s: %s", name, version);
                return new ArchetypeInfo("docker", version);
            }
        }
        for (String name : List.of("docker-compose.yml", "docker-compose.yaml",
                                   "compose.yml", "compose.yaml")) {
            if (Files.isRegularFile(projectRoot.resolve(name))) {
                LOG.debugf("Detected Docker project via %s", name);
                return new ArchetypeInfo("docker", "unknown");
            }
        }
        return null;
    }

    private ArchetypeInfo probeForWildFly(Path path, String displayPath) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            for (String line : content.lines().toList()) {
                Matcher m = WILDFLY_FROM_PATTERN.matcher(line);
                if (m.find()) {
                    String version = m.group(1);
                    LOG.debugf("Detected WildFly via Dockerfile %s: %s", displayPath, version);
                    return new ArchetypeInfo("wildfly", version);
                }
            }
        } catch (IOException e) {
            LOG.debugf("Could not read Dockerfile %s: %s", path, e.getMessage());
        }
        return null;
    }

    private String extractDockerFromTag(Path dockerfile) {
        try {
            for (String line : Files.readAllLines(dockerfile, StandardCharsets.UTF_8)) {
                Matcher m = DOCKER_FROM_TAG_PATTERN.matcher(line);
                if (m.matches()) return m.group(1);
            }
        } catch (IOException e) {
            LOG.debugf("DockerfileDetector: cannot read %s: %s", dockerfile.getFileName(), e.getMessage());
        }
        return "unknown";
    }

    /**
     * Returns {@code true} if any path segment between {@code root} and {@code path}
     * is in {@link #SKIP_DIRS}.
     */
    static boolean isInSkippedDir(Path root, Path path) {
        Path relative = root.relativize(path);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (SKIP_DIRS.contains(relative.getName(i).toString())) return true;
        }
        return false;
    }
}
