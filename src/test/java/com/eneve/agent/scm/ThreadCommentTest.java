package com.eneve.agent.scm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreadCommentTest {

    @Test
    void createRootComment() {
        ThreadComment comment = new ThreadComment(123L, 0L, "John Doe", 
                "This is a root comment", "2023-12-01T10:30:00Z", false);
        
        assertEquals(123L, comment.id());
        assertEquals(0L, comment.parentId());
        assertEquals("John Doe", comment.author());
        assertEquals("This is a root comment", comment.content());
        assertEquals("2023-12-01T10:30:00Z", comment.createdOn());
        assertFalse(comment.isAgent());
    }

    @Test
    void createReplyComment() {
        ThreadComment comment = new ThreadComment(456L, 123L, "Agent Bot", 
                "This is a reply to the root comment", "2023-12-01T10:35:00Z", true);
        
        assertEquals(456L, comment.id());
        assertEquals(123L, comment.parentId());
        assertEquals("Agent Bot", comment.author());
        assertEquals("This is a reply to the root comment", comment.content());
        assertEquals("2023-12-01T10:35:00Z", comment.createdOn());
        assertTrue(comment.isAgent());
    }

    @Test
    void createWithNullValues() {
        ThreadComment comment = new ThreadComment(0L, 0L, null, null, null, false);
        
        assertEquals(0L, comment.id());
        assertEquals(0L, comment.parentId());
        assertNull(comment.author());
        assertNull(comment.content());
        assertNull(comment.createdOn());
        assertFalse(comment.isAgent());
    }

    @Test
    void createWithEmptyValues() {
        ThreadComment comment = new ThreadComment(1L, 0L, "", "", "", true);
        
        assertEquals(1L, comment.id());
        assertEquals(0L, comment.parentId());
        assertEquals("", comment.author());
        assertEquals("", comment.content());
        assertEquals("", comment.createdOn());
        assertTrue(comment.isAgent());
    }

    @Test
    void createWithNegativeIds() {
        ThreadComment comment = new ThreadComment(-1L, -5L, "User", "Content", "2023-01-01", false);
        
        assertEquals(-1L, comment.id());
        assertEquals(-5L, comment.parentId());
        assertEquals("User", comment.author());
        assertEquals("Content", comment.content());
        assertEquals("2023-01-01", comment.createdOn());
        assertFalse(comment.isAgent());
    }

    @Test
    void createWithLargeIds() {
        ThreadComment comment = new ThreadComment(Long.MAX_VALUE, Long.MAX_VALUE - 1, 
                "User", "Content", "2023-01-01", false);
        
        assertEquals(Long.MAX_VALUE, comment.id());
        assertEquals(Long.MAX_VALUE - 1, comment.parentId());
    }

    @Test
    void recordEquality() {
        ThreadComment comment1 = new ThreadComment(123L, 0L, "John", "Content", "2023-01-01", true);
        ThreadComment comment2 = new ThreadComment(123L, 0L, "John", "Content", "2023-01-01", true);
        ThreadComment comment3 = new ThreadComment(124L, 0L, "John", "Content", "2023-01-01", true);
        ThreadComment comment4 = new ThreadComment(123L, 1L, "John", "Content", "2023-01-01", true);
        ThreadComment comment5 = new ThreadComment(123L, 0L, "Jane", "Content", "2023-01-01", true);
        ThreadComment comment6 = new ThreadComment(123L, 0L, "John", "Different", "2023-01-01", true);
        ThreadComment comment7 = new ThreadComment(123L, 0L, "John", "Content", "2023-01-02", true);
        ThreadComment comment8 = new ThreadComment(123L, 0L, "John", "Content", "2023-01-01", false);
        
        assertEquals(comment1, comment2);
        assertNotEquals(comment1, comment3);
        assertNotEquals(comment1, comment4);
        assertNotEquals(comment1, comment5);
        assertNotEquals(comment1, comment6);
        assertNotEquals(comment1, comment7);
        assertNotEquals(comment1, comment8);
        assertEquals(comment1.hashCode(), comment2.hashCode());
    }

    @Test
    void recordToString() {
        ThreadComment comment = new ThreadComment(42L, 10L, "Test User", 
                "Test content", "2023-05-15T14:30:00Z", true);
        String toString = comment.toString();
        
        assertTrue(toString.contains("42"));
        assertTrue(toString.contains("10"));
        assertTrue(toString.contains("Test User"));
        assertTrue(toString.contains("Test content"));
        assertTrue(toString.contains("2023-05-15T14:30:00Z"));
        assertTrue(toString.contains("true"));
    }

    @Test
    void recordWithMarkdownContent() {
        String markdownContent = "**Bold text** and _italic text_\n\n```java\ncode block\n```";
        ThreadComment comment = new ThreadComment(1L, 0L, "Developer", 
                markdownContent, "2023-01-01T00:00:00Z", false);
        
        assertEquals(markdownContent, comment.content());
    }

    @Test
    void recordWithSpecialCharacters() {
        ThreadComment comment = new ThreadComment(1L, 0L, 
                "User with åccëñts", 
                "Content with \"quotes\", 'apostrophes', and\nnewlines\ttabs", 
                "2023-01-01T00:00:00Z", false);
        
        assertEquals("User with åccëñts", comment.author());
        assertEquals("Content with \"quotes\", 'apostrophes', and\nnewlines\ttabs", comment.content());
    }

    @Test
    void recordWithIsoDateFormat() {
        String isoDate = "2023-12-25T23:59:59.999Z";
        ThreadComment comment = new ThreadComment(1L, 0L, "User", "Content", isoDate, false);
        
        assertEquals(isoDate, comment.createdOn());
    }

    @Test
    void recordWithAzureDevOpsDateFormat() {
        String azureDate = "2023-12-25T23:59:59.9990000Z";
        ThreadComment comment = new ThreadComment(1L, 0L, "User", "Content", azureDate, false);
        
        assertEquals(azureDate, comment.createdOn());
    }
}