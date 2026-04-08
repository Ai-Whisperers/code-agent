package com.eneve.agent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BuildValidator#buildErrorExcerpt(String)}.
 */
class BuildValidatorTest {

    // ─── Short output (under MAX) ─────────────────────────────────────────────────

    @Test
    void returnsOutputUnchangedWhenShort() {
        String output = "Build succeeded.";
        assertEquals(output, BuildValidator.buildErrorExcerpt(output));
    }

    @Test
    void returnsEmptyStringForNull() {
        assertEquals("", BuildValidator.buildErrorExcerpt(null));
    }

    // ─── Maven patterns ───────────────────────────────────────────────────────────

    @Test
    void extractsMavenErrorLines() {
        String output = "a".repeat(4000) + "\n[ERROR] Compilation failure\n[INFO] some noise\n[FATAL] OOM";
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertTrue(excerpt.contains("[ERROR]"), "Should contain Maven [ERROR] line");
        assertTrue(excerpt.contains("[FATAL]"), "Should contain Maven [FATAL] line");
        assertFalse(excerpt.contains("[INFO]"), "Should not contain [INFO] noise");
    }

    @Test
    void extractsMavenBuildFailureLine() {
        String output = "x".repeat(4000) + "\nBUILD FAILURE\n[INFO] Total time: 5s";
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertTrue(excerpt.contains("BUILD FAILURE"));
    }

    // ─── MSBuild / dotnet CLI patterns ────────────────────────────────────────────

    @Test
    void extractsMsBuildCsErrorLine() {
        String output = "x".repeat(4000) + "\nProgram.cs(10,5): error CS0103: The name 'foo' does not exist\nsome noise";
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertTrue(excerpt.contains(": error CS"), "Should contain MSBuild CS error");
        assertFalse(excerpt.contains("some noise"), "Should not contain noise");
    }

    @Test
    void extractsMsBuildMsbErrorLine() {
        String output = "x".repeat(4000) + "\nMyApp.csproj : error MSB4019: The imported project was not found\nsome noise";
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertTrue(excerpt.contains(": error MSB"));
    }

    @Test
    void extractsNuGetErrorLine() {
        String output = "x".repeat(4000) + "\nPackage 'Foo 1.0.0' : error NU1101: Unable to find package\nsome noise";
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertTrue(excerpt.contains(": error NU"));
    }

    @Test
    void extractsNetSdkErrorLine() {
        String output = "x".repeat(4000) + "\nMyApp.csproj : error NETSDK1045: The current .NET SDK does not support\nsome noise";
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertTrue(excerpt.contains(": error NETSDK"));
    }

    @Test
    void extractsBuildFailedLine() {
        String output = "x".repeat(4000) + "\nBuild FAILED.\n    0 Warning(s)\n    1 Error(s)";
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertTrue(excerpt.contains("Build FAILED"));
        assertTrue(excerpt.contains("Error(s)"));
    }

    @Test
    void extractsUnhandledExceptionLine() {
        String output = "x".repeat(4000) + "\nUnhandled exception. System.InvalidOperationException: something went wrong";
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertTrue(excerpt.contains("Unhandled exception"));
    }

    // ─── Fallback (head+tail) ─────────────────────────────────────────────────────

    @Test
    void fallsBackToHeadAndTailWhenNoMatchingLines() {
        String output = "x".repeat(4000);
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertFalse(excerpt.isBlank());
        assertTrue(excerpt.length() <= 3100, "Excerpt should be bounded");
    }

    @Test
    void fallsBackToHeadAndTailWhenErrorLinesExceedMax() {
        // Generate many error lines totalling > 3000 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("Program.cs(").append(i).append(",1): error CS0103: The name 'x").append(i).append("' does not exist\n");
        }
        String output = sb.toString();
        String excerpt = BuildValidator.buildErrorExcerpt(output);
        assertFalse(excerpt.isBlank());
        assertTrue(excerpt.length() <= 3100, "Excerpt should be bounded even when error lines are large");
    }
}
