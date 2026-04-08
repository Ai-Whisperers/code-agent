package com.eneve.agent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the static language-detection helpers in AgentPromptBuilder
 * (test command, source dir, test dir resolution).
 */
class AgentPromptBuilderHelpersTest {

    @TempDir
    Path tempDir;

    // ─── resolveTestCommand ────────────────────────────────────────────────────────

    @Test
    void resolvesTestCommandAsMvnwWhenMvnwPresent() throws IOException {
        Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("./mvnw test", cmd);
    }

    @Test
    void resolvesTestCommandAsMvnWhenPomPresentWithoutMvnw() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("mvn test", cmd);
    }

    @Test
    void resolvesTestCommandAsGradleWhenBuildGradlePresent() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "apply plugin: 'java'");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("gradle test", cmd);
    }

    @Test
    void resolvesTestCommandAsGradleWhenKotlinBuildScriptPresent() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins { java }");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("gradle test", cmd);
    }

    @Test
    void resolvesTestCommandAsNpmTestWhenPackageJsonPresent() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), "{}");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("npm test", cmd);
    }

    @Test
    void resolvesTestCommandAsPnpmWhenPnpmLockfilePresent() throws IOException {
        Files.writeString(tempDir.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");
        Files.writeString(tempDir.resolve("package.json"), """
                { "scripts": { "test": "vitest run" } }
                """);

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("pnpm test", cmd);
    }

    @Test
    void resolvesTestCommandAsDotnetTestWhenCsprojPresent() throws IOException {
        Files.writeString(tempDir.resolve("MyApp.csproj"), "<Project/>");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("dotnet test", cmd);
    }

    @Test
    void resolvesTestCommandAsDotnetTestWhenSlnPresent() throws IOException {
        Files.writeString(tempDir.resolve("MyApp.sln"), "");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("dotnet test", cmd);
    }

    @Test
    void resolvesTestCommandWithSlnPathWhenSlnIsNested() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(sub.resolve("MyApp.sln"), "");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertTrue(cmd.startsWith("dotnet test "), "Expected path-qualified dotnet test, got: " + cmd);
        assertTrue(cmd.contains("MyApp.sln"), "Expected sln path in command, got: " + cmd);
    }

    @Test
    void resolvesTestCommandAsDotnetTestAtRootWhenMultipleSlnFound() throws IOException {
        Path sub1 = Files.createDirectories(tempDir.resolve("app1"));
        Path sub2 = Files.createDirectories(tempDir.resolve("app2"));
        Files.writeString(sub1.resolve("App1.sln"), "");
        Files.writeString(sub2.resolve("App2.sln"), "");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("dotnet test", cmd);
    }

    @Test
    void resolvesTestCommandAsPhpArtisanWhenArtisanPresent() throws IOException {
        Files.writeString(tempDir.resolve("composer.json"), "{}");
        Files.writeString(tempDir.resolve("artisan"), "#!/usr/bin/env php");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("php artisan test", cmd);
    }

    @Test
    void resolvesTestCommandAsPhpUnitWhenComposerJsonPresentWithoutArtisan() throws IOException {
        Files.writeString(tempDir.resolve("composer.json"), "{}");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("vendor/bin/phpunit", cmd);
    }

    @Test
    void resolvesTestCommandAsFallbackWhenNullWorkspace() {
        String cmd = AgentPromptBuilder.resolveTestCommand(null);

        assertTrue(cmd.contains("appropriate"), "Fallback message expected when workspace is null");
    }

    @Test
    void resolvesTestCommandAsFallbackWhenEmptyDirectory() {
        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertTrue(cmd.contains("appropriate"), "Fallback message expected for unrecognised project");
    }

    @Test
    void mvnwTakesPriorityOverPomXml() throws IOException {
        Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh");
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        String cmd = AgentPromptBuilder.resolveTestCommand(tempDir);

        assertEquals("./mvnw test", cmd);
    }

    // ─── resolveSourceDir ─────────────────────────────────────────────────────────

    @Test
    void sourceDirIsJavaMainWhenPresent() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java"));

        String dir = AgentPromptBuilder.resolveSourceDir(tempDir);

        assertEquals("src/main/java", dir);
    }

    @Test
    void sourceDirIsSrcWhenNoJavaMain() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));

        String dir = AgentPromptBuilder.resolveSourceDir(tempDir);

        assertEquals("src", dir);
    }

    @Test
    void sourceDirIsAppWhenPresent() throws IOException {
        Files.createDirectories(tempDir.resolve("app"));

        String dir = AgentPromptBuilder.resolveSourceDir(tempDir);

        assertEquals("app", dir);
    }

    @Test
    void sourceDirDefaultsToSrcWhenNull() {
        String dir = AgentPromptBuilder.resolveSourceDir(null);
        assertEquals("src", dir);
    }

    // ─── resolveTestDir ───────────────────────────────────────────────────────────

    @Test
    void testDirIsJavaTestWhenPresent() throws IOException {
        Files.createDirectories(tempDir.resolve("src/test/java"));

        String dir = AgentPromptBuilder.resolveTestDir(tempDir);

        assertEquals("src/test/java", dir);
    }

    @Test
    void testDirIsTestsWhenPresentAndNoJavaTest() throws IOException {
        Files.createDirectories(tempDir.resolve("tests"));

        String dir = AgentPromptBuilder.resolveTestDir(tempDir);

        assertEquals("tests", dir);
    }

    @Test
    void testDirIsTestWhenPresent() throws IOException {
        Files.createDirectories(tempDir.resolve("test"));

        String dir = AgentPromptBuilder.resolveTestDir(tempDir);

        assertEquals("test", dir);
    }

    @Test
    void testDirDefaultsToTestsWhenNull() {
        String dir = AgentPromptBuilder.resolveTestDir(null);
        assertEquals("tests", dir);
    }
}
