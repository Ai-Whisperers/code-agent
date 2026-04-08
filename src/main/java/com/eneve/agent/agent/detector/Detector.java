package com.eneve.agent.agent.detector;

import com.eneve.agent.agent.ArchetypeDetector.ArchetypeInfo;

import java.nio.file.Path;

/**
 * Strategy interface for detecting a project archetype from a repository root.
 *
 * <p>Each implementation handles one technology family (Maven/Quarkus/WildFly,
 * .NET, TypeScript, Docker, Terraform, SQL, Shell, PHP). The orchestrator
 * ({@link com.eneve.agent.agent.ArchetypeDetector}) tries each detector in
 * priority order and returns the first non-null result.
 */
public interface Detector {

    /**
     * Attempts to detect the archetype for the given project root.
     *
     * @param projectRoot root directory of the cloned repository
     * @return detected archetype info, or {@code null} if this detector does not apply
     */
    ArchetypeInfo detect(Path projectRoot);
}
