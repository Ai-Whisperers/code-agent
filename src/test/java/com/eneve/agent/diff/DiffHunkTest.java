package com.eneve.agent.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffHunkTest {

    @Test
    void recordCreationAndAccessors() {
        List<DiffLine> lines = List.of(
            new DiffLine(DiffLine.Type.CONTEXT, 10, "context line"),
            new DiffLine(DiffLine.Type.ADDED, 11, "added line"),
            new DiffLine(DiffLine.Type.REMOVED, -1, "removed line")
        );
        
        DiffHunk hunk = new DiffHunk(10, 3, lines);
        
        assertEquals(10, hunk.newStart());
        assertEquals(3, hunk.newCount());
        assertEquals(lines, hunk.lines());
        assertEquals(3, hunk.lines().size());
    }

    @Test
    void recordWithEmptyLines() {
        DiffHunk hunk = new DiffHunk(1, 0, List.of());
        
        assertEquals(1, hunk.newStart());
        assertEquals(0, hunk.newCount());
        assertEquals(List.of(), hunk.lines());
        assertTrue(hunk.lines().isEmpty());
    }

    @Test
    void recordWithSingleLine() {
        List<DiffLine> singleLine = List.of(
            new DiffLine(DiffLine.Type.ADDED, 5, "single added line")
        );
        
        DiffHunk hunk = new DiffHunk(5, 1, singleLine);
        
        assertEquals(5, hunk.newStart());
        assertEquals(1, hunk.newCount());
        assertEquals(1, hunk.lines().size());
        assertEquals(DiffLine.Type.ADDED, hunk.lines().get(0).type());
    }

    @Test
    void recordWithNullLines() {
        DiffHunk hunk = new DiffHunk(1, 0, null);
        
        assertEquals(1, hunk.newStart());
        assertEquals(0, hunk.newCount());
        assertNull(hunk.lines());
    }

    @Test
    void recordWithNegativeValues() {
        List<DiffLine> lines = List.of(
            new DiffLine(DiffLine.Type.REMOVED, -1, "removed")
        );
        
        DiffHunk hunk = new DiffHunk(-1, -1, lines);
        
        assertEquals(-1, hunk.newStart());
        assertEquals(-1, hunk.newCount());
        assertEquals(lines, hunk.lines());
    }

    @Test
    void recordWithZeroStart() {
        List<DiffLine> lines = List.of(
            new DiffLine(DiffLine.Type.ADDED, 1, "first line of file")
        );
        
        DiffHunk hunk = new DiffHunk(0, 1, lines);
        
        assertEquals(0, hunk.newStart());
        assertEquals(1, hunk.newCount());
        assertEquals(lines, hunk.lines());
    }

    @Test
    void recordWithLargeValues() {
        List<DiffLine> lines = List.of(
            new DiffLine(DiffLine.Type.CONTEXT, 10000, "line near end of file")
        );
        
        DiffHunk hunk = new DiffHunk(10000, 1000, lines);
        
        assertEquals(10000, hunk.newStart());
        assertEquals(1000, hunk.newCount());
        assertEquals(lines, hunk.lines());
    }

    @Test
    void recordWithManyLines() {
        List<DiffLine> manyLines = List.of(
            new DiffLine(DiffLine.Type.CONTEXT, 1, "line 1"),
            new DiffLine(DiffLine.Type.CONTEXT, 2, "line 2"),
            new DiffLine(DiffLine.Type.REMOVED, -1, "old line 3"),
            new DiffLine(DiffLine.Type.ADDED, 3, "new line 3"),
            new DiffLine(DiffLine.Type.ADDED, 4, "new line 4"),
            new DiffLine(DiffLine.Type.CONTEXT, 5, "line 5"),
            new DiffLine(DiffLine.Type.CONTEXT, 6, "line 6")
        );
        
        DiffHunk hunk = new DiffHunk(1, 6, manyLines);
        
        assertEquals(1, hunk.newStart());
        assertEquals(6, hunk.newCount());
        assertEquals(7, hunk.lines().size());
        
        // Verify all line types are present
        assertTrue(hunk.lines().stream().anyMatch(line -> line.type() == DiffLine.Type.CONTEXT));
        assertTrue(hunk.lines().stream().anyMatch(line -> line.type() == DiffLine.Type.ADDED));
        assertTrue(hunk.lines().stream().anyMatch(line -> line.type() == DiffLine.Type.REMOVED));
    }

    @Test
    void recordEquality() {
        List<DiffLine> lines = List.of(
            new DiffLine(DiffLine.Type.ADDED, 1, "line")
        );
        
        DiffHunk hunk1 = new DiffHunk(1, 1, lines);
        DiffHunk hunk2 = new DiffHunk(1, 1, lines);
        DiffHunk hunk3 = new DiffHunk(2, 1, lines);
        
        assertEquals(hunk1, hunk2);
        assertNotEquals(hunk1, hunk3);
        assertEquals(hunk1.hashCode(), hunk2.hashCode());
    }

    @Test
    void recordToString() {
        List<DiffLine> lines = List.of(
            new DiffLine(DiffLine.Type.ADDED, 42, "test line")
        );
        
        DiffHunk hunk = new DiffHunk(42, 1, lines);
        String toString = hunk.toString();
        
        assertTrue(toString.contains("42"));
        assertTrue(toString.contains("1"));
        // The lines list should be represented in the string
        assertTrue(toString.contains("DiffLine"));
    }

    @Test
    void recordWithMixedLineNumbers() {
        List<DiffLine> lines = List.of(
            new DiffLine(DiffLine.Type.CONTEXT, 10, "context before"),
            new DiffLine(DiffLine.Type.REMOVED, -1, "removed line 1"),
            new DiffLine(DiffLine.Type.REMOVED, -1, "removed line 2"),
            new DiffLine(DiffLine.Type.ADDED, 11, "added line 1"),
            new DiffLine(DiffLine.Type.ADDED, 12, "added line 2"),
            new DiffLine(DiffLine.Type.ADDED, 13, "added line 3"),
            new DiffLine(DiffLine.Type.CONTEXT, 14, "context after")
        );
        
        DiffHunk hunk = new DiffHunk(10, 5, lines);
        
        assertEquals(10, hunk.newStart());
        assertEquals(5, hunk.newCount());
        assertEquals(7, hunk.lines().size());
        
        // Check that removed lines have -1 line numbers
        long removedCount = hunk.lines().stream()
            .filter(line -> line.type() == DiffLine.Type.REMOVED)
            .filter(line -> line.newLineNo() == -1)
            .count();
        assertEquals(2, removedCount);
        
        // Check that added lines have positive line numbers
        long addedCount = hunk.lines().stream()
            .filter(line -> line.type() == DiffLine.Type.ADDED)
            .filter(line -> line.newLineNo() > 0)
            .count();
        assertEquals(3, addedCount);
    }
}