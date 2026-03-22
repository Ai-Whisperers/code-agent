package com.eneve.agent.tools;

import java.util.Map;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Tool that allows AI agents to explicitly set the active product context.
 * This enables clean switching between different products and their associated
 * repositories, Jira projects, Confluence spaces, etc.
 */
@ApplicationScoped
public class SetProductContextTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(SetProductContextTool.class);

    @Inject
    CustomerRegistryStore registryStore;

    @Override
    public String name() {
        return "set_product_context";
    }

    @Override
    public boolean isReadOnly() {
        return true; // Only sets workspace metadata, doesn't modify files
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String productId = (String) input.get("productId");
        if (productId == null || productId.isBlank()) {
            return "ERROR: 'productId' parameter is required. " +
                   "Call lookup_customer_context with no parameters to see available products.";
        }

        ProductConfig product = registryStore.getProduct(productId).orElse(null);
        if (product == null) {
            return "ERROR: Product '" + productId + "' not found. " +
                   "Call lookup_customer_context with no parameters to see available products.";
        }

        // Set active product context metadata
        workspace.putMetadata("activeProduct", productId);
        workspace.putMetadata("activeProductName", product.displayName());
        workspace.putMetadata("customerId", product.customerId());
        
        LOG.infof("SetProductContextTool: Set activeProduct=%s, customerId=%s", productId, product.customerId());

        StringBuilder result = new StringBuilder();
        result.append("Switched to product context: **").append(product.displayName())
              .append("** (`").append(productId).append("`)\n");
        result.append("Customer: ").append(product.customerId()).append("\n\n");

        // Set git workspace context
        if (product.git() != null && product.git().workspace() != null) {
            workspace.putMetadata("workspace", product.git().workspace());
            result.append("**Git Context:**\n");
            result.append("- Workspace: `").append(product.git().workspace()).append("`\n");
            
            if (product.git().repos() != null && !product.git().repos().isEmpty()) {
                workspace.putMetadata("productRepos", String.join(",", product.git().repos()));
                // Set primary repository as default
                workspace.putMetadata("repoSlug", product.git().repos().get(0));
                
                LOG.infof("SetProductContextTool: Set workspace=%s, productRepos=%s, repoSlug=%s", 
                         product.git().workspace(), String.join(",", product.git().repos()), product.git().repos().get(0));
                
                result.append("- Repositories: ").append(String.join(", ", product.git().repos())).append("\n");
                result.append("- Primary repo: `").append(product.git().repos().get(0)).append("`\n");
            }
        } else {
            result.append("**Git Context:** Not configured\n");
        }

        // Set Jira context
        if (product.jira() != null && product.jira().projects() != null && !product.jira().projects().isEmpty()) {
            result.append("\n**Jira Projects:**\n");
            for (Map.Entry<String, String> entry : product.jira().projects().entrySet()) {
                workspace.putMetadata("jiraProject_" + entry.getKey(), entry.getValue());
                result.append("- ").append(entry.getKey()).append(": `").append(entry.getValue()).append("`\n");
            }
            // Set first project as default
            String firstProject = product.jira().projects().keySet().iterator().next();
            workspace.putMetadata("jiraProject", firstProject);
            workspace.putMetadata("jiraProjectKey", product.jira().projects().get(firstProject));
        }

        // Set Confluence context
        if (product.confluence() != null) {
            if (product.confluence().spaceKey() != null) {
                workspace.putMetadata("confluenceSpaceKey", product.confluence().spaceKey());
                result.append("\n**Confluence:**\n");
                result.append("- Space: `").append(product.confluence().spaceKey()).append("`\n");
            }
            if (product.confluence().rootPageId() != null) {
                workspace.putMetadata("confluenceParentPageId", product.confluence().rootPageId());
                result.append("- Root page ID: `").append(product.confluence().rootPageId()).append("`\n");
            }
        }

        result.append("\nContext is now set for tools like `search_code`, `semantic_search`, `query_code_graph`, and others.");
        
        return result.toString();
    }
}
