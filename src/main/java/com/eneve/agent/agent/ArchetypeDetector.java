package com.eneve.agent.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Detects the primary framework archetype (e.g. Quarkus, WildFly, React, Angular) and its
 * version from a Maven {@code pom.xml}, a {@code Dockerfile}, or a {@code package.json}.
 *
 * <p>Detection order:
 * <ol>
 *   <li><b>Maven projects</b> ({@code pom.xml} present):
 *     <ol>
 *       <li>POM-based: parent groupId, BOM in {@code dependencyManagement}, known
 *           {@code <properties>} keys, and the {@code wildfly-maven-plugin} presence.</li>
 *       <li>Dockerfile-based WildFly fallback: scans the project tree for a
 *           {@code FROM quay.io/wildfly/wildfly:X.Y.Z.Final} line.</li>
 *     </ol>
 *   </li>
 *   <li><b>TypeScript frontend projects</b> (no {@code pom.xml}):
 *     <ol>
 *       <li>{@code package.json}-based: checks {@code dependencies} and
 *           {@code devDependencies} for {@code @angular/core} (Angular) or {@code react}
 *           (React), in that priority order.</li>
 *       <li>Dockerfile-based WildFly fallback (for non-Maven server images).</li>
 *     </ol>
 *   </li>
 * </ol>
 *
 * <p>Detection is intentionally lightweight: it reads only the root {@code pom.xml},
 * {@code package.json}, and a small set of Dockerfile candidates; it resolves Maven
 * {@code ${property}} references within the same file and does not fetch parent POMs
 * from Maven Central or remote npm registries.
 */
@ApplicationScoped
public class ArchetypeDetector {

    private static final Logger LOG = Logger.getLogger(ArchetypeDetector.class);

    public record ArchetypeInfo(String archetype, String version) {}

    /**
     * Maximum directory depth searched when scanning for Dockerfiles in a multi-module project.
     * Depth 8 covers deeply grouped module structures such as
     * {@code group/sub-group/module/src/main/docker/Dockerfile.jvm} from the project root.
     */
    static final int DOCKERFILE_SCAN_MAX_DEPTH = 8;

    /**
     * Directory names that are never descended into during the Dockerfile scan.
     */
    static final Set<String> DOCKERFILE_SKIP_DIRS = Set.of(
            ".git", "target", "node_modules", ".mvn", ".idea", ".gradle", "build"
    );

