package com.eneve.agent.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NodeProjectHelperTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsPnpmFromLockfile() throws IOException {
        Files.writeString(tempDir.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");
        Files.writeString(tempDir.resolve("package.json"), """
                { "scripts": { "test": "vitest run" } }
                """);

        assertEquals(NodeProjectHelper.PackageManager.PNPM, NodeProjectHelper.detectPackageManager(tempDir));
        assertEquals("pnpm test", NodeProjectHelper.suggestedTestCommand(tempDir));
    }

    @Test
    void detectsYarnFromLockfile() throws IOException {
        Files.writeString(tempDir.resolve("yarn.lock"), "# yarn lockfile v1\n");
        Files.writeString(tempDir.resolve("package.json"), """
                { "scripts": { "test": "jest" } }
                """);

        assertEquals(NodeProjectHelper.PackageManager.YARN, NodeProjectHelper.detectPackageManager(tempDir));
        assertEquals("yarn test", NodeProjectHelper.suggestedTestCommand(tempDir));
    }

    @Test
    void npmWhenNoAlternateLockfile() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                { "scripts": { "test": "echo ok" } }
                """);

        assertEquals(NodeProjectHelper.PackageManager.NPM, NodeProjectHelper.detectPackageManager(tempDir));
        assertEquals("npm test", NodeProjectHelper.suggestedTestCommand(tempDir));
    }

    @Test
    void angularWorkspaceWithoutTestScriptSuggestsNgTest() throws IOException {
        Files.writeString(tempDir.resolve("angular.json"), "{ \"projects\": {} }");
        Files.writeString(tempDir.resolve("package.json"), "{}");

        assertTrue(NodeProjectHelper.suggestedTestCommand(tempDir).contains("ng test"));
    }

    @Test
    void vitestConfigWithoutTestScript() throws IOException {
        Files.writeString(tempDir.resolve("vitest.config.ts"), "import { defineConfig } from 'vitest/config'\nexport default defineConfig({})\n");
        Files.writeString(tempDir.resolve("package.json"), "{}");

        assertEquals("npx vitest run", NodeProjectHelper.suggestedTestCommand(tempDir));
    }

    @Test
    void installAndTestUsesPnpmWhenLockfilePresent() throws IOException {
        Files.writeString(tempDir.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");
        Files.writeString(tempDir.resolve("package.json"), """
                { "scripts": { "test": "node -e \"process.exit(0)\"" } }
                """);

        String cmd = NodeProjectHelper.installAndTestCommand(tempDir);
        assertTrue(cmd.startsWith("pnpm install --frozen-lockfile"));
        assertTrue(cmd.contains(" && "));
        assertTrue(cmd.endsWith("pnpm test"));
    }
}
