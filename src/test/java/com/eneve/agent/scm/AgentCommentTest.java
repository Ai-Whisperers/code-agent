package com.eneve.agent.scm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentCommentTest {

    @Test
    void createGeneralComment() {
        AgentComment comment = new AgentComment("", 0, "General comment content");
        
        assertEquals("", comment.filePath());
        assertEquals(0, comment.line());
        assertEquals("General comment content", comment.content());
    }

    @Test
    void createFileComment() {
        AgentComment comment = new AgentComment("src/main/java/Test.java", 0, "File-level comment");
        
        assertEquals("src/main/java/Test.java", comment.filePath());
        assertEquals(0, comment.line());
        assertEquals("File-level comment", comment.content());
    }

    @Test
    void createInlineComment() {
        AgentComment comment = new AgentComment("src/main/java/Test.java", 42, "Line-specific comment");
        
        assertEquals("src/main/java/Test.java", comment.filePath());
        assertEquals(42, comment.line());
        assertEquals("Line-specific comment", comment.content());
    }

    @Test
    void createWithNullFilePath() {
        AgentComment comment = new AgentComment(null, 10, "Comment with null file path");
        
        assertNull(comment.filePath());
        assertEquals(10, comment.line());
        assertEquals("Comment with null file path", comment.content());
    }

    @Test
    void createWithNullContent() {
        AgentComment comment = new AgentComment("file.java", 5, null);
        
        assertEquals("file.java", comment.filePath());
        assertEquals(5, comment.line());
        assertNull(comment.content());
    }

    @Test
    void createWithEmptyContent() {
        AgentComment comment = new AgentComment("file.java", 1, "");
        
        assertEquals("file.java", comment.filePath());
        assertEquals(1, comment.line());
        assertEquals("", comment.content());
    }

    @Test
    void createWithNegativeLine() {
        AgentComment comment = new AgentComment("file.java", -1, "Comment with negative line");
        
        assertEquals("file.java", comment.filePath());
        assertEquals(-1, comment.line());
        assertEquals("Comment with negative line", comment.content());
    }

    @Test
    void recordEquality() {
        AgentComment comment1 = new AgentComment("file.java", 10, "content");
        AgentComment comment2 = new AgentComment("file.java", 10, "content");
        AgentComment comment3 = new AgentComment("file.java", 10, "different content");
        AgentComment comment4 = new AgentComment("file.java", 11, "content");
        AgentComment comment5 = new AgentComment("other.java", 10, "content");
        
        assertEquals(comment1, comment2);
        assertNotEquals(comment1, comment3);
        assertNotEquals(comment1, comment4);
        assertNotEquals(comment1, comment5);
        assertEquals(comment1.hashCode(), comment2.hashCode());
    }

    @Test
    void recordToString() {
        AgentComment comment = new AgentComment("src/test/Test.java", 25, "Test comment");
        String toString = comment.toString();
        
        assertTrue(toString.contains("src/test/Test.java"));
        assertTrue(toString.contains("25"));
        assertTrue(toString.contains("Test comment"));
    }

    @Test
    void recordWithSpecialCharacters() {
        AgentComment comment = new AgentComment("path/with spaces/file.java", 1, 
                "Comment with special chars: \n\t\"quotes\" and 'apostrophes'");
        
        assertEquals("path/with spaces/file.java", comment.filePath());
        assertEquals(1, comment.line());
        assertEquals("Comment with special chars: \n\t\"quotes\" and 'apostrophes'", comment.content());
    }
}