    /**
     * Matches WildFly/EAP {@code FROM} lines and captures the server version.
     * Examples matched:
     * <ul>
     *   <li>{@code FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk17}</li>
     *   <li>{@code FROM quay.io/wildfly/wildfly:31.0.0.Final}</li>
     *   <li>{@code FROM jboss/wildfly:26.1.3.Final}</li>
     * </ul>
     */
    static final Pattern WILDFLY_FROM_PATTERN = Pattern.compile(
            "^\\s*FROM\\s+(?:quay\\.io/wildfly/wildfly|jboss/wildfly|registry\\.redhat\\.io/jboss-eap-[^/]+/[^:]+)" +
            ":(\\d+\\.\\d+\\.\\d+\\.\\w+)(?:-jdk\\d+)?(?:\\s|$)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Attempts to detect the framework and version for the given project.
     *
     * <p>Strategy:
     * <ol>
     *   <li>If {@code pom.xml} is present: POM-based detection (Quarkus, then WildFly),
     *       followed by a WildFly Dockerfile fallback.</li>
     *   <li>Otherwise: TypeScript frontend detection via {@code package.json}
     *       (Angular, then React), followed by a WildFly Dockerfile fallback.</li>
     * </ol>
     *
     * @param projectRoot root directory of the cloned repository
     * @return detected archetype info, or {@code null} if not detected or on parse error
     */
    public ArchetypeInfo detect(Path projectRoot) {
        Path pomPath = projectRoot.resolve("pom.xml");

        if (Files.exists(pomPath)) {
            try (InputStream in = Files.newInputStream(pomPath)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(false);
                // Disable external entity processing (XXE) using the portable JAXP constant.
                // The Xerces-specific disallow-doctype-decl feature is also attempted but
                // wrapped in a separate try-catch so that unsupported parsers don't swallow
                // detection entirely.
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                try {
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                } catch (Exception ignored) {
                    // Not all XML parser implementations support this Xerces-specific feature;
                    // FEATURE_SECURE_PROCESSING above is sufficient for our purposes.
                }
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(in);
                doc.getDocumentElement().normalize();

                Map<String, String> properties = extractProperties(doc);

                ArchetypeInfo quarkus = detectQuarkus(doc, properties);
                if (quarkus != null) {
                    return quarkus;
                }

                ArchetypeInfo wildfly = detectWildFlyFromPom(doc, properties);
                if (wildfly != null) {
                    return wildfly;
                }

            } catch (Exception e) {
                LOG.warnf("ArchetypeDetector: failed to parse pom.xml at %s: %s", pomPath, e.getMessage());
            }

            // Dockerfile fallback — useful when the POM alone is not conclusive
            // (e.g. no explicit BOM but the server image tag is present in the Dockerfile).
            return detectWildFlyFromDockerfiles(projectRoot);
        }

        // No pom.xml — try TypeScript frontend projects first, then WildFly Dockerfile.
        ArchetypeInfo tsResult = detectTypeScriptFrontend(projectRoot);
        if (tsResult != null) {
            return tsResult;
        }
        return detectWildFlyFromDockerfiles(projectRoot);
    }

    // ─── Quarkus ─────────────────────────────────────────────────────────────────

    private ArchetypeInfo detectQuarkus(Document doc, Map<String, String> properties) {
        // 1. Check parent: <groupId>io.quarkus.platform</groupId> or <groupId>io.quarkus</groupId>
        String parentGroupId = resolve(firstText(doc, "project > parent > groupId"), properties);
        String parentVersion = firstText(doc, "project > parent > version");

        if (isQuarkusGroup(parentGroupId) && parentVersion != null) {
            String resolved = resolve(parentVersion, properties);
            if (resolved != null) {
                LOG.debugf("Detected Quarkus via parent POM: %s", resolved);
                return new ArchetypeInfo("quarkus", resolved);
            }
        }

        // 2. Check <dependencyManagement> for quarkus-bom or quarkus-universe-bom.
        // groupId and artifactId are resolved through properties first so that the common
        // pattern of using ${quarkus.platform.group-id} / ${quarkus.platform.artifact-id}
        // is handled correctly.
        NodeList deps = doc.getElementsByTagName("dependency");
        for (int i = 0; i < deps.getLength(); i++) {
            Node node = deps.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element dep = (Element) node;
            String groupId    = resolve(textContent(dep, "groupId"),    properties);
            String artifactId = resolve(textContent(dep, "artifactId"), properties);
            String version    = textContent(dep, "version");

            if (isQuarkusGroup(groupId) && isQuarkusBom(artifactId) && version != null) {
                String resolved = resolve(version, properties);
                if (resolved != null) {
                    LOG.debugf("Detected Quarkus via BOM in dependencyManagement: %s", resolved);
                    return new ArchetypeInfo("quarkus", resolved);
                }
            }
        }

        // 3. Check properties for quarkus.platform.version or quarkus.version.
        // This is the primary path for POMs that declare the version only in <properties>
        // and reference it everywhere else via ${quarkus.platform.version}.
        String version = properties.get("quarkus.platform.version");
        if (version == null) {
            version = properties.get("quarkus.version");
        }
        if (version != null && !version.startsWith("$")) {
            LOG.debugf("Detected Quarkus via property: %s", version);
            return new ArchetypeInfo("quarkus", version);
        }

        return null;
    }

    private static boolean isQuarkusGroup(String groupId) {
        return groupId != null
                && (groupId.equals("io.quarkus.platform") || groupId.equals("io.quarkus"));
    }

    private static boolean isQuarkusBom(String artifactId) {
        return artifactId != null
                && (artifactId.equals("quarkus-bom")
                        || artifactId.equals("quarkus-universe-bom")
                        || artifactId.startsWith("quarkus-"));
    }

    // ─── WildFly — POM ───────────────────────────────────────────────────────────

    private ArchetypeInfo detectWildFlyFromPom(Document doc, Map<String, String> properties) {
        // 1. Parent POM
        String parentGroupId          = resolve(firstText(doc, "project > parent > groupId"),    properties);
        String wildFlyParentArtifactId = resolve(firstText(doc, "project > parent > artifactId"), properties);
        String parentVersion           = firstText(doc, "project > parent > version");

        if (isWildFlyParent(parentGroupId, wildFlyParentArtifactId) && parentVersion != null) {
            String resolved = resolve(parentVersion, properties);
            if (resolved != null) {
                LOG.debugf("Detected WildFly via parent POM: %s", resolved);
                return new ArchetypeInfo("wildfly", resolved);
            }
        }

        // 2. BOM in <dependencyManagement> — resolve groupId/artifactId through properties
        //    so that ${wildfly.bom.group-id} style references work correctly.
        NodeList deps = doc.getElementsByTagName("dependency");
        for (int i = 0; i < deps.getLength(); i++) {
            Node node = deps.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element dep = (Element) node;
            String groupId    = resolve(textContent(dep, "groupId"),    properties);
            String artifactId = resolve(textContent(dep, "artifactId"), properties);
            String version    = textContent(dep, "version");

            if (isWildFlyBom(groupId, artifactId) && version != null) {
                String resolved = resolve(version, properties);
                if (resolved != null) {
                    LOG.debugf("Detected WildFly via BOM: %s", resolved);
                    return new ArchetypeInfo("wildfly", resolved);
                }
            }
        }

        // 3. Well-known version properties
        for (String key : List.of("wildfly.version", "version.wildfly", "version.wildfly.server",
                                   "jboss.eap.version", "version.jboss.eap")) {
            String v = properties.get(key);
            if (v != null && !v.startsWith("$")) {
                LOG.debugf("Detected WildFly via property %s: %s", key, v);
                return new ArchetypeInfo("wildfly", v);
            }
        }

        // 4. Presence of wildfly-maven-plugin — the server version is then read from the
        //    wildfly.version property (already checked above) or from the Dockerfile fallback.
        //    Here we only use the plugin as a signal when a version property is available.
        NodeList plugins = doc.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Node node = plugins.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element plugin = (Element) node;
            String gid = resolve(textContent(plugin, "groupId"), properties);
            String aid = resolve(textContent(plugin, "artifactId"), properties);
            if (isWildFlyMavenPlugin(gid, aid)) {
                // Plugin version reflects the plugin release, not the server version.
                // Return a placeholder so the Dockerfile fallback can enrich the version.
                LOG.debugf("Detected WildFly via wildfly-maven-plugin (version unknown from plugin alone)");
                return new ArchetypeInfo("wildfly", "unknown");
            }
        }

        return null;
    }

