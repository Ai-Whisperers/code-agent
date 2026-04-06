package com.eneve.agent.scm.azuredevops;

import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class AzureDevOpsPlatformServiceTest {

    private AzureDevOpsPlatformService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AzureDevOpsPlatformService();
        injectField("objectMapper", new ObjectMapper());
        injectField("settingsService", new SettingsService() {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }
        });
    }

    private void injectField(String fieldName, Object value) throws Exception {
        Field field = AzureDevOpsPlatformService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Test
    void escapeJsonHandlesNullInput() throws Exception {
        Method escapeJson = AzureDevOpsPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, (String) null);
        
        assertEquals("", result);
    }

    @Test
    void escapeJsonHandlesEmptyString() throws Exception {
        Method escapeJson = AzureDevOpsPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "");
        
        assertEquals("", result);
    }

    @Test
    void escapeJsonEscapesDoubleQuotes() throws Exception {
        Method escapeJson = AzureDevOpsPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Hello \"world\"");
        
        assertEquals("Hello \\\"world\\\"", result);
    }

    @Test
    void escapeJsonEscapesBackslashes() throws Exception {
        Method escapeJson = AzureDevOpsPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Path\\to\\file");
        
        assertEquals("Path\\\\to\\\\file", result);
    }

    @Test
    void escapeJsonEscapesNewlines() throws Exception {
        Method escapeJson = AzureDevOpsPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Line 1\nLine 2");
        
        assertEquals("Line 1\\nLine 2", result);
    }

    @Test
    void escapeJsonEscapesCarriageReturns() throws Exception {
        Method escapeJson = AzureDevOpsPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Line 1\rLine 2");
        
        assertEquals("Line 1\\rLine 2", result);
    }

    @Test
    void escapeJsonEscapesTabs() throws Exception {
        Method escapeJson = AzureDevOpsPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String result = (String) escapeJson.invoke(service, "Value\tSeparated");
        
        assertEquals("Value\\tSeparated", result);
    }

    @Test
    void escapeJsonHandlesCombinedSpecialCharacters() throws Exception {
        Method escapeJson = AzureDevOpsPlatformService.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);
        
        String input = "Title with \"quotes\"\nAnd newline\tAnd tab\\And backslash";
        String result = (String) escapeJson.invoke(service, input);
        
        assertEquals("Title with \\\"quotes\\\"\\nAnd newline\\tAnd tab\\\\And backslash", result);
    }

    @Test
    void stripRefsHeadsRemovesPrefix() throws Exception {
        Method stripRefsHeads = AzureDevOpsPlatformService.class.getDeclaredMethod("stripRefsHeads", String.class);
        stripRefsHeads.setAccessible(true);
        
        String result = (String) stripRefsHeads.invoke(service, "refs/heads/feature/my-branch");
        
        assertEquals("feature/my-branch", result);
    }

    @Test
    void stripRefsHeadsReturnsOriginalWhenNoPrefixPresent() throws Exception {
        Method stripRefsHeads = AzureDevOpsPlatformService.class.getDeclaredMethod("stripRefsHeads", String.class);
        stripRefsHeads.setAccessible(true);
        
        String result = (String) stripRefsHeads.invoke(service, "main");
        
        assertEquals("main", result);
    }

    @Test
    void stripRefsHeadsHandlesEmptyString() throws Exception {
        Method stripRefsHeads = AzureDevOpsPlatformService.class.getDeclaredMethod("stripRefsHeads", String.class);
        stripRefsHeads.setAccessible(true);
        
        String result = (String) stripRefsHeads.invoke(service, "");
        
        assertEquals("", result);
    }

    @Test
    void stripRefsHeadsHandlesNullInput() throws Exception {
        Method stripRefsHeads = AzureDevOpsPlatformService.class.getDeclaredMethod("stripRefsHeads", String.class);
        stripRefsHeads.setAccessible(true);
        
        String result = (String) stripRefsHeads.invoke(service, (String) null);
        
        // Based on the actual implementation: return refName != null ? refName : "";
        assertEquals("", result);
    }

    @Test
    void stripRefsHeadsHandlesExactPrefix() throws Exception {
        Method stripRefsHeads = AzureDevOpsPlatformService.class.getDeclaredMethod("stripRefsHeads", String.class);
        stripRefsHeads.setAccessible(true);
        
        String result = (String) stripRefsHeads.invoke(service, "refs/heads/");
        
        assertEquals("", result);
    }

    @Test
    void stripRefsHeadsHandlesPartialPrefix() throws Exception {
        Method stripRefsHeads = AzureDevOpsPlatformService.class.getDeclaredMethod("stripRefsHeads", String.class);
        stripRefsHeads.setAccessible(true);
        
        String result = (String) stripRefsHeads.invoke(service, "refs/heads");
        
        // Based on implementation: it only strips if it starts with "refs/heads/"
        assertEquals("refs/heads", result);
    }

    @Test
    void stripRefsHeadsHandlesCaseSensitivity() throws Exception {
        Method stripRefsHeads = AzureDevOpsPlatformService.class.getDeclaredMethod("stripRefsHeads", String.class);
        stripRefsHeads.setAccessible(true);
        
        String result = (String) stripRefsHeads.invoke(service, "REFS/HEADS/main");
        
        assertEquals("REFS/HEADS/main", result); // Case sensitive, should not be stripped
    }

    @Test
    void repoApiUrlGeneratesCorrectUrl() throws Exception {
        Method repoApiUrl = AzureDevOpsPlatformService.class.getDeclaredMethod("repoApiUrl", String.class, String.class, String.class);
        repoApiUrl.setAccessible(true);
        
        String result = (String) repoApiUrl.invoke(service, "myorg", "myproject", "myrepo");
        
        assertEquals("https://dev.azure.com/myorg/myproject/_apis/git/repositories/myrepo", result);
    }

    @Test
    void repoApiUrlHandlesEmptyValues() throws Exception {
        Method repoApiUrl = AzureDevOpsPlatformService.class.getDeclaredMethod("repoApiUrl", String.class, String.class, String.class);
        repoApiUrl.setAccessible(true);
        
        String result = (String) repoApiUrl.invoke(service, "", "", "");
        
        // The actual implementation concatenates, resulting in an extra slash
        assertEquals("https://dev.azure.com///_apis/git/repositories/", result);
    }

    @Test
    void repoApiUrlHandlesSpecialCharacters() throws Exception {
        Method repoApiUrl = AzureDevOpsPlatformService.class.getDeclaredMethod("repoApiUrl", String.class, String.class, String.class);
        repoApiUrl.setAccessible(true);
        
        String result = (String) repoApiUrl.invoke(service, "my-org", "my.project", "my_repo");
        
        assertEquals("https://dev.azure.com/my-org/my.project/_apis/git/repositories/my_repo", result);
    }

    @Test
    void parseThreadFirstCommentIdParsesValidResponse() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        String response = "{\"comments\": [{\"id\": 123}, {\"id\": 456}]}";
        long result = (long) parseThreadFirstCommentId.invoke(service, response);
        
        assertEquals(123L, result);
    }

    @Test
    void parseThreadFirstCommentIdReturnsZeroForEmptyComments() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        String response = "{\"comments\": []}";
        long result = (long) parseThreadFirstCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }

    @Test
    void parseThreadFirstCommentIdReturnsZeroForMissingComments() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        String response = "{\"other\": \"value\"}";
        long result = (long) parseThreadFirstCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }

    @Test
    void parseThreadFirstCommentIdReturnsZeroForInvalidJson() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        String response = "invalid json";
        long result = (long) parseThreadFirstCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }

    @Test
    void parseThreadFirstCommentIdReturnsZeroForNullResponse() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        long result = (long) parseThreadFirstCommentId.invoke(service, (String) null);
        
        assertEquals(0L, result);
    }

    @Test
    void parseThreadFirstCommentIdHandlesStringIdValue() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        String response = "{\"comments\": [{\"id\": \"789\"}]}";
        long result = (long) parseThreadFirstCommentId.invoke(service, response);
        
        assertEquals(789L, result);
    }

    @Test
    void parseThreadFirstCommentIdHandlesNestedStructure() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        String response = "{\"thread\": {\"comments\": [{\"id\": 999}]}, \"comments\": [{\"id\": 123}]}";
        long result = (long) parseThreadFirstCommentId.invoke(service, response);
        
        assertEquals(123L, result);
    }

    @Test
    void parseThreadFirstCommentIdHandlesLargeId() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        String response = "{\"comments\": [{\"id\": " + Long.MAX_VALUE + "}]}";
        long result = (long) parseThreadFirstCommentId.invoke(service, response);
        
        assertEquals(Long.MAX_VALUE, result);
    }

    @Test
    void parseThreadFirstCommentIdHandlesNonNumericId() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        String response = "{\"comments\": [{\"id\": \"notanumber\"}]}";
        long result = (long) parseThreadFirstCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }

    @Test
    void parseThreadFirstCommentIdHandlesMissingIdField() throws Exception {
        Method parseThreadFirstCommentId = AzureDevOpsPlatformService.class.getDeclaredMethod("parseThreadFirstCommentId", String.class);
        parseThreadFirstCommentId.setAccessible(true);
        
        String response = "{\"comments\": [{\"content\": \"comment without id\"}]}";
        long result = (long) parseThreadFirstCommentId.invoke(service, response);
        
        assertEquals(0L, result);
    }
}