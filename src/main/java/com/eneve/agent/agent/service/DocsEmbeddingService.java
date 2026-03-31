package com.eneve.agent.agent.service;

import com.eneve.agent.agent.store.EmbeddingStore;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Indexes generated documentation into the embedding store so that doc content
 * is available via semantic search during reviews and other agent jobs.
 * Each h2 section becomes a separate embedding with symbol_type = DOCUMENTATION.
 */
@ApplicationScoped
public class DocsEmbeddingService {

    private static final Logger LOG = Logger.getLogger(DocsEmbeddingService.class);
    private static final Pattern H2_SPLIT = Pattern.compile("(?=^## )", Pattern.MULTILINE);
    private static final Pattern H2_TITLE = Pattern.compile("^##\\s+(.+)$", Pattern.MULTILINE);
    private static final String SYMBOL_TYPE = "DOCUMENTATION";

    @Inject
    BedrockEmbeddingService bedrockService;

    @Inject
    EmbeddingStore embeddingStore;

    /**
     * Scans docs/*.md in the workspace, chunks by h2 headings, and upserts
     * embeddings into the store. Stale doc embeddings are purged first.
     */
    public void indexDocs(WorkspaceContext workspace, String ws, String repoSlug) {
        if (!bedrockService.isConfigured()) {
            LOG.info("Bedrock embedding not configured, skipping doc embedding");
            return;
        }

        Path docsDir = workspace.getRoot().resolve("docs");
        if (!Files.isDirectory(docsDir)) {
            LOG.info("No docs/ directory found, skipping doc embedding");
            return;
        }

        purgeStaleDocEmbeddings(ws, repoSlug);

        List<DocChunk> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.list(docsDir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .forEach(p -> chunks.addAll(chunkFile(p, docsDir)));
        } catch (IOException e) {
            LOG.errorf("Failed to list docs directory: %s", e.getMessage());
            return;
        }

        if (chunks.isEmpty()) {
            LOG.info("No doc chunks found to embed");
            return;
        }

        LOG.infof("Embedding %d doc chunks for %s/%s", chunks.size(), ws, repoSlug);

        List<String> texts = chunks.stream().map(c -> c.content).toList();
        List<float[]> embeddings = bedrockService.embed(texts, "document");
        if (embeddings == null || embeddings.size() != chunks.size()) {
            LOG.error("Embedding generation returned unexpected results, aborting doc indexing");
            return;
        }

        for (int i = 0; i < chunks.size(); i++) {
            DocChunk chunk = chunks.get(i);
            embeddingStore.upsertEmbedding(
                    ws, repoSlug, chunk.filePath, chunk.symbolName,
                    SYMBOL_TYPE, chunk.content, null, null, embeddings.get(i));
        }

        LOG.infof("Indexed %d doc embeddings for %s/%s", chunks.size(), ws, repoSlug);
    }

    private void purgeStaleDocEmbeddings(String ws, String repoSlug) {
        try {
            embeddingStore.deleteBySymbolType(ws, repoSlug, SYMBOL_TYPE);
        } catch (Exception e) {
            LOG.warnf("Failed to purge stale doc embeddings for %s/%s: %s", ws, repoSlug, e.getMessage());
        }
    }

    List<DocChunk> chunkFile(Path file, Path docsDir) {
        List<DocChunk> chunks = new ArrayList<>();
        String fileName = docsDir.relativize(file).toString();
        String filePath = "docs/" + fileName;

        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            LOG.warnf("Failed to read %s: %s", file, e.getMessage());
            return chunks;
        }

        String[] sections = H2_SPLIT.split(content);
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;

            Matcher titleMatcher = H2_TITLE.matcher(trimmed);
            String sectionTitle;
            if (titleMatcher.find()) {
                sectionTitle = titleMatcher.group(1).trim();
            } else {
                sectionTitle = "Introduction";
            }

            String symbolName = fileName + "#" + sectionTitle;
            chunks.add(new DocChunk(filePath, symbolName, trimmed));
        }

        return chunks;
    }

    record DocChunk(String filePath, String symbolName, String content) {}
}
