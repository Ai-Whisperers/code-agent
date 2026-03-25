package com.eneve.agent.tools;

import com.eneve.agent.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HtmlTextExtractor}.
 *
 * All tests are pure-JUnit-5, no CDI container required.
 * The {@link SettingsService} parameter of {@code validateUrl} is supplied via
 * a minimal anonymous subclass that overrides only {@link SettingsService#get(String, String)}.
 */
class HtmlTextExtractorTest {

    // ── extractText — content selection ───────────────────────────────────────

    @Test
    void extractText_returnsBodyTextWhenNoSemanticElement() {
        String html = "<html><body><p>Hello world</p></body></html>";
        assertTrue(HtmlTextExtractor.extractText(html, "https://example.com").contains("Hello world"));
    }

    @Test
    void extractText_prefersMainElement() {
        String html = """
                <html><body>
                  <nav>Nav link</nav>
                  <main><p>Main content here</p></main>
                  <footer>Footer text</footer>
                </body></html>
                """;
        String result = HtmlTextExtractor.extractText(html, "https://example.com");
        assertTrue(result.contains("Main content here"));
        assertFalse(result.contains("Footer text"), "footer must be stripped");
        assertFalse(result.contains("Nav link"), "nav must be stripped");
    }

    @Test
    void extractText_prefersArticleElement() {
        String html = """
                <html><body>
                  <article><p>Article body</p></article>
                  <aside>Sidebar ad</aside>
                </body></html>
                """;
        String result = HtmlTextExtractor.extractText(html, "https://example.com");
        assertTrue(result.contains("Article body"));
        assertFalse(result.contains("Sidebar ad"), "aside must be stripped");
    }

    @Test
    void extractText_stripsScriptAndStyleContent() {
        String html = """
                <html><body>
                  <script>alert('xss');</script>
                  <style>.foo { color: red; }</style>
                  <p>Real page content</p>
                </body></html>
                """;
        String result = HtmlTextExtractor.extractText(html, "https://example.com");
        assertTrue(result.contains("Real page content"));
        assertFalse(result.contains("alert("), "script must be stripped");
        assertFalse(result.contains(".foo"), "style must be stripped");
    }

    @Test
    void extractText_stripsNavFooterHeaderAside() {
        String html = """
                <html><body>
                  <header>Site header</header>
                  <nav>Menu items</nav>
                  <p>Body paragraph</p>
                  <aside>Related links</aside>
                  <footer>Copyright 2025</footer>
                </body></html>
                """;
        String result = HtmlTextExtractor.extractText(html, "https://example.com");
        assertTrue(result.contains("Body paragraph"));
        assertFalse(result.contains("Site header"));
        assertFalse(result.contains("Menu items"));
        assertFalse(result.contains("Related links"));
        assertFalse(result.contains("Copyright 2025"));
    }

    @Test
    void extractText_collapsesExcessiveBlankLines() {
        String html = "<html><body><p>A</p>\n\n\n\n\n\n<p>B</p></body></html>";
        String result = HtmlTextExtractor.extractText(html, "https://example.com");
        assertFalse(result.contains("\n\n\n"), "three or more consecutive newlines must be collapsed");
    }

    @Test
    void extractText_tripsLeadingAndTrailingWhitespace() {
        String html = "<html><body>   <p>trimmed</p>   </body></html>";
        String result = HtmlTextExtractor.extractText(html, "https://example.com");
        assertEquals(result, result.strip());
    }

    @Test
    void extractText_returnsBlankForEmptyBody() {
        String html = "<html><body></body></html>";
        assertTrue(HtmlTextExtractor.extractText(html, "https://example.com").isBlank());
    }

    // ── validateUrl — scheme checks ───────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/page",
            "ftp://example.com/file",
            "file:///etc/passwd"
    })
    void validateUrl_nonHttpsSchemeIsRejected(String url) {
        String error = HtmlTextExtractor.validateUrl(url, null);
        assertNotNull(error, "non-HTTPS URL should produce an error");
        assertTrue(error.contains("HTTPS"), "error should mention HTTPS");
    }

    @Test
    void validateUrl_httpsPublicUnknownDomainPasses() {
        // Unknown host → UnknownHostException is silently ignored → SSRF check passes
        String error = HtmlTextExtractor.validateUrl("https://completely-unknown-host-test-xyz.io/docs", null);
        assertNull(error, "unknown public-looking domain should pass SSRF guard");
    }

    // ── validateUrl — SSRF / private IP checks ────────────────────────────────

    @Test
    void validateUrl_loopbackAddressIsRejected() {
        String error = HtmlTextExtractor.validateUrl("https://127.0.0.1/admin", null);
        assertNotNull(error);
        assertTrue(error.contains("private/internal"));
    }

    @Test
    void validateUrl_privateClassCAddressIsRejected() {
        String error = HtmlTextExtractor.validateUrl("https://192.168.1.100/service", null);
        assertNotNull(error);
        assertTrue(error.contains("private/internal"));
    }

    @Test
    void validateUrl_privateClassAAddressIsRejected() {
        String error = HtmlTextExtractor.validateUrl("https://10.0.0.1/api", null);
        assertNotNull(error);
        assertTrue(error.contains("private/internal"));
    }

    @Test
    void validateUrl_rfc1918Class172AddressIsRejected() {
        String error = HtmlTextExtractor.validateUrl("https://172.16.0.1/internal", null);
        assertNotNull(error);
        assertTrue(error.contains("private/internal"));
    }

    // ── validateUrl — allowlist (settings != null) ────────────────────────────

    @Test
    void validateUrl_nullSettingsSkipsAllowlistCheck() {
        // With settings=null the allowlist gate is entirely skipped
        String error = HtmlTextExtractor.validateUrl("https://totally-different-site-abc.io/docs", null);
        assertNull(error);
    }

    @Test
    void validateUrl_emptyAllowlistAllowsAllDomains() {
        String error = HtmlTextExtractor.validateUrl(
                "https://any-unknown-domain-abc.io/docs", allowlist(""));
        assertNull(error, "empty allowlist must not block anything");
    }

    @Test
    void validateUrl_blankAllowlistAllowsAllDomains() {
        String error = HtmlTextExtractor.validateUrl(
                "https://any-unknown-domain-abc.io/docs", allowlist("   "));
        assertNull(error);
    }

    @Test
    void validateUrl_domainNotInAllowlistIsRejected() {
        String error = HtmlTextExtractor.validateUrl(
                "https://blocked-host-xyz.io/page", allowlist("allowed-host-abc.io,other-host-abc.io"));
        assertNotNull(error);
        assertTrue(error.contains("allowed-domains"));
    }

    @Test
    void validateUrl_exactDomainInAllowlistIsAllowed() {
        String error = HtmlTextExtractor.validateUrl(
                "https://allowed-host-xyz.io/page", allowlist("allowed-host-xyz.io"));
        assertNull(error);
    }

    @Test
    void validateUrl_subdomainOfAllowedDomainIsAllowed() {
        String error = HtmlTextExtractor.validateUrl(
                "https://docs.allowed-host-xyz.io/guide", allowlist("allowed-host-xyz.io"));
        assertNull(error, "subdomain of an allowed domain must pass");
    }

    @Test
    void validateUrl_deepSubdomainOfAllowedDomainIsAllowed() {
        String error = HtmlTextExtractor.validateUrl(
                "https://api.docs.allowed-host-xyz.io/v2", allowlist("allowed-host-xyz.io"));
        assertNull(error);
    }

    @Test
    void validateUrl_allowlistMatchIsCaseInsensitive() {
        String error = HtmlTextExtractor.validateUrl(
                "https://docs.ALLOWED-HOST-XYZ.IO/guide", allowlist("allowed-host-xyz.io"));
        assertNull(error, "allowlist comparison must be case-insensitive");
    }

    @Test
    void validateUrl_allowlistWithWhitespaceAroundDomainIsHandled() {
        String error = HtmlTextExtractor.validateUrl(
                "https://allowed-host-xyz.io/page", allowlist("  allowed-host-xyz.io  , other.io  "));
        assertNull(error, "surrounding whitespace in allowlist entries must be trimmed");
    }

    @Test
    void validateUrl_crawlerBypassWorksWithNullSettings() {
        // Verify the key design decision: when the crawler calls validateUrl(url, null),
        // the allowlist is never consulted even if one would normally be set.
        String crawlerError = HtmlTextExtractor.validateUrl(
                "https://quarkus.io/guides/getting-started", null);
        // quarkus.io is a real domain — it will resolve and be checked for private IPs.
        // It's a public address, so SSRF check passes. allowlist check is skipped.
        assertNull(crawlerError, "crawler must not be blocked by allowlist");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a minimal {@link SettingsService} stub that returns {@code domains}
     * for the {@code tools.fetch-url.allowed-domains} key and the supplied default
     * for everything else.
     */
    private static SettingsService allowlist(String domains) {
        return new SettingsService() {
            @Override
            public String get(String key, String defaultValue) {
                if ("tools.fetch-url.allowed-domains".equals(key)) return domains;
                return defaultValue;
            }
        };
    }
}
