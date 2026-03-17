package com.eneve.agent.linter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LinterUtilsTest {

    @Test
    void toRelativePathConvertsAbsolutePathWithinWorkspace(@TempDir Path workspaceRoot) throws IOException {
        // Create a file within the workspace
        Path subDir = Files.createDirectory(workspaceRoot.resolve("src"));
        Path file = Files.createFile(subDir.resolve("Test.java"));
        
        String result = LinterUtils.toRelativePath(file.toString(), workspaceRoot);
        
        assertEquals("src/Test.java", result.replace('\\', '/'));
    }

    @Test
    void toRelativePathReturnsOriginalPathWhenOutsideWorkspace(@TempDir Path workspaceRoot, @TempDir Path otherDir) throws IOException {
        Path file = Files.createFile(otherDir.resolve("external.txt"));
        
        String result = LinterUtils.toRelativePath(file.toString(), workspaceRoot);
        
        assertEquals(file.toString(), result);
    }

    @Test
    void toRelativePathHandlesInvalidPath(@TempDir Path workspaceRoot) {
        String invalidPath = "not-a-valid-path\u0000";
        
        String result = LinterUtils.toRelativePath(invalidPath, workspaceRoot);
        
        assertEquals(invalidPath, result);
    }

    @Test
    void toRelativePathHandlesNullPath(@TempDir Path workspaceRoot) {
        String result = LinterUtils.toRelativePath(null, workspaceRoot);
        
        assertNull(result);
    }

    @Test
    void toRelativePathHandlesEmptyPath(@TempDir Path workspaceRoot) {
        String result = LinterUtils.toRelativePath("", workspaceRoot);
        
        assertEquals("", result);
    }

    @Test
    void toRelativePathHandlesWorkspaceRootItself(@TempDir Path workspaceRoot) {
        String result = LinterUtils.toRelativePath(workspaceRoot.toString(), workspaceRoot);
        
        assertEquals("", result);
    }

    @Test
    void parseIntSafeReturnsValidInteger() {
        assertEquals(123, LinterUtils.parseIntSafe("123"));
        assertEquals(-456, LinterUtils.parseIntSafe("-456"));
        assertEquals(0, LinterUtils.parseIntSafe("0"));
    }

    @Test
    void parseIntSafeReturnsZeroForInvalidInput() {
        assertEquals(0, LinterUtils.parseIntSafe("not-a-number"));
        assertEquals(0, LinterUtils.parseIntSafe("123.45"));
        assertEquals(0, LinterUtils.parseIntSafe(""));
        assertEquals(0, LinterUtils.parseIntSafe("abc123"));
        assertEquals(0, LinterUtils.parseIntSafe("123abc"));
    }

    @Test
    void parseIntSafeReturnsZeroForNull() {
        assertEquals(0, LinterUtils.parseIntSafe(null));
    }

    @Test
    void parseIntSafeHandlesIntegerOverflow() {
        assertEquals(0, LinterUtils.parseIntSafe("99999999999999999999"));
        assertEquals(0, LinterUtils.parseIntSafe("-99999999999999999999"));
    }

    @Test
    void truncateReturnsOriginalStringWhenShort() {
        String shortText = "This is a short text";
        assertEquals(shortText, LinterUtils.truncate(shortText));
    }

    @Test
    void truncateReturnsEmptyStringForNull() {
        assertEquals("", LinterUtils.truncate(null));
    }

    @Test
    void truncateReturnsEmptyStringForEmpty() {
        assertEquals("", LinterUtils.truncate(""));
    }

    @Test
    void truncateCutsLongStringAt2000Characters() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 2100; i++) {
            longText.append("a");
        }
        
        String result = LinterUtils.truncate(longText.toString());
        
        assertEquals(2003, result.length()); // 2000 + "..." (3 chars)
        assertTrue(result.endsWith("..."));
        assertEquals("a".repeat(2000), result.substring(0, 2000));
    }

    @Test
    void truncateExactly2000CharactersReturnsOriginal() {
        String exactly2000 = "a".repeat(2000);
        String result = LinterUtils.truncate(exactly2000);
        
        assertEquals(exactly2000, result);
        assertEquals(2000, result.length());
    }

    @Test
    void truncate2001CharactersGetsTruncated() {
        String text2001 = "a".repeat(2001);
        String result = LinterUtils.truncate(text2001);
        
        assertEquals(2003, result.length()); // 2000 + "..."
        assertTrue(result.endsWith("..."));
    }

    @Test
    void truncateHandlesUnicodeCharacters() {
        String unicode = "🌟".repeat(1001); // Each emoji is actually multiple bytes
        String result = LinterUtils.truncate(unicode);
        
        assertTrue(result.length() <= 2003); // Should be truncated
        assertTrue(result.endsWith("..."));
    }
}