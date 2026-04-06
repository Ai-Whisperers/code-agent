package com.eneve.agent.scm.bitbucket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class BitbucketPlatformServiceTest {

    private BitbucketPlatformService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new BitbucketPlatformService();
        Field objectMapperField = BitbucketPlatformService.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(service, new ObjectMapper());
    }

    @Test
    void escapeJsonHandlesNullInput() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, (String) null);
        
        assertEquals("", result);
    }

    @Test
    void escapeJsonHandlesEmptyString() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "");
        
        assertEquals("", result);
    }

    @Test
    void escapeJsonEscapesDoubleQuotes() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Hello \"world\"");
        
        assertEquals("Hello \\\"world\\\"", result);
    }

    @Test
    void escapeJsonEscapesBackslashes() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Path\\to\\file");
        
        assertEquals("Path\\\\to\\\\file", result);
    }

    @Test
    void escapeJsonEscapesNewlines() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Line 1\nLine 2");
        
        assertEquals("Line 1\\nLine 2", result);
    }

    @Test
    void escapeJsonEscapesCarriageReturns() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Line 1\rLine 2");
        
        assertEquals("Line 1\\rLine 2", result);
    }

    @Test
    void escapeJsonEscapesTabs() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Value\tSeparated");
        
        assertEquals("Value\\tSeparated", result);
    }

    @Test
    void escapeJsonHandlesCombinedSpecialCharacters() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String input = "Title with \"quotes\"\nAnd newline\tAnd tab\\And backslash";
        String result = (String) escapeJson.invoke(service, input);
        
        assertEquals("Title with \\\"quotes\\\"\\nAnd newline\\tAnd tab\\\\And backslash", result);
    }

    @Test
    void escapeJsonHandlesUnicodeCharacters() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String input = "Unicode: 🚀 αβγ 中文";
        String result = (String) escapeJson.invoke(service, input);
        
        assertEquals("Unicode: 🚀 αβγ 中文", result);
    }

    @Test
    void escapeJsonHandlesLongString() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String longString = "a".repeat(1000) + "\"" + "b".repeat(1000);
        String result = (String) escapeJson.invoke(service, longString);
        
        String expected = "a".repeat(1000) + "\\\"" + "b".repeat(1000);
        assertEquals(expected, result);
    }

    @Test
    void parseCommentIdParsesValidResponse() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "{\"id\": 12345, \"content\": {\"raw\": \"comment\"}}";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(12345L, result);
    }

    @Test
    void parseCommentIdReturnsZeroForInvalidJson() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "invalid json";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }

    @Test
    void parseCommentIdReturnsZeroForMissingId() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "{\"content\": {\"raw\": \"comment\"}}";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }

    @Test
    void parseCommentIdReturnsZeroForNullResponse() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        long result = (long) parseCommentId.invoke(service, (String) null);
        
        assertEquals(0L, result);
    }

    @Test
    void parseCommentIdReturnsZeroForEmptyResponse() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        long result = (long) parseCommentId.invoke(service, "");
        
        assertEquals(0L, result);
    }

    @Test
    void parseCommentIdHandlesStringIdValue() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "{\"id\": \"67890\"}";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(67890L, result);
    }

    @Test
    void parseCommentIdHandlesLargeId() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "{\"id\": " + Long.MAX_VALUE + "}";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(Long.MAX_VALUE, result);
    }

    @Test
    void parseCommentIdHandlesNestedResponse() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "{\"outer\": {\"id\": 999}, \"id\": 12345}";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(12345L, result);
    }

    @Test
    void parseCommentIdHandlesNonNumericId() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "{\"id\": \"notanumber\"}";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }

    @Test
    void parseCommentIdHandlesNullIdField() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "{\"id\": null}";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }

    @Test
    void parseCommentIdHandlesZeroId() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "{\"id\": 0}";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }

    @Test
    void parseCommentIdHandlesNegativeId() throws Exception {
        Method parseCommentId = BitbucketPlatformService.class.getDeclaredMethod("parseCommentId", String.class);
        parseCommentId.setAccessible(true);
        
        String response = "{\"id\": -123}";
        long result = (long) parseCommentId.invoke(service, response);
        
        assertEquals(-123L, result);
    }

    @Test
    void escapeJsonEscapesOnlyBasicJsonCharacters() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        // Test that the five basic JSON escape sequences are handled
        String input = "\\\"\\n\\r\\t";
        String result = (String) escapeJson.invoke(service, input);
        
        assertTrue(result.contains("\\\\"));   // backslash is escaped
        assertTrue(result.contains("\\\""));   // quote is escaped  
        assertTrue(result.contains("\\n"));    // newline is escaped
        assertTrue(result.contains("\\r"));    // carriage return is escaped
        assertTrue(result.contains("\\t"));    // tab is escaped
    }

    @Test
    void escapeJsonPreservesNonEscapedCharacters() throws Exception {
        Method escapeJson = BitbucketPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String input = "Normal text with spaces and 123!@#$%";
        String result = (String) escapeJson.invoke(service, input);
        
        assertEquals(input, result);
    }
}