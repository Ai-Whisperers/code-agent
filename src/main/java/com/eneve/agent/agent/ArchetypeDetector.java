package com.eneve.agent.agent;

import com.eneve.agent.agent.detector.DockerfileDetector;
import com.eneve.agent.agent.detector.DotnetDetector;
import com.eneve.agent.agent.detector.MavenDetector;
import com.eneve.agent.agent.detector.PhpDetector;
import com.eneve.agent.agent.detector.ShellDetector;
import com.eneve.agent.agent.detector.SqlDetector;
import com.eneve.agent.agent.detector.TerraformDetector;
import com.eneve.agent.agent.detector.TypeScriptDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Detects the primary framework archetype and its version from project files.
 *
 * <p>This class is a thin orchestrator. Each technology family is handled by a
 * dedicated {@link com.eneve.agent.agent.detector.Detector} implementation.
 * Detection order:
 * <ol>
 *   <li><b>Maven projects</b> ({@code pom.xml} present):
 *     Quarkus → WildFly (POM) → WildFly (Dockerfile) → generic Maven packaging.
 *   </li>
 *   <li><b>Non-Maven projects</b>:
 *     .NET → PHP → TypeScript (Angular/React) → WildFly (Dockerfile) →
 *     Docker → Terraform → SQL → Shell.
 *   </li>
 * </ol>
 *
 * <p>Detection is intentionally lightweight: it reads only a small, well-known set
 * of files and does not fetch remote metadata.
 */
@ApplicationScoped
public class ArchetypeDetector {

    @Inject
    ObjectMapper objectMapper;

    public record ArchetypeInfo(String archetype, String version, Map<String, String> dependencyVersions) {

        /** Convenience constructor for archetypes with no tracked extra dependencies. */
        public ArchetypeInfo(String archetype, String version) {
            this(archetype, version, Map.of());
        }
    }

    /**
     * Attempts to detect the primary archetype and version for the given project root.
     *
     * @param projectRoot root directory of the cloned repository
     * @return detected archetype info, or {@code null} if not detected or on parse error
     */
    public ArchetypeInfo detect(Path projectRoot) {
        DockerfileDetector dockerfileDetector = new DockerfileDetector();
        MavenDetector mavenDetector = new MavenDetector(dockerfileDetector);

        // Maven projects are handled first and entirely within MavenDetector.
        ArchetypeInfo maven = mavenDetector.detect(projectRoot);
        if (maven != null) return maven;

        // WildFly Dockerfile check for non-Maven projects (e.g. standalone Docker-only repos).
        ArchetypeInfo wildFlyDocker = dockerfileDetector.detectWildFly(projectRoot);
        if (wildFlyDocker != null) return wildFlyDocker;

        // Non-Maven detectors tried in priority order.
        List<com.eneve.agent.agent.detector.Detector> nonMavenDetectors = List.of(
                new DotnetDetector(objectMapper),
                new PhpDetector(objectMapper),
                new TypeScriptDetector(objectMapper),
                dockerfileDetector,
                new TerraformDetector(),
                new SqlDetector(),
                new ShellDetector()
        );

        for (com.eneve.agent.agent.detector.Detector detector : nonMavenDetectors) {
            ArchetypeInfo result = detector.detect(projectRoot);
            if (result != null) return result;
        }

        return null;
    }

    // ─── Kept for backward compatibility with callers that use these utilities ───

    /**
     * Strips leading semver range specifiers ({@code ^}, {@code ~}, {@code >=}, …)
     * from a version string.
     *
     * @see TypeScriptDetector#stripVersionRange(String)
     */
    static String stripVersionRange(String version) {
        return TypeScriptDetector.stripVersionRange(version);
    }
}
