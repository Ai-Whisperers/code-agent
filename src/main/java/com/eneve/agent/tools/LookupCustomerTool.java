package com.eneve.agent.tools;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.TeamMember;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Claude tool that resolves customer/product context from the registry —
 * team members by role, environments with AWS account IDs, Jira projects,
 * and Confluence spaces.
 */
@ApplicationScoped
public class LookupCustomerTool implements ToolExecutor {

    @Inject
    CustomerRegistryStore registryStore;

    @Override
    public String name() {
        return "lookup_customer_context";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String productId = (String) input.get("productId");
        String customerId = (String) input.get("customerId");
        String jiraProject = (String) input.get("jiraProject");

        if (productId == null && customerId == null && jiraProject == null) {
            List<ProductConfig> all = registryStore.listAllProducts();
            if (all.isEmpty()) {
                return "No products are configured in the registry.";
            }
            StringBuilder sb = new StringBuilder("Available products:\n\n");
            for (ProductConfig p : all) {
                sb.append("- **").append(p.displayName())
                  .append("** (`").append(p.productId()).append("`)");
                if (p.git() != null) {
                    if (p.git().workspace() != null) {
                        sb.append(" — git workspace: `").append(p.git().workspace()).append("`");
                    }
                    if (p.git().repos() != null && !p.git().repos().isEmpty()) {
                        sb.append(" — repos: ").append(String.join(", ", p.git().repos()));
                    }
                }
                sb.append("\n");
            }
            sb.append("\nUse the `productId` to narrow a follow-up lookup, or use the repo slugs directly with `query_code_graph` and `semantic_search`.");
            return sb.toString();
        }

        // Resolve to a ProductConfig
        ProductConfig product = null;

        if (productId != null && !productId.isBlank()) {
            product = registryStore.getProduct(productId).orElse(null);
        }

        if (product == null && jiraProject != null && !jiraProject.isBlank()) {
            product = registryStore.findByJiraProject(jiraProject).orElse(null);
        }

        if (product == null && customerId != null && !customerId.isBlank()) {
            // Return all products for the customer
            List<ProductConfig> products = registryStore.listProducts(customerId);
            if (products.isEmpty()) {
                return "No products found for customer: " + customerId;
            }
            if (products.size() == 1) {
                product = products.get(0);
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("Customer has ").append(products.size())
                  .append(" products. Use productId to narrow the lookup.\n\n");
                for (ProductConfig p : products) {
                    sb.append("- ").append(p.productId()).append(": ").append(p.displayName()).append("\n");
                }
                return sb.toString();
            }
        }

        if (product == null) {
            return "No product found matching the provided identifiers.";
        }

        // Set workspace metadata to enable auto-discovery for other tools
        if (product.git() != null && product.git().workspace() != null) {
            workspace.putMetadata("workspace", product.git().workspace());
            if (product.git().repos() != null && !product.git().repos().isEmpty()) {
                workspace.putMetadata("productRepos", String.join(",", product.git().repos()));
                // Default to first repo for auto-discovery, can be overridden by tools
                workspace.putMetadata("repoSlug", product.git().repos().get(0));
            }
        }

        return formatProduct(product);
    }

    private String formatProduct(ProductConfig p) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Product: ").append(p.displayName())
          .append(" (").append(p.productId()).append(")\n");
        sb.append("Customer: ").append(p.customerId()).append("\n\n");

        // Teams
        if (p.teams() != null && !p.teams().isEmpty()) {
            sb.append("### Team\n");
            for (Map.Entry<String, List<TeamMember>> entry : p.teams().entrySet()) {
                sb.append("**").append(entry.getKey()).append("**:\n");
                for (TeamMember m : entry.getValue()) {
                    sb.append("  - ").append(m.name());
                    if (m.email() != null) sb.append(" <").append(m.email()).append(">");
                    if (m.jiraAccountId() != null) sb.append(" (Jira: ").append(m.jiraAccountId()).append(")");
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        // Environments (from customer)
        if (p.customerId() != null) {
            registryStore.getCustomer(p.customerId()).ifPresent(customer -> {
                if (customer.environments() != null && !customer.environments().isEmpty()) {
                    sb.append("### Environments\n");
                    for (EnvironmentConfig env : customer.environments()) {
                        sb.append("**").append(env.name()).append("**:\n");
                        if (env.aws() != null) {
                            sb.append("  - AWS Account: ").append(env.aws().accountId()).append("\n");
                            sb.append("  - Region: ").append(env.aws().region()).append("\n");
                            if (env.aws().iamRole() != null) {
                                sb.append("  - IAM Role: ").append(env.aws().iamRole()).append("\n");
                            }
                        }
                    }
                    sb.append("\n");
            });
        }

        // Jira
        if (p.jira() != null && p.jira().projects() != null && !p.jira().projects().isEmpty()) {
            sb.append("### Jira Projects\n");
            for (Map.Entry<String, String> e : p.jira().projects().entrySet()) {
                sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            sb.append("\n");
        }

        // Confluence
        if (p.confluence() != null && p.confluence().spaceKey() != null) {
            sb.append("### Confluence\n");
            sb.append("  - Space: ").append(p.confluence().spaceKey()).append("\n");
            if (p.confluence().rootPageId() != null) {
                sb.append("  - Root page ID: ").append(p.confluence().rootPageId()).append("\n");
            }
            sb.append("\n");
        }

        // Git
        if (p.git() != null) {
            sb.append("### Git\n");
            sb.append("  - Platform: ").append(p.git().platform()).append("\n");
            sb.append("  - Workspace: ").append(p.git().workspace()).append("\n");
            sb.append("\n");
        }

        return sb.toString();
    }
}
