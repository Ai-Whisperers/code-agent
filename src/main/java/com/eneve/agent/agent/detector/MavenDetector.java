package com.eneve.agent.agent.detector;

import com.eneve.agent.agent.ArchetypeDetector.ArchetypeInfo;
import com.eneve.agent.util.XmlParserFactory;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Detects Maven-based project archetypes: Quarkus, WildFly/EAP, and generic
 * Maven packaging ({@code jar}, {@code pom}).
 *
 * <p>Applies only when a {@code pom.xml} is present at the project root.
 * Detection order within Maven projects:
 * <ol>
 *   <li>Quarkus — parent groupId, BOM in {@code dependencyManagement}, known properties.</li>
 *   <li>WildFly — POM parent/BOM/properties/plugin, then Dockerfile fallback.</li>
 *   <li>Generic Maven packaging ({@code pom} or {@code jar}).</li>
 * </ol>
 */
public class MavenDetector implements Detector {

    private static final Logger LOG = Logger.getLogger(MavenDetector.class);

    private final DockerfileDetector dockerfileDetector;

    public MavenDetector(DockerfileDetector dockerfileDetector) {
        this.dockerfileDetector = dockerfileDetector;
    }

    @Override
    public ArchetypeInfo detect(Path projectRoot) {
        Path pomPath = projectRoot.resolve("pom.xml");
        if (!Files.exists(pomPath)) return null;

        Document doc = null;
        Map<String, String> properties = Map.of();
        try (InputStream in = Files.newInputStream(pomPath)) {
            DocumentBuilder builder = XmlParserFactory.createSecureBuilder();
            doc = builder.parse(in);
            doc.getDocumentElement().normalize();
            properties = extractProperties(doc);
        } catch (Exception e) {
            LOG.warnf("MavenDetector: failed to parse pom.xml at %s: %s", pomPath, e.getMessage());
        }

        if (doc != null) {
            Map<String, String> depVersions = new TreeMap<>(detectDependencyVersions(doc, properties));
            mergeModuleXmlDependencies(projectRoot, depVersions);

            ArchetypeInfo quarkus = detectQuarkus(doc, properties);
            if (quarkus != null) return withDependencies(quarkus, depVersions);

            ArchetypeInfo wildfly = detectWildFlyFromPom(doc, properties);
            if (wildfly != null) return withDependencies(wildfly, depVersions);
        }

        ArchetypeInfo wildflyDocker = dockerfileDetector.detectWildFly(projectRoot);
        if (wildflyDocker != null) {
            Map<String, String> depVersions = new TreeMap<>();
            mergeModuleXmlDependencies(projectRoot, depVersions);
            return withDependencies(wildflyDocker, depVersions);
        }

        if (doc != null) {
            return detectMavenPackaging(doc, properties, projectRoot);
        }
        return null;
    }

    // ─── Quarkus ─────────────────────────────────────────────────────────────────

    private ArchetypeInfo detectQuarkus(Document doc, Map<String, String> properties) {
        String parentGroupId = resolve(firstText(doc, "project > parent > groupId"), properties);
        String parentVersion = firstText(doc, "project > parent > version");

        if (isQuarkusGroup(parentGroupId) && parentVersion != null) {
            String resolved = resolve(parentVersion, properties);
            if (resolved != null) {
                LOG.debugf("Detected Quarkus via parent POM: %s", resolved);
                return new ArchetypeInfo("quarkus", resolved);
            }
        }

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

        String version = properties.get("quarkus.platform.version");
        if (version == null) version = properties.get("quarkus.version");
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
        String parentGroupId           = resolve(firstText(doc, "project > parent > groupId"),    properties);
        String wildFlyParentArtifactId = resolve(firstText(doc, "project > parent > artifactId"), properties);
        String parentVersion           = firstText(doc, "project > parent > version");

        if (isWildFlyParent(parentGroupId, wildFlyParentArtifactId) && parentVersion != null) {
            String resolved = resolve(parentVersion, properties);
            if (resolved != null) {
                LOG.debugf("Detected WildFly via parent POM: %s", resolved);
                return new ArchetypeInfo("wildfly", resolved);
            }
        }

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

        for (String key : List.of("wildfly.version", "version.wildfly", "version.wildfly.server",
                                   "jboss.eap.version", "version.jboss.eap")) {
            String v = properties.get(key);
            if (v != null && !v.startsWith("$")) {
                LOG.debugf("Detected WildFly via property %s: %s", key, v);
                return new ArchetypeInfo("wildfly", v);
            }
        }

        NodeList plugins = doc.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Node node = plugins.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element plugin = (Element) node;
            String gid = resolve(textContent(plugin, "groupId"), properties);
            String aid = resolve(textContent(plugin, "artifactId"), properties);
            if (isWildFlyMavenPlugin(gid, aid)) {
                LOG.debugf("Detected WildFly via wildfly-maven-plugin (version unknown from plugin alone)");
                return new ArchetypeInfo("wildfly", "unknown");
            }
        }

