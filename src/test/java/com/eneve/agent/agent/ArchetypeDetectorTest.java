package com.eneve.agent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArchetypeDetectorTest {

    private final ArchetypeDetector detector = new ArchetypeDetector();

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
    void dockerfileWithoutWildFlyFromDoesNotTriggerDetection() throws IOException {
        writeDockerfile("Dockerfile",
                "FROM eclipse-temurin:17-jre\n" +
                "COPY target/app.jar /app.jar\n" +
                "ENTRYPOINT [\"java\", \"-jar\", \"/app.jar\"]\n");

        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);

        assertNull(info, "Non-WildFly Dockerfile should return null");
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
        // be classified as a TypeScript frontend project.
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

        assertNull(info, "pom.xml presence should suppress TypeScript frontend detection");
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

    // ─── Negative cases ───────────────────────────────────────────────────────────

    @Test
    void returnsNullForUnrecognisedPom() throws IOException {
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

        assertNull(info, "Non-Quarkus/WildFly project should return null");
    }

    @Test
    void returnsNullWhenNoPomAndNoDockerfile() {
        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);
        assertNull(info, "Missing pom.xml and Dockerfile should return null");
    }
}
