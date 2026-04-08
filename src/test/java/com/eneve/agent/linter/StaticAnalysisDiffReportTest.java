package com.eneve.agent.linter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StaticAnalysisDiffReportTest {

    // ─── Fixtures ────────────────────────────────────────────────────────

    private static LinterFinding finding(String linter, String file, int line, String rule) {
        return new LinterFinding(linter, file, line, LinterFinding.SEVERITY_WARNING, rule, "message");
    }

    private static LinterFinding errorFinding(String linter, String file, int line, String rule) {
        return new LinterFinding(linter, file, line, LinterFinding.SEVERITY_ERROR, rule, "error message");
    }

    private static LinterResult result(String linter, LinterFinding... findings) {
        return new LinterResult(linter, List.of(findings), true, "");
    }

    // ─── verdict() ───────────────────────────────────────────────────────

    @Test
    void verdictPassWhenNoNewIssues() {
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(), List.of(), Set.of(), false);
        assertEquals(StaticAnalysisDiffReport.Verdict.PASS, report.verdict());
    }

    @Test
    void verdictPassWhenNewListIsEmpty() {
        LinterFinding resolved = finding("checkstyle", "Foo.java", 10, "Rule");
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(), List.of(resolved), Set.of(), false);
        assertEquals(StaticAnalysisDiffReport.Verdict.PASS, report.verdict());
    }

    @Test
    void verdictDegradedWhenNewIssuesExceedResolved() {
        LinterFinding newA = finding("checkstyle", "Foo.java", 10, "RuleA");
        LinterFinding newB = finding("checkstyle", "Foo.java", 20, "RuleB");
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(newA, newB), List.of(), Set.of(), false);
        assertEquals(StaticAnalysisDiffReport.Verdict.DEGRADED, report.verdict());
    }

    @Test
    void verdictImprovedWhenResolvedExceedsNew() {
        LinterFinding newA = finding("checkstyle", "Foo.java", 10, "RuleA");
        LinterFinding resolvedA = finding("checkstyle", "Bar.java", 5, "RuleB");
        LinterFinding resolvedB = finding("checkstyle", "Bar.java", 15, "RuleC");
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(newA), List.of(resolvedA, resolvedB), Set.of(), false);
        assertEquals(StaticAnalysisDiffReport.Verdict.IMPROVED, report.verdict());
    }

    @Test
    void verdictDegradedWhenNewEqualsResolved() {
        LinterFinding newA = finding("checkstyle", "Foo.java", 10, "RuleA");
        LinterFinding resolved = finding("checkstyle", "Bar.java", 5, "RuleB");
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(newA), List.of(resolved), Set.of(), false);
        assertEquals(StaticAnalysisDiffReport.Verdict.DEGRADED, report.verdict());
    }

    // ─── baselineTotal() / currentTotal() ───────────────────────────────

    @Test
    void totalCountsAreCorrect() {
        LinterFinding f1 = finding("checkstyle", "A.java", 1, "R1");
        LinterFinding f2 = finding("pmd", "B.java", 2, "R2");
        LinterFinding f3 = finding("pmd", "B.java", 3, "R3");

        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(result("checkstyle", f1)),
                List.of(result("pmd", f2, f3)),
                List.of(), List.of(), Set.of(), false);

        assertEquals(1, report.baselineTotal());
        assertEquals(2, report.currentTotal());
    }

    // ─── formatMarkdown() ────────────────────────────────────────────────

    @Test
    void formatMarkdownContainsPassVerdict() {
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(), List.of(), Set.of(), false);
        String md = report.formatMarkdown();
        assertTrue(md.contains("PASS"), "Expected PASS in: " + md);
        assertTrue(md.contains("## Static Analysis Diff"));
    }

    @Test
    void formatMarkdownContainsDegradedVerdict() {
        LinterFinding newIssue = finding("checkstyle", "Foo.java", 10, "MagicNumber");
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(newIssue), List.of(), Set.of(), false);
        String md = report.formatMarkdown();
        assertTrue(md.contains("DEGRADED"), "Expected DEGRADED in: " + md);
    }

    @Test
    void formatMarkdownContainsImprovedVerdict() {
        LinterFinding newIssue = finding("checkstyle", "Foo.java", 10, "MagicNumber");
        LinterFinding r1 = finding("pmd", "Bar.java", 5, "UnusedLocal");
        LinterFinding r2 = finding("pmd", "Bar.java", 8, "EmptyCatch");
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(newIssue), List.of(r1, r2), Set.of(), false);
        String md = report.formatMarkdown();
        assertTrue(md.contains("IMPROVED"), "Expected IMPROVED in: " + md);
    }

    @Test
    void formatMarkdownContainsPerLinterTable() {
        LinterFinding baseline1 = finding("checkstyle", "Foo.java", 1, "R1");
        LinterFinding current1  = finding("checkstyle", "Foo.java", 1, "R1");
        LinterFinding current2  = finding("pmd",        "Bar.java", 2, "R2");

        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(result("checkstyle", baseline1)),
                List.of(result("checkstyle", current1), result("pmd", current2)),
                List.of(current2), List.of(), Set.of(), false);

        String md = report.formatMarkdown();
        assertTrue(md.contains("| checkstyle |"), "Expected checkstyle row");
        assertTrue(md.contains("| pmd |"), "Expected pmd row");
        assertTrue(md.contains("| **Total** |"), "Expected Total row");
    }

    @Test
    void formatMarkdownShowsNewIssuesSectionWhenPresent() {
        LinterFinding newIssue = finding("spotbugs", "Foo.java", 42, "NP_NULL");
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(newIssue), List.of(), Set.of(), false);
        String md = report.formatMarkdown();
        assertTrue(md.contains("### New Issues Introduced"), "Expected new-issues section");
        assertTrue(md.contains("Foo.java"), "Expected filename in new-issues section");
        assertTrue(md.contains("NP_NULL"), "Expected rule name");
    }

    @Test
    void formatMarkdownShowsResolvedIssuesSectionWhenPresent() {
        LinterFinding resolved = finding("pmd", "Bar.java", 7, "EmptyBlock");
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(), List.of(resolved), Set.of(), false);
        String md = report.formatMarkdown();
        assertTrue(md.contains("### Issues Resolved"), "Expected resolved-issues section");
        assertTrue(md.contains("Bar.java"), "Expected filename in resolved-issues section");
    }

    @Test
    void formatMarkdownOmitsNewIssuesSectionWhenEmpty() {
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(), List.of(), Set.of(), false);
        String md = report.formatMarkdown();
        assertFalse(md.contains("### New Issues Introduced"), "Should not have new-issues section");
    }

    @Test
    void formatMarkdownOmitsResolvedSectionWhenEmpty() {
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(), List.of(), Set.of(), false);
        String md = report.formatMarkdown();
        assertFalse(md.contains("### Issues Resolved"), "Should not have resolved-issues section");
    }

    @Test
    void formatMarkdownIncludesScopingNoteWhenScoped() {
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(), List.of(),
                Set.of("Foo.java", "Bar.java"), true);
        String md = report.formatMarkdown();
        assertTrue(md.contains("scoped to 2 file"), "Expected scoping note");
    }

    @Test
    void formatMarkdownOmitsScopingNoteWhenNotScoped() {
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(), List.of(),
                Set.of("Foo.java"), false);
        String md = report.formatMarkdown();
        assertFalse(md.contains("scoped to"), "Should not have scoping note when scopedToChangedFiles=false");
    }

    @Test
    void formatMarkdownIncludesErrorSeverityLabel() {
        LinterFinding errorIssue = errorFinding("spotbugs", "Foo.java", 5, "CRITICAL_RULE");
        StaticAnalysisDiffReport report = new StaticAnalysisDiffReport(
                List.of(), List.of(), List.of(errorIssue), List.of(), Set.of(), false);
        String md = report.formatMarkdown();
        assertTrue(md.contains("ERROR"), "Expected ERROR severity label in report");
    }
}
