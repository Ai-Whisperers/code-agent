package com.eneve.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the aggregate-score computation logic that exists inside
 * {@link ScopeService#computeAggregate}.
 *
 * The private method is mirrored here as a package-level helper so we can test
 * the logic exhaustively without needing Quarkus or any mocks.
 */
class ScopeAggregateScoreTest {

    // ── No children: own score is returned ───────────────────────────────────

    @Test
    void noChildrenReturnsOwnScore() {
        assertEquals(75, computeAggregate(75, List.of(), true));
        assertEquals(75, computeAggregate(75, List.of(), false));
    }

    @Test
    void noChildrenOwnScoreNullReturnsNull() {
        assertNull(computeAggregate(null, List.of(), true));
    }

    // ── Simple average (weight disabled) ─────────────────────────────────────

    @Test
    void simpleAverageOfTwoEqualChildren() {
        List<int[]> children = List.of(new int[]{60, 0}, new int[]{80, 0});
        assertEquals(70, computeAggregate(null, children, false));
    }

    @Test
    void simpleAverageRoundsHalf() {
        List<int[]> children = List.of(new int[]{70, 0}, new int[]{71, 0});
        // avg = 70.5 → rounds to 71
        assertEquals(71, computeAggregate(null, children, false));
    }

    // ── Weighted average ──────────────────────────────────────────────────────

    @Test
    void weightedAverageHigherComplexityDominates() {
        // child A: score=100, complexity=10
        // child B: score=0,   complexity=90
        // weighted = (100*10 + 0*90) / 100 = 10
        List<int[]> children = List.of(new int[]{100, 10}, new int[]{0, 90});
        assertEquals(10, computeAggregate(null, children, true));
    }

    @Test
    void weightedAverageWithEqualWeightsSameAsSimpleAverage() {
        List<int[]> children = List.of(new int[]{60, 50}, new int[]{80, 50});
        assertEquals(70, computeAggregate(null, children, true));
    }

    @Test
    void weightedAverageFallsBackToSimpleWhenAllComplexityZero() {
        List<int[]> children = List.of(new int[]{60, 0}, new int[]{80, 0});
        // total weight = 0 → simple average
        assertEquals(70, computeAggregate(null, children, true));
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void singleChildReturnsItsScore() {
        assertEquals(42, computeAggregate(null, List.of(new int[]{42, 10}), true));
        assertEquals(42, computeAggregate(null, List.of(new int[]{42, 10}), false));
    }

    @Test
    void perfectScoreReturnedUnchanged() {
        List<int[]> children = List.of(new int[]{100, 20}, new int[]{100, 80});
        assertEquals(100, computeAggregate(null, children, true));
    }

    @Test
    void zeroScoreChildrenReturnZero() {
        List<int[]> children = List.of(new int[]{0, 50}, new int[]{0, 50});
        assertEquals(0, computeAggregate(null, children, true));
        assertEquals(0, computeAggregate(null, children, false));
    }

    // ── Mirror of private helper ──────────────────────────────────────────────

    private static Integer computeAggregate(Integer ownScore, List<int[]> childScores, boolean weightEnabled) {
        if (childScores.isEmpty()) return ownScore;
        if (weightEnabled) {
            long totalWeight = childScores.stream().mapToLong(a -> a[1]).sum();
            if (totalWeight == 0) {
                return (int) Math.round(childScores.stream().mapToInt(a -> a[0]).average().orElse(0));
            }
            double weightedSum = childScores.stream().mapToDouble(a -> (double) a[0] * a[1]).sum();
            return (int) Math.round(weightedSum / totalWeight);
        }
        return (int) Math.round(childScores.stream().mapToInt(a -> a[0]).average().orElse(0));
    }
}
