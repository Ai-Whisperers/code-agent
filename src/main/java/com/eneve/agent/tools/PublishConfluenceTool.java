package com.eneve.agent.tools;

import java.util.Map;

import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Agent tool that publishes a Markdown document to a Confluence page.
 * <p>
 * The first page published becomes the root documentation page. All subsequent
 * pages are created as children of that root, forming a single-level hierarchy
 * under the configured parent page.
 */
@ApplicationScoped
public class PublishConfluenceTool implements ToolExecutor {

    private static final String META_DOCS_ROOT_PAGE_ID = "confluenceDocsRootPageId";

    @Inject
    ConfluenceService confluenceService;

    @Override
    public String name() {
        return "publish_confluence";
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        if (!confluenceService.isEnabled()) {
            return "ERROR: Confluence is not configured (set CONFLUENCE_BASE_URL, CONFLUENCE_USER, CONFLUENCE_API_TOKEN)";
        }

        String title = (String) input.get("title");
        if (title == null || title.isBlank()) {
            return "ERROR: 'title' parameter is required";
        }

        String markdownContent = (String) input.get("markdown_content");
        if (markdownContent == null || markdownContent.isBlank()) {
            return "ERROR: 'markdown_content' parameter is required";
        }

        String spaceKey = (String) input.get("space_key");
        if (spaceKey == null || spaceKey.isBlank()) {
            spaceKey = workspace.getMetadata("confluenceSpaceKey");
        }
        if (spaceKey == null || spaceKey.isBlank()) {
            return "ERROR: No Confluence space key available. Provide 'space_key' parameter or configure it in repo settings.";
        }

        String parentPageId = resolveParentPageId(workspace, input);

        ConfluenceService.PageResult result =
                confluenceService.createOrUpdatePage(spaceKey, parentPageId, title, markdownContent);

        if (result == null) {
            return "ERROR: Failed to publish page '" + title + "' to Confluence space " + spaceKey;
        }

        if (workspace.getMetadata(META_DOCS_ROOT_PAGE_ID) == null) {
            workspace.putMetadata(META_DOCS_ROOT_PAGE_ID, result.pageId());
        }

        return "Published Confluence page: " + title + "\nURL: " + result.pageUrl();
    }

    /**
     * Determines the parent page for the current publish call.
     * - If a docs root page has already been published in this session, use it.
     * - Otherwise fall back to the tool parameter or repo-settings parent page.
     */
    private String resolveParentPageId(WorkspaceContext workspace, Map<String, Object> input) {
        String docsRoot = workspace.getMetadata(META_DOCS_ROOT_PAGE_ID);
        if (docsRoot != null) {
            return docsRoot;
        }

        String explicit = (String) input.get("parent_page_id");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }

        return workspace.getMetadata("confluenceParentPageId");
    }
}
