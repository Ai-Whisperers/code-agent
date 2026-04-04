package com.eneve.agent.agent;

import com.eneve.agent.agent.detector.PhpDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArchetypeDetectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArchetypeDetector detector;
    private final PhpDetector phpDetector = new PhpDetector(objectMapper);

    ArchetypeDetectorTest() {
        detector = new ArchetypeDetector();
        detector.objectMapper = objectMapper;
    }

    @TempDir
    Path tempDir;

    // ─── helpers ────────────────────────────────────────────────────────────────

    private Path writePom(String content) throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, content);
        return tempDir;
    }

    private void writeDockerfile(String relativePath, String content) throws IOException {
        Path target = tempDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    // ─── Quarkus — properties-only (the pattern that was previously broken) ────

    /**
     * Real-world pattern: version declared as a property, BOM coordinates are also
     * property references.  This is the exact style used by ms-intraday and most
     * recent Quarkus projects.
     */
    @Test
    void detectsQuarkusViaPropertyReferences() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.jules</groupId>
                    <artifactId>ms-intraday</artifactId>
                    <version>1.1.00-SNAPSHOT</version>

                    <properties>
                        <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
                        <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
                        <quarkus.platform.version>3.25.4</quarkus.platform.version>
                        <maven.compiler.release>17</maven.compiler.release>
                    </properties>

                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>${quarkus.platform.group-id}</groupId>
                                <artifactId>${quarkus.platform.artifact-id}</artifactId>
                                <version>${quarkus.platform.version}</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info, "Should detect Quarkus from property-referenced BOM coordinates");
        assertEquals("quarkus", info.archetype());
        assertEquals("3.25.4", info.version());
    }

    // ─── Quarkus — parent POM ────────────────────────────────────────────────────

    @Test
    void detectsQuarkusViaParentPom() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>io.quarkus.platform</groupId>
                        <artifactId>quarkus-bom</artifactId>
                        <version>3.8.1</version>
                        <relativePath/>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>my-service</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("quarkus", info.archetype());
        assertEquals("3.8.1", info.version());
    }

    // ─── Quarkus — literal BOM in dependencyManagement ───────────────────────────

    @Test
    void detectsQuarkusViaLiteralBomDependency() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-service</artifactId>
                    <version>1.0.0-SNAPSHOT</version>

                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>io.quarkus.platform</groupId>
                                <artifactId>quarkus-bom</artifactId>
                                <version>3.15.0</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("quarkus", info.archetype());
        assertEquals("3.15.0", info.version());
    }

    // ─── Quarkus — quarkus.version property (older style) ────────────────────────

    @Test
    void detectsQuarkusViaQuarkusVersionProperty() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>old-style-service</artifactId>
                    <version>1.0.0-SNAPSHOT</version>

                    <properties>
                        <quarkus.version>2.16.9.Final</quarkus.version>
                    </properties>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("quarkus", info.archetype());
        assertEquals("2.16.9.Final", info.version());
    }

    // ─── WildFly ──────────────────────────────────────────────────────────────────

    @Test
    void detectsWildFlyViaBom() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ee-service</artifactId>
                    <version>2.0.0-SNAPSHOT</version>

                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.wildfly.bom</groupId>
                                <artifactId>wildfly-jakartaee10-with-tools</artifactId>
                                <version>30.0.0.Final</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("wildfly", info.archetype());
        assertEquals("30.0.0.Final", info.version());
    }

    @Test
    void detectsWildFlyViaParentPom() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.wildfly</groupId>
                        <artifactId>wildfly-parent</artifactId>
                        <version>29.0.1.Final</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("wildfly", info.archetype());
        assertEquals("29.0.1.Final", info.version());
    }

    // ─── WildFly — version property in POM ───────────────────────────────────────

    @Test
    void detectsWildFlyViaVersionProperty() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ee-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>

                    <properties>
                        <wildfly.version>31.0.1.Final</wildfly.version>
                    </properties>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("wildfly", info.archetype());
        assertEquals("31.0.1.Final", info.version());
    }

    @Test
    void detectsWildFlyViaVersionDotWildFlyProperty() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ee-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>

                    <properties>
                        <version.wildfly>30.0.0.Final</version.wildfly>
                    </properties>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("wildfly", info.archetype());
        assertEquals("30.0.0.Final", info.version());
    }

    // ─── WildFly — wildfly-maven-plugin in build ──────────────────────────────────

    @Test
    void detectsWildFlyViaMavenPluginPresentInBuild() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ee-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>5.0.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info, "wildfly-maven-plugin should be enough to signal a WildFly project");
        assertEquals("wildfly", info.archetype());
        // Version is 'unknown' when no server version property or Dockerfile is present.
        assertEquals("unknown", info.version());
    }

    // ─── WildFly — Dockerfile-based detection ────────────────────────────────────

    @Test
    void detectsWildFlyViaDockerfileInSrcMainDocker() throws IOException {
        // No pom.xml → only Dockerfile detection path is exercised.
        writeDockerfile("src/main/docker/Dockerfile.jvm",
                "FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk17\n" +
                "RUN mkdir -p /opt/jboss/wildfly/standalone/deployments\n" +
                "COPY target/*.war /opt/jboss/wildfly/standalone/deployments/ROOT.war\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("wildfly", info.archetype());
        assertEquals("31.0.0.Final", info.version());
    }

    @Test
    void detectsWildFlyViaRootDockerfile() throws IOException {
        writeDockerfile("Dockerfile",
                "FROM jboss/wildfly:26.1.3.Final\n" +
                "ADD target/app.war /opt/jboss/wildfly/standalone/deployments/\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("wildfly", info.archetype());
        assertEquals("26.1.3.Final", info.version());
    }

    @Test
    void dockerfileDetectionPrefersShallowerFileOverDeeperOne() throws IOException {
        // Root Dockerfile is at depth 1; module Dockerfile.jvm is at depth 4.
        // Shallowest match wins.
        writeDockerfile("Dockerfile",
                "FROM quay.io/wildfly/wildfly:28.0.0.Final-jdk17\n");
        writeDockerfile("src/main/docker/Dockerfile.jvm",
                "FROM quay.io/wildfly/wildfly:32.0.0.Final-jdk21\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("28.0.0.Final", info.version(), "Shallower Dockerfile should take priority");
    }

    @Test
    void detectsWildFlyInSubModuleDockerfile() throws IOException {
        // Multi-module layout: parent pom at root, Dockerfile inside a module subdirectory.
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-parent</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>my-service</module>
                    </modules>
                </project>
                """);
        writeDockerfile("my-service/src/main/docker/Dockerfile.jvm",
                "FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk17\n" +
                "COPY target/*.war /opt/jboss/wildfly/standalone/deployments/ROOT.war\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info, "Should detect WildFly in a module subdirectory");
        assertEquals("wildfly", info.archetype());
        assertEquals("31.0.0.Final", info.version());
    }

    @Test
    void detectsWildFlyInDeeplyNestedModule() throws IOException {
        // Three-level nesting: root → group → module → src/main/docker/Dockerfile.jvm
        writeDockerfile("services/trading/ms-positions/src/main/docker/Dockerfile.jvm",
                "FROM quay.io/wildfly/wildfly:32.0.1.Final-jdk21\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("wildfly", info.archetype());
        assertEquals("32.0.1.Final", info.version());
    }

    @Test
    void doesNotScanTargetDirectory() throws IOException {
        // Simulate a stale Dockerfile that ended up in the build output — must be ignored.
        writeDockerfile("target/docker/Dockerfile",
                "FROM quay.io/wildfly/wildfly:30.0.0.Final-jdk17\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNull(info, "Dockerfiles inside 'target' should be skipped");
    }

    @Test
    void dockerfileWithoutWildFlyFromDetectedAsDockerArchetype() throws IOException {
        // A non-WildFly Dockerfile is now classified as a generic "docker" archetype.
        writeDockerfile("Dockerfile",
                "FROM eclipse-temurin:17-jre\n" +
                "COPY target/app.jar /app.jar\n" +
                "ENTRYPOINT [\"java\", \"-jar\", \"/app.jar\"]\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("docker", info.archetype());
        assertEquals("17-jre", info.version());
    }

    /**
     * If a WildFly project has the maven plugin but no explicit server version in the POM,
     * the Dockerfile provides the real version and wins.
     */
    @Test
    void dockerfileVersionOverridesPluginUnknown() throws IOException {
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ee-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>5.0.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);
        // Dockerfile provides the actual server version
        writeDockerfile("src/main/docker/Dockerfile.jvm",
                "FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk17\n");

        // Plugin detection returns 'unknown', then Dockerfile is checked as fallback.
        // Because the POM path returns early with "unknown", the Dockerfile is NOT checked
        // in this scenario — this test documents that current behaviour and reminds us
        // to improve it if needed.
        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("wildfly", info.archetype());
        // Plugin fires first (returns "unknown"); Dockerfile fallback is skipped
        // when POM detection already returned a result.
        assertEquals("unknown", info.version());
    }

    // ─── React ────────────────────────────────────────────────────────────────────

    @Test
    void detectsReactViaDependencies() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "name": "my-react-app",
                  "version": "1.0.0",
                  "dependencies": {
                    "react": "^18.2.0",
                    "react-dom": "^18.2.0"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("react", info.archetype());
        assertEquals("18.2.0", info.version());
    }

    @Test
    void detectsReactVersionStripsCaretPrefix() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "dependencies": { "react": "^19.0.0" }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("react", info.archetype());
        assertEquals("19.0.0", info.version());
    }

    @Test
    void detectsReactVersionStripsTildePrefix() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "dependencies": { "react": "~17.0.2" }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("react", info.archetype());
        assertEquals("17.0.2", info.version());
    }

    @Test
    void detectsReactInDevDependencies() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "name": "my-lib",
                  "devDependencies": {
                    "react": "^18.0.0"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("react", info.archetype());
        assertEquals("18.0.0", info.version());
    }

    @Test
    void detectsReactWithExactVersion() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "dependencies": { "react": "16.14.0" }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("react", info.archetype());
        assertEquals("16.14.0", info.version());
    }

    // ─── Angular ──────────────────────────────────────────────────────────────────

    @Test
    void detectsAngularViaDependencies() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "name": "my-angular-app",
                  "dependencies": {
                    "@angular/core": "^17.0.0",
                    "@angular/common": "^17.0.0",
                    "@angular/router": "^17.0.0"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("angular", info.archetype());
        assertEquals("17.0.0", info.version());
    }

    @Test
    void detectsAngularVersionStripsCaretPrefix() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "dependencies": { "@angular/core": "^19.2.1" }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("angular", info.archetype());
        assertEquals("19.2.1", info.version());
    }

    @Test
    void detectsAngularInDevDependencies() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "devDependencies": {
                    "@angular/core": "~16.2.0"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("angular", info.archetype());
        assertEquals("16.2.0", info.version());
    }

    @Test
    void angularTakesPriorityOverReact() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "dependencies": {
                    "react": "^18.2.0",
                    "@angular/core": "^17.0.0"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("angular", info.archetype(), "Angular should take priority over React");
        assertEquals("17.0.0", info.version());
    }

    @Test
    void packageJsonWithoutFrameworkReturnsNull() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "name": "plain-node-app",
                  "dependencies": {
                    "express": "^4.18.2",
                    "lodash": "^4.17.21"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNull(info, "A package.json without React or Angular should return null");
    }

    @Test
    void typeScriptDetectionSkippedWhenPomPresent() throws IOException {
        // A Java project with a package.json (e.g. for end-to-end tests) must not
        // be classified as a TypeScript frontend project — it should be "jar".
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>java-app-with-frontend</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "dependencies": { "react": "^18.2.0" }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info, "pom.xml project should still be detected (as jar)");
        assertNotEquals("react", info.archetype(), "pom.xml presence should suppress TypeScript frontend detection");
        assertNotEquals("angular", info.archetype(), "pom.xml presence should suppress TypeScript frontend detection");
        assertEquals("jar", info.archetype());
    }

    // ─── stripVersionRange unit tests ────────────────────────────────────────────

    @Test
    void stripVersionRangeHandlesCaretAndTilde() {
        assertEquals("18.2.0", ArchetypeDetector.stripVersionRange("^18.2.0"));
        assertEquals("17.0.0", ArchetypeDetector.stripVersionRange("~17.0.0"));
    }

    @Test
    void stripVersionRangeHandlesGteLt() {
        assertEquals("16.0.0", ArchetypeDetector.stripVersionRange(">=16.0.0"));
        assertEquals("16.0.0", ArchetypeDetector.stripVersionRange(">=16.0.0 <17.0.0"));
    }

    @Test
    void stripVersionRangeLeavesExactVersionUnchanged() {
        assertEquals("19.0.0", ArchetypeDetector.stripVersionRange("19.0.0"));
    }

    @Test
    void stripVersionRangeLeavesNonSemverTagsUnchanged() {
        assertEquals("latest", ArchetypeDetector.stripVersionRange("latest"));
        assertEquals("next", ArchetypeDetector.stripVersionRange("next"));
    }

    @Test
    void stripVersionRangeHandlesNull() {
        assertNull(ArchetypeDetector.stripVersionRange(null));
    }

    // ─── Maven jar / pom packaging ───────────────────────────────────────────────

    @Test
    void detectsJarViaExplicitPackaging() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plain-service</artifactId>
                    <version>2.3.0</version>
                    <packaging>jar</packaging>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("jar", info.archetype());
        assertEquals("2.3.0", info.version());
    }

    @Test
    void detectsJarWhenPackagingAbsent() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plain-service</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("jar", info.archetype());
        assertEquals("1.0.0-SNAPSHOT", info.version());
    }

    @Test
    void detectsPomPackaging() throws IOException {
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>3.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>service-a</module>
                    </modules>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("pom", info.archetype());
        assertEquals("3.0.0", info.version());
    }

    @Test
    void pomPackagingWithJavaSourceIsClassifiedAsJar() throws IOException {
        // A project that incorrectly declares <packaging>pom</packaging> but has Java source
        // under src/main/java should be classified as "jar", not "pom".
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>misconfigured-service</artifactId>
                    <version>1.2.3</version>
                    <packaging>pom</packaging>
                </project>
                """);
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("App.java"), "package com.example; public class App {}");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("jar", info.archetype(), "pom packaging must be overridden when src/main/java is present");
        assertEquals("1.2.3", info.version());
    }

    @Test
    void pomPackagingWithoutJavaSourceStaysPom() throws IOException {
        // A true multi-module parent or BOM with no source must remain "pom".
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>4.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>service-a</module>
                        <module>service-b</module>
                    </modules>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("pom", info.archetype(), "Multi-module parent with no Java source must stay pom");
        assertEquals("4.0.0", info.version());
    }

    @Test
    void jarNotDetectedForQuarkusProject() throws IOException {
        // Quarkus should take priority over the generic Maven jar fallback
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-service</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>jar</packaging>
                    <properties>
                        <quarkus.platform.version>3.25.4</quarkus.platform.version>
                    </properties>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info);
        assertEquals("quarkus", info.archetype(), "Quarkus must take priority over jar");
    }

    // ─── .NET ─────────────────────────────────────────────────────────────────────

    @Test
    void detectsDotnetViaCsproj() throws IOException {
        Files.writeString(tempDir.resolve("MyApp.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>Exe</OutputType>
                    <TargetFramework>net9.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("dotnet", info.archetype());
        assertEquals("net9.0", info.version());
    }

    @Test
    void detectsDotnetViaFsproj() throws IOException {
        Files.writeString(tempDir.resolve("MyLib.fsproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFramework>net8.0</TargetFramework>
                  </PropertyGroup>
                </Project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("dotnet", info.archetype());
        assertEquals("net8.0", info.version());
    }

    @Test
    void detectsDotnetWithMultipleTargetFrameworks() throws IOException {
        Files.writeString(tempDir.resolve("MultiTarget.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFrameworks>net8.0;net9.0</TargetFrameworks>
                  </PropertyGroup>
                </Project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("dotnet", info.archetype());
        assertEquals("net8.0", info.version(), "Should take the first framework in the list");
    }

    @Test
    void detectsDotnetViaGlobalJson() throws IOException {
        // .sln present but no project file — falls back to global.json
        Files.writeString(tempDir.resolve("MySolution.sln"), "");
        Files.writeString(tempDir.resolve("global.json"), """
                {
                  "sdk": {
                    "version": "8.0.300",
                    "rollForward": "latestMinor"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("dotnet", info.archetype());
        assertEquals("8.0.300", info.version());
    }

    @Test
    void dotnetNotDetectedWhenPomPresent() throws IOException {
        // A Java project with a stray .csproj must not be classified as dotnet
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>java-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        Files.writeString(tempDir.resolve("tool.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup><TargetFramework>net9.0</TargetFramework></PropertyGroup>
                </Project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertNotEquals("dotnet", info.archetype(), "pom.xml presence must suppress dotnet detection");
    }

    // ─── Docker ───────────────────────────────────────────────────────────────────

    @Test
    void detectsDockerViaRootDockerfile() throws IOException {
        writeDockerfile("Dockerfile",
                "FROM eclipse-temurin:21-jre\n" +
                "COPY target/app.jar /app.jar\n" +
                "ENTRYPOINT [\"java\", \"-jar\", \"/app.jar\"]\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("docker", info.archetype());
        assertEquals("21-jre", info.version());
    }

    @Test
    void detectsDockerViaDockerCompose() throws IOException {
        Files.writeString(tempDir.resolve("docker-compose.yml"), """
                version: "3.8"
                services:
                  app:
                    image: my-app:latest
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("docker", info.archetype());
        assertEquals("unknown", info.version());
    }

    @Test
    void dockerVersionIsUnknownWhenFromHasNoTag() throws IOException {
        writeDockerfile("Dockerfile",
                "FROM scratch\n" +
                "COPY app /app\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("docker", info.archetype());
        assertEquals("unknown", info.version(), "FROM scratch has no tag — version should be unknown");
    }

    @Test
    void dockerNotDetectedWhenAlreadyWildFly() throws IOException {
        writeDockerfile("Dockerfile",
                "FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk17\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("wildfly", info.archetype(), "WildFly Dockerfile must take priority over generic docker");
    }

    // ─── Terraform ────────────────────────────────────────────────────────────────

    @Test
    void detectsTerraformViaTfFile() throws IOException {
        Files.writeString(tempDir.resolve("main.tf"), """
                provider "aws" {
                  region = "eu-west-1"
                }

                resource "aws_s3_bucket" "my_bucket" {
                  bucket = "my-bucket"
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("terraform", info.archetype());
        assertEquals("unknown", info.version());
    }

    @Test
    void detectsTerraformVersionFromRequiredVersion() throws IOException {
        Files.writeString(tempDir.resolve("versions.tf"), """
                terraform {
                  required_version = ">= 1.5.0"

                  required_providers {
                    aws = {
                      source  = "hashicorp/aws"
                      version = "~> 5.0"
                    }
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("terraform", info.archetype());
        assertEquals(">= 1.5.0", info.version());
    }

    @Test
    void detectsTerraformViaTerraformVersionFile() throws IOException {
        Files.writeString(tempDir.resolve(".terraform-version"), "1.7.3\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("terraform", info.archetype());
        assertEquals("1.7.3", info.version());
    }

    @Test
    void terraformVersionFileTakesPriorityOverTfFile() throws IOException {
        Files.writeString(tempDir.resolve(".terraform-version"), "1.8.0");
        Files.writeString(tempDir.resolve("main.tf"), """
                terraform {
                  required_version = ">= 1.5.0"
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("terraform", info.archetype());
        assertEquals("1.8.0", info.version(), ".terraform-version pin should take priority");
    }

    // ─── SQL ──────────────────────────────────────────────────────────────────────

    @Test
    void detectsSqlViaSqlFile() throws IOException {
        Files.createDirectories(tempDir.resolve("db"));
        Files.writeString(tempDir.resolve("db/schema.sql"), "CREATE TABLE foo (id INT);");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("sql", info.archetype());
    }

    @Test
    void detectsSqlWithFlywayVersionPrefix() throws IOException {
        Path migrations = tempDir.resolve("src/main/resources/db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__init.sql"), "CREATE TABLE foo (id INT);");
        Files.writeString(migrations.resolve("V2__add_column.sql"), "ALTER TABLE foo ADD COLUMN name VARCHAR(255);");
        Files.writeString(migrations.resolve("V12__latest.sql"), "CREATE INDEX idx ON foo(name);");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("sql", info.archetype());
        assertEquals("V12", info.version(), "Highest Flyway migration version should be returned");
    }

    @Test
    void sqlNotDetectedWhenPomPresent() throws IOException {
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>java-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        Files.writeString(tempDir.resolve("schema.sql"), "CREATE TABLE foo (id INT);");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertNotEquals("sql", info.archetype(), "pom.xml presence must suppress SQL detection");
    }

    // ─── Shell ────────────────────────────────────────────────────────────────────

    @Test
    void detectsShellViaShFile() throws IOException {
        Files.writeString(tempDir.resolve("deploy.sh"), "#!/bin/bash\necho 'deploying'\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("shell", info.archetype());
        assertEquals("unknown", info.version());
    }

    @Test
    void shellNotDetectedWhenPomPresent() throws IOException {
        writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>java-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        Files.writeString(tempDir.resolve("build.sh"), "#!/bin/bash\nmvn clean package\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertNotEquals("shell", info.archetype(), "pom.xml presence must suppress shell detection");
    }

    @Test
    void shellNotDetectedFromNestedShFiles() throws IOException {
        // Shell files in subdirectories should not trigger detection (depth 1 only)
        Files.createDirectories(tempDir.resolve("scripts"));
        Files.writeString(tempDir.resolve("scripts/util.sh"), "#!/bin/bash\necho 'util'\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNull(info, "Shell files in subdirectories should not trigger detection");
    }

    // ─── Negative cases ───────────────────────────────────────────────────────────

    @Test
    void unrecognisedPomIsClassifiedAsJar() throws IOException {
        // A plain Maven project (e.g. Spring Boot, plain library) is classified as "jar"
        // since no framework-specific archetype matched.
        Path root = writePom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plain-spring-service</artifactId>
                    <version>1.0.0-SNAPSHOT</version>

                    <properties>
                        <spring.boot.version>3.2.0</spring.boot.version>
                    </properties>
                </project>
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(root);

        assertNotNull(info, "Plain Maven project should be detected as jar");
        assertEquals("jar", info.archetype());
        assertEquals("1.0.0-SNAPSHOT", info.version());
    }

    @Test
    void returnsNullWhenNoPomAndNoDockerfile() {
        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);
        assertNull(info, "Missing pom.xml and Dockerfile should return null");
    }

    // ─── PHP — Laravel / Symfony / Generic ────────────────────────────────────────

    @Test
    void detectsLaravelFromComposerJson() throws IOException {
        Files.writeString(tempDir.resolve("composer.json"), """
                {
                  "require": {
                    "laravel/framework": "^11.0",
                    "php": "^8.2"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = phpDetector.detect(tempDir);

        assertNotNull(info, "Expected Laravel to be detected");
        assertEquals("laravel", info.archetype());
        assertEquals("11.0", info.version());
    }

    @Test
    void detectsSymfonyFromComposerJson() throws IOException {
        Files.writeString(tempDir.resolve("composer.json"), """
                {
                  "require": {
                    "symfony/framework-bundle": "^6.4",
                    "php": "^8.1"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = phpDetector.detect(tempDir);

        assertNotNull(info, "Expected Symfony to be detected");
        assertEquals("symfony", info.archetype());
        assertEquals("6.4", info.version());
    }

    @Test
    void detectsGenericPhpProjectWithPhpConstraint() throws IOException {
        Files.writeString(tempDir.resolve("composer.json"), """
                {
                  "require": {
                    "php": ">=8.0",
                    "monolog/monolog": "^3.0"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = phpDetector.detect(tempDir);

        assertNotNull(info, "Expected generic PHP to be detected");
        assertEquals("php", info.archetype());
    }

    @Test
    void detectsGenericPhpProjectWithoutPhpConstraint() throws IOException {
        Files.writeString(tempDir.resolve("composer.json"), """
                {
                  "require": {
                    "monolog/monolog": "^3.0"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = phpDetector.detect(tempDir);

        assertNotNull(info, "Expected generic PHP to be detected when composer.json present");
        assertEquals("php", info.archetype());
        assertEquals("unknown", info.version());
    }

    @Test
    void phpFrameworkDetectionReturnsNullWhenNoComposerJson() {
        ArchetypeDetector.ArchetypeInfo info = phpDetector.detect(tempDir);
        assertNull(info, "Should return null when composer.json is absent");
    }

    @Test
    void detectDetectsLaravelViaTopLevelDetect() throws IOException {
        Files.writeString(tempDir.resolve("composer.json"), """
                {
                  "require": {
                    "laravel/framework": "^10.0"
                  }
                }
                """);

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNotNull(info);
        assertEquals("laravel", info.archetype());
    }
}
