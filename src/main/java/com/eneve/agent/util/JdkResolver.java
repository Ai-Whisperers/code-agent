package com.eneve.agent.util;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves a suitable JDK home directory for a given workspace.
 *
 * <p>Resolution strategy:
 * <ol>
 *   <li>Read the required Java version from {@code pom.xml}
 *       ({@code maven.compiler.release}, {@code maven.compiler.source}, or
 *       {@code java.version} properties; falls back to {@code <source>/<release>}
 *       inside the compiler plugin configuration).</li>
 *   <li>Map the required version to one of the three supported major versions:
 *       8, 17, or 21 (picks the smallest that satisfies the requirement).</li>
 *   <li>Search for a matching JDK in SDKMAN ({@code ~/.sdkman/candidates/java/})
 *       and common Linux JVM directories ({@code /usr/lib/jvm/},
 *       {@code /usr/local/lib/jvm/}, {@code /opt/java/}, {@code /opt/jdk/}).</li>
 * </ol>
 *
 * <p>Returns {@code null} at every step that cannot be completed so callers can
 * fall back gracefully.
 */
public final class JdkResolver {

    private static final Logger LOG = Logger.getLogger(JdkResolver.class);

    /** Supported major versions in ascending order. */
    private static final int[] SUPPORTED_MAJORS = {8, 17, 21};

    private JdkResolver() {}

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Reads the required Java major version from {@code pom.xml} and returns the
     * path to a matching JDK installation, or {@code null} if none can be found.
     *
     * @param workspaceRoot root directory of the cloned project
     * @return absolute path to a JDK home (e.g. {@code /home/user/.sdkman/candidates/java/17.0.11-tem}),
     *         or {@code null}
     */
    public static String resolveForWorkspace(Path workspaceRoot) {
        int requiredMajor = readRequiredMajorFromPom(workspaceRoot);
        if (requiredMajor <= 0) {
            LOG.debugf("JdkResolver: could not determine required Java version from pom.xml");
            return null;
        }
        return resolveForMajor(requiredMajor);
    }

    /**
     * Returns the path to a JDK installation for the given major version, or
     * {@code null} if no suitable installation is found.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Well-known environment variables set by the Docker image:
     *       {@code JAVA_8_HOME}, {@code JAVA_17_HOME}, {@code JAVA_21_HOME}.</li>
     *   <li>SDKMAN candidates directory ({@code ~/.sdkman/candidates/java/}).</li>
     *   <li>Standard Linux JVM directories ({@code /usr/lib/jvm/}, etc.).</li>
     * </ol>
     *
     * @param requiredMajor the required Java major version (e.g. 8, 11, 17, 21)
     */
    public static String resolveForMajor(int requiredMajor) {
        int targetMajor = pickSupportedMajor(requiredMajor);
        if (targetMajor <= 0) {
            LOG.debugf("JdkResolver: no supported JDK major covers required version %d", requiredMajor);
            return null;
        }

        // 1. Docker image env vars — fastest path, always correct in the container
        String envVar = "JAVA_" + targetMajor + "_HOME";
        String envHome = System.getenv(envVar);
        if (envHome != null && !envHome.isBlank() && isValidJdkHome(Path.of(envHome))) {
            LOG.infof("JdkResolver: found JDK %d via %s: %s", targetMajor, envVar, envHome);
            return envHome;
        }

        String home = System.getProperty("user.home", "");

        // 2. SDKMAN (local dev machines)
        if (!home.isBlank()) {
            String sdkman = findInSdkman(Path.of(home, ".sdkman", "candidates", "java"), targetMajor);
            if (sdkman != null) {
                LOG.infof("JdkResolver: found JDK %d via SDKMAN: %s", targetMajor, sdkman);
                return sdkman;
            }
        }

        // 3. Standard Linux JVM directories
        for (String base : List.of("/usr/lib/jvm", "/usr/local/lib/jvm", "/opt/java", "/opt/jdk")) {
            String found = findInDirectory(Path.of(base), targetMajor);
            if (found != null) {
                LOG.infof("JdkResolver: found JDK %d in %s: %s", targetMajor, base, found);
                return found;
            }
        }

        LOG.debugf("JdkResolver: no JDK %d installation found", targetMajor);
        return null;
    }

    // ─── pom.xml parsing ─────────────────────────────────────────────────────

    /**
     * Reads the required Java major version from {@code pom.xml}.
     * Returns 0 if the version cannot be determined.
     */
    static int readRequiredMajorFromPom(Path workspaceRoot) {
        Path pom = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(pom)) return 0;

