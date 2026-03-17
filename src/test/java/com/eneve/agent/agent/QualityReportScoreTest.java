package com.eneve.agent.agent;

import java.util.Map;

import com.eneve.agent.agent.QualityReport.AikidoSection;
import com.eneve.agent.agent.QualityReport.ComplexitySection;
import com.eneve.agent.agent.QualityReport.CoverageSection;
import com.eneve.agent.agent.QualityReport.LinterSection;
import com.eneve.agent.agent.QualityReport.ReviewSection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QualityReportScoreTest {

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static CoverageSection coverage(double lineRate) {
        return new CoverageSection(lineRate, lineRate, lineRate, lineRate,
                (int) lineRate, (int) (100 - lineRate), 0, 0, 0, 0, 0, 0);
    }

    private static LinterSection linter(int errors, int warnings) {
        return new LinterSection(errors + warnings, errors, warnings, 0, Map.of(), Map.of());
    }

    private static AikidoSection aikido(int critical, int high, int medium, int low) {
        return new AikidoSection(critical + high + medium + low, critical, high, medium, low);
    }

    private static ComplexitySection complexity(int methodsAbove, int totalMethods) {
        return new ComplexitySection(totalMethods, methodsAbove, 5.0, 15, 10);
    }

    private static ReviewSection review(double resolutionRate) {
        long total = 100;
        long resolved = (long) (total * resolutionRate);
        return new ReviewSection(total, resolved, resolutionRate, 5, 0.05);
    }

    // ─── Perfect score ────────────────────────────────────────────────────

    @Test
    void perfectScoreWhenAllMetricsIdeal() {
        double score = QualityReport.computeScore(
                coverage(100),
                linter(0, 0),
                aikido(0, 0, 0, 0),
                complexity(0, 20),
                review(1.0));
        assertEquals(1.0, score, 0.0001);
    }

    // ─── Zero score ───────────────────────────────────────────────────────

    @Test
    void zeroScoreWhenAllMetricsWorst() {
        double score = QualityReport.computeScore(
                coverage(0),
                linter(10, 0),  // 10 errors * 0.1 = 1.0 penalty → 0.0
                aikido(2, 0, 0, 0), // 2 critical * 0.5 = 1.0 penalty → 0.0
                complexity(20, 20), // all methods above threshold → 0.0
                review(0.0));
        assertEquals(0.0, score, 0.0001);
    }

    // ─── Equal weight redistribution ─────────────────────────────────────

    @Test
    void missingAikidoRedistributesWeightToRemainingFour() {
        // 4 sections present, all perfect → score should still be 1.0
        double score = QualityReport.computeScore(
                coverage(100),
                linter(0, 0),
                null,
                complexity(0, 20),
                review(1.0));
        assertEquals(1.0, score, 0.0001);
    }

    @Test
    void onlyOneSection_scoreEqualsItsSectionScore() {
        double score = QualityReport.computeScore(
                coverage(80),
                null, null, null, null);
        assertEquals(0.8, score, 0.0001);
    }

    @Test
    void allNullSections_returnsZero() {
        double score = QualityReport.computeScore(null, null, null, null, null);
        assertEquals(0.0, score);
    }

    // ─── Coverage score ───────────────────────────────────────────────────

    @Test
    void coverageScoreIsLineRateDividedBy100() {
        double score = QualityReport.computeScore(coverage(60), null, null, null, null);
        assertEquals(0.6, score, 0.0001);
    }

    // ─── Linter score ─────────────────────────────────────────────────────

    @Test
    void linterPenalizesErrorsMoreThanWarnings() {
        // 1 error (0.1 penalty) → score = 0.9
        double scoreWithError = QualityReport.computeScore(null, linter(1, 0), null, null, null);
        // 1 warning (0.01 penalty) → score = 0.99
        double scoreWithWarning = QualityReport.computeScore(null, linter(0, 1), null, null, null);
        assertTrue(scoreWithWarning > scoreWithError);
        assertEquals(0.9, scoreWithError, 0.0001);
        assertEquals(0.99, scoreWithWarning, 0.0001);
    }

    @Test
    void linterScoreClampedAtZeroWhenSeverePenalty() {
        // 20 errors → penalty = 2.0 → clamped to 1.0 → score = 0.0
        double score = QualityReport.computeScore(null, linter(20, 0), null, null, null);
        assertEquals(0.0, score, 0.0001);
    }

    // ─── Aikido score ─────────────────────────────────────────────────────

    @Test
    void aikidoCriticalIssueSignificantlyReducesScore() {
        // 1 critical (0.5 penalty) → score = 0.5
        double score = QualityReport.computeScore(null, null, aikido(1, 0, 0, 0), null, null);
        assertEquals(0.5, score, 0.0001);
    }

    @Test
    void aikidoLowIssuesHaveSmallPenalty() {
        // 5 low issues (0.05 penalty) → score = 0.95
        double score = QualityReport.computeScore(null, null, aikido(0, 0, 0, 5), null, null);
        assertEquals(0.95, score, 0.0001);
    }

    // ─── Complexity score ─────────────────────────────────────────────────

    @Test
    void complexityScoreReflectsFractionOfCleanMethods() {
        // 4/20 methods above threshold → score = 1 - 0.2 = 0.8
        double score = QualityReport.computeScore(null, null, null, complexity(4, 20), null);
        assertEquals(0.8, score, 0.0001);
    }

    // ─── Review score ─────────────────────────────────────────────────────

    @Test
    void reviewScoreEqualsResolutionRate() {
        double score = QualityReport.computeScore(null, null, null, null, review(0.75));
        assertEquals(0.75, score, 0.0001);
    }

    // ─── Aggregate with mixed sections ────────────────────────────────────

    @Test
    void aggregateScoreAveragesAvailableSections() {
        // coverage=0.8, linter=0.9 (1 error), aikido=null → 2 sections → avg = 0.85
        double score = QualityReport.computeScore(coverage(80), linter(1, 0), null, null, null);
        assertEquals(0.85, score, 0.0001);
    }

    @Test
    void scoreIsRoundedToFourDecimalPlaces() {
        // coverage=0.333... lineRate
        double score = QualityReport.computeScore(coverage(100.0 / 3), null, null, null, null);
        // Should be rounded to 4 decimal places
        String scoreStr = Double.toString(score);
        int decimalPlaces = scoreStr.contains(".") ? scoreStr.length() - scoreStr.indexOf('.') - 1 : 0;
        assertTrue(decimalPlaces <= 4);
    }
}
