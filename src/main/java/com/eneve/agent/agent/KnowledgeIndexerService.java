package com.eneve.agent.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.ProductConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Orchestrates knowledge indexing from Jira and Confluence.
 * For each source it produces {@link KnowledgeEmbeddingStore.KnowledgeChunk}s,
 * embeds them via {@link VoyageEmbeddingService}, and persists via
 * {@link KnowledgeEmbeddingStore}.
 */
@ApplicationScoped
public class KnowledgeIndexerService {

    private static final Logger LOG = Logger.getLogger(KnowledgeIndexerService.class);

    /** Maximum attachment size in bytes that will be downloaded and indexed. */
    @ConfigProperty(name = "knowledge.indexer.max-attachment-bytes", defaultValue = "5242880")
    long maxAttachmentBytes;

    /** Maximum number of Jira issues fetched per project in a single indexing pass. */
    @ConfigProperty(name = "knowledge.indexer.jira-max-results", defaultValue = "200")
    int jiraMaxResults;

    /** Approximate token budget per Confluence page chunk (1 token ≈ 4 chars). */
    private static final int CONFLUENCE_CHUNK_CHARS = 2000;

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain", "text/markdown", "text/csv", "text/xml",
            "application/json", "application/xml", "application/x-yaml",
            "application/yaml", "text/yaml"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".log", ".csv", ".json", ".xml", ".yml", ".yaml",
            ".properties", ".conf", ".toml"
    );

    @Inject JiraService jiraService;
    @Inject ConfluenceService confluenceService;
    @Inject VoyageEmbeddingService voyageService;
    @Inject KnowledgeEmbeddingStore store;
    @Inject CustomerRegistryStore registryStore;

    public record IndexResult(
            String sourceType,
            String scopeId,
            int chunksIndexed,
            int chunksSkipped,
            List<String> errors
    ) {}

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Index all Jira issues in a project, including attachments and linked
     * Confluence pages.
     *
     * @param projectKey Jira project key (e.g. "ENG")
     * @param productId  product registry ID for scoping
     * @param customerId customer registry ID for scoping
     */
    public IndexResult indexJiraProject(String projectKey, String productId, String customerId) {
        LOG.infof("Starting Jira indexing for project %s (product=%s)", projectKey, productId);
        int indexed = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        String jql = "project = \"" + projectKey + "\" ORDER BY created DESC";
        List<JiraService.JiraIssueDetail> issues = jiraService.searchIssues(jql, jiraMaxResults);

        // Track Confluence pages already scheduled to avoid re-indexing from multiple issues
        Set<String> scheduledConfluencePageIds = new java.util.HashSet<>();

        for (JiraService.JiraIssueDetail issue : issues) {
            try {
                // 1. Main issue chunk: summary + description + comments
                String issueText = buildIssueText(issue);
                if (!issueText.isBlank()) {
                    var chunk = new KnowledgeEmbeddingStore.KnowledgeChunk(
                            "jira",
                            issue.key(),
                            productId,
                            customerId,
                            issue.summary(),
                            issueText,
                            Map.of(
                                    "status", issue.status() != null ? issue.status() : "",
                                    "reporter", issue.reporter() != null ? issue.reporter() : "",
                                    "assignee", issue.assignee() != null ? issue.assignee() : "",
                                    "labels", issue.labels() != null ? String.join(",", issue.labels()) : "",
                                    "url", jiraService instanceof Object
                                            ? "" : ""
                            )
                    );
                    if (embedAndStore(chunk)) indexed++; else skipped++;
                }

                // 2. Attachments
                if (issue.attachments() != null) {
                    for (JiraService.JiraAttachment att : issue.attachments()) {
                        try {
                            int attResult = indexAttachment(att, issue.key(), productId, customerId);
                            if (attResult > 0) indexed += attResult;
                            else if (attResult == 0) skipped++;
                        } catch (Exception e) {
                            errors.add("Attachment " + att.filename() + " on " + issue.key() + ": " + e.getMessage());
                        }
                    }
                }

                // 3. Linked Confluence pages via remote links
                List<JiraService.JiraRemoteLink> remoteLinks = jiraService.fetchRemoteLinks(issue.key());
                for (JiraService.JiraRemoteLink link : remoteLinks) {
                    String pageId = confluenceService.extractPageIdFromUrl(link.url());
                    if (pageId == null || scheduledConfluencePageIds.contains(pageId)) continue;
                    scheduledConfluencePageIds.add(pageId);
                    try {
                        int pageResult = indexConfluencePage(pageId, link.title(), productId, customerId);
                        indexed += pageResult;
                    } catch (Exception e) {
                        errors.add("Confluence page " + pageId + " linked from " + issue.key() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                errors.add("Issue " + issue.key() + ": " + e.getMessage());
                LOG.warnf("Failed to index Jira issue %s: %s", issue.key(), e.getMessage());
            }
        }

        LOG.infof("Jira indexing complete for %s: indexed=%d, skipped=%d, errors=%d",
                projectKey, indexed, skipped, errors.size());
        return new IndexResult("jira", projectKey, indexed, skipped, errors);
    }

    /**
     * Index all pages in a Confluence space, chunked by heading section.
     *
     * @param spaceKey   Confluence space key
     * @param productId  product registry ID for scoping
     * @param customerId customer registry ID for scoping
     */
    public IndexResult indexConfluenceSpace(String spaceKey, String productId, String customerId) {
        LOG.infof("Starting Confluence indexing for space %s (product=%s)", spaceKey, productId);
        int indexed = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        List<ConfluenceService.ConfluencePage> pages = confluenceService.listPagesInSpace(spaceKey);
        for (ConfluenceService.ConfluencePage page : pages) {
            try {
                int result = indexConfluencePage(page.pageId(), page.title(), productId, customerId);
                indexed += result;
            } catch (Exception e) {
                errors.add("Page " + page.pageId() + " (" + page.title() + "): " + e.getMessage());
                LOG.warnf("Failed to index Confluence page %s: %s", page.pageId(), e.getMessage());
            }
        }

        LOG.infof("Confluence indexing complete for space %s: indexed=%d, skipped=%d, errors=%d",
                spaceKey, indexed, skipped, errors.size());
        return new IndexResult("confluence", spaceKey, indexed, skipped, errors);
    }

    /**
     * Reindex all products in the customer registry.
     * Iterates every product and indexes all its configured Jira projects and
     * Confluence spaces.
     */
    public List<IndexResult> reindexAll() {
        List<IndexResult> results = new ArrayList<>();
        for (var customer : registryStore.listCustomers()) {
            for (var product : registryStore.listProducts(customer.customerId())) {
                results.addAll(indexProduct(product));
            }
        }
        return results;
    }

    /**
     * Index a single product's Jira projects and Confluence space.
     */
    public List<IndexResult> indexProduct(ProductConfig product) {
        List<IndexResult> results = new ArrayList<>();
        String pid = product.productId();
        String cid = product.customerId();

        if (product.jira() != null && product.jira().projects() != null) {
            for (String projectKey : product.jira().projects().values()) {
                if (projectKey != null && !projectKey.isBlank()) {
                    results.add(indexJiraProject(projectKey, pid, cid));
                }
            }
        }
        if (product.confluence() != null
                && product.confluence().spaceKey() != null
                && !product.confluence().spaceKey().isBlank()) {
            results.add(indexConfluenceSpace(product.confluence().spaceKey(), pid, cid));
        }
        return results;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    private int indexConfluencePage(String pageId, String title, String productId, String customerId) {
        String body = confluenceService.getPageBody(pageId);
        if (body == null || body.isBlank()) return 0;

        List<String> chunks = splitIntoChunks(body, CONFLUENCE_CHUNK_CHARS);
        int indexed = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            String chunkId = chunks.size() > 1 ? pageId + "/chunk/" + i : pageId;
            var chunk = new KnowledgeEmbeddingStore.KnowledgeChunk(
                    "confluence",
                    chunkId,
                    productId,
                    customerId,
                    title,
                    chunkText,
                    Map.of("pageId", pageId, "chunkIndex", i)
            );
            if (embedAndStore(chunk)) indexed++;
        }
        return indexed;
    }

    private int indexAttachment(JiraService.JiraAttachment att, String issueKey,
                                 String productId, String customerId) {
        if (att.size() > maxAttachmentBytes) {
            LOG.debugf("Skipping large attachment %s (%d bytes) on %s", att.filename(), att.size(), issueKey);
            return 0;
        }

        String text = extractAttachmentText(att);
        if (text == null || text.isBlank()) return 0;

        List<String> chunks = splitIntoChunks(text, CONFLUENCE_CHUNK_CHARS);
        int indexed = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = chunks.size() > 1
                    ? issueKey + "/attachment/" + att.id() + "/chunk/" + i
                    : issueKey + "/attachment/" + att.id();
            var chunk = new KnowledgeEmbeddingStore.KnowledgeChunk(
                    "jira-attachment",
                    chunkId,
                    productId,
                    customerId,
                    issueKey + " – " + att.filename(),
                    chunks.get(i),
                    Map.of("issueKey", issueKey, "filename", att.filename(), "mimeType", att.mimeType())
            );
            if (embedAndStore(chunk)) indexed++;
        }
        return indexed;
    }

    private String extractAttachmentText(JiraService.JiraAttachment att) {
        String filename = att.filename().toLowerCase();
        String mime = att.mimeType() != null ? att.mimeType().toLowerCase() : "";

        boolean isText = TEXT_MIME_TYPES.contains(mime)
                || TEXT_EXTENSIONS.stream().anyMatch(filename::endsWith);
        boolean isPdf = mime.contains("pdf") || filename.endsWith(".pdf");

        if (!isText && !isPdf) return null;

        byte[] data = jiraService.downloadAttachment(att.contentUrl());
        if (data == null || data.length == 0) return null;

        if (isPdf) {
            try (PDDocument doc = Loader.loadPDF(data)) {
                return new PDFTextStripper().getText(doc);
            } catch (Exception e) {
                LOG.warnf("Failed to extract PDF text from %s: %s", att.filename(), e.getMessage());
                return null;
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private boolean embedAndStore(KnowledgeEmbeddingStore.KnowledgeChunk chunk) {
        if (!voyageService.isConfigured()) {
            LOG.warn("Voyage AI not configured — skipping embedding for " + chunk.sourceId());
            return false;
        }
        float[] embedding = voyageService.embedSingle(chunk.contentChunk(), "document");
        if (embedding == null) {
            LOG.warnf("Failed to generate embedding for %s/%s", chunk.sourceType(), chunk.sourceId());
            return false;
        }
        store.upsert(chunk, embedding);
        return true;
    }

    private static String buildIssueText(JiraService.JiraIssueDetail issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("Issue: ").append(issue.key()).append("\n");
        sb.append("Summary: ").append(issue.summary()).append("\n");
        if (issue.status() != null && !issue.status().isBlank()) {
            sb.append("Status: ").append(issue.status()).append("\n");
        }
        if (issue.assignee() != null && !issue.assignee().isBlank()) {
            sb.append("Assignee: ").append(issue.assignee()).append("\n");
        }
        if (issue.labels() != null && !issue.labels().isEmpty()) {
            sb.append("Labels: ").append(String.join(", ", issue.labels())).append("\n");
        }
        if (issue.description() != null && !issue.description().isBlank()) {
            sb.append("\nDescription:\n").append(issue.description()).append("\n");
        }
        if (issue.comments() != null && !issue.comments().isEmpty()) {
            sb.append("\nComments:\n");
            for (String c : issue.comments()) {
                sb.append("- ").append(c).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Splits text into chunks of at most {@code maxChars} characters,
     * trying to break on paragraph boundaries first.
     */
    private static List<String> splitIntoChunks(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= maxChars) {
            chunks.add(text);
            return chunks;
        }
        String[] paragraphs = text.split("\n\n+");
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            if (current.length() + para.length() + 2 > maxChars && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (para.length() > maxChars) {
                // Hard-split oversized paragraph
                int start = 0;
                while (start < para.length()) {
                    int end = Math.min(start + maxChars, para.length());
                    chunks.add(para.substring(start, end).trim());
                    start = end;
                }
            } else {
                if (!current.isEmpty()) current.append("\n\n");
                current.append(para);
            }
        }
        if (!current.isEmpty()) chunks.add(current.toString().trim());
        return chunks;
    }
}
