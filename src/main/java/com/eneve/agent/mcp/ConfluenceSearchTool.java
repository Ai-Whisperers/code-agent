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
 * MCP tool: Search Confluence pages using CQL.
 */
@ApplicationScoped
public class ConfluenceSearchTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(ConfluenceSearchTool.class);

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    ConfluenceService confluenceService;

    @Override
    public String name() {
        return "confluence_search";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String query = (String) input.get("query");
        String spaceKey = (String) input.get("spaceKey");

        if (query == null || query.isBlank()) {
            return "ERROR: 'query' parameter is required";
        }

        int maxResults = 10;
        Object maxObj = input.get("maxResults");
        if (maxObj instanceof Number n) {
            maxResults = Math.min(Math.max(1, n.intValue()), 50);
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
            String cql = spaceKey != null && !spaceKey.isBlank()
                    ? "space = " + spaceKey + " AND text ~ \"" + query + "\""
                    : "text ~ \"" + query + "\"";
            java.util.List<ConfluenceService.ConfluencePage> pages = confluenceService.searchPages(cql, maxResults, creds.get());
            if (pages.isEmpty()) {
                return "No pages found for query: " + query;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(pages.size()).append(" page(s):\n\n");
            for (ConfluenceService.ConfluencePage page : pages) {
                sb.append("- ").append(page.title()).append("\n");
                sb.append("  Page ID: ").append(page.pageId()).append("\n");
                sb.append("  URL: ").append(page.url()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Confluence search failed: %s", e.getMessage());
            return "ERROR: Failed to search Confluence: " + e.getMessage();
        }
    }
}
