package com.eneve.agent.agent.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KnowledgeIndexerService#extractStaticFileText(String, String, byte[])}.
 *
 * Tests are pure JUnit 5 — no CDI container or database required.
 * The method is package-visible so this class lives in the same package.
 */
class StaticFileTextExtractionTest {

    // ── .txt files ─────────────────────────────────────────────────────────────

    @Test
    void txtFile_returnsUtf8DecodedContent() {
        byte[] data = "Hello, knowledge base!".getBytes(StandardCharsets.UTF_8);
        String result = KnowledgeIndexerService.extractStaticFileText("notes.txt", "text/plain", data);
        assertEquals("Hello, knowledge base!", result);
    }

    @Test
    void txtFile_preservesUnicodeCharacters() {
        String content = "Ärger über Öl — naïve café";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String result = KnowledgeIndexerService.extractStaticFileText("unicode.txt", "text/plain", data);
        assertEquals(content, result);
    }

    @Test
    void txtFile_contentTypeOnlyIsRecognised() {
        byte[] data = "Content via MIME".getBytes(StandardCharsets.UTF_8);
        String result = KnowledgeIndexerService.extractStaticFileText("upload", "text/plain", data);
        assertNotNull(result);
        assertEquals("Content via MIME", result);
    }

    // ── .md (Markdown) files ───────────────────────────────────────────────────

    @Test
    void mdFile_returnsRawMarkdownText() {
        String md = "# Title\n\nSome **bold** text.\n\n- item 1\n- item 2";
        byte[] data = md.getBytes(StandardCharsets.UTF_8);
        String result = KnowledgeIndexerService.extractStaticFileText("README.md", "text/markdown", data);
        assertEquals(md, result);
    }

    @Test
    void mdFile_extensionAloneIsEnough() {
        byte[] data = "## Section\nContent here.".getBytes(StandardCharsets.UTF_8);
        // Supply an unrecognised MIME type — extension should still be matched
        String result = KnowledgeIndexerService.extractStaticFileText("doc.md", "application/octet-stream", data);
        assertNotNull(result, "extension .md should be recognised regardless of MIME type");
        assertTrue(result.contains("Content here."));
    }

    // ── .pdf files ─────────────────────────────────────────────────────────────

    @Test
    void pdfFile_extractsTextFromSinglePage() throws Exception {
        byte[] pdf = buildPdf("Knowledge from PDF");
        String result = KnowledgeIndexerService.extractStaticFileText("doc.pdf", "application/pdf", pdf);
        assertNotNull(result);
        assertTrue(result.contains("Knowledge from PDF"),
                "extracted text must contain the original content");
    }

    @Test
    void pdfFile_mimeTypeAloneTriggersExtraction() throws Exception {
        byte[] pdf = buildPdf("MIME-triggered extraction");
        String result = KnowledgeIndexerService.extractStaticFileText("upload", "application/pdf", pdf);
        assertNotNull(result);
        assertTrue(result.contains("MIME-triggered extraction"));
    }

    @Test
    void pdfFile_extensionAloneIsEnough() throws Exception {
        byte[] pdf = buildPdf("Extension-triggered extraction");
        String result = KnowledgeIndexerService.extractStaticFileText("report.pdf", "application/octet-stream", pdf);
        assertNotNull(result);
        assertTrue(result.contains("Extension-triggered extraction"));
    }

    @Test
    void pdfFile_corruptBytesReturnNull() {
        byte[] garbage = "not a real pdf".getBytes(StandardCharsets.UTF_8);
        String result = KnowledgeIndexerService.extractStaticFileText("bad.pdf", "application/pdf", garbage);
        assertNull(result, "corrupt PDF bytes must return null without throwing");
    }

    // ── Unsupported types ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"archive.zip", "binary.exe", "image.png", "sheet.xlsx", "data.bin"})
    void unsupportedExtension_returnsNull(String filename) {
        byte[] data = "irrelevant bytes".getBytes(StandardCharsets.UTF_8);
        String result = KnowledgeIndexerService.extractStaticFileText(filename, "application/octet-stream", data);
        assertNull(result, "unsupported file type '" + filename + "' must return null");
    }

    // ── Edge cases ─────────────────────────────────────────────────────────────

    @Test
    void nullData_returnsNull() {
        assertNull(KnowledgeIndexerService.extractStaticFileText("notes.txt", "text/plain", null));
    }

    @Test
    void emptyData_returnsNull() {
        assertNull(KnowledgeIndexerService.extractStaticFileText("notes.txt", "text/plain", new byte[0]));
    }

    @Test
    void nullFilenameWithTextMime_returnsContent() {
        byte[] data = "text body".getBytes(StandardCharsets.UTF_8);
        String result = KnowledgeIndexerService.extractStaticFileText(null, "text/plain", data);
        assertNotNull(result, "null filename with text/plain MIME should still be extracted");
        assertEquals("text body", result);
    }

    @Test
    void nullFilenameAndNullMime_returnsNull() {
        byte[] data = "irrelevant".getBytes(StandardCharsets.UTF_8);
        assertNull(KnowledgeIndexerService.extractStaticFileText(null, null, data),
                "with no filename and no MIME type the type is indeterminate — must return null");
    }

    @Test
    void extensionIsCaseInsensitive() {
        byte[] data = "content".getBytes(StandardCharsets.UTF_8);
        assertNotNull(KnowledgeIndexerService.extractStaticFileText("NOTES.TXT", "application/octet-stream", data));
        assertNotNull(KnowledgeIndexerService.extractStaticFileText("README.MD", "application/octet-stream", data));
    }

    // ── Real-world PDF (api-manual-etpa.pdf) ───────────────────────────────────

    @Test
    void realPdf_extractsNonEmptyText() throws Exception {
        byte[] data = loadTestResource("api-manual-etpa.pdf");
        String result = KnowledgeIndexerService.extractStaticFileText(
                "API Manual ETPA 08-09-2023.pdf", "application/pdf", data);
        assertNotNull(result, "extractStaticFileText must return non-null for a valid PDF");
        assertFalse(result.isBlank(), "extracted text must not be blank");
    }

    @Test
    void realPdf_extensionAloneIsEnough() throws Exception {
        byte[] data = loadTestResource("api-manual-etpa.pdf");
        String result = KnowledgeIndexerService.extractStaticFileText(
                "API Manual ETPA 08-09-2023.pdf", "application/octet-stream", data);
        assertNotNull(result, "extension .pdf must trigger extraction even when MIME is octet-stream");
        assertFalse(result.isBlank());
    }

    @Test
    void realPdf_mimeTypeAloneIsEnough() throws Exception {
        byte[] data = loadTestResource("api-manual-etpa.pdf");
        String result = KnowledgeIndexerService.extractStaticFileText(
                "upload", "application/pdf", data);
        assertNotNull(result, "MIME application/pdf must trigger extraction even without .pdf extension");
        assertFalse(result.isBlank());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static byte[] loadTestResource(String name) throws Exception {
        try (InputStream is = StaticFileTextExtractionTest.class
                .getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "Test resource not found on classpath: " + name);
            return is.readAllBytes();
        }
    }

    /**
     * Creates a single-page PDF containing {@code text} using PDFBox.
     * This mirrors the way PDFBox is already used inside {@link KnowledgeIndexerService}.
     */
    private static byte[] buildPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
