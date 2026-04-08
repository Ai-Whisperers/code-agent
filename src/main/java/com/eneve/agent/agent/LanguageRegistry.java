package com.eneve.agent.agent;

import java.util.Set;

/**
 * Central registry of source file extensions supported by the code graph indexer,
 * embedding indexer, and metrics calculator.
 *
 * <p>All subsystems must reference this class instead of maintaining their own
 * inline {@code Set.of(...)} literals, so that adding a new language only requires
 * a single change here.
 */
public final class LanguageRegistry {

    private LanguageRegistry() {}

    /**
     * File extensions that are eligible for code-graph indexing, embedding, and metrics.
     * The set is ordered for readability; {@link Set#of} does not guarantee order.
     */
    public static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(".java", ".cs", ".ts", ".tsx", ".php", ".blade.php");

    /**
     * Returns {@code true} if the given file path ends with a supported extension.
     *
     * @param path relative or absolute file path
     */
    public static boolean isSupported(String path) {
        return SUPPORTED_EXTENSIONS.stream().anyMatch(path::endsWith);
    }
}
