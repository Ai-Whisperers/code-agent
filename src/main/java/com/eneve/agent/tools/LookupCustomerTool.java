package com.eneve.agent.tools;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.store.CloudAccountStore;
import com.eneve.agent.agent.store.ConversationContextStore;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.TeamStore;
import com.eneve.agent.model.CloudAccount;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.Team;
import com.eneve.agent.model.TeamMemberEntry;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Claude tool that resolves a customer name (or ID) to its full context:
 * deployment environments with AWS account IDs / IAM roles, associated
 * products (git/jira/confluence), team members, and cloud account credentials.
 *
 * <p>After a successful lookup the active {@code customerId} is stored in the
 * workspace so downstream AWS tools ({@code aws_ecs}, {@code aws_cloudwatch_metrics},
 * etc.) can pick it up without requiring the AI to repeat it.
 */
@ApplicationScoped
public class LookupCustomerTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(LookupCustomerTool.class);

    @Inject
    CustomerRegistryStore registryStore;

    @Inject
    CloudAccountStore cloudAccountStore;

    @Inject
    ConversationContextStore contextStore;

    @Inject
    TeamStore teamStore;

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
        String customerName = (String) input.get("customerName");
        String customerId   = (String) input.get("customerId");
        String jiraProject  = (String) input.get("jiraProject");
        String productId    = (String) input.get("productId");

        // ── No parameters → list all customers ───────────────────────────────
        if (customerName == null && customerId == null && jiraProject == null && productId == null) {
            List<CustomerConfig> all = registryStore.listCustomers();
            if (all.isEmpty()) {
                return "No customers are configured in the registry.";
            }
            StringBuilder sb = new StringBuilder("Available customers:\n\n");
            for (CustomerConfig c : all) {
                sb.append("- **").append(c.name())
                  .append("** (`").append(c.customerId()).append("`)");
                if (c.environments() != null && !c.environments().isEmpty()) {
                    sb.append(" — environments: ");
                    c.environments().forEach(e ->
                            sb.append(effectiveEnvName(e)).append(", "));
                    sb.setLength(sb.length() - 2); // trim trailing ", "
                }
                sb.append("\n");
            }
            sb.append("\nCall again with `customerName` or `customerId` to get full context including AWS account IDs.");
            return sb.toString();
        }

        // ── Resolve CustomerConfig ────────────────────────────────────────────

        // 1. Exact customer ID
        CustomerConfig customer = null;
        if (customerId != null && !customerId.isBlank()) {
            customer = registryStore.getCustomer(customerId).orElse(null);
            if (customer == null) {
                return "No customer found with ID: " + customerId;
            }
        }

        // 2. Name search (case-insensitive partial match)
        if (customer == null && customerName != null && !customerName.isBlank()) {
            List<CustomerConfig> matches = registryStore.findCustomersByName(customerName);
            if (matches.isEmpty()) {
                return "No customer found matching name: \"" + customerName + "\". "
                        + "Call with no parameters to see all available customers.";
            }
            if (matches.size() > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("Multiple customers match \"").append(customerName).append("\":\n\n");
                for (CustomerConfig c : matches) {
                    sb.append("- **").append(c.name()).append("** (`").append(c.customerId()).append("`)\n");
                }
                sb.append("\nPlease retry with the exact `customerId`.");
                return sb.toString();
            }
            customer = matches.get(0);
        }

        // 3. Via Jira project → product → customer
        if (customer == null && jiraProject != null && !jiraProject.isBlank()) {
            ProductConfig p = registryStore.findByJiraProject(jiraProject).orElse(null);
            if (p == null) {
                return "No product found for Jira project key: " + jiraProject;
            }
            if (p.customerId() != null) {
                customer = registryStore.getCustomer(p.customerId()).orElse(null);
            }
            if (customer == null) {
                return formatProduct(p, null, workspace);
            }
        }

        // 4. Via product ID → customer
        if (customer == null && productId != null && !productId.isBlank()) {
            ProductConfig p = registryStore.getProduct(productId).orElse(null);
            if (p == null) {
                return "No product found with ID: " + productId;
            }
            if (p.customerId() != null) {
                customer = registryStore.getCustomer(p.customerId()).orElse(null);
            }
            if (customer == null) {
                return formatProduct(p, null, workspace);
            }
        }

        if (customer == null) {
            return "Could not resolve a customer from the provided parameters.";
        }

        // ── Store resolved customer in workspace for downstream AWS tools ─────
        workspace.putMetadata("customerId", customer.customerId());

        // ── Load products for this customer ───────────────────────────────────
        List<ProductConfig> products = registryStore.listProducts(customer.customerId());

        // Apply git workspace metadata from the first (or only) product
        if (!products.isEmpty()) {
            ProductConfig first = products.get(0);
            if (first.git() != null && first.git().workspace() != null) {
                workspace.putMetadata("workspace", first.git().workspace());
                if (first.git().repos() != null && !first.git().repos().isEmpty()) {
                    workspace.putMetadata("productRepos", String.join(",", first.git().repos()));
                    workspace.putMetadata("repoSlug", first.git().repos().get(0));
                }
            }
        }

        // ── Persist resolved context to DB so it survives across sessions ─────
        String conversationId = workspace.getConversationId();
        if (conversationId != null) {
            try {
                List<String> productIds = products.stream().map(ProductConfig::productId).toList();
                contextStore.mergeContext(conversationId, List.of(customer.customerId()), productIds);
                LOG.debugf("Persisted customer=%s products=%s to conversation context %s",
                        customer.customerId(), productIds, conversationId);
            } catch (Exception e) {
                LOG.warnf("Failed to persist customer context for conversation %s: %s",
                        conversationId, e.getMessage());
            }
        }

        return formatCustomer(customer, products);
    }

    // ─── Formatters ───────────────────────────────────────────────────────────────

    private String formatCustomer(CustomerConfig customer, List<ProductConfig> products) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Customer: ").append(customer.name())
          .append(" (`").append(customer.customerId()).append("`)\n\n");

        // Cloud account
        if (customer.cloudAccountId() != null) {
            CloudAccount ca = cloudAccountStore.getCloudAccount(customer.cloudAccountId()).orElse(null);
            sb.append("### Cloud Account\n");
            if (ca != null) {
                sb.append("  - ID: ").append(ca.id()).append("\n");
                sb.append("  - Name: ").append(ca.name()).append("\n");
                sb.append("  - Type: ").append(ca.type()).append("\n");
                if (ca.description() != null) sb.append("  - Description: ").append(ca.description()).append("\n");
            } else {
                sb.append("  - ID: ").append(customer.cloudAccountId()).append(" (details unavailable)\n");
            }
            sb.append("\n");
        }

        // Environments — the key context for AWS tools
        if (customer.environments() != null && !customer.environments().isEmpty()) {
            sb.append("### Environments\n");
            sb.append("Use `customerId=\"").append(customer.customerId())
              .append("\"` with AWS tools and pass the exact `environmentName` shown below:\n\n");
            for (EnvironmentConfig env : customer.environments()) {
                String displayName = effectiveEnvName(env);
                sb.append("**").append(displayName).append("**");
                if (env.type() != null && !env.type().equalsIgnoreCase(displayName)) {
                    sb.append(" (type: ").append(env.type()).append(")");
                }
                sb.append("\n");
                if (env.aws() != null) {
                    sb.append("  - AWS Account: ").append(env.aws().accountId()).append("\n");
                    sb.append("  - Region: ").append(env.aws().region()).append("\n");
                    if (env.aws().iamRole() != null) {
                        sb.append("  - IAM Role: ").append(env.aws().iamRole()).append("\n");
                    }
                }
            }
            sb.append("\n");
        } else {
            sb.append("No environments configured for this customer.\n\n");
        }

        // Products
        if (!products.isEmpty()) {
            sb.append("### Products\n");
            for (ProductConfig p : products) {
                sb.append("- **").append(p.displayName()).append("** (`").append(p.productId()).append("`)\n");
                if (p.git() != null && p.git().repos() != null && !p.git().repos().isEmpty()) {
                    sb.append("  - Repos: ").append(String.join(", ", p.git().repos())).append("\n");
                }
                if (p.jira() != null && p.jira().projects() != null && !p.jira().projects().isEmpty()) {
                    sb.append("  - Jira: ").append(p.jira().projects().values()).append("\n");
                }
                List<Team> teams = teamStore.listTeamsForProduct(p.productId());
                for (Team t : teams) {
                    sb.append("  - Team: **").append(t.name()).append("**");
                    if (t.members() != null && !t.members().isEmpty()) {
                        sb.append(" — ");
                        t.members().forEach(m -> {
                            String name = (m.firstName() != null ? m.firstName() + " " : "")
                                    + (m.lastName() != null ? m.lastName() : "");
                            sb.append(name.isBlank() ? m.username() : name.trim())
                              .append(" (").append(m.role()).append("), ");
                        });
                        sb.setLength(sb.length() - 2);
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("### Next Steps\n");
        sb.append("To query AWS resources for this customer, use `customerId=\"")
          .append(customer.customerId()).append("\"` with:\n");
        sb.append("- `aws_ecs` — ECS clusters, services, tasks\n");
        sb.append("- `aws_cloudwatch_metrics` — CloudWatch metrics\n");
        sb.append("- `aws_cloudwatch_logs` — CloudWatch log groups and events\n");
        sb.append("- `aws_rds` — RDS instances and clusters\n");

        return sb.toString();
    }

    /**
     * Returns the effective display/match name for an environment.
     * Falls back to {@code type} when {@code name} is null or blank.
     */
    private static String effectiveEnvName(EnvironmentConfig e) {
        return (e.name() != null && !e.name().isBlank()) ? e.name() : e.type();
    }

    /** Fallback formatter when we have a product but no customer (unlinked product). */
    private String formatProduct(ProductConfig p, CustomerConfig customer, WorkspaceContext workspace) {
        if (p.git() != null && p.git().workspace() != null) {
            workspace.putMetadata("workspace", p.git().workspace());
            if (p.git().repos() != null && !p.git().repos().isEmpty()) {
                workspace.putMetadata("productRepos", String.join(",", p.git().repos()));
                workspace.putMetadata("repoSlug", p.git().repos().get(0));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Product: ").append(p.displayName()).append(" (`").append(p.productId()).append("`)\n");
        sb.append("This product is not linked to a customer — no AWS environment context is available.\n\n");
        List<Team> teams = teamStore.listTeamsForProduct(p.productId());
        if (!teams.isEmpty()) {
            sb.append("### Teams\n");
            for (Team t : teams) {
                sb.append("**").append(t.name()).append("**\n");
                if (t.members() != null) {
                    for (TeamMemberEntry m : t.members()) {
                        String name = (m.firstName() != null ? m.firstName() + " " : "")
                                + (m.lastName() != null ? m.lastName() : "");
                        sb.append("  - [").append(m.role()).append("] ")
                          .append(name.isBlank() ? m.username() : name.trim());
                        if (m.email() != null) sb.append(" <").append(m.email()).append(">");
                        sb.append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }
}
