package com.eneve.agent.linter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LinterService} delta-detection and diff-report logic.
 * Injects dependencies via reflection to avoid requiring a CDI container.
 */
class LinterServiceTest {

    private LinterService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new LinterService();
        injectConfig(service, stubConfig(0, true));
    }

    // ─── Fixtures ────────────────────────────────────────────────────────

    private static LinterFinding finding(String linter, String file, int line, String rule) {
        return new LinterFinding(linter, file, line, LinterFinding.SEVERITY_WARNING, rule, "msg");
    }

    private static LinterResult result(String linter, LinterFinding... findings) {
        return new LinterResult(linter, List.of(findings), true, "");
    }

    // ─── findNewIssues ───────────────────────────────────────────────────

    @Test
    void findNewIssuesReturnsEmptyWhenCurrentMatchesBaseline() {
        LinterFinding f = finding("cs", "Foo.java", 10, "R");
        List<LinterResult> baseline = List.of(result("cs", f));
        List<LinterResult> current  = List.of(result("cs", f));
        assertTrue(service.findNewIssues(baseline, current).isEmpty());
    }

    @Test
    void findNewIssuesDetectsNewFinding() {
        LinterFinding existing = finding("cs", "Foo.java", 10, "R1");
        LinterFinding newOne   = finding("cs", "Foo.java", 20, "R2");
        List<LinterResult> baseline = List.of(result("cs", existing));
        List<LinterResult> current  = List.of(result("cs", existing, newOne));
        List<LinterFinding> issues = service.findNewIssues(baseline, current);
        assertEquals(1, issues.size());
        assertEquals("R2", issues.get(0).rule());
    }

    @Test
    void findNewIssuesIgnoresLineShiftWithinTolerance() throws Exception {
        injectConfig(service, stubConfig(5, true));
        LinterFinding baseline = finding("cs", "Foo.java", 10, "R");
        LinterFinding shifted  = finding("cs", "Foo.java", 14, "R");
        List<LinterResult> base = List.of(result("cs", baseline));
        List<LinterResult> curr = List.of(result("cs", shifted));
        assertTrue(service.findNewIssues(base, curr).isEmpty(),
                "A shifted-but-same finding should not appear as new");
    }

    @Test
    void findNewIssuesFlagsIssueBeyondTolerance() throws Exception {
        injectConfig(service, stubConfig(5, true));
        LinterFinding baseline = finding("cs", "Foo.java", 10, "R");
        LinterFinding shifted  = finding("cs", "Foo.java", 20, "R");
        List<LinterResult> base = List.of(result("cs", baseline));
        List<LinterResult> curr = List.of(result("cs", shifted));
        List<LinterFinding> issues = service.findNewIssues(base, curr);
        assertEquals(1, issues.size(),
                "A finding shifted beyond tolerance should be flagged as new");
    }

    // ─── findResolvedIssues ──────────────────────────────────────────────

    @Test
    void findResolvedIssuesReturnsEmptyWhenNothingChanged() {
        LinterFinding f = finding("pmd", "Bar.java", 5, "R");
        List<LinterResult> baseline = List.of(result("pmd", f));
        List<LinterResult> current  = List.of(result("pmd", f));
        assertTrue(service.findResolvedIssues(baseline, current).isEmpty());
    }

    @Test
    void findResolvedIssuesDetectsRemovedFinding() {
        LinterFinding kept    = finding("pmd", "Bar.java", 5, "R1");
        LinterFinding removed = finding("pmd", "Bar.java", 15, "R2");
        List<LinterResult> baseline = List.of(result("pmd", kept, removed));
        List<LinterResult> current  = List.of(result("pmd", kept));
        List<LinterFinding> resolved = service.findResolvedIssues(baseline, current);
        assertEquals(1, resolved.size());
        assertEquals("R2", resolved.get(0).rule());
    }

    @Test
    void findResolvedIssuesReturnsAllWhenCurrentIsEmpty() {
        LinterFinding f1 = finding("pmd", "A.java", 1, "R1");
        LinterFinding f2 = finding("pmd", "A.java", 2, "R2");
        List<LinterResult> baseline = List.of(result("pmd", f1, f2));
        List<LinterFinding> resolved = service.findResolvedIssues(baseline, List.of());
        assertEquals(2, resolved.size());
    }

    // ─── buildDiffReport ─────────────────────────────────────────────────

    @Test
    void buildDiffReportScopesToChangedFilesWhenEnabled() throws Exception {
        injectConfig(service, stubConfig(0, true));

        LinterFinding inChanged    = finding("cs", "Changed.java",   5,  "R1");
        LinterFinding notInChanged = finding("cs", "Unchanged.java", 10, "R2");

        List<LinterResult> baseline = List.of(result("cs"));
        List<LinterResult> current  = List.of(result("cs", inChanged, notInChanged));

        StaticAnalysisDiffReport report = service.buildDiffReport(
                baseline, current, Set.of("Changed.java"));

        assertEquals(1, report.newIssues().size(),
                "Only the finding in the changed file should appear");
        assertEquals("Changed.java", report.newIssues().get(0).file());
        assertTrue(report.scopedToChangedFiles());
    }

    @Test
    void buildDiffReportDoesNotScopeWhenChangedFilesEmpty() throws Exception {
        injectConfig(service, stubConfig(0, true));

        LinterFinding f = finding("cs", "Any.java", 5, "R");
        List<LinterResult> baseline = List.of(result("cs"));
        List<LinterResult> current  = List.of(result("cs", f));

        StaticAnalysisDiffReport report = service.buildDiffReport(baseline, current, Set.of());

        assertEquals(1, report.newIssues().size(), "All new findings should be included");
        assertFalse(report.scopedToChangedFiles(),
                "No scoping when changed-files set is empty");
    }

    @Test
    void buildDiffReportDoesNotScopeWhenConfigDisabled() throws Exception {
        injectConfig(service, stubConfig(0, false));

        LinterFinding inChanged    = finding("cs", "Changed.java",   5,  "R1");
        LinterFinding notInChanged = finding("cs", "Unchanged.java", 10, "R2");

        List<LinterResult> baseline = List.of(result("cs"));
        List<LinterResult> current  = List.of(result("cs", inChanged, notInChanged));

        StaticAnalysisDiffReport report = service.buildDiffReport(
                baseline, current, Set.of("Changed.java"));

        assertEquals(2, report.newIssues().size(),
                "Both findings should appear when scoping is disabled");
        assertFalse(report.scopedToChangedFiles());
    }

    @Test
    void buildDiffReportCapturesBaselineAndCurrentResults() {
        LinterResult baselineResult = result("checkstyle", finding("checkstyle", "A.java", 1, "R"));
        LinterResult currentResult  = result("pmd",        finding("pmd",        "B.java", 2, "Q"));

        StaticAnalysisDiffReport report = service.buildDiffReport(
                List.of(baselineResult), List.of(currentResult), Set.of());

        assertEquals(1, report.baselineResults().size());
        assertEquals(1, report.currentResults().size());
        assertEquals("checkstyle", report.baselineResults().get(0).linterName());
        assertEquals("pmd",        report.currentResults().get(0).linterName());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** Creates a minimal {@link LinterConfig} with controllable tolerance and scope. */
    private static LinterConfig stubConfig(int lineTolerance, boolean scopeToChangedFiles) throws Exception {
        LinterConfig cfg = new LinterConfig();
        setField(cfg, "enabled", true);
        setField(cfg, "checkstyleEnabled", true);
        setField(cfg, "pmdEnabled", true);
        setField(cfg, "spotbugsEnabled", true);
        setField(cfg, "eslintEnabled", true);
        setField(cfg, "dotnetFormatEnabled", true);
        setField(cfg, "maxFixIterations", 2);
        setField(cfg, "failOnNewIssues", false);
        setField(cfg, "timeoutMinutes", 10L);
        setField(cfg, "reportOnPr", true);
        setField(cfg, "scopeToChangedFiles", scopeToChangedFiles);
        setField(cfg, "lineTolerance", lineTolerance);
        return cfg;
    }

    private static void injectConfig(LinterService svc, LinterConfig cfg) throws Exception {
        setField(svc, "config", cfg);
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = findField(obj.getClass(), fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), name);
            }
            throw e;
        }
    }
}
