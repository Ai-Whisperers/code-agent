package com.eneve.agent.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DotnetWorkspaceProbeTest {

    @TempDir
    Path tempDir;

    // ─── hasDotnetAtRoot ──────────────────────────────────────────────────────────

    @Test
    void detectsCsprojAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("MyApp.csproj"), "<Project/>");
        assertTrue(DotnetWorkspaceProbe.hasDotnetAtRoot(tempDir));
    }

    @Test
    void detectsFsprojAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("MyLib.fsproj"), "<Project/>");
        assertTrue(DotnetWorkspaceProbe.hasDotnetAtRoot(tempDir));
    }

    @Test
    void detectsVbprojAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("MyLib.vbproj"), "<Project/>");
        assertTrue(DotnetWorkspaceProbe.hasDotnetAtRoot(tempDir));
    }

    @Test
    void detectsSlnAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("MyApp.sln"), "");
        assertTrue(DotnetWorkspaceProbe.hasDotnetAtRoot(tempDir));
    }

    @Test
    void detectsDirectoryBuildPropsAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("Directory.Build.props"), "<Project/>");
        assertTrue(DotnetWorkspaceProbe.hasDotnetAtRoot(tempDir));
    }

    @Test
    void detectsDirectoryBuildTargetsAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("Directory.Build.targets"), "<Project/>");
        assertTrue(DotnetWorkspaceProbe.hasDotnetAtRoot(tempDir));
    }

    @Test
    void detectsGlobalJsonAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("global.json"), "{}");
        assertTrue(DotnetWorkspaceProbe.hasDotnetAtRoot(tempDir));
    }

    @Test
    void returnsFalseForEmptyDirectory() {
        assertFalse(DotnetWorkspaceProbe.hasDotnetAtRoot(tempDir));
    }

    @Test
    void returnsFalseForNull() {
        assertFalse(DotnetWorkspaceProbe.hasDotnetAtRoot(null));
    }

    @Test
    void doesNotDetectCsprojInSubdirectory() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(sub.resolve("MyApp.csproj"), "<Project/>");
        assertFalse(DotnetWorkspaceProbe.hasDotnetAtRoot(tempDir));
    }

    // ─── findSlnFiles ─────────────────────────────────────────────────────────────

    @Test
    void findsSlnAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("MyApp.sln"), "");
        List<Path> slns = DotnetWorkspaceProbe.findSlnFiles(tempDir);
        assertEquals(1, slns.size());
        assertEquals("MyApp.sln", slns.get(0).getFileName().toString());
    }

    @Test
    void findsSlnInSubdirectory() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(sub.resolve("MyApp.sln"), "");
        List<Path> slns = DotnetWorkspaceProbe.findSlnFiles(tempDir);
        assertEquals(1, slns.size());
    }

    @Test
    void findsMultipleSlnFiles() throws IOException {
        Path sub1 = Files.createDirectories(tempDir.resolve("app1"));
        Path sub2 = Files.createDirectories(tempDir.resolve("app2"));
        Files.writeString(sub1.resolve("App1.sln"), "");
        Files.writeString(sub2.resolve("App2.sln"), "");
        List<Path> slns = DotnetWorkspaceProbe.findSlnFiles(tempDir);
        assertEquals(2, slns.size());
    }

    @Test
    void doesNotFindSlnBeyondMaxDepth() throws IOException {
        Path deep = Files.createDirectories(tempDir.resolve("a/b/c/d"));
        Files.writeString(deep.resolve("Deep.sln"), "");
        List<Path> slns = DotnetWorkspaceProbe.findSlnFiles(tempDir);
        assertTrue(slns.isEmpty(), "Should not find sln beyond max depth 3");
    }

    @Test
    void skipsObjDirectory() throws IOException {
        Path obj = Files.createDirectories(tempDir.resolve("obj"));
        Files.writeString(obj.resolve("Generated.sln"), "");
        List<Path> slns = DotnetWorkspaceProbe.findSlnFiles(tempDir);
        assertTrue(slns.isEmpty(), "Should skip obj directory");
    }

    @Test
    void skipsBinDirectory() throws IOException {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Files.writeString(bin.resolve("Release.sln"), "");
        List<Path> slns = DotnetWorkspaceProbe.findSlnFiles(tempDir);
        assertTrue(slns.isEmpty(), "Should skip bin directory");
    }

    // ─── resolveDotnetTestCommand ─────────────────────────────────────────────────

    @Test
    void returnsDotnetTestWhenRootHasCsproj() throws IOException {
        Files.writeString(tempDir.resolve("MyApp.csproj"), "<Project/>");
        assertEquals("dotnet test", DotnetWorkspaceProbe.resolveDotnetTestCommand(tempDir));
    }

    @Test
    void returnsDotnetTestWhenRootHasSln() throws IOException {
        Files.writeString(tempDir.resolve("MyApp.sln"), "");
        assertEquals("dotnet test", DotnetWorkspaceProbe.resolveDotnetTestCommand(tempDir));
    }

    @Test
    void returnsPathQualifiedCommandForSingleNestedSln() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(sub.resolve("MyApp.sln"), "");

        String cmd = DotnetWorkspaceProbe.resolveDotnetTestCommand(tempDir);

        assertTrue(cmd.startsWith("dotnet test "), "Expected path-qualified command, got: " + cmd);
        assertTrue(cmd.contains("MyApp.sln"), "Expected sln path in command, got: " + cmd);
    }

    @Test
    void returnsDotnetTestAtRootForMultipleNestedSlns() throws IOException {
        Path sub1 = Files.createDirectories(tempDir.resolve("app1"));
        Path sub2 = Files.createDirectories(tempDir.resolve("app2"));
        Files.writeString(sub1.resolve("App1.sln"), "");
        Files.writeString(sub2.resolve("App2.sln"), "");

        assertEquals("dotnet test", DotnetWorkspaceProbe.resolveDotnetTestCommand(tempDir));
    }

    @Test
    void returnsPathQualifiedCommandForSingleNestedCsproj() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("src/MyApp"));
        Files.writeString(sub.resolve("MyApp.csproj"), "<Project/>");

        String cmd = DotnetWorkspaceProbe.resolveDotnetTestCommand(tempDir);

        assertTrue(cmd.startsWith("dotnet test "), "Expected path-qualified command, got: " + cmd);
        assertTrue(cmd.contains("MyApp.csproj"), "Expected csproj path in command, got: " + cmd);
    }

    @Test
    void returnsDotnetTestFallbackForEmptyDirectory() {
        assertEquals("dotnet test", DotnetWorkspaceProbe.resolveDotnetTestCommand(tempDir));
    }
}
