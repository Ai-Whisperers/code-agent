package com.eneve.agent.tools;

import java.util.Map;

import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Agent tool that publishes a Markdown document to a Confluence page.
 * The space key and parent page ID default to workspace metadata (set by
 * executeGenerateDocs) but can be overridden via tool parameters.
 */
@ApplicationScoped
public class PublishConfluenceTool implements ToolExecutor {

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

        String parentPageId = (String) input.get("parent_page_id");
        if (parentPageId == null || parentPageId.isBlank()) {
            parentPageId = workspace.getMetadata("confluenceParentPageId");
        }

        String pageUrl = confluenceService.createOrUpdatePage(spaceKey, parentPageId, title, markdownContent);

        if (pageUrl == null) {
            return "ERROR: Failed to publish page '" + title + "' to Confluence space " + spaceKey;
        }

        return "Published Confluence page: " + title + "\nURL: " + pageUrl;
    }
}
