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
import java.util.TreeMap;

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
 * Detects the primary framework archetype and its version from project files.
 *
 * <p>Detection order:
 * <ol>
 *   <li><b>Maven projects</b> ({@code pom.xml} present):
 *     <ol>
 *       <li>Quarkus — parent groupId, BOM in {@code dependencyManagement}, known properties.</li>
 *       <li>WildFly — POM parent/BOM/properties/plugin, then Dockerfile fallback.</li>
 *       <li>Maven packaging — {@code <packaging>pom</packaging>} → {@code "pom"},
 *           {@code jar} or absent → {@code "jar"}.</li>
 *     </ol>
 *   </li>
 *   <li><b>Non-Maven projects</b> (no {@code pom.xml}):
 *     <ol>
 *       <li>.NET — {@code .csproj}, {@code .fsproj}, {@code .vbproj}, or {@code .sln}.</li>
 *       <li>Angular / React — {@code package.json} dependencies.</li>
 *       <li>WildFly Dockerfile fallback.</li>
 *       <li>Docker — any {@code Dockerfile} or {@code docker-compose.yml} at root.</li>
 *       <li>Terraform — {@code *.tf} files (up to depth 2).</li>
 *       <li>SQL — {@code *.sql} files (up to depth 3).</li>
 *       <li>Shell — {@code *.sh} files at root.</li>
 *     </ol>
 *   </li>
 * </ol>
 *
 * <p>Detection is intentionally lightweight: it reads only a small, well-known set of files
 * and does not fetch remote metadata.
 */
@ApplicationScoped
public class ArchetypeDetector {

    private static final Logger LOG = Logger.getLogger(ArchetypeDetector.class);

    public record ArchetypeInfo(String archetype, String version, Map<String, String> dependencyVersions) {

        /** Convenience constructor for archetypes with no tracked extra dependencies. */
        public ArchetypeInfo(String archetype, String version) {
            this(archetype, version, Map.of());
        }
    }

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
     * Attempts to detect the primary archetype and version for the given project root.
     *
     * <p>See the class-level Javadoc for the full detection hierarchy.
     *
     * @param projectRoot root directory of the cloned repository
     * @return detected archetype info, or {@code null} if not detected or on parse error
     */
    public ArchetypeInfo detect(Path projectRoot) {
        Path pomPath = projectRoot.resolve("pom.xml");

        if (Files.exists(pomPath)) {
            Document doc = null;
            Map<String, String> properties = Map.of();
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
                doc = builder.parse(in);
                doc.getDocumentElement().normalize();
                properties = extractProperties(doc);
            } catch (Exception e) {
                LOG.warnf("ArchetypeDetector: failed to parse pom.xml at %s: %s", pomPath, e.getMessage());
            }

            if (doc != null) {
                // Start with POM dependency versions, then layer in WildFly module.xml detections.
                Map<String, String> depVersions = new TreeMap<>(detectDependencyVersions(doc, properties));
                mergeModuleXmlDependencies(projectRoot, depVersions);

                ArchetypeInfo quarkus = detectQuarkus(doc, properties);
                if (quarkus != null) return withDependencies(quarkus, depVersions);

                ArchetypeInfo wildfly = detectWildFlyFromPom(doc, properties);
                if (wildfly != null) return withDependencies(wildfly, depVersions);
            }

            // Dockerfile fallback — useful when the POM alone is not conclusive.
            ArchetypeInfo wildflyDocker = detectWildFlyFromDockerfiles(projectRoot);
            if (wildflyDocker != null) {
                Map<String, String> depVersions = new TreeMap<>();
                mergeModuleXmlDependencies(projectRoot, depVersions);
                return withDependencies(wildflyDocker, depVersions);
            }

            // Generic Maven packaging — must come after all framework-specific checks.
            if (doc != null) {
                return detectMavenPackaging(doc, properties);
            }
            return null;
        }

        // No pom.xml — non-Java project.
        ArchetypeInfo dotnet = detectDotnet(projectRoot);
        if (dotnet != null) return dotnet;

