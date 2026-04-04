package com.eneve.agent.util;

import com.github.javaparser.ParserConfiguration;

/**
 * Shared JavaParser settings for indexing and embeddings (Java 21 baseline).
 */
public final class JavaParserConfig {

    private JavaParserConfig() {}

    /**
     * Base configuration without symbol resolution — suitable for embeddings and lightweight parsing.
     */
    public static ParserConfiguration java21BaseConfiguration() {
        return new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }
}
