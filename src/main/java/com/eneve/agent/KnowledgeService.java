package com.eneve.agent;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.eneve.agent.agent.model.StaticFileSource;
import com.eneve.agent.agent.model.WebDocSource;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.KnowledgeEmbeddingStore;
import com.eneve.agent.agent.store.StaticFileSourceStore;
import com.eneve.agent.agent.store.WebDocSourceStore;
import com.eneve.agent.agent.service.KnowledgeIndexerService;
import com.eneve.agent.agent.service.KnowledgeSearchService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ApplicationScoped
public class KnowledgeService {

    private static final Logger LOG = Logger.getLogger(KnowledgeService.class);
    private static final long MAX_STATIC_FILE_BYTES = 10 * 1024 * 1024L;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".txt", ".md", ".pdf");

    @Inject KnowledgeIndexerService indexer;
    @Inject KnowledgeSearchService searcher;
    @Inject KnowledgeEmbeddingStore store;
    @Inject CustomerRegistryStore registryStore;
    @Inject WebDocSourceStore webDocSourceStore;
    @Inject StaticFileSourceStore staticFileStore;
    @Inject S3Client s3Client;

    @ConfigProperty(name = "attachment.s3.bucket", defaultValue = "code-agent-attachments")
    String s3Bucket;

    // ── Custom exceptions ─────────────────────────────────────────────────

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(String message) { super(message); }
    }

    public static class WebDocSourceNotFoundException extends RuntimeException {
        public WebDocSourceNotFoundException(String message) { super(message); }
    }

    public static class StaticFileNotFoundException extends RuntimeException {
        public StaticFileNotFoundException(String message) { super(message); }
    }

    public static class DuplicateSourceException extends RuntimeException {
        public DuplicateSourceException(String message) { super(message); }
    }

    // ── Public service methods ────────────────────────────────────────────

    public KnowledgeIndexerService.IndexResult indexJira(String projectKey) {
        return indexer.indexJiraProject(projectKey);
    }

    public KnowledgeIndexerService.IndexResult indexConfluence(String spaceKey) {
        return indexer.indexConfluenceSpace(spaceKey);
    }

    public List<KnowledgeIndexerService.IndexResult> reindexAll() {
        return indexer.reindexAll();
    }

    public List<KnowledgeIndexerService.IndexResult> indexProduct(String productId) {
        return registryStore.getProduct(productId)
                .map(indexer::indexProduct)
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + productId));
    }

    public List<?> search(String query, List<String> sourceTypes, int topK) {
        return searcher.search(query, sourceTypes, topK);
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "jira",          store.countBySource("jira"),
                "confluence",    store.countBySource("confluence"),
                "jiraAttachment", store.countBySource("jira-attachment"),
                "webDocs",       store.countBySource("web-docs"),
                "staticFiles",   store.countBySource("static-file")
        );
    }

    public WebDocSource registerWebDocSource(String name, String baseUrl,
                                              String allowedPathPrefix,
                                              int maxPages, int crawlDelayMs) {
        return webDocSourceStore.insert(name, baseUrl, allowedPathPrefix, maxPages, crawlDelayMs)
                .orElseThrow(() -> new DuplicateSourceException(
                        "A source with this baseUrl already exists"));
    }

    public List<WebDocSource> listWebDocSources() {
        return webDocSourceStore.listAll();
    }

    public void deleteWebDocSource(String id) {
        var source = webDocSourceStore.findById(id)
                .orElseThrow(() -> new WebDocSourceNotFoundException(
                        "Web doc source not found: " + id));
        store.deleteBySourceIdPrefix("web-docs", source.baseUrl());
        webDocSourceStore.delete(id);
    }

    public KnowledgeIndexerService.IndexResult crawlWebDocSource(String id) {
        var source = webDocSourceStore.findById(id)
                .orElseThrow(() -> new WebDocSourceNotFoundException(
                        "Web doc source not found: " + id));
        return indexer.indexWebDocSource(source);
    }

    public List<KnowledgeIndexerService.IndexResult> crawlAllWebDocSources() {
        return indexer.indexAllWebDocSources();
    }

    public StaticFileSource uploadStaticFile(String filename, InputStream fileStream,
                                              String contentType, Long fileSize,
                                              String displayName) {
        String lower = filename.toLowerCase();
        boolean allowed = ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Unsupported file type. Allowed extensions: .txt, .md, .pdf");
        }

        long size = fileSize != null ? fileSize : 0;
        if (size > MAX_STATIC_FILE_BYTES) {
            throw new IllegalArgumentException("File too large. Maximum size is 10 MB");
        }

        String effectiveContentType = contentType != null && !contentType.isBlank()
                ? contentType : "application/octet-stream";
        String effectiveDisplayName = displayName != null && !displayName.isBlank()
                ? displayName.trim()
                : filename.replaceAll("\\.[^.]+$", "");

        String fileId = UUID.randomUUID().toString();
        String s3Key = "knowledge/static-files/" + fileId + "/" + filename;

        try {
            byte[] data = fileStream.readAllBytes();
            if (size == 0) size = data.length;

            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(s3Bucket)
                            .key(s3Key)
                            .contentType(effectiveContentType)
                            .contentLength((long) data.length)
                            .build(),
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(data));
            LOG.infof("Uploaded static file to S3: %s", s3Key);

            var source = staticFileStore.insert(effectiveDisplayName, filename,
                            effectiveContentType, s3Key, data.length)
                    .orElseThrow(() -> new RuntimeException("Failed to persist static file metadata"));

            var result = indexer.indexStaticFile(source);
            LOG.infof("Static file indexed: %s chunks=%d", source.id(), result.chunksIndexed());

            return staticFileStore.findById(source.id()).orElse(source);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("Failed to upload or index static file %s: %s", filename, e.getMessage());
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(s3Bucket).key(s3Key).build());
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }

    public List<StaticFileSource> listStaticFiles() {
        return staticFileStore.listAll();
    }

    public void deleteStaticFile(String id) {
        var source = staticFileStore.findById(id)
                .orElseThrow(() -> new StaticFileNotFoundException(
                        "Static file not found: " + id));
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(s3Bucket).key(source.s3Key()).build());
        store.deleteBySource("static-file", source.id());
        staticFileStore.delete(id);
    }

    public KnowledgeIndexerService.IndexResult reindexStaticFile(String id) {
        var source = staticFileStore.findById(id)
                .orElseThrow(() -> new StaticFileNotFoundException(
                        "Static file not found: " + id));
        return indexer.indexStaticFile(source);
    }
}
