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
     * environment variables removed, and optionally sets {@code JAVA_HOME} and
     * prepends {@code $JAVA_HOME/bin} to {@code PATH}.
     *
     * <p>Pass {@code null} or blank string as {@code javaHome} to leave the
     * JDK environment unchanged.
     *
     * @param javaHome path to a JDK home (e.g. {@code /usr/lib/jvm/java-21});
     *                 when {@code null} or blank the environment is left unchanged
     * @param command  command and arguments
     */
    public static ProcessBuilder cleanBuilder(String javaHome, String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        Map<String, String> env = pb.environment();
        POLLUTING_ENV_VARS.forEach(env::remove);
        if (javaHome != null && !javaHome.isBlank()) {
            env.put("JAVA_HOME", javaHome);
            env.put("PATH", javaHome + "/bin:" + env.getOrDefault("PATH", ""));
        }
        return pb;
    }

    /**
     * Like {@link #cleanBuilder(String, String...)} but also overrides {@code MAVEN_HOME}
     * / {@code M2_HOME} and prepends the Maven {@code bin} directory to {@code PATH}.
     *
     * <p>Pass {@code null} or blank string for either home to leave it unchanged.
     *
     * @param javaHome  path to a JDK home (e.g. {@code /usr/lib/jvm/java-21})
     * @param mavenHome path to a Maven installation (e.g. {@code /opt/maven/3.9})
     * @param command   command and arguments
     */
    public static ProcessBuilder cleanBuilderWithMaven(String javaHome, String mavenHome, String... command) {
        ProcessBuilder pb = cleanBuilder(javaHome, command);
        if (mavenHome != null && !mavenHome.isBlank()) {
            Map<String, String> env = pb.environment();
            env.put("MAVEN_HOME", mavenHome);
            env.put("M2_HOME", mavenHome);
            env.put("PATH", mavenHome + "/bin:" + env.getOrDefault("PATH", ""));
        }
        return pb;
    }


    /**
     * Returns the Maven executable to use for the given workspace root.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code ./mvnw} if a Maven wrapper exists in {@code root}</li>
     *   <li>{@code <mavenHome>/bin/mvn} if {@code mavenHome} is non-blank</li>
     *   <li>{@code mvn} from the system {@code PATH}</li>
     * </ol>
     *
     * @param root      workspace root directory
     * @param mavenHome path to a Maven installation (e.g. {@code /opt/maven/3.9});
     *                  pass {@code null} or blank to skip
     */
    public static String mvn(Path root, String mavenHome) {
        if (Files.exists(root.resolve("mvnw"))) {
            return "./mvnw";
        }
        if (mavenHome != null && !mavenHome.isBlank()) {
            return mavenHome + "/bin/mvn";
        }
        return "mvn";
    }

    /**
     * Returns {@code ./mvnw} if a Maven wrapper exists in {@code root},
     * otherwise falls back to {@code mvn}.
     */
    public static String mvn(Path root) {
        return mvn(root, null);
    }
}
