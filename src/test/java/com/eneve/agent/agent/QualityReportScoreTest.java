package com.eneve.agent.agent;

import com.eneve.agent.agent.model.QualityReport;

import java.util.Map;

import com.eneve.agent.agent.model.QualityReport.AikidoSection;
import com.eneve.agent.agent.model.QualityReport.ComplexitySection;
import com.eneve.agent.agent.model.QualityReport.CoverageSection;
import com.eneve.agent.agent.model.QualityReport.LinterSection;
import com.eneve.agent.agent.model.QualityReport.ReviewSection;
import com.eneve.agent.agent.model.QualityReport.TestPresenceSection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QualityReportScoreTest {

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** linesCovered / (linesCovered + linesMissed) = lineRate */
    private static CoverageSection coverage(int linesCovered, int linesMissed) {
        int total = linesCovered + linesMissed;
        double lineRate = total > 0 ? 100.0 * linesCovered / total : 0.0;
        return new CoverageSection(lineRate, 0.0, 0.0, 0.0,
                linesCovered, linesMissed, 0, 0, 0, 0, 0, 0, java.util.List.of());
    }

    /** testFiles / sourceFiles = ratio (e.g. tests(80,100) → 0.8) */
    private static TestPresenceSection tests(int testFiles, int sourceFiles) {
        double ratio = sourceFiles == 0 ? 0.0 : Math.min(1.0, (double) testFiles / sourceFiles);
        return new TestPresenceSection(sourceFiles, testFiles, ratio, java.util.List.of("Java"));
    }

    private static LinterSection linter(int errors, int warnings) {
        return new LinterSection(errors + warnings, errors, warnings, 0, Map.of(), Map.of());
    }

    private static AikidoSection aikido(int critical, int high, int medium, int low) {
        return new AikidoSection(critical + high + medium + low, critical, high, medium, low, 0, 0, 0, 0, 0);
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
                null,
                tests(20, 20),       // 100% test ratio
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
                null,
                tests(0, 20),          // no test files → ratio 0
                linter(10, 0),         // 10 errors → 0
                aikido(2, 0, 0, 0),    // 2 critical → 0
                complexity(20, 20),    // all above threshold → 0
                review(0.0));
        assertEquals(0.0, score, 0.0001);
    }

    // ─── Weight redistribution ────────────────────────────────────────────

    @Test
    void missingAikidoRedistributesWeightToRemainingThree() {
        // All sections perfect, aikido absent → weight(tests=10 + cplx=50 + lint=20) = 80 → 1.0
        double score = QualityReport.computeScore(
                null,
                tests(20, 20),
                linter(0, 0),
                null,
                complexity(0, 20),
                review(1.0));
        assertEquals(1.0, score, 0.0001);
    }

    @Test
    void testPresenceOnlyScoreEqualsRatio() {
        // 16/20 files have tests → ratio=0.8; totalWeight=10; score=0.8
        double score = QualityReport.computeScore(null, tests(16, 20), null, null, null, null);
        assertEquals(0.8, score, 0.0001);
    }

    @Test
    void allNullSections_returnsZero() {
        // test presence absent → covScore=0, weight=10 in denominator → 0/10=0
        double score = QualityReport.computeScore(null, null, null, null, null, null);
        assertEquals(0.0, score);
    }

    // ─── Test presence score ──────────────────────────────────────────────

    @Test
    void testPresenceScoreEqualsRatio() {
        // 6/10 source files have tests → ratio=0.6; totalWeight=10; score=0.6
        double score = QualityReport.computeScore(null, tests(6, 10), null, null, null, null);
        assertEquals(0.6, score, 0.0001);
    }

    @Test
    void missingTestPresencePenalisesScore() {
        // null test section → covScore=0; complexity=0.8 (4/20 above threshold)
        // totalWeight=10+50=60; weighted=0*10 + 0.8*50=40; score=40/60≈0.6667
        double score = QualityReport.computeScore(null, null, null, null, complexity(4, 20), null);
        assertEquals(0.6667, score, 0.0001);
    }

    @Test
    void testRatioIsCappedAtOne() {
        // More test files than source files — ratio capped at 1.0
        double score = QualityReport.computeScore(null, tests(50, 10), null, null, null, null);
        assertEquals(1.0, score, 0.0001);
    }

    // ─── Linter score ─────────────────────────────────────────────────────

    @Test
    void linterPenalizesErrorsMoreThanWarnings() {
        // 1 error → lintScore=0.9; totalWeight=10+20=30; weighted=0*10 + 0.9*20=18; score=0.6
        double scoreWithError = QualityReport.computeScore(null, null, linter(1, 0), null, null, null);
        // 1 warning → lintScore=0.99; totalWeight=30; weighted=0.99*20=19.8; score=0.66
        double scoreWithWarning = QualityReport.computeScore(null, null, linter(0, 1), null, null, null);
        assertTrue(scoreWithWarning > scoreWithError);
        assertEquals(0.6, scoreWithError, 0.0001);
        assertEquals(0.66, scoreWithWarning, 0.0001);
    }

    @Test
    void linterScoreClampedAtZeroWhenSeverePenalty() {
        // 20 errors → lintScore=0; totalWeight=30; score=0
        double score = QualityReport.computeScore(null, null, linter(20, 0), null, null, null);
        assertEquals(0.0, score, 0.0001);
    }

    // ─── Aikido score ─────────────────────────────────────────────────────

    @Test
    void aikidoCriticalIssueSignificantlyReducesScore() {
        // aikScore=0.5; totalWeight=10+30=40; weighted=0*10 + 0.5*30=15; score=15/40=0.375
        double score = QualityReport.computeScore(null, null, null, aikido(1, 0, 0, 0), null, null);
        assertEquals(0.375, score, 0.0001);
    }

    @Test
    void aikidoLowIssuesHaveSmallPenalty() {
        // aikScore=0.95; totalWeight=40; weighted=0.95*30=28.5; score=28.5/40=0.7125
        double score = QualityReport.computeScore(null, null, null, aikido(0, 0, 0, 5), null, null);
        assertEquals(0.7125, score, 0.0001);
    }

    // ─── Complexity score ─────────────────────────────────────────────────

    @Test
    void complexityScoreReflectsFractionOfCleanMethods() {
        // cplxScore=0.8; totalWeight=10+50=60; weighted=0*10 + 0.8*50=40; score=40/60≈0.6667
        double score = QualityReport.computeScore(null, null, null, null, complexity(4, 20), null);
        assertEquals(0.6667, score, 0.0001);
    }

    // ─── Review score (excluded from aggregate) ───────────────────────────

    @Test
    void reviewQualityDoesNotAffectScore() {
        // review is collected but excluded from score computation
        double withReview    = QualityReport.computeScore(null, tests(16, 20), null, null, null, review(1.0));
        double withoutReview = QualityReport.computeScore(null, tests(16, 20), null, null, null, null);
        assertEquals(withoutReview, withReview, 0.0001);
    }

    // ─── Aggregate with mixed sections ────────────────────────────────────

    @Test
    void aggregateScoreAppliesFixedWeights() {
        // tests=0.8 (w10), linter score=0.9 (w20), aikido=null → totalWeight=10+20=30
        // weighted = 0.8*10 + 0.9*20 = 8+18 = 26 → score=26/30≈0.8667
        double score = QualityReport.computeScore(null, tests(16, 20), linter(1, 0), null, null, null);
        assertEquals(0.8667, score, 0.0001);
    }

    // ─── Coverage section score ───────────────────────────────────────────

    @Test
    void coverageSectionOverridesTestPresenceInScore() {
        // coverage 80% line rate → covScore=0.8; totalWeight=10; score=0.8
        double withCoverage = QualityReport.computeScore(coverage(80, 20), null, null, null, null, null);
        assertEquals(0.8, withCoverage, 0.0001);
    }

    @Test
    void coverageSectionTakesPriorityOverTestPresence() {
        // coverage 60% line rate wins over tests(16,20)=0.8; covScore=0.6
        double score = QualityReport.computeScore(coverage(60, 40), tests(16, 20), null, null, null, null);
        assertEquals(0.6, score, 0.0001);
    }

    @Test
    void zeroCoverageRatePenalisesScore() {
        // coverage 0% (no lines covered) → covScore=0.0; totalWeight=10; score=0.0
        double score = QualityReport.computeScore(coverage(0, 100), null, null, null, null, null);
        assertEquals(0.0, score, 0.0001);
    }

    @Test
    void fullCoverageWithOtherSectionsPerfectScore() {
        // 100% coverage, all other sections ideal → score=1.0
        double score = QualityReport.computeScore(
                coverage(100, 0),
                tests(20, 20),
                linter(0, 0),
                aikido(0, 0, 0, 0),
                complexity(0, 20),
                review(1.0));
        assertEquals(1.0, score, 0.0001);
    }

    @Test
    void scoreIsRoundedToFourDecimalPlaces() {
        // 1/3 test ratio
        double score = QualityReport.computeScore(null, tests(1, 3), null, null, null, null);
        String scoreStr = Double.toString(score);
        int decimalPlaces = scoreStr.contains(".") ? scoreStr.length() - scoreStr.indexOf('.') - 1 : 0;
        assertTrue(decimalPlaces <= 4);
    }
}
