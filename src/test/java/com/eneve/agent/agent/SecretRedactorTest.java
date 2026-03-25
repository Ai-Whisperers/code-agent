package com.eneve.agent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.eneve.agent.agent.SecretRedactor.REDACTED;
import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {

    // ── Null / blank pass-through ─────────────────────────────────────────

    @Test
    void nullIsReturnedUnchanged() {
        assertNull(SecretRedactor.redact(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n"})
    void blankIsReturnedUnchanged(String blank) {
        assertEquals(blank, SecretRedactor.redact(blank));
    }

    // ── Plain text without secrets is untouched ───────────────────────────

    @Test
    void textWithoutSecretsIsUnchanged() {
        String plain = "The user assigned ENG-42 to Alice and resolved it in sprint 3.";
        assertEquals(plain, SecretRedactor.redact(plain));
    }

    // ── Password patterns ─────────────────────────────────────────────────

    @Test
    void passwordEqualsIsRedacted() {
        String result = SecretRedactor.redact("password=super-secret-123");
        assertTrue(result.contains("password="), "key name preserved");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("super-secret-123"));
    }

    @Test
    void passwordColonIsRedacted() {
        String result = SecretRedactor.redact("password: MyP@ssw0rd!");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("MyP@ssw0rd!"));
    }

    @Test
    void passwdIsRedacted() {
        String result = SecretRedactor.redact("passwd=abc123");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("abc123"));
    }

    @Test
    void pwdIsRedacted() {
        String result = SecretRedactor.redact("pwd=hunter2");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("hunter2"));
    }

    @Test
    void passwordIsCaseInsensitive() {
        String result = SecretRedactor.redact("PASSWORD=Secret99");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("Secret99"));
    }

    // ── Secret patterns ───────────────────────────────────────────────────

    @Test
    void secretEqualsIsRedacted() {
        String result = SecretRedactor.redact("secret=abc-def-ghi");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("abc-def-ghi"));
    }

    @Test
    void clientSecretIsRedacted() {
        String result = SecretRedactor.redact("client_secret=xyzXYZ012");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("xyzXYZ012"));
    }

    @Test
    void clientSecretWithDashIsRedacted() {
        String result = SecretRedactor.redact("client-secret=xyzXYZ012");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("xyzXYZ012"));
    }

    // ── Token patterns ────────────────────────────────────────────────────

    @Test
    void tokenEqualsIsRedacted() {
        String result = SecretRedactor.redact("token=eyJhbGciOiJIUzI1NiJ9");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    void accessTokenIsRedacted() {
        String result = SecretRedactor.redact("access_token=tok-abc123");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("tok-abc123"));
    }

    @Test
    void refreshTokenIsRedacted() {
        String result = SecretRedactor.redact("refresh_token=ref-xyz789");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("ref-xyz789"));
    }

    // ── API key patterns ──────────────────────────────────────────────────

    @Test
    void apiKeyIsRedacted() {
        String result = SecretRedactor.redact("api_key=sk-prod-1234567890abcdef");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("sk-prod-1234567890abcdef"));
    }

    @Test
    void apiKeyWithDashIsRedacted() {
        String result = SecretRedactor.redact("api-key=sk-test-abc");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("sk-test-abc"));
    }

    // ── Access key / private key patterns ────────────────────────────────

    @Test
    void accessKeyIsRedacted() {
        String result = SecretRedactor.redact("access_key=ABCDEF1234567890");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("ABCDEF1234567890"));
    }

    @Test
    void privateKeyIsRedacted() {
        String result = SecretRedactor.redact("private_key=MIIEpAIBAAKCAQEA");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("MIIEpAIBAAKCAQEA"));
    }

    // ── Credential / authorization patterns ──────────────────────────────

    @Test
    void credentialsIsRedacted() {
        String result = SecretRedactor.redact("credentials=user:pass");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("user:pass"));
    }

    @Test
    void authorizationValueIsRedacted() {
        String result = SecretRedactor.redact("authorization=Basic dXNlcjpwYXNz");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("dXNlcjpwYXNz"));
    }

    // ── Bearer token ──────────────────────────────────────────────────────

    @Test
    void bearerTokenIsRedacted() {
        String result = SecretRedactor.redact("Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.payload.signature");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("eyJhbGciOiJSUzI1NiJ9.payload.signature"));
    }

    // ── AWS Access Key ID ─────────────────────────────────────────────────

    @Test
    void awsAccessKeyIdIsRedacted() {
        String result = SecretRedactor.redact("aws_access_key_id=AKIAIOSFODNN7EXAMPLE");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void awsAccessKeyIdStandaloneIsRedacted() {
        String result = SecretRedactor.redact("Key: AKIAIOSFODNN7EXAMPLE is set");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"));
    }

    // ── URLs with embedded credentials ───────────────────────────────────

    @Test
    void urlWithPasswordIsRedacted() {
        String result = SecretRedactor.redact("jdbc:postgresql://dbuser:s3cr3tPa$$@db.example.com:5432/mydb");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("s3cr3tPa$$"));
        assertTrue(result.contains("dbuser"), "username may be preserved");
    }

    @Test
    void httpsUrlWithPasswordIsRedacted() {
        String result = SecretRedactor.redact("https://admin:password123@internal.corp/api");
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("password123"));
    }

    // ── PEM private key block ─────────────────────────────────────────────

    @Test
    void pemPrivateKeyBlockIsRedacted() {
        String pem = """
                -----BEGIN RSA PRIVATE KEY-----
                MIIEpAIBAAKCAQEA0Z3VS5JJcds3xHn/ygWep4bkBPKjNX
                ZnmZGOFGVYqSqJnA
                -----END RSA PRIVATE KEY-----
                """;
        String result = SecretRedactor.redact("Key material:\n" + pem);
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("MIIEpAIBAAKCAQEA"));
        assertFalse(result.contains("BEGIN RSA PRIVATE KEY"));
    }

    @Test
    void pemEcPrivateKeyBlockIsRedacted() {
        String pem = "-----BEGIN EC PRIVATE KEY-----\nABCDEFGH\n-----END EC PRIVATE KEY-----";
        String result = SecretRedactor.redact(pem);
        assertTrue(result.contains(REDACTED));
        assertFalse(result.contains("ABCDEFGH"));
    }

    // ── Realistic multi-line document ─────────────────────────────────────

    @Test
    void multiLineJiraIssueTextIsPartiallyRedacted() {
        String issueText = """
                Issue: ENG-101
                Summary: Connect the reporting service to the production DB
                Status: In Progress
                Assignee: bob@example.com

                Description:
                Use the following credentials to connect:
                  db_password=Pr0duct10n!Pass
                  api_key=sk-live-abc123xyz
                  jdbc:postgresql://reporting_user:hunter2@db.prod.example.com/reports

                Comments:
                - Alice: I've added the AWS key AKIAIOSFODNN7EXAMPLE to the vault.
                """;

        String result = SecretRedactor.redact(issueText);

        // Structural content preserved
        assertTrue(result.contains("ENG-101"));
        assertTrue(result.contains("bob@example.com"));
        assertTrue(result.contains("Connect the reporting service"));

        // Secrets removed
        assertFalse(result.contains("Pr0duct10n!Pass"));
        assertFalse(result.contains("sk-live-abc123xyz"));
        assertFalse(result.contains("hunter2"));
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"));
    }
}
