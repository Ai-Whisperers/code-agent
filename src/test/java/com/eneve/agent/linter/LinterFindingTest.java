package com.eneve.agent.linter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinterFindingTest {

    private static LinterFinding finding(String file, int line, String rule) {
        return new LinterFinding("checkstyle", file, line, LinterFinding.SEVERITY_WARNING, rule, "msg");
    }

    // ─── constants ───────────────────────────────────────────────────────────

    @Test
    void constantsHaveCorrectValues() {
        assertEquals("ERROR", LinterFinding.SEVERITY_ERROR);
        assertEquals("WARNING", LinterFinding.SEVERITY_WARNING);
        assertEquals("INFO", LinterFinding.SEVERITY_INFO);
    }

    // ─── record accessors / equality / toString ──────────────────────────────

    @Test
    void recordCreationAndAccessors() {
        LinterFinding f = new LinterFinding(
            "CheckStyle", "src/Test.java", 42,
            LinterFinding.SEVERITY_ERROR, "UnusedVariable", "Variable 'x' is never used"
        );
        assertEquals("CheckStyle", f.linterName());
        assertEquals("src/Test.java", f.file());
        assertEquals(42, f.line());
        assertEquals(LinterFinding.SEVERITY_ERROR, f.severity());
        assertEquals("UnusedVariable", f.rule());
        assertEquals("Variable 'x' is never used", f.message());
    }

    @Test
    void recordEquality() {
        LinterFinding f1 = new LinterFinding("Linter", "file.java", 1, "ERROR", "Rule", "Message");
        LinterFinding f2 = new LinterFinding("Linter", "file.java", 1, "ERROR", "Rule", "Message");
        LinterFinding f3 = new LinterFinding("Linter", "file.java", 1, "ERROR", "Rule", "Different");
        assertEquals(f1, f2);
        assertNotEquals(f1, f3);
        assertEquals(f1.hashCode(), f2.hashCode());
    }

    @Test
    void recordToString() {
        LinterFinding f = new LinterFinding("PMD", "Test.java", 42, "ERROR", "Rule", "Msg");
        String s = f.toString();
        assertTrue(s.contains("PMD"));
        assertTrue(s.contains("Test.java"));
        assertTrue(s.contains("42"));
        assertTrue(s.contains("ERROR"));
        assertTrue(s.contains("Rule"));
        assertTrue(s.contains("Msg"));
    }

    // ─── matches() (strict) ──────────────────────────────────────────────────

    @Test
    void strictMatchesIdenticalFinding() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 10, "MagicNumber");
        assertTrue(a.matches(b));
    }

    @Test
    void strictDoesNotMatchDifferentLine() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 15, "MagicNumber");
        assertFalse(a.matches(b));
    }

    @Test
    void strictDoesNotMatchDifferentFile() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Bar.java", 10, "MagicNumber");
        assertFalse(a.matches(b));
    }

    @Test
    void strictDoesNotMatchDifferentRule() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 10, "UnusedImport");
        assertFalse(a.matches(b));
    }

    @Test
    void matchesIgnoresDifferentMessage() {
        LinterFinding f1 = new LinterFinding("SpotBugs", "src/Bar.java", 5, "ERROR", "NullPointer", "Possible null pointer");
        LinterFinding f2 = new LinterFinding("SpotBugs", "src/Bar.java", 5, "ERROR", "NullPointer", "Null pointer detected");
        assertTrue(f1.matches(f2));
        assertTrue(f2.matches(f1));
    }

    @Test
    void matchesIgnoresDifferentSeverity() {
        LinterFinding f1 = new LinterFinding("ESLint", "src/app.js", 15, LinterFinding.SEVERITY_WARNING, "no-console", "Console statement");
        LinterFinding f2 = new LinterFinding("ESLint", "src/app.js", 15, LinterFinding.SEVERITY_ERROR,   "no-console", "Console statement");
        assertTrue(f1.matches(f2));
        assertTrue(f2.matches(f1));
    }

    @Test
    void matchesHandlesNullValues() {
        LinterFinding f1 = new LinterFinding(null, null, 0, null, null, null);
        LinterFinding f2 = new LinterFinding(null, null, 0, null, null, "Different message");
        assertTrue(f1.matches(f2));
        assertTrue(f2.matches(f1));
    }

    @Test
    void matchesHandlesMixedNullValues() {
        LinterFinding f1 = new LinterFinding("Linter", "file.java", 1, "ERROR", "Rule", "Msg");
        LinterFinding f2 = new LinterFinding(null,     "file.java", 1, "ERROR", "Rule", "Msg");
        assertFalse(f1.matches(f2));
        assertFalse(f2.matches(f1));
    }

    // ─── matchesLoose() (fuzzy) ──────────────────────────────────────────────

    @Test
    void looseMatchesExactLine() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 10, "MagicNumber");
        assertTrue(a.matchesLoose(b, 5));
    }

    @Test
    void looseMatchesWithinTolerance() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 14, "MagicNumber");
        assertTrue(a.matchesLoose(b, 5));
    }

    @Test
    void looseMatchesAtExactTolerance() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 15, "MagicNumber");
        assertTrue(a.matchesLoose(b, 5));
    }

    @Test
    void looseDoesNotMatchBeyondTolerance() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 16, "MagicNumber");
        assertFalse(a.matchesLoose(b, 5));
    }

    @Test
    void looseMatchesNegativeShiftWithinTolerance() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 7, "MagicNumber");
        assertTrue(a.matchesLoose(b, 5));
    }

    @Test
    void looseDoesNotMatchDifferentFile() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Bar.java", 10, "MagicNumber");
        assertFalse(a.matchesLoose(b, 10));
    }

    @Test
    void looseDoesNotMatchDifferentRule() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 10, "UnusedImport");
        assertFalse(a.matchesLoose(b, 10));
    }

    @Test
    void looseDoesNotMatchDifferentLinter() {
        LinterFinding a = new LinterFinding("checkstyle", "Foo.java", 10, LinterFinding.SEVERITY_WARNING, "Rule", "msg");
        LinterFinding b = new LinterFinding("pmd",        "Foo.java", 10, LinterFinding.SEVERITY_WARNING, "Rule", "msg");
        assertFalse(a.matchesLoose(b, 10));
    }

    @Test
    void looseMatchesWithZeroToleranceExactLine() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 10, "MagicNumber");
        assertTrue(a.matchesLoose(b, 0));
    }

    @Test
    void looseDoesNotMatchWithZeroToleranceDifferentLine() {
        LinterFinding a = finding("Foo.java", 10, "MagicNumber");
        LinterFinding b = finding("Foo.java", 11, "MagicNumber");
        assertFalse(a.matchesLoose(b, 0));
    }
}
