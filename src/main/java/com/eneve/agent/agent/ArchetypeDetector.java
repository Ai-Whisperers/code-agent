package com.eneve.agent.agent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Detects the primary framework archetype (e.g. Quarkus, WildFly) and its version
 * from a Maven {@code pom.xml} in the project root.
 *
 * <p>Detection is intentionally lightweight: it reads only the root {@code pom.xml},
 * resolves {@code ${property}} references within the same file, and looks for
 * well-known BOM artifacts and parent group IDs. It does not fetch parent POMs
 * from Maven Central.
 */
@ApplicationScoped
public class ArchetypeDetector {

    private static final Logger LOG = Logger.getLogger(ArchetypeDetector.class);

    public record ArchetypeInfo(String archetype, String version) {}

    /**
     * Attempts to detect the framework and version from {@code pom.xml} in the given directory.
     *
     * @param projectRoot root directory of the cloned repository
     * @return detected archetype info, or {@code null} if not detected or on parse error
     */
    public ArchetypeInfo detect(Path projectRoot) {
        Path pomPath = projectRoot.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return null;
        }

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

            ArchetypeInfo wildfly = detectWildFly(doc, properties);
            if (wildfly != null) {
                return wildfly;
            }

        } catch (Exception e) {
            LOG.warnf("ArchetypeDetector: failed to parse pom.xml at %s: %s", pomPath, e.getMessage());
        }
        return null;
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

    // ─── WildFly ─────────────────────────────────────────────────────────────────

    private ArchetypeInfo detectWildFly(Document doc, Map<String, String> properties) {
        // Check parent for jboss-parent or wildfly-parent
        String parentGroupId = resolve(firstText(doc, "project > parent > groupId"), properties);
        String wildFlyParentArtifactId = resolve(firstText(doc, "project > parent > artifactId"), properties);
        String parentVersion = firstText(doc, "project > parent > version");

        if (isWildFlyParent(parentGroupId, wildFlyParentArtifactId) && parentVersion != null) {
            String resolved = resolve(parentVersion, properties);
            if (resolved != null) {
                LOG.debugf("Detected WildFly via parent POM: %s", resolved);
                return new ArchetypeInfo("wildfly", resolved);
            }
        }

        // Check dependencyManagement for wildfly-bom or jboss-eap-jakartaee8-with-tools.
        // Resolve groupId and artifactId through properties for consistency.
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

        return null;
    }

    private static boolean isWildFlyParent(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) return false;
        return (groupId.equals("org.wildfly") || groupId.equals("org.jboss"))
                && (artifactId.contains("wildfly") || artifactId.contains("jboss-eap"));
    }

    private static boolean isWildFlyBom(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) return false;
        return (groupId.equals("org.wildfly.bom") || groupId.equals("org.jboss.bom"))
                && (artifactId.contains("wildfly") || artifactId.contains("jboss-eap"));
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
