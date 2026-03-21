package com.eneve.agent.linter;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PHPStan JSON output parsing.
 * Tests the private {@code parseJsonOutput} method via reflection to avoid
 * needing a real PHP / PHPStan installation in CI.
 */
class PhpStanLinterTest {

    private final PhpStanLinter linter = new PhpStanLinter();

    @Test
    void nameReturnsPhpstan() {
        assertEquals("phpstan", linter.name());
    }

    @Test
    void parsesFileErrorsFromJsonOutput() throws Exception {
        String json = """
                {
                  "totals": { "errors": 0, "file_errors": 2 },
                  "files": {
                    "/var/www/src/Foo.php": {
                      "errors": 2,
                      "messages": [
                        { "message": "Undefined variable: $bar", "line": 10, "ignorable": true },
                        { "message": "Call to undefined method Bar::baz()", "line": 25, "ignorable": false }
                      ]
                    }
                  },
                  "errors": []
                }
                """;

        List<LinterFinding> findings = invokeParseJsonOutput(json, Path.of("/var/www"));

        assertEquals(2, findings.size());

        LinterFinding first = findings.get(0);
        assertEquals("phpstan", first.linterName());
        assertEquals("src/Foo.php", first.file());
        assertEquals(10, first.line());
        assertEquals(LinterFinding.SEVERITY_ERROR, first.severity());
        assertTrue(first.message().contains("Undefined variable"));

        LinterFinding second = findings.get(1);
        assertEquals(25, second.line());
        assertTrue(second.message().contains("Call to undefined method"));
    }

    @Test
    void parsesGlobalErrors() throws Exception {
        String json = """
                {
                  "totals": { "errors": 1, "file_errors": 0 },
                  "files": {},
                  "errors": ["Cannot load extension foo"]
                }
                """;

        List<LinterFinding> findings = invokeParseJsonOutput(json, Path.of("/var/www"));

        assertEquals(1, findings.size());
        assertEquals("", findings.get(0).file());
        assertEquals(0, findings.get(0).line());
        assertTrue(findings.get(0).message().contains("Cannot load extension foo"));
    }

    @Test
    void returnsEmptyListForEmptyFilesObject() throws Exception {
        String json = """
                {
                  "totals": { "errors": 0, "file_errors": 0 },
                  "files": {},
                  "errors": []
                }
                """;

        List<LinterFinding> findings = invokeParseJsonOutput(json, Path.of("/var/www"));

        assertTrue(findings.isEmpty());
    }

    @Test
    void returnsEmptyListForMalformedJson() throws Exception {
        String json = "not valid json";

        List<LinterFinding> findings = invokeParseJsonOutput(json, Path.of("/var/www"));

        assertTrue(findings.isEmpty());
    }

    @Test
    void relativePathIsComputedCorrectly() throws Exception {
        String json = """
                {
                  "totals": { "errors": 0, "file_errors": 1 },
                  "files": {
                    "/project/root/src/Controllers/HomeController.php": {
                      "errors": 1,
                      "messages": [
                        { "message": "Parameter $id not typed", "line": 5, "ignorable": false }
                      ]
                    }
                  },
                  "errors": []
                }
                """;

        List<LinterFinding> findings = invokeParseJsonOutput(json, Path.of("/project/root"));

        assertEquals(1, findings.size());
        assertEquals("src/Controllers/HomeController.php", findings.get(0).file());
    }

    // ─── Helper ───────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<LinterFinding> invokeParseJsonOutput(String json, Path workspaceRoot) throws Exception {
        Method method = PhpStanLinter.class.getDeclaredMethod("parseJsonOutput", String.class, Path.class);
        method.setAccessible(true);
        return (List<LinterFinding>) method.invoke(linter, json, workspaceRoot);
    }
}
