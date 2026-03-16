package com.eneve.agent.linter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinterFindingTest {

    private static LinterFinding finding(String file, int line, String rule) {
        return new LinterFinding("checkstyle", file, line, LinterFinding.SEVERITY_WARNING, rule, "msg");
    }

    // ─── matches() (strict) ──────────────────────────────────────────────

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

    // ─── matchesLoose() (fuzzy) ──────────────────────────────────────────

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
