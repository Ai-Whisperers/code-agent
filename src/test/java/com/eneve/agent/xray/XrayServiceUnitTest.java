package com.eneve.agent.xray;

import com.eneve.agent.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link XrayService} configuration and credential logic.
 *
 * <p>HTTP-dependent paths (authenticate, graphql, API methods) are not covered here
 * because no mock HTTP server is available in the test suite. They are exercised
 * by the tool-level tests and by contract tests that run against a real Xray Cloud
 * environment in CI.
 */
class XrayServiceUnitTest {

    private SettingsService settings;
    private XrayService service;

    @BeforeEach
    void setUp() throws Exception {
        settings = Mockito.mock(SettingsService.class);
        service  = new XrayService();
        injectField(service, "settingsService", settings);
    }

    // ─── isConfigured ─────────────────────────────────────────────────────────────

    @Test
    void isConfigured_allPresent_returnsTrue() {
        stubSettings("clientId123", "secret456", "https://xray.cloud.getxray.app");
        assertTrue(service.isConfigured());
    }

    @Test
    void isConfigured_missingClientId_returnsFalse() {
        stubSettings("", "secret456", "https://xray.cloud.getxray.app");
        assertFalse(service.isConfigured());
    }

    @Test
    void isConfigured_missingClientSecret_returnsFalse() {
        stubSettings("clientId123", "", "https://xray.cloud.getxray.app");
        assertFalse(service.isConfigured());
    }

    @Test
    void isConfigured_missingBaseUrl_returnsFalse() {
        stubSettings("clientId123", "secret456", "");
        assertFalse(service.isConfigured());
    }

    @Test
    void isConfigured_blankValues_returnsFalse() {
        stubSettings("  ", "  ", "  ");
        assertFalse(service.isConfigured());
    }

    // ─── systemCredentials ────────────────────────────────────────────────────────

    @Test
    void systemCredentials_returnsCredentialsFromSettings() {
        stubSettings("myClientId", "mySecret", "https://eu.xray.cloud.getxray.app");

        XrayService.XrayCredentials creds = service.systemCredentials();

        assertEquals("myClientId", creds.clientId());
        assertEquals("mySecret",   creds.clientSecret());
        assertEquals("https://eu.xray.cloud.getxray.app", creds.baseUrl());
    }

    // ─── individual getters ───────────────────────────────────────────────────────

    @Test
    void getClientId_delegatesToSettings() {
        when(settings.get("xray.client-id", "")).thenReturn("abc123");
        assertEquals("abc123", service.getClientId());
    }

    @Test
    void getClientSecret_delegatesToGetSecret() {
        when(settings.getSecret("xray.client-secret")).thenReturn("supersecret");
        assertEquals("supersecret", service.getClientSecret());
    }

    @Test
    void getSystemBaseUrl_returnsStoredValue() {
        when(settings.get("xray.base-url", "https://xray.cloud.getxray.app"))
                .thenReturn("https://eu.xray.cloud.getxray.app");
        assertEquals("https://eu.xray.cloud.getxray.app", service.getSystemBaseUrl());
    }

    @Test
    void getSystemBaseUrl_fallsBackToDefaultWhenBlank() {
        when(settings.get("xray.base-url", "https://xray.cloud.getxray.app"))
                .thenReturn("https://xray.cloud.getxray.app");
        assertEquals("https://xray.cloud.getxray.app", service.getSystemBaseUrl());
    }

    // ─── XrayCredentials helpers ──────────────────────────────────────────────────

    @Test
    void xrayCredentials_graphqlUrl_appendsCorrectPath() {
        XrayService.XrayCredentials creds =
                new XrayService.XrayCredentials("id", "secret", "https://xray.cloud.getxray.app");
        assertEquals("https://xray.cloud.getxray.app/api/v2/graphql", creds.graphqlUrl());
    }

    @Test
    void xrayCredentials_authUrl_appendsCorrectPath() {
        XrayService.XrayCredentials creds =
                new XrayService.XrayCredentials("id", "secret", "https://xray.cloud.getxray.app");
        assertEquals("https://xray.cloud.getxray.app/api/v1/authenticate", creds.authUrl());
    }

    @Test
    void xrayCredentials_euRegion_usesEuBaseUrl() {
        XrayService.XrayCredentials creds =
                new XrayService.XrayCredentials("id", "secret", "https://eu.xray.cloud.getxray.app");
        assertEquals("https://eu.xray.cloud.getxray.app/api/v2/graphql", creds.graphqlUrl());
        assertEquals("https://eu.xray.cloud.getxray.app/api/v1/authenticate", creds.authUrl());
    }

    @Test
    void xrayCredentials_recordEquality_basedOnAllFields() {
        XrayService.XrayCredentials a = new XrayService.XrayCredentials("id", "s", "https://base.url");
        XrayService.XrayCredentials b = new XrayService.XrayCredentials("id", "s", "https://base.url");
        XrayService.XrayCredentials c = new XrayService.XrayCredentials("other", "s", "https://base.url");

        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    // ─── Result record helpers ────────────────────────────────────────────────────

    @Test
    void xrayTest_recordHoldsExpectedFields() {
        XrayService.XrayTest test = new XrayService.XrayTest("42", "PROJ-10", "Login test", "TODO");

        assertEquals("42",         test.issueId());
        assertEquals("PROJ-10",    test.key());
        assertEquals("Login test", test.summary());
        assertEquals("TODO",       test.status());
    }

    @Test
    void xrayTestRun_recordHoldsExpectedFields() {
        XrayService.XrayTestRun run = new XrayService.XrayTestRun(
                "run-1", "PROJ-10", "Login test", "PASS", "2025-01-01T10:00:00", "2025-01-01T10:05:00");

        assertEquals("run-1",      run.id());
        assertEquals("PROJ-10",    run.testKey());
        assertEquals("Login test", run.testSummary());
        assertEquals("PASS",       run.status());
    }

    @Test
    void xrayTestExecution_recordHoldsTestRuns() {
        var runs = java.util.List.of(
                new XrayService.XrayTestRun("r1", "T-1", "s1", "PASS", "", ""),
                new XrayService.XrayTestRun("r2", "T-2", "s2", "FAIL", "", "")
        );
        XrayService.XrayTestExecution exec =
                new XrayService.XrayTestExecution("123", "EX-5", "Sprint 1 regression", runs);

        assertEquals("EX-5", exec.key());
        assertEquals(2, exec.testRuns().size());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private void stubSettings(String clientId, String secret, String baseUrl) {
        when(settings.get("xray.client-id", "")).thenReturn(clientId);
        when(settings.getSecret("xray.client-secret")).thenReturn(secret);
        when(settings.get("xray.base-url", "")).thenReturn(baseUrl);
        when(settings.get("xray.base-url", "https://xray.cloud.getxray.app")).thenReturn(
                baseUrl.isBlank() ? "https://xray.cloud.getxray.app" : baseUrl);
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