        return null;
    }

    private static boolean isWildFlyParent(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) return false;
        if ((groupId.equals("org.wildfly") || groupId.equals("org.jboss"))
                && (artifactId.contains("wildfly") || artifactId.contains("jboss-eap"))) {
            return true;
        }
        return groupId.equals("com.redhat.eap") && artifactId.contains("eap");
    }

    private static boolean isWildFlyBom(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) return false;
        if ((groupId.equals("org.wildfly.bom") || groupId.equals("org.jboss.bom"))
                && (artifactId.contains("wildfly") || artifactId.contains("jboss-eap"))) {
            return true;
        }
        return (groupId.equals("com.redhat.eap") || groupId.equals("org.jboss.eap"))
                && artifactId.contains("eap");
    }

    private static boolean isWildFlyMavenPlugin(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) return false;
        return (groupId.equals("org.wildfly.plugins") || groupId.equals("org.jboss.as.plugins"))
                && artifactId.contains("wildfly");
    }

    // ─── Maven packaging ─────────────────────────────────────────────────────────

    private ArchetypeInfo detectMavenPackaging(Document doc, Map<String, String> properties, Path projectRoot) {
        String packaging = firstText(doc, "project > packaging");
        String version   = resolve(firstText(doc, "project > version"), properties);
        if (version == null) version = "unknown";

        if ("pom".equalsIgnoreCase(packaging)) {
            // A pom-packaged project that also contains Java source is a jar in disguise.
            // This happens when a single-module project incorrectly declares pom packaging,
            // or when a parent POM lives alongside source code in the same directory.
            if (hasJavaSource(projectRoot)) {
                LOG.debugf("Detected Maven jar (pom packaging overridden by src/main/java presence): version %s", version);
                return new ArchetypeInfo("jar", version);
            }
            LOG.debugf("Detected Maven pom (multi-module / BOM): version %s", version);
            return new ArchetypeInfo("pom", version);
        }
        if (packaging == null || "jar".equalsIgnoreCase(packaging)) {
            LOG.debugf("Detected Maven jar: version %s", version);
            return new ArchetypeInfo("jar", version);
        }
        return null;
    }

    /**
     * Returns {@code true} when the project root contains at least one {@code .java} file
     * under {@code src/main/java}, indicating the presence of compiled Java source.
     */
    private static boolean hasJavaSource(Path projectRoot) {
        Path srcMain = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(srcMain)) return false;
        try (var stream = Files.walk(srcMain, 10)) {
            return stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().endsWith(".java"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    // ─── Dependency version detection ────────────────────────────────────────────

    Map<String, String> detectDependencyVersions(Document doc, Map<String, String> properties) {
        Map<String, String> found = new TreeMap<>();
        NodeList deps = doc.getElementsByTagName("dependency");
        for (int i = 0; i < deps.getLength(); i++) {
            Node node = deps.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element dep = (Element) node;
            String groupId    = resolve(textContent(dep, "groupId"),    properties);
            String artifactId = resolve(textContent(dep, "artifactId"), properties);
            String version    = textContent(dep, "version");

            if ("org.postgresql".equals(groupId) && "postgresql".equals(artifactId) && version != null) {
                String resolved = resolve(version, properties);
                if (resolved != null && !resolved.startsWith("$")) {
                    LOG.debugf("MavenDetector: detected postgresql-jdbc version %s from pom.xml", resolved);
                    found.put("postgresql-jdbc", resolved);
                    found.put("postgresql-jdbc-source", "pom");
                }
            }
        }
        return found;
    }

    void mergeModuleXmlDependencies(Path projectRoot, Map<String, String> depVersions) {
        if (depVersions.containsKey("postgresql-jdbc")) return;
        Path configDir = projectRoot.resolve("config");
        if (!Files.isDirectory(configDir)) return;
        try (var stream = Files.walk(configDir, 6)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().equalsIgnoreCase("module.xml"))
                  .filter(p -> !DockerfileDetector.isInSkippedDir(projectRoot, p))
                  .forEach(moduleXml -> probeModuleXmlForPostgres(moduleXml, depVersions));
        } catch (java.io.IOException e) {
            LOG.debugf("MavenDetector: cannot walk config/ for module.xml files: %s", e.getMessage());
        }
    }

    private static final java.util.regex.Pattern POSTGRES_JAR_PATTERN =
            java.util.regex.Pattern.compile("(?i)^postgresql-(\\d+(?:\\.\\d+)*)(?:\\.jdbc\\d+)?\\.jar$");

    private void probeModuleXmlForPostgres(Path moduleXml, Map<String, String> depVersions) {
        if (depVersions.containsKey("postgresql-jdbc")) return;
        try {
            String content = Files.readString(moduleXml, java.nio.charset.StandardCharsets.UTF_8);
            if (!content.toLowerCase().contains("postgresql")) return;
            DocumentBuilder builder = XmlParserFactory.createSecureBuilder();
            builder.setErrorHandler(null);
            Document doc = builder.parse(moduleXml.toFile());
            doc.getDocumentElement().normalize();

            NodeList resources = doc.getElementsByTagName("resource-root");
            for (int i = 0; i < resources.getLength(); i++) {
                Node node = resources.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                String path = ((Element) node).getAttribute("path");
                if (path == null || path.isBlank()) continue;
                String filename = Path.of(path).getFileName().toString();
                java.util.regex.Matcher m = POSTGRES_JAR_PATTERN.matcher(filename);
                if (m.matches()) {
                    String version = m.group(1);
                    LOG.debugf("MavenDetector: detected postgresql-jdbc version %s from %s", version, moduleXml);
                    depVersions.put("postgresql-jdbc", version);
                    depVersions.put("postgresql-jdbc-source", "module.xml");
                    return;
                }
            }
        } catch (Exception e) {
            LOG.debugf("MavenDetector: cannot parse module.xml at %s: %s", moduleXml, e.getMessage());
        }
    }

    // ─── XML helpers ─────────────────────────────────────────────────────────────

    static Map<String, String> extractProperties(Document doc) {
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

    static String textContent(Element parent, String tagName) {
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl.getLength() == 0) return null;
        String text = nl.item(0).getTextContent();
        return text != null ? text.trim() : null;
    }

    static String firstText(Document doc, String path) {
        String[] parts = path.split("\\s*>\\s*");
        Node current = doc;
        for (String part : parts) {
            NodeList children = current.getChildNodes();
            Node found = null;
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(part)) {
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

    static String resolve(String value, Map<String, String> properties) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String key = trimmed.substring(2, trimmed.length() - 1);
            return properties.getOrDefault(key, null);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ArchetypeInfo withDependencies(ArchetypeInfo base, Map<String, String> depVersions) {
        if (depVersions.isEmpty()) return base;
        Map<String, String> merged = new TreeMap<>(base.dependencyVersions());
        merged.putAll(depVersions);
        return new ArchetypeInfo(base.archetype(), base.version(), merged);
    }
}