        ArchetypeInfo phpResult = detectPhpFramework(projectRoot);
        if (phpResult != null) return phpResult;

        ArchetypeInfo tsResult = detectTypeScriptFrontend(projectRoot);
        if (tsResult != null) return tsResult;

        ArchetypeInfo wildflyDocker = detectWildFlyFromDockerfiles(projectRoot);
        if (wildflyDocker != null) return wildflyDocker;

        ArchetypeInfo docker = detectDockerProject(projectRoot);
        if (docker != null) return docker;

        ArchetypeInfo terraform = detectTerraform(projectRoot);
        if (terraform != null) return terraform;

        ArchetypeInfo sql = detectSql(projectRoot);
        if (sql != null) return sql;

        return detectShell(projectRoot);
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

    // ─── Maven packaging (jar / pom) ─────────────────────────────────────────────

    /**
     * Classifies a Maven project that did not match any framework-specific archetype.
     *
     * <ul>
     *   <li>{@code <packaging>pom</packaging>} → {@code "pom"} (multi-module parent or BOM)</li>
     *   <li>{@code <packaging>jar</packaging>} or no {@code <packaging>} element → {@code "jar"}</li>
     * </ul>
     *
     * <p>Version is taken from the project's own {@code <version>} element.
     */
    private ArchetypeInfo detectMavenPackaging(Document doc, Map<String, String> properties) {
        String packaging = firstText(doc, "project > packaging");
        String version   = resolve(firstText(doc, "project > version"), properties);
        if (version == null) {
            version = "unknown";
        }

        if ("pom".equalsIgnoreCase(packaging)) {
            LOG.debugf("Detected Maven pom (multi-module / BOM): version %s", version);
            return new ArchetypeInfo("pom", version);
        }

        if (packaging == null || "jar".equalsIgnoreCase(packaging)) {
            LOG.debugf("Detected Maven jar: version %s", version);
            return new ArchetypeInfo("jar", version);
        }

        // war, ear, rar, etc. — not yet classified as a named archetype
        return null;
    }

    // ─── .NET ────────────────────────────────────────────────────────────────────

    /**
     * Project-file extensions that indicate a .NET SDK project.
     */
    private static final Set<String> DOTNET_PROJECT_EXTENSIONS = Set.of(
            ".csproj", ".fsproj", ".vbproj"
    );

