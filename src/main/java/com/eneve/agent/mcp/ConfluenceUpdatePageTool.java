package com.eneve.agent.mcp;

import java.util.Map;
import java.util.Optional;

import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * MCP tool: Update a Confluence page.
 */
@ApplicationScoped
public class ConfluenceUpdatePageTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(ConfluenceUpdatePageTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    ConfluenceService confluenceService;

    @Override
    public String name() {
        return "confluence_update_page";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String pageId = (String) input.get("pageId");
        String title = (String) input.get("title");
        String body = (String) input.get("body");

        if (pageId == null || pageId.isBlank()) {
            return "ERROR: 'pageId' parameter is required";
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
            boolean success = confluenceService.updatePage(pageId, title, body, creds.get());
            if (success) {
                return "Updated page: " + pageId;
            } else {
                return "ERROR: Failed to update page";
            }
        } catch (Exception e) {
            LOG.errorf("Confluence update page failed: %s", e.getMessage());
            return "ERROR: Failed to update page: " + e.getMessage();
        }
    }
}
