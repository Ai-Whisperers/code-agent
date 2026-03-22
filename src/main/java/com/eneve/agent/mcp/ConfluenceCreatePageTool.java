package com.eneve.agent.mcp;

import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * MCP tool: Create a Confluence page.
 */
@ApplicationScoped
public class ConfluenceCreatePageTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(ConfluenceCreatePageTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    ConfluenceService confluenceService;

    @Override
    public String name() {
        return "confluence_create_page";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String spaceKey = (String) input.get("spaceKey");
        String title = (String) input.get("title");
        String body = (String) input.get("body");
        String parentPageId = (String) input.get("parentPageId");

        if (spaceKey == null || spaceKey.isBlank()) {
            return "ERROR: 'spaceKey' parameter is required";
        }
        if (title == null || title.isBlank()) {
            return "ERROR: 'title' parameter is required";
        }
        if (body == null || body.isBlank()) {
            return "ERROR: 'body' parameter is required";
        }

        String userId = workspace.getUserId();
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return "ERROR: No authenticated user. Please link your Confluence account in Settings > MCP Profiles.";
        }

        Optional<ConfluenceService.ConfluenceCredentials> creds = linkedAccountService.resolveConfluence(userId);
        if (creds.isEmpty()) {
            return "ERROR: No Confluence account linked. Please link your Confluence account in Settings > MCP Profiles.";
        }

        try {
            String pageId = confluenceService.createPage(spaceKey, parentPageId, title, body, creds.get());
            if (pageId == null) {
                return "ERROR: Failed to create page";
            }
            return "Created page: " + pageId + " (" + title + ")";
        } catch (Exception e) {
            LOG.errorf("Confluence create page failed: %s", e.getMessage());
            return "ERROR: Failed to create page: " + e.getMessage();
        }
    }
}
