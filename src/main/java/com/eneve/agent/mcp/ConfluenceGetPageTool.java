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
 * MCP tool: Get a Confluence page by ID.
 */
@ApplicationScoped
public class ConfluenceGetPageTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(ConfluenceGetPageTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    ConfluenceService confluenceService;

    @Override
    public String name() {
        return "confluence_get_page";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String pageId = (String) input.get("pageId");
        if (pageId == null || pageId.isBlank()) {
            return "ERROR: 'pageId' parameter is required";
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
            ConfluenceService.PageContent page = confluenceService.getPage(pageId, creds.get());
            if (page == null) {
                return "Page not found: " + pageId;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Title: ").append(page.title()).append("\n");
            sb.append("Page ID: ").append(page.pageId()).append("\n");
            sb.append("URL: ").append(page.url()).append("\n\n");
            if (page.body() != null && !page.body().isBlank()) {
                sb.append("Content:\n").append(page.body());
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Confluence get page failed: %s", e.getMessage());
            return "ERROR: Failed to get page: " + e.getMessage();
        }
    }
}
