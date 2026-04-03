package com.eneve.agent.agent.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.eneve.agent.agent.SecretRedactor;
import com.eneve.agent.agent.model.StaticFileSource;
import com.eneve.agent.agent.model.WebDocSource;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.IntegrationFilterStore;
import com.eneve.agent.agent.store.KnowledgeEmbeddingStore;
import com.eneve.agent.agent.store.KnowledgeQualityBlacklistStore;
import com.eneve.agent.agent.store.StaticFileSourceStore;
import com.eneve.agent.agent.store.WebDocSourceStore;
import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates knowledge indexing from Jira and Confluence.
 * For each source it produces {@link KnowledgeEmbeddingStore.KnowledgeChunk}s,
 * embeds them via {@link BedrockEmbeddingService}, and persists via
 * {@link KnowledgeEmbeddingStore}.
 */
@ApplicationScoped
public class KnowledgeIndexerService {

    private static final Logger LOG = Logger.getLogger(KnowledgeIndexerService.class);

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
    @Inject BedrockEmbeddingService bedrockService;
    @Inject KnowledgeEmbeddingStore store;
    @Inject KnowledgeQualityBlacklistStore qualityBlacklist;
    @Inject CustomerRegistryStore registryStore;
    @Inject IntegrationFilterStore integrationFilterStore;
    @Inject SettingsService settingsService;
    @Inject WebDocsCrawlerService crawlerService;
    @Inject WebDocSourceStore webDocSourceStore;
    @Inject StaticFileSourceStore staticFileStore;
    @Inject S3Client s3Client;
    @Inject AnthropicClient anthropicClient;

