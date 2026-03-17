package com.eneve.agent.upgrade;

/**
 * Maps framework versions to their minimum required Java version.
 *
 * <p>Both Quarkus and WildFly are released with a specific Java baseline in mind and that
 * baseline increases over time. Upgrade plans for these frameworks must therefore include a
 * step to verify — and potentially update — the project's Java version alongside the framework
 * version.
 *
 * <p>Known baselines (update this class when new framework majors are released):
 * <ul>
 *   <li><b>Quarkus 2.x</b>: Java 11+</li>
 *   <li><b>Quarkus 3.0 – 3.14</b>: Java 17+</li>
 *   <li><b>Quarkus 3.15+ / 4.x+</b>: Java 21+ (LTS stream requirement)</li>
 *   <li><b>WildFly &lt; 27</b>: Java 11+</li>
 *   <li><b>WildFly 27 – 32</b>: Java 11+ (Java 17/21 strongly recommended)</li>
 *   <li><b>WildFly 33+</b>: Java 21+ (minimum for all provisioning modes)</li>
 * </ul>
 */
public final class FrameworkJavaRequirements {

    private FrameworkJavaRequirements() {}

    /**
     * Returns the minimum Java version required by the given framework version,
     * or {@code null} if the archetype is not Java-based or the version cannot be determined.
     *
     * @param archetype the framework archetype (e.g. {@code "quarkus"}, {@code "wildfly"})
     * @param version   the target framework version string (e.g. {@code "3.17.0"},
     *                  {@code "33.0.1.Final"})
     * @return minimum Java version string (e.g. {@code "17"}, {@code "21"}), or {@code null}
     */
    public static String minimumJavaVersion(String archetype, String version) {
        if (version == null || version.isBlank() || "unknown".equalsIgnoreCase(version.trim())) {
            return null;
        }

        int major = parseMajor(version);

        return switch (archetype) {
            case "quarkus" -> {
                if (major >= 4) yield "21";
                if (major == 3) {
                    int minor = parseMinor(version);
                    yield minor >= 15 ? "21" : "17";
                }
                yield "11"; // Quarkus 2.x
            }
            case "wildfly" -> {
                // WildFly 33+ raised the minimum to Java 21 for all provisioning modes.
                // Earlier 27-32 still support Java 11 but Java 21 is strongly recommended.
                if (major >= 33) yield "21";
                if (major >= 27) yield "11"; // technically 11, but 21 is recommended
                yield "11";
            }
            default -> null;
        };
    }

    /**
     * Returns a human-readable note about the Java version requirement suitable for inclusion
     * in an upgrade spec, or an empty string if no Java-specific note is needed.
     *
     * @param archetype      the framework archetype
     * @param targetVersion  the target framework version
     * @param currentVersion the current (pre-upgrade) framework version, used to determine
     *                       whether the Java requirement is actually changing
     */
    public static String javaVersionNote(String archetype, String targetVersion,
                                          String currentVersion) {
        String requiredJava = minimumJavaVersion(archetype, targetVersion);
        if (requiredJava == null) return "";

        String currentRequired = minimumJavaVersion(archetype, currentVersion);
        boolean javaRequirementIncreases = currentRequired == null
                || Integer.parseInt(requiredJava) > Integer.parseInt(currentRequired);

        String frameworkLabel = "wildfly".equals(archetype) ? "WildFly" : "Quarkus";

        if (javaRequirementIncreases) {
            return String.format(
                    "%s %s requires Java %s or later — this is an increase from the current requirement "
                    + "(Java %s+). As part of this upgrade:\n"
                    + "  - Update the java.version / maven.compiler.source / maven.compiler.target "
                    + "properties in pom.xml to %s.\n"
                    + "  - Update the JDK version in all CI/CD pipeline configurations "
                    + "(.github/workflows, .gitlab-ci.yml, bitbucket-pipelines.yml, etc.) to Java %s.\n"
                    + "  - Update the base image tag in any Dockerfile(s) to a Java %s variant "
                    + "(e.g. FROM eclipse-temurin:%s-jre or the WildFly image tag that includes -jdk%s).",
                    frameworkLabel, targetVersion, requiredJava,
                    currentRequired != null ? currentRequired : "unknown",
                    requiredJava, requiredJava, requiredJava, requiredJava, requiredJava);
        }

        return String.format(
                "%s %s requires Java %s or later. Verify that pom.xml, CI/CD configurations, "
                + "and Dockerfile(s) already target Java %s+ and update them if not.",
                frameworkLabel, targetVersion, requiredJava, requiredJava);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static int parseMajor(String version) {
        String[] parts = stripPrefix(version).split("\\.");
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseMinor(String version) {
        String[] parts = stripPrefix(version).split("\\.");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Strips leading non-numeric characters (e.g. {@code "v3.17.0"} → {@code "3.17.0"}). */
    private static String stripPrefix(String version) {
        return version.trim().replaceFirst("^[^0-9]+", "");
    }
}