        try {
            DocumentBuilder builder = XmlParserFactory.createSecureBuilder();
            Document doc = builder.parse(pom.toFile());

            // Check <properties> for maven.compiler.release, maven.compiler.source, java.version
            for (String propName : List.of("maven.compiler.release", "maven.compiler.source", "java.version")) {
                NodeList nodes = doc.getElementsByTagName(propName);
                if (nodes.getLength() > 0) {
                    int v = parseMajor(nodes.item(0).getTextContent().trim());
                    if (v > 0) return v;
                }
            }

            // Fall back to compiler plugin <configuration><release> or <source>
            NodeList configs = doc.getElementsByTagName("configuration");
            for (int i = 0; i < configs.getLength(); i++) {
                org.w3c.dom.Element cfg = (org.w3c.dom.Element) configs.item(i);
                for (String tag : List.of("release", "source")) {
                    NodeList tagNodes = cfg.getElementsByTagName(tag);
                    if (tagNodes.getLength() > 0) {
                        int v = parseMajor(tagNodes.item(0).getTextContent().trim());
                        if (v > 0) return v;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("JdkResolver: failed to parse pom.xml: %s", e.getMessage());
        }
        return 0;
    }

    // ─── Version mapping ─────────────────────────────────────────────────────

    /**
     * Maps any required major version to the smallest supported major that satisfies it.
     * Supported majors: 8, 17, 21.
     *
     * <ul>
     *   <li>1–8  → 8</li>
     *   <li>9–17 → 17</li>
     *   <li>18–21 → 21</li>
     *   <li>&gt;21 → 21 (agent ships JDK 21; POM may target a newer {@code release} — Maven may still fail until upgraded)</li>
     * </ul>
     */
    static int pickSupportedMajor(int required) {
        if (required <= 0) {
            return 0;
        }
        if (required > 21) {
            LOG.debugf("JdkResolver: POM declares Java %d — mapping to 21 for JDK selection (newest shipped in image)",
                    required);
            required = 21;
        }
        for (int supported : SUPPORTED_MAJORS) {
            if (required <= supported) return supported;
        }
        return 0;
    }

    // ─── Directory scanning ───────────────────────────────────────────────────

    /**
     * Scans the SDKMAN java candidates directory for an installed JDK whose directory
     * name starts with the target major version number.
     *
     * <p>SDKMAN names directories like {@code 21.0.3-tem}, {@code 17.0.11-amzn},
     * {@code 8.0.412-zulu}. For Java 8, SDKMAN may use {@code 8.x.y-...} or
     * {@code 1.8.x-...}.
     */
    private static String findInSdkman(Path sdkmanJavaDir, int targetMajor) {
        if (!Files.isDirectory(sdkmanJavaDir)) return null;
        return scanDirectory(sdkmanJavaDir, targetMajor);
    }

    /**
     * Scans a standard JVM base directory (e.g. {@code /usr/lib/jvm}) for a subdirectory
     * whose name contains the target major version.
     */
    private static String findInDirectory(Path base, int targetMajor) {
        if (!Files.isDirectory(base)) return null;
        return scanDirectory(base, targetMajor);
    }

    /**
     * Lists immediate subdirectories of {@code dir} and returns the first one that
     * looks like a valid JDK for {@code targetMajor}.
     */
    private static String scanDirectory(Path dir, int targetMajor) {
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory)
                   .filter(p -> matchesMajor(p.getFileName().toString(), targetMajor))
                   .filter(JdkResolver::isValidJdkHome)
                   .forEach(candidates::add);
        } catch (IOException e) {
            LOG.debugf("JdkResolver: could not list %s: %s", dir, e.getMessage());
            return null;
        }
        if (candidates.isEmpty()) return null;
        // Prefer the lexicographically last entry (typically highest patch version)
        candidates.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return candidates.getLast().toAbsolutePath().toString();
    }

    /**
     * Returns true if the directory name matches the target major version.
     *
     * <p>Handles common naming patterns:
     * <ul>
     *   <li>SDKMAN: {@code 21.0.3-tem}, {@code 17.0.11-amzn}, {@code 8.0.412-zulu}</li>
     *   <li>Linux packages: {@code java-21-openjdk-amd64}, {@code java-17-openjdk},
     *       {@code java-1.8.0-openjdk}</li>
     *   <li>Temurin/Eclipse: {@code temurin-21}, {@code jdk-17.0.11+9}</li>
     * </ul>
     */
    static boolean matchesMajor(String dirName, int targetMajor) {
        if (dirName == null || dirName.equals("current")) return false;
        // Java 8 special case: "1.8" prefix used in older naming conventions
        if (targetMajor == 8 && (dirName.startsWith("1.8") || dirName.startsWith("8."))) return true;
        // General: look for the major version number as a standalone token
        // Matches: "21.0.3-tem", "java-21-openjdk", "temurin-21", "jdk-21.0.11+9"
        return dirName.matches(".*(?:^|[-._])0*" + targetMajor + "(?:[.\\-+_].*|$)");
    }

    /**
     * Returns true if the directory looks like a valid JDK home — it must contain
     * a {@code bin/java} or {@code bin/javac} executable.
     */
    private static boolean isValidJdkHome(Path candidate) {
        return Files.exists(candidate.resolve("bin/java"))
                || Files.exists(candidate.resolve("bin/javac"));
    }

    // ─── Version string parsing ───────────────────────────────────────────────

    /**
     * Parses a Java version string to its major version number.
     * Handles both old-style ({@code 1.8}, {@code 1.11}) and new-style ({@code 11}, {@code 17}).
     */
    static int parseMajor(String version) {
        if (version == null || version.isBlank()) return 0;
        // Strip non-numeric prefix (e.g. "v17" → "17")
        String cleaned = version.trim().replaceFirst("^[^0-9]+", "");
        String[] parts = cleaned.split("[.\\-+_]");
        try {
            int first = Integer.parseInt(parts[0]);
            // Old-style: "1.8" → major is second segment
            if (first == 1 && parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return first;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