    @ConfigProperty(name = "attachment.s3.bucket", defaultValue = "code-agent-attachments")
    String s3Bucket;

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
     * <p>Three optional quality filters are applied before embedding:
     * <ol>
     *   <li><b>JQL extra conditions</b> ({@code knowledge.indexer.jira-jql-extra}) – appended to
     *       the base JQL so Jira discards empty/stub issues before they are even fetched.
     *       Default: {@code description is not EMPTY}.</li>
     *   <li><b>Minimum character count</b> ({@code knowledge.indexer.jira-min-chars}) – issues
     *       whose combined text (summary + description + comments) is shorter than this threshold
     *       are skipped locally. Default: {@code 100}.</li>
     *   <li><b>Claude quality score</b> ({@code knowledge.indexer.jira-quality-filter=true}) –
     *       a fast Claude Haiku call classifies the ticket as useful/not-useful. Disabled by
     *       default because it adds latency and incurs extra API cost.</li>
     * </ol>
     *
     * @param projectKey Jira project key (e.g. "ENG")
     */
    public IndexResult indexJiraProject(String projectKey) {
        LOG.infof("Starting Jira indexing for project %s", projectKey);
        int indexed = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        int jiraMaxResults = Integer.parseInt(settingsService.get("knowledge.indexer.jira-max-results", "200"));
        String jqlExtra = settingsService.get("knowledge.indexer.jira-jql-extra", "description is not EMPTY");
        int minChars = Integer.parseInt(settingsService.get("knowledge.indexer.jira-min-chars", "100"));
        boolean qualityFilter = Boolean.parseBoolean(settingsService.get("knowledge.indexer.jira-quality-filter", "false"));

        String jql = "project = \"" + projectKey + "\"";
        if (jqlExtra != null && !jqlExtra.isBlank()) {
            jql += " AND (" + jqlExtra.trim() + ")";
        }
        jql += " ORDER BY created DESC";

        LOG.debugf("Jira JQL: %s (maxResults=%d, minChars=%d, qualityFilter=%b)", jql, jiraMaxResults, minChars, qualityFilter);
        List<JiraService.JiraIssueDetail> issues = jiraService.searchIssues(jql, jiraMaxResults);

        // Track Confluence pages already scheduled to avoid re-indexing from multiple issues
        Set<String> scheduledConfluencePageIds = new java.util.HashSet<>();

        for (JiraService.JiraIssueDetail issue : issues) {
            try {
                // 1. Main issue chunk: summary + description + comments
                String issueText = SecretRedactor.redact(buildIssueText(issue));
                if (issueText.isBlank()) {
                    skipped++;
                    continue;
                }

                // 2. Minimum character guard — skip tickets that are too short to be useful
                if (issueText.length() < minChars) {
                    LOG.debugf("Skipping %s: text length %d below threshold %d", issue.key(), issueText.length(), minChars);
                    skipped++;
                    continue;
                }

                // 3. Claude quality filter — check blacklist first, then call Claude if needed
                if (qualityFilter) {
                    String contentHash = KnowledgeQualityBlacklistStore.md5(issueText);
                    if (qualityBlacklist.isBlacklisted("jira", issue.key(), contentHash)) {
                        LOG.debugf("Skipping %s: present in quality blacklist", issue.key());
                        skipped++;
                        continue;
                    }
                    if (!isHighQualityTicket(issue.key(), issueText)) {
                        LOG.debugf("Skipping %s: classified as low quality by Claude — adding to blacklist", issue.key());
                        qualityBlacklist.add("jira", issue.key(), contentHash, "claude-quality-filter");
                        skipped++;
                        continue;
                    }
                }

                var chunk = new KnowledgeEmbeddingStore.KnowledgeChunk(
                        "jira",
                        issue.key(),
                        issue.summary(),
                        issueText,
                        Map.of(
                                "status", issue.status() != null ? issue.status() : "",
                                "reporter", issue.reporter() != null ? issue.reporter() : "",
                                "assignee", issue.assignee() != null ? issue.assignee() : "",
                                "labels", issue.labels() != null ? String.join(",", issue.labels()) : "",
                                "url", ""
                        )
                );
                if (embedAndStore(chunk)) indexed++; else skipped++;

                // 2. Attachments
                if (issue.attachments() != null) {
                    for (JiraService.JiraAttachment att : issue.attachments()) {
                        try {
                            int attResult = indexAttachment(att, issue.key());
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
                        int pageResult = indexConfluencePage(pageId, link.title());
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
     * Index (or reindex) a single Jira issue, including its attachments and linked
     * Confluence pages. Existing chunks for the issue are deleted first so that
     * stale content (edited description, removed comments, replaced attachments)
     * does not persist in the knowledge store.
     *
     * @param issueKey Jira issue key (e.g. "ENG-123")
     */
    public IndexResult indexJiraIssue(String issueKey) {
        LOG.infof("Starting single-issue Jira indexing for %s", issueKey);
        int indexed = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        // Delete stale chunks before re-indexing
        store.deleteBySource("jira", issueKey);
        store.deleteBySourceIdPrefix("jira-attachment", issueKey + "/attachment/");

        String jql = "issue = \"" + issueKey + "\"";
        List<JiraService.JiraIssueDetail> issues = jiraService.searchIssues(jql, 1);
        if (issues.isEmpty()) {
            LOG.warnf("Single-issue indexing: issue %s not found via JQL", issueKey);
            return new IndexResult("jira", issueKey, 0, 0, List.of("Issue not found: " + issueKey));
        }

        JiraService.JiraIssueDetail issue = issues.get(0);
        try {
            String issueText = SecretRedactor.redact(buildIssueText(issue));
            if (!issueText.isBlank()) {
                var chunk = new KnowledgeEmbeddingStore.KnowledgeChunk(
                        "jira",
                        issue.key(),
                        issue.summary(),
                        issueText,
                        Map.of(
                                "status", issue.status() != null ? issue.status() : "",
                                "reporter", issue.reporter() != null ? issue.reporter() : "",
                                "assignee", issue.assignee() != null ? issue.assignee() : "",
                                "labels", issue.labels() != null ? String.join(",", issue.labels()) : "",
                                "url", ""
                        )
                );
                if (embedAndStore(chunk)) indexed++; else skipped++;
            }

            if (issue.attachments() != null) {
                for (JiraService.JiraAttachment att : issue.attachments()) {
                    try {
                        int attResult = indexAttachment(att, issue.key());
                        if (attResult > 0) indexed += attResult;
                        else if (attResult == 0) skipped++;
                    } catch (Exception e) {
                        errors.add("Attachment " + att.filename() + " on " + issue.key() + ": " + e.getMessage());
                    }
                }
            }

            List<JiraService.JiraRemoteLink> remoteLinks = jiraService.fetchRemoteLinks(issue.key());
            for (JiraService.JiraRemoteLink link : remoteLinks) {
                String pageId = confluenceService.extractPageIdFromUrl(link.url());
                if (pageId == null) continue;
                try {
                    int pageResult = indexConfluencePage(pageId, link.title());
                    indexed += pageResult;
                } catch (Exception e) {
                    errors.add("Confluence page " + pageId + " linked from " + issue.key() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("Issue " + issue.key() + ": " + e.getMessage());
            LOG.warnf("Failed to index Jira issue %s: %s", issue.key(), e.getMessage());
        }

        LOG.infof("Single-issue Jira indexing complete for %s: indexed=%d, skipped=%d, errors=%d",
                issueKey, indexed, skipped, errors.size());
        return new IndexResult("jira", issueKey, indexed, skipped, errors);
    }

    /**
     * Index all pages in a Confluence space, chunked by heading section.
     *
     * @param spaceKey Confluence space key
     */
    public IndexResult indexConfluenceSpace(String spaceKey) {
        LOG.infof("Starting Confluence indexing for space %s", spaceKey);
        int indexed = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        List<ConfluenceService.ConfluencePage> pages = confluenceService.listPagesInSpace(spaceKey);
        for (ConfluenceService.ConfluencePage page : pages) {
            try {
                int result = indexConfluencePage(page.pageId(), page.title());
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

        if (product.jira() != null && product.jira().projects() != null) {
            for (String projectKey : product.jira().projects().values()) {
                if (projectKey != null && !projectKey.isBlank()) {
                    if (!integrationFilterStore.isEnabled("jira", projectKey)) {
                        LOG.infof("Skipping disabled Jira project %s for product %s", projectKey, product.productId());
                        continue;
                    }
                    results.add(indexJiraProject(projectKey));
                }
            }
        }
        if (product.confluence() != null
                && product.confluence().spaceKey() != null
                && !product.confluence().spaceKey().isBlank()) {
            String spaceKey = product.confluence().spaceKey();
            if (!integrationFilterStore.isEnabled("confluence", spaceKey)) {
                LOG.infof("Skipping disabled Confluence space %s for product %s", spaceKey, product.productId());
            } else {
                results.add(indexConfluenceSpace(spaceKey));
            }
        }
        return results;
    }

    /**
     * Crawl and index a single web documentation source.
     * Deletes all previously indexed chunks for this source before crawling (delete-before-crawl),
     * then embeds and stores the fresh content.
     *
     * @param source the {@link WebDocSource} to crawl
     * @return indexing result with chunk counts and any errors
     */
    public IndexResult indexWebDocSource(WebDocSource source) {
        LOG.infof("Starting web-docs crawl for %s (maxPages=%d)", source.baseUrl(), source.maxPages());
        List<String> errors = new ArrayList<>();

        // Delete stale chunks before re-crawling
        int deleted = store.deleteBySourceIdPrefix("web-docs", source.baseUrl());
        LOG.debugf("Deleted %d stale web-docs chunks for %s", deleted, source.baseUrl());

        List<WebDocsCrawlerService.WebPage> pages;
        try {
            pages = crawlerService.crawl(source);
        } catch (Exception e) {
            String msg = "Crawl failed for " + source.baseUrl() + ": " + e.getMessage();
            LOG.warnf(msg);
            webDocSourceStore.updateCrawlResult(source.id(), 0, msg);
            return new IndexResult("web-docs", source.baseUrl(), 0, 0, List.of(msg));
        }

        int indexed = 0;
        int skipped = 0;

        for (WebDocsCrawlerService.WebPage page : pages) {
            try {
                String text = SecretRedactor.redact(page.textContent());
                if (text == null || text.isBlank()) {
                    skipped++;
                    continue;
                }
                List<String> chunks = splitIntoChunks(text, CONFLUENCE_CHUNK_CHARS);
                for (int i = 0; i < chunks.size(); i++) {
                    String chunkId = chunks.size() > 1
                            ? page.url() + "#chunk/" + i
                            : page.url();
                    var chunk = new KnowledgeEmbeddingStore.KnowledgeChunk(
                            "web-docs",
                            chunkId,
                            page.title(),
                            chunks.get(i),
                            Map.of("url", page.url())
                    );
                    if (embedAndStore(chunk)) indexed++; else skipped++;
                }
            } catch (Exception e) {
                errors.add("Page " + page.url() + ": " + e.getMessage());
                LOG.warnf("Failed to index web page %s: %s", page.url(), e.getMessage());
            }
        }

        String errorSummary = errors.isEmpty() ? null : errors.size() + " page error(s)";
        webDocSourceStore.updateCrawlResult(source.id(), indexed, errorSummary);

        LOG.infof("Web-docs crawl complete for %s: indexed=%d, skipped=%d, errors=%d",
                source.baseUrl(), indexed, skipped, errors.size());
        return new IndexResult("web-docs", source.baseUrl(), indexed, skipped, errors);
    }

    /**
     * Crawl and index all registered web documentation sources.
     *
     * @return list of results, one per source
     */
    public List<IndexResult> indexAllWebDocSources() {
        List<WebDocSource> sources = webDocSourceStore.listAll();
        List<IndexResult> results = new ArrayList<>();
        for (WebDocSource source : sources) {
            results.add(indexWebDocSource(source));
        }
        return results;
    }

    /**
     * Index a single admin-uploaded static file.
     *
     * <p>Downloads the raw bytes from S3, extracts text via
     * {@link #extractStaticFileText(String, String, byte[])}, chunks and embeds
     * the result, then updates the {@code static_file_sources} row with the outcome.
     *
     * @param source the {@link StaticFileSource} to index
     * @return indexing result with chunk counts and any errors
     */
    public IndexResult indexStaticFile(StaticFileSource source) {
        LOG.infof("Indexing static file: %s (%s)", source.originalFilename(), source.id());
        List<String> errors = new ArrayList<>();

        // Delete stale embeddings before re-indexing
        int deleted = store.deleteBySource("static-file", source.id());
        LOG.debugf("Deleted %d stale static-file chunks for %s", deleted, source.id());

        byte[] data;
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(s3Bucket).key(source.s3Key()).build());
            data = response.asByteArray();
        } catch (Exception e) {
            String msg = "Failed to download static file from S3 (" + source.s3Key() + "): " + e.getMessage();
            LOG.warnf(msg);
            staticFileStore.updateIndexResult(source.id(), 0, msg);
            return new IndexResult("static-file", source.id(), 0, 0, List.of(msg));
        }

        String text = SecretRedactor.redact(extractStaticFileText(
                source.originalFilename(), source.contentType(), data));

        if (text == null || text.isBlank()) {
            String msg = "No text extracted from file: " + source.originalFilename();
            LOG.warnf(msg);
            staticFileStore.updateIndexResult(source.id(), 0, msg);
            return new IndexResult("static-file", source.id(), 0, 1, List.of(msg));
        }

        List<String> chunks = splitIntoChunks(text, CONFLUENCE_CHUNK_CHARS);
        int indexed = 0;
        int skipped = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = chunks.size() > 1
                    ? source.id() + "/chunk/" + i
                    : source.id();
            var chunk = new KnowledgeEmbeddingStore.KnowledgeChunk(
                    "static-file",
                    chunkId,
                    source.name(),
                    chunks.get(i),
                    Map.of(
                            "originalFilename", source.originalFilename(),
                            "contentType", source.contentType()
                    )
            );
            if (embedAndStore(chunk)) indexed++; else skipped++;
        }

        String errorSummary = errors.isEmpty() ? null : errors.size() + " error(s)";
        staticFileStore.updateIndexResult(source.id(), indexed, errorSummary);

        LOG.infof("Static file indexing complete for %s: indexed=%d, skipped=%d",
                source.originalFilename(), indexed, skipped);
        return new IndexResult("static-file", source.id(), indexed, skipped, errors);
    }

    /**
     * Index all registered static file sources.
     *
     * @return list of results, one per source
     */
    public List<IndexResult> indexAllStaticFiles() {
        List<StaticFileSource> sources = staticFileStore.listAll();
        List<IndexResult> results = new ArrayList<>();
        for (StaticFileSource source : sources) {
            results.add(indexStaticFile(source));
        }
        return results;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    int indexConfluencePage(String pageId, String title) {
        String body = SecretRedactor.redact(confluenceService.getPageBody(pageId));
        if (body == null || body.isBlank()) return 0;

        List<String> chunks = splitIntoChunks(body, CONFLUENCE_CHUNK_CHARS);
        int indexed = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            String chunkId = chunks.size() > 1 ? pageId + "/chunk/" + i : pageId;
            var chunk = new KnowledgeEmbeddingStore.KnowledgeChunk(
                    "confluence",
                    chunkId,
                    title,
                    chunkText,
                    Map.of("pageId", pageId, "chunkIndex", i)
            );
            if (embedAndStore(chunk)) indexed++;
        }
        return indexed;
    }

    private int indexAttachment(JiraService.JiraAttachment att, String issueKey) {
        long maxAttachmentBytes = Long.parseLong(settingsService.get("knowledge.indexer.max-attachment-bytes", "5242880"));
        if (att.size() > maxAttachmentBytes) {
            LOG.debugf("Skipping large attachment %s (%d bytes) on %s", att.filename(), att.size(), issueKey);
            return 0;
        }

        String text = SecretRedactor.redact(extractAttachmentText(att));
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

    /**
     * Calls Claude Haiku to decide whether a Jira ticket contains enough context
     * to be worth indexing. A ticket is considered useful when it has a clear
     * problem statement or acceptance criteria that a developer could act on.
     *
     * <p>The method returns {@code true} when Claude answers "YES" (or the call
     * fails, so that network errors never silently drop legitimate tickets).
     *
     * <p>Only invoked when {@code knowledge.indexer.jira-quality-filter=true}.
     *
     * @param issueKey  Jira issue key, used only for log messages
     * @param issueText full issue text produced by {@link #buildIssueText}
     * @return {@code true} if the ticket should be indexed, {@code false} if it
     *         should be silently skipped
     */
    private boolean isHighQualityTicket(String issueKey, String issueText) {
        String model = settingsService.get("knowledge.indexer.jira-quality-model", "claude-haiku-4-5");
        // Truncate to ~2 000 chars to keep the Haiku call cheap
        String excerpt = issueText.length() > 2000 ? issueText.substring(0, 2000) + "\n…" : issueText;
        String prompt = """
                You are a triage assistant evaluating whether a Jira ticket contains \
                enough information to be useful context for a code reviewer or developer.

                A ticket is USEFUL if it has at least ONE of:
                - A concrete description of what needs to be done or what went wrong
                - Reproduction steps, acceptance criteria, or expected vs. actual behaviour
                - Enough domain context for a developer to understand the purpose

                A ticket is NOT USEFUL if it is:
                - Just a title with no description (e.g. "Fix bug", "Update screen")
                - A placeholder, template stub, or test ticket
                - Fewer than 2 meaningful sentences of context

                Reply with exactly one word: YES or NO.

                Ticket:
                """ + excerpt;

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(model))
                .maxTokens(5)
                .messages(List.of(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(prompt)
                                .build()
                ))
                .build();

        try {
            Message response = anthropicClient.messages().create(params);
            String answer = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(b -> b.asText().text().trim().toUpperCase())
                    .findFirst()
                    .orElse("YES");
            boolean useful = answer.startsWith("YES");
            LOG.debugf("Quality filter for %s: %s → %s", issueKey, answer, useful ? "KEEP" : "DROP");
            return useful;
        } catch (Exception e) {
            LOG.warnf("Quality filter call failed for %s (%s) — keeping ticket to avoid data loss", issueKey, e.getMessage());
            return true;
        }
    }

    private boolean embedAndStore(KnowledgeEmbeddingStore.KnowledgeChunk chunk) {
        if (!bedrockService.isConfigured()) {
            LOG.warn("Bedrock embedding not configured — skipping embedding for " + chunk.sourceId());
            return false;
        }
        if (store.isContentIndexed(chunk.sourceType(), chunk.sourceId(), chunk.contentChunk())) {
            LOG.debugf("Skipping unchanged chunk %s/%s", chunk.sourceType(), chunk.sourceId());
            return true;
        }
        float[] embedding = bedrockService.embedSingleText(chunk.contentChunk(), "document");
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
     * Extracts plain text from a static-file upload.
     *
     * <p>Supported types:
     * <ul>
     *   <li>{@code .pdf} / {@code application/pdf} — text extracted via PDFBox</li>
     *   <li>{@code .txt}, {@code .md}, and any MIME type in {@link #TEXT_MIME_TYPES} — decoded as UTF-8</li>
     * </ul>
     *
     * <p>Package-visible so it can be exercised by unit tests without a CDI container.
     *
     * @param filename    original filename (used for extension detection)
     * @param contentType MIME content-type reported by the multipart upload
     * @param data        raw file bytes
     * @return extracted plain text, or {@code null} if the type is unsupported or extraction fails
     */
    static String extractStaticFileText(String filename, String contentType, byte[] data) {
        if (data == null || data.length == 0) return null;

        String lower = filename != null ? filename.toLowerCase() : "";
        String mime  = contentType != null ? contentType.toLowerCase() : "";

        boolean isPdf  = mime.contains("pdf") || lower.endsWith(".pdf");
        boolean isText = lower.endsWith(".txt") || lower.endsWith(".md")
                || TEXT_MIME_TYPES.contains(mime);

        if (isPdf) {
            try (PDDocument doc = Loader.loadPDF(data)) {
                return new PDFTextStripper().getText(doc);
            } catch (Exception e) {
                LOG.warnf("Failed to extract PDF text from %s: %s", filename, e.getMessage());
                return null;
            }
        }
        if (isText) {
            return new String(data, StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Splits text into chunks of at most {@code maxChars} characters,
     * trying to break on paragraph boundaries first.
     */
    static List<String> splitIntoChunks(String text, int maxChars) {
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
