package com.eneve.agent.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParsedDiffFileTest {

    @Test
    void recordCreationAndAccessors() {
        List<DiffHunk> hunks = List.of(
            new DiffHunk(1, 3, List.of(
                new DiffLine(DiffLine.Type.CONTEXT, 1, "context line"),
                new DiffLine(DiffLine.Type.ADDED, 2, "added line"),
                new DiffLine(DiffLine.Type.CONTEXT, 3, "context line 2")
            )),
            new DiffHunk(10, 2, List.of(
                new DiffLine(DiffLine.Type.REMOVED, -1, "removed line"),
                new DiffLine(DiffLine.Type.ADDED, 10, "replacement line")
            ))
        );
        
        ParsedDiffFile file = new ParsedDiffFile("src/main/java/Test.java", hunks);
        
        assertEquals("src/main/java/Test.java", file.path());
        assertEquals(hunks, file.hunks());
        assertEquals(2, file.hunks().size());
    }

    @Test
    void recordWithEmptyPath() {
        List<DiffHunk> hunks = List.of(
            new DiffHunk(1, 1, List.of(
                new DiffLine(DiffLine.Type.ADDED, 1, "new file content")
            ))
        );
        
        ParsedDiffFile file = new ParsedDiffFile("", hunks);
        
        assertEquals("", file.path());
        assertEquals(hunks, file.hunks());
    }

    @Test
    void recordWithNullPath() {
        List<DiffHunk> hunks = List.of(
            new DiffHunk(1, 1, List.of(
                new DiffLine(DiffLine.Type.ADDED, 1, "content")
            ))
        );
        
        ParsedDiffFile file = new ParsedDiffFile(null, hunks);
        
        assertNull(file.path());
        assertEquals(hunks, file.hunks());
    }

    @Test
    void recordWithEmptyHunks() {
        ParsedDiffFile file = new ParsedDiffFile("src/Empty.java", List.of());
        
        assertEquals("src/Empty.java", file.path());
        assertEquals(List.of(), file.hunks());
        assertTrue(file.hunks().isEmpty());
    }

    @Test
    void recordWithNullHunks() {
        ParsedDiffFile file = new ParsedDiffFile("src/Test.java", null);
        
        assertEquals("src/Test.java", file.path());
        assertNull(file.hunks());
    }

    @Test
    void recordWithSingleHunk() {
        DiffHunk singleHunk = new DiffHunk(5, 1, List.of(
            new DiffLine(DiffLine.Type.ADDED, 5, "single added line")
        ));
        
        ParsedDiffFile file = new ParsedDiffFile("single.txt", List.of(singleHunk));
        
        assertEquals("single.txt", file.path());
        assertEquals(1, file.hunks().size());
        assertEquals(singleHunk, file.hunks().get(0));
    }

    @Test
    void recordWithComplexPath() {
        String complexPath = "src/main/java/com/example/very/deeply/nested/package/VeryLongClassName.java";
        List<DiffHunk> hunks = List.of(
            new DiffHunk(1, 1, List.of(
                new DiffLine(DiffLine.Type.CONTEXT, 1, "package com.example.very.deeply.nested.package;")
            ))
        );
        
        ParsedDiffFile file = new ParsedDiffFile(complexPath, hunks);
        
        assertEquals(complexPath, file.path());
        assertEquals(hunks, file.hunks());
    }

    @Test
    void recordWithSpecialCharactersInPath() {
        String specialPath = "files with spaces/special-chars_123/file (2).java";
        List<DiffHunk> hunks = List.of(
            new DiffHunk(1, 1, List.of(
                new DiffLine(DiffLine.Type.ADDED, 1, "// special file")
            ))
        );
        
        ParsedDiffFile file = new ParsedDiffFile(specialPath, hunks);
        
        assertEquals(specialPath, file.path());
        assertEquals(hunks, file.hunks());
    }

    @Test
    void recordWithManyHunks() {
        List<DiffHunk> manyHunks = List.of(
            new DiffHunk(1, 2, List.of(
                new DiffLine(DiffLine.Type.ADDED, 1, "line 1"),
                new DiffLine(DiffLine.Type.ADDED, 2, "line 2")
            )),
            new DiffHunk(10, 1, List.of(
                new DiffLine(DiffLine.Type.CONTEXT, 10, "context at 10")
            )),
            new DiffHunk(20, 3, List.of(
                new DiffLine(DiffLine.Type.REMOVED, -1, "old line"),
                new DiffLine(DiffLine.Type.ADDED, 20, "new line"),
                new DiffLine(DiffLine.Type.CONTEXT, 21, "context")
            )),
            new DiffHunk(50, 1, List.of(
                new DiffLine(DiffLine.Type.ADDED, 50, "final addition")
            ))
        );
        
        ParsedDiffFile file = new ParsedDiffFile("multi-hunk.java", manyHunks);
        
        assertEquals("multi-hunk.java", file.path());
        assertEquals(4, file.hunks().size());
        assertEquals(manyHunks, file.hunks());
        
        // Verify all hunks are accessible
        assertEquals(1, file.hunks().get(0).newStart());
        assertEquals(10, file.hunks().get(1).newStart());
        assertEquals(20, file.hunks().get(2).newStart());
        assertEquals(50, file.hunks().get(3).newStart());
    }

    @Test
    void recordEquality() {
        List<DiffHunk> hunks = List.of(
            new DiffHunk(1, 1, List.of(
                new DiffLine(DiffLine.Type.ADDED, 1, "line")
            ))
        );
        
        ParsedDiffFile file1 = new ParsedDiffFile("test.java", hunks);
        ParsedDiffFile file2 = new ParsedDiffFile("test.java", hunks);
        ParsedDiffFile file3 = new ParsedDiffFile("different.java", hunks);
        
        assertEquals(file1, file2);
        assertNotEquals(file1, file3);
        assertEquals(file1.hashCode(), file2.hashCode());
    }

    @Test
    void recordToString() {
        List<DiffHunk> hunks = List.of(
            new DiffHunk(42, 1, List.of(
                new DiffLine(DiffLine.Type.ADDED, 42, "test content")
            ))
        );
        
        ParsedDiffFile file = new ParsedDiffFile("TestFile.java", hunks);
        String toString = file.toString();
        
        assertTrue(toString.contains("TestFile.java"));
        assertTrue(toString.contains("DiffHunk"));
    }

    @Test
    void recordWithDifferentFileExtensions() {
        String[] paths = {
            "README.md",
            "script.sh",
            "config.json",
            "style.css",
            "index.html",
            "app.py",
            "main.cpp",
            "data.xml"
        };
        
        for (String path : paths) {
            List<DiffHunk> hunks = List.of(
                new DiffHunk(1, 1, List.of(
                    new DiffLine(DiffLine.Type.ADDED, 1, "content for " + path)
                ))
            );
            
            ParsedDiffFile file = new ParsedDiffFile(path, hunks);
            
            assertEquals(path, file.path());
            assertEquals(1, file.hunks().size());
        }
    }

    @Test
    void recordWithUnixAndWindowsPaths() {
        String[] unixPaths = {
            "src/main/java/Test.java",
            "./relative/path/file.txt",
            "/absolute/path/file.py"
        };
        
        String[] windowsPaths = {
            "src\\main\\java\\Test.java",
            ".\\relative\\path\\file.txt",
            "C:\\absolute\\path\\file.py"
        };
        
        List<DiffHunk> hunks = List.of(
            new DiffHunk(1, 1, List.of(
                new DiffLine(DiffLine.Type.ADDED, 1, "content")
            ))
        );
        
        for (String path : unixPaths) {
            ParsedDiffFile file = new ParsedDiffFile(path, hunks);
            assertEquals(path, file.path());
        }
        
        for (String path : windowsPaths) {
            ParsedDiffFile file = new ParsedDiffFile(path, hunks);
            assertEquals(path, file.path());
        }
    }
}