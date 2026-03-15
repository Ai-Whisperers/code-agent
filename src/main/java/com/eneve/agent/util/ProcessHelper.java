package com.eneve.agent.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Factory for ProcessBuilder instances that do not inherit JVM environment variables
 * which pollute Maven's internal classloader isolation (PluginContainerException /
 * ClassRealm foreign-imports).
 *
 * When the Quarkus process sets JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, JDK_JAVA_OPTIONS,
 * or JAVA_OPTS (e.g. for the JBoss log manager), those values are silently picked up
 * by every child JVM process, including Maven. This causes Maven's ClassRealm to see
 * "foreign imports" from the parent's classpath and refuse to load plugins.
 *
 * CLASSPATH, if set, injects classes directly into Maven's system classloader and
 * breaks realm isolation in the same way.
 */
public final class ProcessHelper {

    private static final List<String> POLLUTING_ENV_VARS = List.of(
            "CLASSPATH",
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "JAVA_OPTS"
    );

    private ProcessHelper() {
    }

    /**
     * Creates a ProcessBuilder for the given command with all JVM-polluting
     * environment variables removed from the inherited environment.
     */
    public static ProcessBuilder cleanBuilder(String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        Map<String, String> env = pb.environment();
        POLLUTING_ENV_VARS.forEach(env::remove);
        return pb;
    }

    /**
     * Returns {@code ./mvnw} if a Maven wrapper exists in {@code root},
     * otherwise falls back to {@code mvn}.
     */
    public static String mvn(Path root) {
        return Files.exists(root.resolve("mvnw")) ? "./mvnw" : "mvn";
    }
}
