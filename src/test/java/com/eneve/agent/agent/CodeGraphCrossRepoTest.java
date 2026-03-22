package com.eneve.agent.agent;

import com.eneve.agent.agent.service.CodeGraphQueryService;
import com.eneve.agent.agent.store.CodeGraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the cross-repo impact analysis logic in {@link CodeGraphQueryService}.
 *
 * Uses a manual stub for {@link CodeGraphStore} to avoid Mockito dependency.
 */
class CodeGraphCrossRepoTest {

    private static final String WS = "acme";
    private static final String REPO = "shared-utils";

    private StubCodeGraphStore store;
    private CodeGraphQueryService service;

    @BeforeEach
    void setup() throws Exception {
        store = new StubCodeGraphStore();
        service = new CodeGraphQueryService();
        setField(service, "store", store);
        setField(service, "crossRepoEnabled", true);
        setField(service, "criticalThreshold", 3);
    }

    // ── Non-source files produce empty output ────────────────────────────

    @Test
    void nonSourceFiles_returnsEmpty() {
        String result = service.buildImpactSection(WS, REPO, List.of("README.md", "pom.xml"));
        assertTrue(result.isEmpty(), "Expected empty output for non-source files");
        assertEquals(0, store.callCount, "Store should not be queried for non-source files");
    }

    // ── Symbol with no cross-repo usage: no cross-repo section ───────────

    @Test
    void noExternalUsage_noCrossRepoSection() {
        store.symbols.put(key(REPO, "src/Internal.java"), List.of("Internal.doWork"));
        store.crossRepoCount.put("Internal.doWork", 0);

        String result = service.buildImpactSection(WS, REPO, List.of("src/Internal.java"));

        assertFalse(result.contains("Cross-repo"), "Should not include cross-repo section when count=0");
    }

    // ── Symbol above threshold: CRITICAL label, no detail listing ────────

    @Test
    void criticalSymbol_showsCriticalLabelWithCount() {
        store.symbols.put(key(REPO, "src/StringHelper.java"),
                List.of("StringHelper", "StringHelper.sanitize"));
        store.crossRepoCount.put("StringHelper.sanitize", 8);
        store.crossRepoCount.put("StringHelper", 0);

        String result = service.buildImpactSection(WS, REPO, List.of("src/StringHelper.java"));

        assertTrue(result.contains("CRITICAL"), "Expected CRITICAL label for widely-used symbol");
        assertTrue(result.contains("StringHelper.sanitize"), "Expected symbol name in output");
        assertTrue(result.contains("8"), "Expected count in output");
        assertTrue(result.contains("wide blast radius"), "Expected blast-radius warning");
        // Should NOT query for individual callers when critical
        assertEquals(0, store.callersAcrossWorkspaceCount,
                "findCallersAcrossWorkspace must not be called for critical symbols");
    }

    @Test
    void exactlyAtThreshold_isCritical() {
        store.symbols.put(key(REPO, "src/Foo.java"), List.of("Foo.bar"));
        store.crossRepoCount.put("Foo.bar", 3); // equals threshold

        String result = service.buildImpactSection(WS, REPO, List.of("src/Foo.java"));

        assertTrue(result.contains("CRITICAL"),
                "Symbol used in exactly 'threshold' repos should be CRITICAL");
    }

    // ── Symbol below threshold: detailed caller listing ──────────────────

    @Test
    void belowThreshold_listsDetailedCallers() {
        store.symbols.put(key(REPO, "src/StringHelper.java"), List.of("StringHelper.capitalize"));
        store.crossRepoCount.put("StringHelper.capitalize", 2);
        store.callersAcrossWorkspace.put("StringHelper.capitalize", List.of(
                new CodeGraphStore.CrossRepoEdgeResult("service-a", "LoginHandler.doLogin",
                        "src/LoginHandler.java"),
                new CodeGraphStore.CrossRepoEdgeResult("service-b", "ReportFormatter.format",
                        "src/ReportFormatter.java")
        ));

        String result = service.buildImpactSection(WS, REPO, List.of("src/StringHelper.java"));

        assertTrue(result.contains("Cross-repo impact"), "Expected cross-repo section header");
        assertTrue(result.contains("service-a"), "Expected repo-a in results");
        assertTrue(result.contains("LoginHandler.doLogin"), "Expected caller in results");
        assertTrue(result.contains("service-b"), "Expected repo-b in results");
        assertFalse(result.contains("CRITICAL"), "Should not be CRITICAL when below threshold");
    }