    /**
     * Detects a .NET project from {@code *.csproj}, {@code *.fsproj}, {@code *.vbproj}, or
     * {@code *.sln} files at the project root, then reads {@code <TargetFramework>} (or
     * {@code <TargetFrameworks>} taking the first entry) from the project file. Falls back to
     * {@code global.json} {@code sdk.version} when no project file contains a target framework.
     */
    ArchetypeInfo detectDotnet(Path projectRoot) {
        // 1. Look for SDK project files at root depth only
        try (Stream<Path> stream = Files.list(projectRoot)) {
            List<Path> projectFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return DOTNET_PROJECT_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .sorted()
                    .toList();

            for (Path proj : projectFiles) {
                String version = readDotnetTargetFramework(proj);
                if (version != null) {
                    LOG.debugf("Detected .NET via %s: %s", proj.getFileName(), version);
                    return new ArchetypeInfo("dotnet", version);
                }
            }

            // No SDK project files with a target framework — check for a .sln and treat
            // the presence itself as a signal; version comes from global.json if available.
            boolean hasSln = Files.list(projectRoot)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".sln"));
            if (!hasSln && projectFiles.isEmpty()) {
                return null;
            }
        } catch (IOException e) {
            LOG.debugf("ArchetypeDetector: cannot list root dir for .NET detection: %s", e.getMessage());
            return null;
        }

        // 2. Fall back to global.json sdk.version
        Path globalJson = projectRoot.resolve("global.json");
        if (Files.exists(globalJson)) {
            try {
                JsonNode root = new ObjectMapper().readTree(globalJson.toFile());
                JsonNode sdk = root.get("sdk");
                if (sdk != null) {
                    JsonNode sdkVersion = sdk.get("version");
                    if (sdkVersion != null && !sdkVersion.asText().isBlank()) {
                        String v = sdkVersion.asText().trim();
                        LOG.debugf("Detected .NET via global.json sdk.version: %s", v);
                        return new ArchetypeInfo("dotnet", v);
                    }
                }
            } catch (Exception e) {
                LOG.debugf("ArchetypeDetector: failed to parse global.json: %s", e.getMessage());
            }
        }

        LOG.debugf("Detected .NET project (no target framework found)");
        return new ArchetypeInfo("dotnet", "unknown");
    }

    /**
     * Reads a {@code .csproj}/{@code .fsproj}/{@code .vbproj} file and returns the value of
     * the first {@code <TargetFramework>} or the first token of {@code <TargetFrameworks>},
     * or {@code null} if neither element is present.
     */
    private String readDotnetTargetFramework(Path projectFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Suppress SAX error output for malformed project files
            builder.setErrorHandler(null);
            try (InputStream in = Files.newInputStream(projectFile)) {
                Document doc = builder.parse(in);
                doc.getDocumentElement().normalize();

                NodeList tf = doc.getElementsByTagName("TargetFramework");
                if (tf.getLength() > 0) {
                    String text = tf.item(0).getTextContent().trim();
                    return text.isEmpty() ? null : text;
                }

                NodeList tfs = doc.getElementsByTagName("TargetFrameworks");
                if (tfs.getLength() > 0) {
                    String text = tfs.item(0).getTextContent().trim();
                    if (!text.isEmpty()) {
                        // Take the first framework in a semicolon-separated list
                        return text.split(";")[0].trim();
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("ArchetypeDetector: cannot read .NET project file %s: %s",
                    projectFile.getFileName(), e.getMessage());
        }
        return null;
    }

    // ─── PHP (Laravel / Symfony) ─────────────────────────────────────────────────

    /**
     * Detects the PHP framework from {@code composer.json}.
     *
     * <p>Detection priority:
     * <ol>
     *   <li>Laravel ({@code laravel/framework} in require)</li>
     *   <li>Symfony ({@code symfony/framework-bundle} in require)</li>
     *   <li>Generic PHP ({@code composer.json} present but no recognised framework)</li>
     * </ol>
     */
    ArchetypeInfo detectPhpFramework(Path projectRoot) {
        Path composerJson = projectRoot.resolve("composer.json");
        if (!Files.exists(composerJson)) {
            return null;
        }

        try {
            JsonNode root = new ObjectMapper().readTree(composerJson.toFile());

            Map<String, String> allDeps = new java.util.LinkedHashMap<>();
            for (String section : List.of("require", "require-dev")) {
                JsonNode node = root.get(section);
                if (node != null && node.isObject()) {
                    node.fieldNames().forEachRemaining(key ->
                            allDeps.putIfAbsent(key, node.get(key).asText()));
                }
            }

            String laravelVersion = allDeps.get("laravel/framework");
            if (laravelVersion != null) {
                String version = stripVersionRange(laravelVersion);
                LOG.debugf("Detected Laravel via composer.json: %s", version);
                return new ArchetypeInfo("laravel", version);
            }

            String symfonyVersion = allDeps.get("symfony/framework-bundle");
            if (symfonyVersion != null) {
                String version = stripVersionRange(symfonyVersion);
                LOG.debugf("Detected Symfony via composer.json: %s", version);
                return new ArchetypeInfo("symfony", version);
            }

            // Generic PHP project
            JsonNode phpVersion = root.path("require").path("php");
            if (!phpVersion.isMissingNode()) {
                String version = stripVersionRange(phpVersion.asText());
                LOG.debugf("Detected generic PHP via composer.json: %s", version);
                return new ArchetypeInfo("php", version);
            }

            // composer.json exists but no "php" constraint — still a PHP project
            LOG.debugf("Detected generic PHP project (no framework or php version constraint found)");
            return new ArchetypeInfo("php", "unknown");

        } catch (Exception e) {
            LOG.warnf("ArchetypeDetector: failed to parse composer.json at %s: %s",
                    composerJson, e.getMessage());
        }
        return null;
    }

    // ─── Docker ──────────────────────────────────────────────────────────────────

    /**
     * Pattern that matches the first {@code FROM} instruction in a Dockerfile and captures
     * the image tag. Matches {@code FROM image:tag} and {@code FROM image:tag AS alias}.
     * Does NOT match multi-stage {@code FROM scratch} (no tag).
     */
    private static final Pattern DOCKER_FROM_TAG_PATTERN = Pattern.compile(
            "^\\s*FROM\\s+[^:\\s]+:([^\\s]+)(?:\\s+AS\\s+\\S+)?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Detects a generic Docker project when no higher-priority archetype was found.
     *
     * <p>Looks for a {@code Dockerfile} or {@code docker-compose.yml}/{@code docker-compose.yaml}
     * at the project root. Extracts the image tag from the first {@code FROM} line as the
     * version; falls back to {@code "unknown"} when no tag is present.
     */
    ArchetypeInfo detectDockerProject(Path projectRoot) {
        // 1. Root-level Dockerfile
        for (String name : List.of("Dockerfile", "Dockerfile.jvm", "Dockerfile.native")) {
            Path candidate = projectRoot.resolve(name);
            if (Files.isRegularFile(candidate)) {
                String version = extractDockerFromTag(candidate);
                LOG.debugf("Detected Docker project via %s: %s", name, version);
                return new ArchetypeInfo("docker", version);
            }
        }

        // 2. docker-compose at root
        for (String name : List.of("docker-compose.yml", "docker-compose.yaml",
                                   "compose.yml", "compose.yaml")) {
            if (Files.isRegularFile(projectRoot.resolve(name))) {
                LOG.debugf("Detected Docker project via %s", name);
                return new ArchetypeInfo("docker", "unknown");
            }
        }

        return null;
    }

    private String extractDockerFromTag(Path dockerfile) {
        try {
            for (String line : Files.readAllLines(dockerfile, StandardCharsets.UTF_8)) {
                Matcher m = DOCKER_FROM_TAG_PATTERN.matcher(line);
                if (m.matches()) {
                    return m.group(1);
                }
            }
        } catch (IOException e) {
            LOG.debugf("ArchetypeDetector: cannot read %s: %s", dockerfile.getFileName(), e.getMessage());
        }
        return "unknown";
    }

    // ─── Terraform ───────────────────────────────────────────────────────────────

    /**
     * Pattern matching {@code required_version = "..."} inside a {@code terraform} block.
     */
    private static final Pattern TERRAFORM_REQUIRED_VERSION = Pattern.compile(
            "required_version\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Detects a Terraform project from {@code *.tf} files (scanned up to depth 2) or a
     * {@code .terraform-version} pin file at root.
     *
     * <p>Version is the {@code required_version} constraint from the {@code terraform {}} block
     * (e.g. {@code ">= 1.5.0"}), the content of {@code .terraform-version}, or {@code "unknown"}.
     */
    ArchetypeInfo detectTerraform(Path projectRoot) {
        // 1. .terraform-version pin file takes precedence as an explicit lock
        Path pinFile = projectRoot.resolve(".terraform-version");
        if (Files.isRegularFile(pinFile)) {
            try {
                String v = Files.readString(pinFile, StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) {
                    LOG.debugf("Detected Terraform via .terraform-version: %s", v);
                    return new ArchetypeInfo("terraform", v);
                }
            } catch (IOException e) {
                LOG.debugf("ArchetypeDetector: cannot read .terraform-version: %s", e.getMessage());
            }
        }

        // 2. Scan for *.tf files up to depth 2
        try (Stream<Path> stream = Files.walk(projectRoot, 2)) {
            List<Path> tfFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".tf"))
                    .filter(p -> !isInSkippedDir(projectRoot, p))
                    .sorted()
                    .toList();

            if (tfFiles.isEmpty()) return null;

            // Look for required_version in any .tf file
            for (Path tf : tfFiles) {
                try {
                    String content = Files.readString(tf, StandardCharsets.UTF_8);
                    Matcher m = TERRAFORM_REQUIRED_VERSION.matcher(content);
                    if (m.find()) {
                        String v = m.group(1).trim();
                        LOG.debugf("Detected Terraform via required_version in %s: %s",
                                tf.getFileName(), v);
                        return new ArchetypeInfo("terraform", v);
                    }
                } catch (IOException e) {
                    LOG.debugf("ArchetypeDetector: cannot read %s: %s", tf.getFileName(), e.getMessage());
                }
            }

            // .tf files present but no required_version found
            LOG.debugf("Detected Terraform project (no required_version constraint found)");
            return new ArchetypeInfo("terraform", "unknown");

        } catch (IOException e) {
            LOG.debugf("ArchetypeDetector: cannot walk project for Terraform detection: %s", e.getMessage());
            return null;
        }
    }

    // ─── SQL ─────────────────────────────────────────────────────────────────────

    /**
     * Pattern matching Flyway/Liquibase versioned migration file names such as
     * {@code V12__create_table.sql} or {@code V3_1__fix.sql}.
     */
    private static final Pattern SQL_MIGRATION_VERSION = Pattern.compile(
            "^[Vv](\\d+(?:[._]\\d+)*)__.*\\.sql$"
    );

    /**
     * Detects a SQL-centric project from {@code *.sql} files scanned up to depth 3.
     *
     * <p>Attempts to infer a version from the highest Flyway/Liquibase migration number
     * ({@code V<n>__<description>.sql}); falls back to {@code "unknown"} when no versioned
     * migrations are present.
     */
    ArchetypeInfo detectSql(Path projectRoot) {
        try (Stream<Path> stream = Files.walk(projectRoot, DOCKERFILE_SCAN_MAX_DEPTH)) {
            List<Path> sqlFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .filter(p -> !isInSkippedDir(projectRoot, p))
                    .toList();

            if (sqlFiles.isEmpty()) return null;

            // Find the highest numeric migration version
            int maxVersion = -1;
            for (Path sql : sqlFiles) {
                Matcher m = SQL_MIGRATION_VERSION.matcher(sql.getFileName().toString());
                if (m.matches()) {
                    try {
                        int n = Integer.parseInt(m.group(1).replace("_", "").replace(".", ""));
                        if (n > maxVersion) maxVersion = n;
                    } catch (NumberFormatException ignored) {
                        // non-numeric segment — skip
                    }
                }
            }

            String version = maxVersion >= 0 ? "V" + maxVersion : "unknown";
            LOG.debugf("Detected SQL project: highest migration version %s", version);
            return new ArchetypeInfo("sql", version);

        } catch (IOException e) {
            LOG.debugf("ArchetypeDetector: cannot walk project for SQL detection: %s", e.getMessage());
            return null;
        }
    }

    // ─── Shell ───────────────────────────────────────────────────────────────────

    /**
     * Detects a shell-script project from {@code *.sh} files present directly at the
     * project root (depth 1 only, to avoid false positives from build tooling scripts).
     *
     * <p>Version is always {@code "unknown"} — shell scripts have no reliable framework
     * version comparable to a language runtime or framework BOM.
     */
    ArchetypeInfo detectShell(Path projectRoot) {
        try (Stream<Path> stream = Files.list(projectRoot)) {
            boolean hasShell = stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().endsWith(".sh"));
            if (hasShell) {
                LOG.debugf("Detected shell-script project");
                return new ArchetypeInfo("shell", "unknown");
            }
        } catch (IOException e) {
            LOG.debugf("ArchetypeDetector: cannot list root dir for shell detection: %s", e.getMessage());
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

    // ─── Dependency version detection ────────────────────────────────────────────

    /**
     * Scans all {@code <dependency>} elements in the POM and records versions for
     * well-known dependencies that are tracked for independent upgrades.
     *
     * <p>Currently tracked:
     * <ul>
     *   <li>{@code org.postgresql:postgresql} → key {@code "postgresql-jdbc"}</li>
     * </ul>
     *
     * @return a map of dependency key → resolved version string; never null, may be empty
     */
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
                    LOG.debugf("ArchetypeDetector: detected postgresql-jdbc version %s from pom.xml", resolved);
                    found.put("postgresql-jdbc", resolved);
                    found.put("postgresql-jdbc-source", "pom");
                }
            }
        }
        return found;
    }

    /**
     * Pattern matching PostgreSQL JDBC JAR filenames such as {@code postgresql-42.7.3.jar}.
     * Captures the version segment between the first hyphen and {@code .jar}.
     */
    private static final Pattern POSTGRES_JAR_PATTERN =
            Pattern.compile("(?i)^postgresql-(\\d+(?:\\.\\d+)*)(?:\\.jdbc\\d+)?\\.jar$");

    /**
     * Scans for WildFly {@code module.xml} files under {@code config/} (up to depth 6) that
     * reference a PostgreSQL JDBC JAR via a {@code <resource-root path="postgresql-*.jar"/>}
     * element. Merges any found version into the provided {@code depVersions} map under the
     * key {@code "postgresql-jdbc"}, but only if the key is not already set (pom.xml wins).
     *
     * <p>This covers the common WildFly pattern where the driver is installed as a server
     * module rather than declared as a Maven dependency.
     */
    void mergeModuleXmlDependencies(Path projectRoot, Map<String, String> depVersions) {
        if (depVersions.containsKey("postgresql-jdbc")) {
            return; // already detected from pom.xml — source already recorded, no need to scan
        }
        Path configDir = projectRoot.resolve("config");
        if (!Files.isDirectory(configDir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(configDir, 6)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().equalsIgnoreCase("module.xml"))
                  .filter(p -> !isInSkippedDir(projectRoot, p))
                  .forEach(moduleXml -> probeModuleXmlForPostgres(moduleXml, depVersions));
        } catch (IOException e) {
            LOG.debugf("ArchetypeDetector: cannot walk config/ for module.xml files: %s", e.getMessage());
        }
    }

    /**
     * Reads a single {@code module.xml} and, if it contains a {@code <resource-root>} whose
     * {@code path} attribute matches a PostgreSQL JDBC JAR filename, records the version in
     * {@code depVersions} under {@code "postgresql-jdbc"}.
     */
    private void probeModuleXmlForPostgres(Path moduleXml, Map<String, String> depVersions) {
        if (depVersions.containsKey("postgresql-jdbc")) {
            return; // already found in an earlier module.xml
        }
        try {
            String content = Files.readString(moduleXml, StandardCharsets.UTF_8);
            // Quick pre-check before XML parsing
            if (!content.toLowerCase().contains("postgresql")) {
                return;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Document doc = builder.parse(moduleXml.toFile());
            doc.getDocumentElement().normalize();

            NodeList resources = doc.getElementsByTagName("resource-root");
            for (int i = 0; i < resources.getLength(); i++) {
                Node node = resources.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                String path = ((Element) node).getAttribute("path");
                if (path == null || path.isBlank()) continue;
                // Use only the filename portion in case path contains directories
                String filename = Path.of(path).getFileName().toString();
                Matcher m = POSTGRES_JAR_PATTERN.matcher(filename);
                if (m.matches()) {
                    String version = m.group(1);
                    LOG.debugf("ArchetypeDetector: detected postgresql-jdbc version %s from %s",
                            version, moduleXml);
                    depVersions.put("postgresql-jdbc", version);
                    depVersions.put("postgresql-jdbc-source", "module.xml");
                    return;
                }
            }
        } catch (Exception e) {
            LOG.debugf("ArchetypeDetector: cannot parse module.xml at %s: %s",
                    moduleXml, e.getMessage());
        }
    }

    /**
     * Returns a new {@link ArchetypeInfo} that merges the detected dependency versions
     * into the base info's map. If {@code depVersions} is empty, returns the original.
     */
    private static ArchetypeInfo withDependencies(ArchetypeInfo base, Map<String, String> depVersions) {
        if (depVersions.isEmpty()) return base;
        Map<String, String> merged = new TreeMap<>(base.dependencyVersions());
        merged.putAll(depVersions);
        return new ArchetypeInfo(base.archetype(), base.version(), merged);
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
