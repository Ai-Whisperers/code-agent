package com.eneve.agent.diff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class DiffFormatterTest {

    private static final String SIMPLE_DIFF = """
            diff --git a/src/Foo.java b/src/Foo.java
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -10,5 +10,6 @@ class Foo {
                 int x = 1;
            -    int y = 2;
            +    int y = 20;
            +    int z = 3;
                 return x + y;
             }
            """;

    @Test
    void toAnnotatedShowsCorrectLineNumbers() {
        List<ParsedDiffFile> files = DiffParser.parse(SIMPLE_DIFF);
        String annotated = DiffFormatter.toAnnotated(files);

        assertTrue(annotated.contains("--- src/Foo.java ---"), "Should contain file header");
        assertTrue(annotated.contains("   10 |"), "Context line 10");
        assertTrue(annotated.contains("     -|"), "Removed line has no number");
        assertTrue(annotated.contains("   11+|"), "Added line 11 marked with +");
        assertTrue(annotated.contains("   12+|"), "Added line 12 marked with +");
        assertTrue(annotated.contains("   13 |"), "Context line 13");
        assertTrue(annotated.contains("   14 |"), "Context line 14");
    }

    @Test
    void toAnnotatedRemovedLinesHaveNoLineNumber() {
        List<ParsedDiffFile> files = DiffParser.parse(SIMPLE_DIFF);
        String annotated = DiffFormatter.toAnnotated(files);

        for (String line : annotated.split("\n")) {
            if (line.contains("-|")) {
                assertTrue(line.startsWith("     -|"),
                        "Removed lines should show blank space + dash: " + line);
            }
        }
    }

    @Test
    void buildCommentableLinesIncludesAddedAndContext() {
        List<ParsedDiffFile> files = DiffParser.parse(SIMPLE_DIFF);
        Map<String, TreeSet<Integer>> map = DiffFormatter.buildCommentableLines(files);

        assertTrue(map.containsKey("src/Foo.java"));
        TreeSet<Integer> lines = map.get("src/Foo.java");

        // Context: 10, 13, 14; Added: 11, 12
        assertTrue(lines.contains(10), "Context line 10");
        assertTrue(lines.contains(11), "Added line 11");
        assertTrue(lines.contains(12), "Added line 12");
        assertTrue(lines.contains(13), "Context line 13");
        assertTrue(lines.contains(14), "Context line 14");

        // Removed lines should NOT be in the set
        assertEquals(5, lines.size());
    }

    @Test
    void buildCommentableLinesExcludesRemovedOnly() {
        String diff = """
                diff --git a/f.txt b/f.txt
                --- a/f.txt
                +++ b/f.txt
                @@ -1,3 +1,2 @@
                 keep
                -gone
                 also keep
                """;
        List<ParsedDiffFile> files = DiffParser.parse(diff);
        Map<String, TreeSet<Integer>> map = DiffFormatter.buildCommentableLines(files);

        TreeSet<Integer> lines = map.get("f.txt");
        assertNotNull(lines);
        assertTrue(lines.contains(1));
        assertTrue(lines.contains(2));
        assertEquals(2, lines.size());
    }

    @Test
    void snapToNearestExactMatch() {
        TreeSet<Integer> valid = new TreeSet<>(List.of(10, 15, 20, 25));
        assertEquals(15, DiffFormatter.snapToNearest(valid, 15));
    }

    @Test
    void snapToNearestOffByOne() {
        TreeSet<Integer> valid = new TreeSet<>(List.of(10, 15, 20, 25));
        assertEquals(15, DiffFormatter.snapToNearest(valid, 14));
        assertEquals(15, DiffFormatter.snapToNearest(valid, 16));
    }

    @Test
    void snapToNearestOffByMany() {
        TreeSet<Integer> valid = new TreeSet<>(List.of(10, 50));
        assertEquals(10, DiffFormatter.snapToNearest(valid, 20));
        assertEquals(50, DiffFormatter.snapToNearest(valid, 40));
        // Equidistant: prefers floor
        assertEquals(10, DiffFormatter.snapToNearest(valid, 30));
    }

    @Test
    void snapToNearestBelowAll() {
        TreeSet<Integer> valid = new TreeSet<>(List.of(10, 20));
        assertEquals(10, DiffFormatter.snapToNearest(valid, 1));
    }

    @Test
    void snapToNearestAboveAll() {
        TreeSet<Integer> valid = new TreeSet<>(List.of(10, 20));
        assertEquals(20, DiffFormatter.snapToNearest(valid, 100));
    }

    @Test
    void snapToNearestEmptySetReturnsCandidateUnchanged() {
        assertEquals(42, DiffFormatter.snapToNearest(new TreeSet<>(), 42));
        assertEquals(42, DiffFormatter.snapToNearest(null, 42));
    }

    @Test
    void truncateAtFileBoundaryKeepsAllWhenUnderLimit() {
        List<ParsedDiffFile> files = DiffParser.parse(SIMPLE_DIFF);
        List<ParsedDiffFile> result = DiffFormatter.truncateAtFileBoundary(files, 100_000);
        assertEquals(files.size(), result.size());
    }

    @Test
    void truncateAtFileBoundaryDropsFilesWhenOverLimit() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1,2 +1,3 @@
                 line
                +added
                 end
                diff --git a/src/B.java b/src/B.java
                --- a/src/B.java
                +++ b/src/B.java
                @@ -1,2 +1,3 @@
                 line
                +added
                 end
                """;
        List<ParsedDiffFile> files = DiffParser.parse(diff);
        assertEquals(2, files.size());

        // Use a limit that allows only 1 file
        List<ParsedDiffFile> truncated = DiffFormatter.truncateAtFileBoundary(files, 40);
        assertEquals(1, truncated.size());
        assertEquals("src/A.java", truncated.get(0).path());
    }

    @Test
    void truncateAtFileBoundaryKeepsAtLeastOneFile() {
        List<ParsedDiffFile> files = DiffParser.parse(SIMPLE_DIFF);
        // Even with a very tiny limit, we always keep at least 1 file
        List<ParsedDiffFile> result = DiffFormatter.truncateAtFileBoundary(files, 1);
        assertEquals(1, result.size());
    }

    @Test
    void multiFileCommentableLines() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1,2 +1,3 @@
                 ctx
                +add
                 ctx2
                diff --git a/src/B.java b/src/B.java
                --- a/src/B.java
                +++ b/src/B.java
                @@ -5,2 +5,3 @@
                 alpha
                +beta
                 gamma
                """;
        List<ParsedDiffFile> files = DiffParser.parse(diff);
        Map<String, TreeSet<Integer>> map = DiffFormatter.buildCommentableLines(files);

        assertEquals(2, map.size());
        assertTrue(map.containsKey("src/A.java"));
        assertTrue(map.containsKey("src/B.java"));
        assertTrue(map.get("src/B.java").contains(6));
    }
}
