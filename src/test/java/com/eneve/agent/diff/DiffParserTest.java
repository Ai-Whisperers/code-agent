package com.eneve.agent.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffParserTest {

    @Test
    void parseSingleFileSingleHunk() {
        String diff = """
                diff --git a/src/Foo.java b/src/Foo.java
                --- a/src/Foo.java
                +++ b/src/Foo.java
                @@ -10,6 +10,7 @@ public class Foo {
                     int x = 1;
                     int y = 2;
                +    int z = 3;
                     return x + y;
                 }
                """;

        List<ParsedDiffFile> files = DiffParser.parse(diff);

        assertEquals(1, files.size());
        ParsedDiffFile file = files.get(0);
        assertEquals("src/Foo.java", file.path());
        assertEquals(1, file.hunks().size());

        DiffHunk hunk = file.hunks().get(0);
        assertEquals(10, hunk.newStart());
        assertEquals(7, hunk.newCount());

        List<DiffLine> lines = hunk.lines();
        assertEquals(5, lines.size());

        assertEquals(DiffLine.Type.CONTEXT, lines.get(0).type());
        assertEquals(10, lines.get(0).newLineNo());
        assertEquals("    int x = 1;", lines.get(0).content());

        assertEquals(DiffLine.Type.CONTEXT, lines.get(1).type());
        assertEquals(11, lines.get(1).newLineNo());

        assertEquals(DiffLine.Type.ADDED, lines.get(2).type());
        assertEquals(12, lines.get(2).newLineNo());
        assertEquals("    int z = 3;", lines.get(2).content());

        assertEquals(DiffLine.Type.CONTEXT, lines.get(3).type());
        assertEquals(13, lines.get(3).newLineNo());

        assertEquals(DiffLine.Type.CONTEXT, lines.get(4).type());
        assertEquals(14, lines.get(4).newLineNo());
    }

    @Test
    void parseMultiFileMultiHunk() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1,3 +1,4 @@
                 line1
                +added
                 line2
                 line3
                @@ -20,3 +21,4 @@ some function
                 existing
                +new line
                 more
                 end
                diff --git a/src/B.java b/src/B.java
                --- a/src/B.java
                +++ b/src/B.java
                @@ -5,3 +5,4 @@
                 alpha
                +beta
                 gamma
                 delta
                """;

        List<ParsedDiffFile> files = DiffParser.parse(diff);

        assertEquals(2, files.size());
        assertEquals("src/A.java", files.get(0).path());
        assertEquals(2, files.get(0).hunks().size());
        assertEquals("src/B.java", files.get(1).path());
        assertEquals(1, files.get(1).hunks().size());

        // First file, second hunk starts at new-side line 21
        DiffHunk secondHunk = files.get(0).hunks().get(1);
        assertEquals(21, secondHunk.newStart());
        DiffLine addedInSecondHunk = secondHunk.lines().stream()
                .filter(l -> l.type() == DiffLine.Type.ADDED)
                .findFirst().orElseThrow();
        assertEquals(22, addedInSecondHunk.newLineNo());
    }

    @Test
    void parseWithRemovedLines() {
        String diff = """
                diff --git a/src/C.java b/src/C.java
                --- a/src/C.java
                +++ b/src/C.java
                @@ -10,5 +10,4 @@ class C {
                     keep1
                -    removed
                     keep2
                     keep3
                """;

        List<ParsedDiffFile> files = DiffParser.parse(diff);
        assertEquals(1, files.size());

        List<DiffLine> lines = files.get(0).hunks().get(0).lines();
        assertEquals(4, lines.size());

        assertEquals(DiffLine.Type.CONTEXT, lines.get(0).type());
        assertEquals(10, lines.get(0).newLineNo());

        assertEquals(DiffLine.Type.REMOVED, lines.get(1).type());
        assertEquals(-1, lines.get(1).newLineNo());
        assertEquals("    removed", lines.get(1).content());

        assertEquals(DiffLine.Type.CONTEXT, lines.get(2).type());
        assertEquals(11, lines.get(2).newLineNo());

        assertEquals(DiffLine.Type.CONTEXT, lines.get(3).type());
        assertEquals(12, lines.get(3).newLineNo());
    }

    @Test
    void parseNewFile() {
        String diff = """
                diff --git a/src/New.java b/src/New.java
                new file mode 100644
                --- /dev/null
                +++ b/src/New.java
                @@ -0,0 +1,3 @@
                +package com.example;
                +
                +public class New {}
                """;

        List<ParsedDiffFile> files = DiffParser.parse(diff);
        assertEquals(1, files.size());
        assertEquals("src/New.java", files.get(0).path());

        DiffHunk hunk = files.get(0).hunks().get(0);
        assertEquals(1, hunk.newStart());
        assertEquals(3, hunk.newCount());
        assertEquals(3, hunk.lines().size());
        assertTrue(hunk.lines().stream().allMatch(l -> l.type() == DiffLine.Type.ADDED));
        assertEquals(1, hunk.lines().get(0).newLineNo());
        assertEquals(2, hunk.lines().get(1).newLineNo());
        assertEquals(3, hunk.lines().get(2).newLineNo());
    }

    @Test
    void parseDeletedFile() {
        String diff = """
                diff --git a/src/Old.java b/src/Old.java
                deleted file mode 100644
                --- a/src/Old.java
                +++ /dev/null
                @@ -1,3 +0,0 @@
                -package com.example;
                -
                -public class Old {}
                """;

        List<ParsedDiffFile> files = DiffParser.parse(diff);
        // Deleted files have no new-side path, so they should be excluded
        // (the path from --- is used as fallback, but +++ is /dev/null)
        // The parser picks up the path from --- a/src/Old.java
        assertEquals(1, files.size());
        assertEquals("src/Old.java", files.get(0).path());
        assertTrue(files.get(0).hunks().get(0).lines().stream()
                .allMatch(l -> l.type() == DiffLine.Type.REMOVED));
    }

    @Test
    void parseRename() {
        String diff = """
                diff --git a/src/OldName.java b/src/NewName.java
                similarity index 95%
                rename from src/OldName.java
                rename to src/NewName.java
                --- a/src/OldName.java
                +++ b/src/NewName.java
                @@ -1,3 +1,3 @@
                 package com.example;
                -public class OldName {}
                +public class NewName {}
                """;

        List<ParsedDiffFile> files = DiffParser.parse(diff);
        assertEquals(1, files.size());
        assertEquals("src/NewName.java", files.get(0).path());
    }

    @Test
    void emptyDiffReturnsEmptyList() {
        assertEquals(List.of(), DiffParser.parse(""));
        assertEquals(List.of(), DiffParser.parse(null));
    }

    @Test
    void malformedDiffHandledGracefully() {
        String diff = """
                diff --git a/src/Foo.java b/src/Foo.java
                --- a/src/Foo.java
                +++ b/src/Foo.java
                @@ -10,3 +10,4 @@
                 context
                +added
                 more context
                this line is garbage and should not crash
                diff --git a/src/Bar.java b/src/Bar.java
                --- a/src/Bar.java
                +++ b/src/Bar.java
                @@ -1,2 +1,3 @@
                 first
                +second
                 third
                """;

        List<ParsedDiffFile> files = DiffParser.parse(diff);
        assertEquals(2, files.size());
        assertEquals("src/Foo.java", files.get(0).path());
        assertEquals("src/Bar.java", files.get(1).path());
    }

    @Test
    void parseHunkWithSingleLineCount() {
        // When count is omitted, it defaults to 1: @@ -5 +5 @@
        String diff = """
                diff --git a/f.txt b/f.txt
                --- a/f.txt
                +++ b/f.txt
                @@ -5 +5 @@ context header
                 only line
                """;

        List<ParsedDiffFile> files = DiffParser.parse(diff);
        assertEquals(1, files.size());
        DiffHunk hunk = files.get(0).hunks().get(0);
        assertEquals(5, hunk.newStart());
        assertEquals(1, hunk.newCount());
    }

    @Test
    void noNewlineMarkerIsSkipped() {
        String diff = """
                diff --git a/f.txt b/f.txt
                --- a/f.txt
                +++ b/f.txt
                @@ -1,2 +1,2 @@
                -old
                +new
                \\ No newline at end of file
                 context
                """;

        List<ParsedDiffFile> files = DiffParser.parse(diff);
        assertEquals(1, files.size());
        List<DiffLine> lines = files.get(0).hunks().get(0).lines();
        assertEquals(3, lines.size());
        assertEquals(DiffLine.Type.REMOVED, lines.get(0).type());
        assertEquals(DiffLine.Type.ADDED, lines.get(1).type());
        assertEquals(1, lines.get(1).newLineNo());
        assertEquals(DiffLine.Type.CONTEXT, lines.get(2).type());
        assertEquals(2, lines.get(2).newLineNo());
    }
}
