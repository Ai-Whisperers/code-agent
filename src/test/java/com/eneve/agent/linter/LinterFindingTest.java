package com.eneve.agent.linter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinterFindingTest {

    @Test
    void constantsHaveCorrectValues() {
        assertEquals("ERROR", LinterFinding.SEVERITY_ERROR);
        assertEquals("WARNING", LinterFinding.SEVERITY_WARNING);
        assertEquals("INFO", LinterFinding.SEVERITY_INFO);
    }

    @Test
    void recordCreationAndAccessors() {
        LinterFinding finding = new LinterFinding(
            "CheckStyle",
            "src/Test.java", 
            42,
            LinterFinding.SEVERITY_ERROR,
            "UnusedVariable",
            "Variable 'x' is never used"
        );
        
        assertEquals("CheckStyle", finding.linterName());
        assertEquals("src/Test.java", finding.file());
        assertEquals(42, finding.line());
        assertEquals(LinterFinding.SEVERITY_ERROR, finding.severity());
        assertEquals("UnusedVariable", finding.rule());
        assertEquals("Variable 'x' is never used", finding.message());
    }

    @Test
    void matchesReturnsTrueForIdenticalFindings() {
        LinterFinding finding1 = new LinterFinding(
            "PMD",
            "src/Foo.java",
            10,
            LinterFinding.SEVERITY_WARNING,
            "ShortVariable",
            "Variable name too short"
        );
        
        LinterFinding finding2 = new LinterFinding(
            "PMD",
            "src/Foo.java",
            10,
            LinterFinding.SEVERITY_WARNING,
            "ShortVariable",
            "Variable name too short"
        );
        
        assertTrue(finding1.matches(finding2));
        assertTrue(finding2.matches(finding1));
    }

    @Test
    void matchesReturnsTrueForSameFindingWithDifferentMessage() {
        LinterFinding finding1 = new LinterFinding(
            "SpotBugs",
            "src/Bar.java",
            5,
            LinterFinding.SEVERITY_ERROR,
            "NullPointer",
            "Possible null pointer dereference"
        );
        
        LinterFinding finding2 = new LinterFinding(
            "SpotBugs",
            "src/Bar.java",
            5,
            LinterFinding.SEVERITY_ERROR,
            "NullPointer",
            "Null pointer dereference detected"
        );
        
        assertTrue(finding1.matches(finding2));
        assertTrue(finding2.matches(finding1));
    }

    @Test
    void matchesReturnsTrueForSameFindingWithDifferentSeverity() {
        LinterFinding finding1 = new LinterFinding(
            "ESLint",
            "src/app.js",
            15,
            LinterFinding.SEVERITY_WARNING,
            "no-console",
            "Console statement"
        );
        
        LinterFinding finding2 = new LinterFinding(
            "ESLint",
            "src/app.js",
            15,
            LinterFinding.SEVERITY_ERROR,
            "no-console",
            "Console statement"
        );
        
        assertTrue(finding1.matches(finding2));
        assertTrue(finding2.matches(finding1));
    }

    @Test
    void matchesReturnsFalseForDifferentLinter() {
        LinterFinding finding1 = new LinterFinding(
            "CheckStyle",
            "src/Test.java",
            1,
            LinterFinding.SEVERITY_ERROR,
            "Rule1",
            "Message"
        );
        
        LinterFinding finding2 = new LinterFinding(
            "PMD",
            "src/Test.java",
            1,
            LinterFinding.SEVERITY_ERROR,
            "Rule1",
            "Message"
        );
        
        assertFalse(finding1.matches(finding2));
        assertFalse(finding2.matches(finding1));
    }

    @Test
    void matchesReturnsFalseForDifferentFile() {
        LinterFinding finding1 = new LinterFinding(
            "PMD",
            "src/A.java",
            1,
            LinterFinding.SEVERITY_ERROR,
            "Rule1",
            "Message"
        );
        
        LinterFinding finding2 = new LinterFinding(
            "PMD",
            "src/B.java",
            1,
            LinterFinding.SEVERITY_ERROR,
            "Rule1",
            "Message"
        );
        
        assertFalse(finding1.matches(finding2));
        assertFalse(finding2.matches(finding1));
    }

    @Test
    void matchesReturnsFalseForDifferentLine() {
        LinterFinding finding1 = new LinterFinding(
            "SpotBugs",
            "src/Test.java",
            10,
            LinterFinding.SEVERITY_ERROR,
            "Rule1",
            "Message"
        );
        
        LinterFinding finding2 = new LinterFinding(
            "SpotBugs",
            "src/Test.java",
            20,
            LinterFinding.SEVERITY_ERROR,
            "Rule1",
            "Message"
        );
        
        assertFalse(finding1.matches(finding2));
        assertFalse(finding2.matches(finding1));
    }

    @Test
    void matchesReturnsFalseForDifferentRule() {
        LinterFinding finding1 = new LinterFinding(
            "CheckStyle",
            "src/Test.java",
            1,
            LinterFinding.SEVERITY_ERROR,
            "RuleA",
            "Message"
        );
        
        LinterFinding finding2 = new LinterFinding(
            "CheckStyle",
            "src/Test.java",
            1,
            LinterFinding.SEVERITY_ERROR,
            "RuleB",
            "Message"
        );
        
        assertFalse(finding1.matches(finding2));
        assertFalse(finding2.matches(finding1));
    }

    @Test
    void matchesHandlesNullValues() {
        LinterFinding finding1 = new LinterFinding(null, null, 0, null, null, null);
        LinterFinding finding2 = new LinterFinding(null, null, 0, null, null, "Different message");
        
        assertTrue(finding1.matches(finding2));
        assertTrue(finding2.matches(finding1));
    }

    @Test
    void matchesHandlesMixedNullValues() {
        LinterFinding finding1 = new LinterFinding("Linter", "file.java", 1, "ERROR", "Rule", "Msg");
        LinterFinding finding2 = new LinterFinding(null, "file.java", 1, "ERROR", "Rule", "Msg");
        
        assertFalse(finding1.matches(finding2));
        assertFalse(finding2.matches(finding1));
    }

    @Test
    void recordEquality() {
        LinterFinding finding1 = new LinterFinding("Linter", "file.java", 1, "ERROR", "Rule", "Message");
        LinterFinding finding2 = new LinterFinding("Linter", "file.java", 1, "ERROR", "Rule", "Message");
        LinterFinding finding3 = new LinterFinding("Linter", "file.java", 1, "ERROR", "Rule", "Different");
        
        assertEquals(finding1, finding2);
        assertNotEquals(finding1, finding3);
        assertEquals(finding1.hashCode(), finding2.hashCode());
    }

    @Test
    void recordToString() {
        LinterFinding finding = new LinterFinding("PMD", "Test.java", 42, "ERROR", "Rule", "Msg");
        String toString = finding.toString();
        
        assertTrue(toString.contains("PMD"));
        assertTrue(toString.contains("Test.java"));
        assertTrue(toString.contains("42"));
        assertTrue(toString.contains("ERROR"));
        assertTrue(toString.contains("Rule"));
        assertTrue(toString.contains("Msg"));
    }
}