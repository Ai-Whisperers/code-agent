package com.eneve.agent.linter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LinterResultTest {

    @Test
    void recordCreationAndAccessors() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("PMD", "Test.java", 1, LinterFinding.SEVERITY_ERROR, "Rule1", "Error message"),
            new LinterFinding("PMD", "Test.java", 2, LinterFinding.SEVERITY_WARNING, "Rule2", "Warning message")
        );
        
        LinterResult result = new LinterResult("PMD", findings, true, "Raw output");
        
        assertEquals("PMD", result.linterName());
        assertEquals(findings, result.findings());
        assertTrue(result.success());
        assertEquals("Raw output", result.rawOutput());
    }

    @Test
    void errorCountReturnsCorrectNumberOfErrors() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("CheckStyle", "A.java", 1, LinterFinding.SEVERITY_ERROR, "Rule1", "Error 1"),
            new LinterFinding("CheckStyle", "A.java", 2, LinterFinding.SEVERITY_ERROR, "Rule2", "Error 2"),
            new LinterFinding("CheckStyle", "A.java", 3, LinterFinding.SEVERITY_WARNING, "Rule3", "Warning"),
            new LinterFinding("CheckStyle", "A.java", 4, LinterFinding.SEVERITY_INFO, "Rule4", "Info")
        );
        
        LinterResult result = new LinterResult("CheckStyle", findings, true, "");
        
        assertEquals(2, result.errorCount());
    }

    @Test
    void warningCountReturnsCorrectNumberOfWarnings() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("SpotBugs", "B.java", 1, LinterFinding.SEVERITY_ERROR, "Rule1", "Error"),
            new LinterFinding("SpotBugs", "B.java", 2, LinterFinding.SEVERITY_WARNING, "Rule2", "Warning 1"),
            new LinterFinding("SpotBugs", "B.java", 3, LinterFinding.SEVERITY_WARNING, "Rule3", "Warning 2"),
            new LinterFinding("SpotBugs", "B.java", 4, LinterFinding.SEVERITY_WARNING, "Rule4", "Warning 3"),
            new LinterFinding("SpotBugs", "B.java", 5, LinterFinding.SEVERITY_INFO, "Rule5", "Info")
        );
        
        LinterResult result = new LinterResult("SpotBugs", findings, false, "");
        
        assertEquals(3, result.warningCount());
    }

    @Test
    void errorCountReturnsZeroWhenNoErrors() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("ESLint", "app.js", 1, LinterFinding.SEVERITY_WARNING, "Rule1", "Warning"),
            new LinterFinding("ESLint", "app.js", 2, LinterFinding.SEVERITY_INFO, "Rule2", "Info")
        );
        
        LinterResult result = new LinterResult("ESLint", findings, true, "");
        
        assertEquals(0, result.errorCount());
    }

    @Test
    void warningCountReturnsZeroWhenNoWarnings() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("PMD", "Test.java", 1, LinterFinding.SEVERITY_ERROR, "Rule1", "Error"),
            new LinterFinding("PMD", "Test.java", 2, LinterFinding.SEVERITY_INFO, "Rule2", "Info")
        );
        
        LinterResult result = new LinterResult("PMD", findings, true, "");
        
        assertEquals(0, result.warningCount());
    }

    @Test
    void countsReturnZeroForEmptyFindings() {
        LinterResult result = new LinterResult("Linter", List.of(), true, "No issues found");
        
        assertEquals(0, result.errorCount());
        assertEquals(0, result.warningCount());
    }

    @Test
    void countsHandleNullFindings() {
        LinterResult result = new LinterResult("Linter", null, false, "Error occurred");
        
        // This will throw NullPointerException as the stream() is called on null
        assertThrows(NullPointerException.class, () -> result.errorCount());
        assertThrows(NullPointerException.class, () -> result.warningCount());
    }

    @Test
    void countsHandleFindingsWithNullSeverity() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("Linter", "file.java", 1, null, "Rule1", "Message"),
            new LinterFinding("Linter", "file.java", 2, LinterFinding.SEVERITY_ERROR, "Rule2", "Error"),
            new LinterFinding("Linter", "file.java", 3, LinterFinding.SEVERITY_WARNING, "Rule3", "Warning")
        );
        
        LinterResult result = new LinterResult("Linter", findings, true, "");
        
        assertEquals(1, result.errorCount());
        assertEquals(1, result.warningCount());
    }

    @Test
    void countsHandleCustomSeverityValues() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("Linter", "file.java", 1, "CRITICAL", "Rule1", "Critical issue"),
            new LinterFinding("Linter", "file.java", 2, "MINOR", "Rule2", "Minor issue"),
            new LinterFinding("Linter", "file.java", 3, LinterFinding.SEVERITY_ERROR, "Rule3", "Error")
        );
        
        LinterResult result = new LinterResult("Linter", findings, true, "");
        
        assertEquals(1, result.errorCount()); // Only the ERROR severity
        assertEquals(0, result.warningCount()); // No WARNING severity
    }

    @Test
    void countsAreCaseSpecific() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("Linter", "file.java", 1, "error", "Rule1", "Lowercase error"),
            new LinterFinding("Linter", "file.java", 2, "Error", "Rule2", "Mixed case"),
            new LinterFinding("Linter", "file.java", 3, LinterFinding.SEVERITY_ERROR, "Rule3", "Correct case")
        );
        
        LinterResult result = new LinterResult("Linter", findings, true, "");
        
        assertEquals(1, result.errorCount()); // Only the exactly matching ERROR
        assertEquals(0, result.warningCount());
    }

    @Test
    void recordEquality() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("Linter", "file.java", 1, "ERROR", "Rule", "Message")
        );
        
        LinterResult result1 = new LinterResult("Linter", findings, true, "Output");
        LinterResult result2 = new LinterResult("Linter", findings, true, "Output");
        LinterResult result3 = new LinterResult("Linter", findings, false, "Output");
        
        assertEquals(result1, result2);
        assertNotEquals(result1, result3);
        assertEquals(result1.hashCode(), result2.hashCode());
    }

    @Test
    void recordToString() {
        List<LinterFinding> findings = List.of(
            new LinterFinding("PMD", "Test.java", 42, "ERROR", "Rule", "Message")
        );
        
        LinterResult result = new LinterResult("PMD", findings, true, "Raw output");
        String toString = result.toString();
        
        assertTrue(toString.contains("PMD"));
        assertTrue(toString.contains("true"));
        assertTrue(toString.contains("Raw output"));
    }
}