    // ── Type symbol below threshold: implementation listing ──────────────

    @Test
    void typeSymbolBelowThreshold_listsImplementations() {
        store.symbols.put(key(REPO, "src/PaymentGateway.java"), List.of("PaymentGateway"));
        store.crossRepoCount.put("PaymentGateway", 1);
        store.implsAcrossWorkspace.put("PaymentGateway", List.of(
                new CodeGraphStore.CrossRepoEdgeResult("billing-service", "StripeGateway",
                        "src/StripeGateway.java")
        ));

        String result = service.buildImpactSection(WS, REPO, List.of("src/PaymentGateway.java"));

        assertTrue(result.contains("Cross-repo impact"), "Expected cross-repo section");
        assertTrue(result.contains("implemented/extended in other repos"),
                "Expected implementation listing for type symbol");
        assertTrue(result.contains("billing-service"), "Expected billing-service repo in output");
        assertTrue(result.contains("StripeGateway"), "Expected implementing class in output");
    }

    // ── crossRepoEnabled=false skips all workspace queries ───────────────

    @Test
    void crossRepoDisabled_skipsWorkspaceQueries() throws Exception {
        setField(service, "crossRepoEnabled", false);

        store.symbols.put(key(REPO, "src/Util.java"), List.of("Util.parse"));

        service.buildImpactSection(WS, REPO, List.of("src/Util.java"));

        assertEquals(0, store.crossRepoCallCount, "No cross-repo queries when feature is disabled");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String key(String repo, String file) {
        return repo + "|" + file;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    // ── Stub ─────────────────────────────────────────────────────────────

    static class StubCodeGraphStore extends CodeGraphStore {

        final Map<String, List<String>> symbols = new HashMap<>();
        final Map<String, Integer> crossRepoCount = new HashMap<>();
        final Map<String, List<CrossRepoEdgeResult>> callersAcrossWorkspace = new HashMap<>();
        final Map<String, List<CrossRepoEdgeResult>> implsAcrossWorkspace = new HashMap<>();

        int callCount = 0;
        int crossRepoCallCount = 0;
        int callersAcrossWorkspaceCount = 0;

        @Override
        public List<String> findSymbolsInFile(String workspace, String repoSlug, String filePath) {
            callCount++;
            return symbols.getOrDefault(repoSlug + "|" + filePath, List.of());
        }

        @Override
        public List<EdgeResult> findCallers(String workspace, String repoSlug, String symbol) {
            return List.of();
        }

        @Override
        public List<EdgeResult> findImplementations(String workspace, String repoSlug, String symbol) {
            return List.of();
        }

        @Override
        public int countDistinctReposUsing(String workspace, String excludeRepo, String symbolName) {
            crossRepoCallCount++;
            return crossRepoCount.getOrDefault(symbolName, 0);
        }

        @Override
        public List<CrossRepoEdgeResult> findCallersAcrossWorkspace(String workspace, String excludeRepo,
                                                                     String symbolName) {
            callersAcrossWorkspaceCount++;
            return callersAcrossWorkspace.getOrDefault(symbolName, List.of());
        }

        @Override
        public List<CrossRepoEdgeResult> findImplementationsAcrossWorkspace(String workspace, String excludeRepo,
                                                                             String symbolName) {
            return implsAcrossWorkspace.getOrDefault(symbolName, List.of());
        }
    }
}
