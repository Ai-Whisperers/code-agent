package com.eneve.agent.agent;

import com.eneve.agent.diff.DiffHunk;
import com.eneve.agent.diff.DiffLine;
import com.eneve.agent.diff.ParsedDiffFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the diagram-upload path added to {@link PrSummaryGenerator}.
 *
 * Tests verify that:
 * - Bitbucket + upload-enabled mode emits placeholders and {@link PrSummaryGenerator.PendingDiagram}s
 * - Non-Bitbucket platforms emit inline mermaid fences with no pending diagrams
 * - The mermaid.ink fallback path still applies when upload is disabled
 */
class PrSummaryGeneratorDiagramTest {

    private PrSummaryGenerator generator;
    private MermaidRenderer mermaidRenderer;

    @BeforeEach
    void setUp() throws Exception {
        mermaidRenderer = new MermaidRenderer();
        generator = new PrSummaryGenerator();
        injectField(generator, "mermaidRenderer", mermaidRenderer);
    }

    // ── Bitbucket + upload enabled (default) ──────────────────────────────

    @Test
    void bitbucketUploadEnabled_emitsPlaceholdersAndPendingDiagrams() throws Exception {
        setPlatform("bitbucket");
        setUploadEnabled(true);

        String json = buildJson("Add payment flow", "payment/Checkout.java",
                "sequenceDiagram\nUser->>Server: pay");
        PrSummaryGenerator.SummaryResult result = generator.formatComment(json, noFiles(), "42");

        assertEquals(1, result.pendingDiagrams().size(), "Expected one pending diagram");
        PrSummaryGenerator.PendingDiagram pd = result.pendingDiagrams().get(0);

        assertEquals("mermaid-42-1.png", pd.filename());
        assertTrue(pd.placeholder().startsWith(PrSummaryGenerator.DIAGRAM_PLACEHOLDER_PREFIX));
        assertEquals("sequenceDiagram\nUser->>Server: pay", pd.mermaidSource());

        assertTrue(result.body().contains(pd.placeholder()),
                "Body should contain the placeholder URL");
        assertFalse(result.body().contains("mermaid.ink"),
                "Body should not contain mermaid.ink when upload is enabled");
    }

    @Test
    void bitbucketUploadEnabled_sanitisesPrIdInFilename() throws Exception {
        setPlatform("bitbucket");
        setUploadEnabled(true);

        String json = buildJson("title", "file.java", "sequenceDiagram\nA->>B: hi");
        PrSummaryGenerator.SummaryResult result = generator.formatComment(json, noFiles(), "feature/my-branch#99");

        assertEquals(1, result.pendingDiagrams().size());
        String filename = result.pendingDiagrams().get(0).filename();
        assertFalse(filename.contains("/"), "Filename must not contain slashes");
        assertFalse(filename.contains("#"), "Filename must not contain hashes");
        assertTrue(filename.startsWith("mermaid-"));
        assertTrue(filename.endsWith("-1.png"));
    }

    @Test
    void bitbucketUploadEnabled_noDiagramsInJson_noPendingDiagrams() throws Exception {
        setPlatform("bitbucket");
        setUploadEnabled(true);

        String json = """
                {"summary": "Just a fix", "walkthrough": [{"file": "Foo.java", "changes": "fixed"}]}
                """;
        PrSummaryGenerator.SummaryResult result = generator.formatComment(json.strip(), noFiles(), "7");

        assertTrue(result.pendingDiagrams().isEmpty());
        assertFalse(result.body().contains(PrSummaryGenerator.DIAGRAM_PLACEHOLDER_PREFIX));
    }

    @Test
    void bitbucketUploadEnabled_multipleDiagrams_allCollected() throws Exception {
        setPlatform("bitbucket");
        setUploadEnabled(true);

        String json = """
                {
                  "summary": "two diagrams",
                  "walkthrough": [],
                  "diagrams": [
                    {"title": "A", "mermaid": "sequenceDiagram\\nA->>B: one"},
                    {"title": "B", "mermaid": "classDiagram\\nClass01 <|-- Class02"}
                  ]
                }
                """;
        PrSummaryGenerator.SummaryResult result = generator.formatComment(json.strip(), noFiles(), "10");

        assertEquals(2, result.pendingDiagrams().size());
        assertEquals("mermaid-10-1.png", result.pendingDiagrams().get(0).filename());
        assertEquals("mermaid-10-2.png", result.pendingDiagrams().get(1).filename());
    }

    // ── Bitbucket + upload disabled (mermaid.ink fallback) ────────────────

    @Test
    void bitbucketUploadDisabled_emitsMermaidInkUrl() throws Exception {
        setPlatform("bitbucket");
        setUploadEnabled(false);

        String json = buildJson("title", "file.java", "sequenceDiagram\nA->>B: test");
        PrSummaryGenerator.SummaryResult result = generator.formatComment(json, noFiles(), "5");

        assertTrue(result.pendingDiagrams().isEmpty(), "No pending diagrams when upload disabled");
        assertTrue(result.body().contains("mermaid.ink"),
                "Should fall back to mermaid.ink when upload is disabled");
        assertFalse(result.body().contains(PrSummaryGenerator.DIAGRAM_PLACEHOLDER_PREFIX));
    }

    // ── Non-Bitbucket platform ────────────────────────────────────────────

    @Test
    void githubPlatform_emitsNativeMermaidFence() throws Exception {
        setPlatform("github");
        setUploadEnabled(true);

        String json = buildJson("title", "file.java", "sequenceDiagram\nA->>B: test");
        PrSummaryGenerator.SummaryResult result = generator.formatComment(json, noFiles(), "3");

        assertTrue(result.pendingDiagrams().isEmpty());
        assertTrue(result.body().contains("```mermaid"),
                "GitHub should use native mermaid fenced blocks");
    }

    // ── Body structure sanity ─────────────────────────────────────────────

    @Test
    void bodyContainsMarkerAndFooter() throws Exception {
        setPlatform("bitbucket");
        setUploadEnabled(true);

        String json = """
                {"summary": "small fix", "walkthrough": [{"file": "F.java", "changes": "patch"}]}
                """;
        PrSummaryGenerator.SummaryResult result = generator.formatComment(json.strip(), noFiles(), "1");

        assertTrue(result.body().startsWith(PrSummaryGenerator.marker()));
        assertTrue(result.body().contains("_Generated by Code Agent_"));
    }

    @Test
    void nullPrId_doesNotThrow() throws Exception {
        setPlatform("bitbucket");
        setUploadEnabled(true);

        String json = buildJson("title", "file.java", "sequenceDiagram\nA->>B: hi");
        assertDoesNotThrow(() -> generator.formatComment(json, noFiles(), null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void setPlatform(String platform) throws Exception {
        injectField(mermaidRenderer, "platform", platform);
    }

    private void setUploadEnabled(boolean enabled) throws Exception {
        injectField(generator, "diagramUploadEnabled", enabled);
    }

    private static String buildJson(String summary, String file, String mermaid) {
        return """
                {
                  "summary": "%s",
                  "walkthrough": [{"file": "%s", "changes": "changed"}],
                  "diagrams": [{"title": "Flow", "mermaid": "%s"}]
                }
                """.formatted(summary, file, mermaid.replace("\n", "\\n"));
    }

    private static List<ParsedDiffFile> noFiles() {
        return List.of();
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), name);
            }
            throw e;
        }
    }
}
