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
    void returnsNullWhenNoPomExists() {
        ArchetypeDetector.ArchetypeInfo info = detector.detect(tempDir);
        assertNull(info, "Missing pom.xml should return null");
    }
}
