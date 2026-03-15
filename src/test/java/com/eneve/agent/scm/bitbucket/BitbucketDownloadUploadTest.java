package com.eneve.agent.scm.bitbucket;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the multipart body builder used by
 * {@link BitbucketPlatformService#uploadDownload}.
 */
class BitbucketDownloadUploadTest {

    /** Invoke the private {@code buildMultipartBody} via reflection. */
    private byte[] buildMultipartBody(String boundary, String filename,
                                      byte[] data, String contentType) throws Exception {
        Method m = BitbucketPlatformService.class.getDeclaredMethod(
                "buildMultipartBody", String.class, String.class, byte[].class, String.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, boundary, filename, data, contentType);
    }

    @Test
    void multipartBody_containsFilenameAndData() throws Exception {
        byte[] data = "PNG-BYTES".getBytes(StandardCharsets.UTF_8);
        byte[] body = buildMultipartBody("BOUND", "diagram.png", data, "image/png");
        String bodyStr = new String(body, StandardCharsets.UTF_8);

        assertTrue(bodyStr.contains("--BOUND"), "Should have boundary marker");
        assertTrue(bodyStr.contains("name=\"files\""), "Bitbucket Downloads API expects field name 'files'");
        assertTrue(bodyStr.contains("filename=\"diagram.png\""), "Should include filename");
        assertTrue(bodyStr.contains("Content-Type: image/png"), "Should include content type");
        assertTrue(bodyStr.contains("PNG-BYTES"), "Should include raw file data");
        assertTrue(bodyStr.endsWith("--BOUND--\r\n"), "Should end with closing boundary");
    }

    @Test
    void multipartBody_specialCharsInFilename_preserved() throws Exception {
        byte[] data = new byte[]{1, 2, 3};
        byte[] body = buildMultipartBody("B", "mermaid-pr-42-1.png", data, "image/png");
        String bodyStr = new String(body, StandardCharsets.UTF_8);

        assertTrue(bodyStr.contains("filename=\"mermaid-pr-42-1.png\""));
    }
}