    // ─── WildFly — Dockerfile ────────────────────────────────────────────────────

    /**
     * Scans the entire project tree (up to {@link #DOCKERFILE_SCAN_MAX_DEPTH} levels deep) for
     * any file whose name starts with {@code Dockerfile} and contains a WildFly {@code FROM}
     * instruction. This covers both single-module layouts ({@code src/main/docker/Dockerfile.jvm})
     * and multi-module projects where each sub-module has its own Dockerfile.
     *
     * <p>Results are ordered by path depth (shallowest first) so that a root-level file is
     * preferred over a deeply nested one when both match.
     */
    ArchetypeInfo detectWildFlyFromDockerfiles(Path projectRoot) {
        try (Stream<Path> stream = Files.walk(projectRoot, DOCKERFILE_SCAN_MAX_DEPTH)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("Dockerfile"))
                    .filter(p -> !isInSkippedDir(projectRoot, p))
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .map(p -> probeDockerfile(p, projectRoot.relativize(p).toString()))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOG.debugf("Failed to walk project tree for Dockerfiles at %s: %s", projectRoot, e.getMessage());
            return null;
        }
    }

    /**
     * Reads a single Dockerfile and returns {@link ArchetypeInfo} if a WildFly {@code FROM}
     * line is found, or {@code null} otherwise.
     */
    private ArchetypeInfo probeDockerfile(Path path, String displayPath) {
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

    /**
     * Returns {@code true} if any path segment between {@code root} and {@code path}
     * is in {@link #DOCKERFILE_SKIP_DIRS}, preventing descent into irrelevant directories.
     */
    private static boolean isInSkippedDir(Path root, Path path) {
        Path relative = root.relativize(path);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (DOCKERFILE_SKIP_DIRS.contains(relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWildFlyParent(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) return false;
        // Community WildFly
        if ((groupId.equals("org.wildfly") || groupId.equals("org.jboss"))
                && (artifactId.contains("wildfly") || artifactId.contains("jboss-eap"))) {
            return true;
        }
        // Red Hat EAP
        return groupId.equals("com.redhat.eap") && artifactId.contains("eap");
    }

    private static boolean isWildFlyBom(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) return false;
        // Community WildFly BOMs
        if ((groupId.equals("org.wildfly.bom") || groupId.equals("org.jboss.bom"))
                && (artifactId.contains("wildfly") || artifactId.contains("jboss-eap"))) {
            return true;
        }
        // Red Hat EAP BOMs
        return (groupId.equals("com.redhat.eap") || groupId.equals("org.jboss.eap"))
                && artifactId.contains("eap");
    }

    private static boolean isWildFlyMavenPlugin(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) return false;
        return (groupId.equals("org.wildfly.plugins") || groupId.equals("org.jboss.as.plugins"))
                && artifactId.contains("wildfly");
    }

    // ─── TypeScript frontend (React / Angular) ───────────────────────────────────

    /**
     * Detects React or Angular from a {@code package.json} in the project root.
     *
     * <p>Detection priority: Angular ({@code @angular/core}) is checked before React
     * ({@code react}) so that a project that depends on both (rare but possible) is
     * always classified as Angular.  Version strings are stripped of semver range
     * prefixes ({@code ^}, {@code ~}, {@code >=}, …) before being stored.
     *
     * <p>All three dependency sections are consulted in order:
     * {@code dependencies}, {@code devDependencies}, {@code peerDependencies}.
     *
     * @param projectRoot root directory of the cloned repository
     * @return detected archetype info, or {@code null} if not detected or on parse error
     */
    ArchetypeInfo detectTypeScriptFrontend(Path projectRoot) {
        Path pkgJson = projectRoot.resolve("package.json");
        if (!Files.exists(pkgJson)) {
            return null;
        }

        try {
            JsonNode root = new ObjectMapper().readTree(pkgJson.toFile());

            Map<String, String> allDeps = new LinkedHashMap<>();
            for (String section : List.of("dependencies", "devDependencies", "peerDependencies")) {
                JsonNode node = root.get(section);
                if (node != null && node.isObject()) {
                    node.fieldNames().forEachRemaining(key ->
                            allDeps.putIfAbsent(key, node.get(key).asText()));
                }
            }

            String angularVersion = allDeps.get("@angular/core");
            if (angularVersion != null) {
                String version = stripVersionRange(angularVersion);
                LOG.debugf("Detected Angular via package.json: %s", version);
                return new ArchetypeInfo("angular", version);
            }

            String reactVersion = allDeps.get("react");
            if (reactVersion != null) {
                String version = stripVersionRange(reactVersion);
                LOG.debugf("Detected React via package.json: %s", version);
                return new ArchetypeInfo("react", version);
            }

        } catch (Exception e) {
            LOG.warnf("ArchetypeDetector: failed to parse package.json at %s: %s", pkgJson, e.getMessage());
        }

        return null;
    }

    /**
     * Strips leading semver range specifiers ({@code ^}, {@code ~}, {@code >=}, {@code >},
     * {@code <=}, {@code <}, {@code =}) from a version string and returns the first
     * contiguous version token.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code ^18.2.0} → {@code 18.2.0}</li>
     *   <li>{@code ~17.0.0} → {@code 17.0.0}</li>
     *   <li>{@code >=16.0.0 <17.0.0} → {@code 16.0.0}</li>
     *   <li>{@code 19.0.0} → {@code 19.0.0}</li>
     *   <li>{@code latest} → {@code latest}</li>
     * </ul>
     */
    static String stripVersionRange(String version) {
        if (version == null) return null;
        String v = version.trim().replaceAll("^[~^>=<*]+", "").trim();
        int spaceIdx = v.indexOf(' ');
        if (spaceIdx > 0) {
            v = v.substring(0, spaceIdx).trim();
        }
        return v.isEmpty() ? version.trim() : v;
    }

    // ─── XML helpers ─────────────────────────────────────────────────────────────

    /**
     * Extracts all {@code <properties>} entries from the POM into a map.
     */
    private static Map<String, String> extractProperties(Document doc) {
        Map<String, String> map = new HashMap<>();
        NodeList propsList = doc.getElementsByTagName("properties");
        for (int i = 0; i < propsList.getLength(); i++) {
            Node propsNode = propsList.item(i);
            NodeList children = propsNode.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    map.put(child.getNodeName(), child.getTextContent().trim());
                }
            }
        }
        return map;
    }

    /**
     * Returns the text content of the first child element with the given tag name, or null.
     */
    private static String textContent(Element parent, String tagName) {
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl.getLength() == 0) return null;
        String text = nl.item(0).getTextContent();
        return text != null ? text.trim() : null;
    }

    /**
     * Very simple CSS-like path selector for a single chain of element names separated by {@code >}.
     * Only matches direct or indirect children — good enough for pom.xml top-level elements.
     */
    private static String firstText(Document doc, String path) {
        String[] parts = path.split("\\s*>\\s*");
        Node current = doc;
        for (String part : parts) {
            NodeList children = current.getChildNodes();
            Node found = null;
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE
                        && child.getNodeName().equals(part)) {
                    found = child;
                    break;
                }
            }
            if (found == null) return null;
            current = found;
        }
        String text = current.getTextContent();
        return text != null ? text.trim() : null;
    }

    /**
     * Resolves a Maven property reference like {@code ${quarkus.platform.version}} against the
     * provided properties map. Returns the input unchanged if it is not a property reference.
     */
    private static String resolve(String value, Map<String, String> properties) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String key = trimmed.substring(2, trimmed.length() - 1);
            return properties.getOrDefault(key, null);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
