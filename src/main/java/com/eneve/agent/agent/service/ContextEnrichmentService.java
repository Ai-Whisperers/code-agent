package com.eneve.agent.agent.service;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.mcp.LinkedAccountService;
import com.eneve.agent.model.ConversationContext;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.model.ProductConfig;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for enriching conversation context items with full details.
 * Loads complete information for context item IDs to provide to Claude upfront,
 * eliminating the need for Claude to make tool calls during conversations.
 */
@ApplicationScoped
public class ContextEnrichmentService {

    private static final Logger LOG = Logger.getLogger(ContextEnrichmentService.class);

    @Inject
    CustomerRegistryStore customerRegistryStore;
    
    @Inject
    AikidoService aikidoService;
    
    @Inject
    JiraService jiraService;
    
    @Inject
    ConfluenceService confluenceService;
    
    @Inject
    LinkedAccountService linkedAccountService;
    
    @Inject
    SecurityIdentity securityIdentity;
    
    @Inject
    JsonWebToken jwt;

    /**
     * Enriches conversation context with full details for all context items.
     * Returns a formatted markdown string ready to be included in the system prompt.
     */
    public String enrichContext(ConversationContext context, String userId) {
        if (context == null) {
            return "";
        }

        List<String> sections = new ArrayList<>();

        // Enrich customers
        String customerSection = enrichCustomers(context.customerIds());
        if (!customerSection.isEmpty()) {
            sections.add(customerSection);
        }

        // Enrich products  
        String productSection = enrichProducts(context.productIds());
        if (!productSection.isEmpty()) {
            sections.add(productSection);
        }

        // Enrich Jira issues
        String jiraSection = enrichJiraIssues(context.jiraIssueKeys(), userId);
        if (!jiraSection.isEmpty()) {
            sections.add(jiraSection);
        }

        // Enrich Aikido issues
        String aikidoSection = enrichAikidoIssues(context.aikidoIssueIds());
        if (!aikidoSection.isEmpty()) {
            sections.add(aikidoSection);
        }

        // Enrich Confluence documents
        String confluenceSection = enrichConfluenceDocs(context.confluenceDocIds(), userId);
        if (!confluenceSection.isEmpty()) {
            sections.add(confluenceSection);
        }

        if (sections.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        result.append("# Conversation Context\n\n");
        result.append("The following context items are relevant to this conversation. ");
        result.append("Use this information to provide more accurate and contextual responses.\n\n");
        
        for (String section : sections) {
            result.append(section).append("\n");
        }

        return result.toString();
    }

    /**
     * Enriches customer context with full customer details.
     */
    public String enrichCustomers(List<String> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return "";
        }

        List<CustomerConfig> customers = new ArrayList<>();
        for (String customerId : customerIds) {
            try {
                Optional<CustomerConfig> customer = customerRegistryStore.getCustomer(customerId);
                customer.ifPresent(customers::add);
            } catch (Exception e) {
                LOG.warnf("Failed to fetch customer %s: %s", customerId, e.getMessage());
            }
        }

        if (customers.isEmpty()) {
            return "";
        }

        StringBuilder section = new StringBuilder();
        section.append("## Customer Context\n\n");

        for (CustomerConfig customer : customers) {
            section.append("### ").append(customer.name()).append(" (").append(customer.customerId()).append(")\n");
            
            if (customer.metadata() != null && !customer.metadata().isEmpty()) {
                section.append("**Metadata**: ");
                section.append(customer.metadata().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", ")));
                section.append("\n");
            }
            section.append("\n");
        }

        return section.toString();
    }

    /**
     * Enriches product context with full product details.
     */
    public String enrichProducts(List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return "";
        }

        List<ProductConfig> products = new ArrayList<>();
        for (String productId : productIds) {
            try {
                Optional<ProductConfig> product = customerRegistryStore.getProduct(productId);
                product.ifPresent(products::add);
            } catch (Exception e) {
                LOG.warnf("Failed to fetch product %s: %s", productId, e.getMessage());
            }
        }

        if (products.isEmpty()) {
            return "";
        }

        StringBuilder section = new StringBuilder();
        section.append("## Product Context\n\n");

        for (ProductConfig product : products) {
            section.append("### ").append(product.displayName()).append(" (").append(product.productId()).append(")\n");
            
            if (product.customerId() != null) {
                Optional<CustomerConfig> customer = customerRegistryStore.getCustomer(product.customerId());
                if (customer.isPresent()) {
                    section.append("**Customer**: ").append(customer.get().name()).append("\n");
                }
            }
            
            if (product.metadata() != null && !product.metadata().isEmpty()) {
                section.append("**Metadata**: ");
                section.append(product.metadata().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", ")));
                section.append("\n");
            }
            section.append("\n");
        }

        return section.toString();
    }

    /**
     * Enriches Jira issue context with full issue details.
     */
    public String enrichJiraIssues(List<String> jiraIssueKeys, String userId) {
        if (jiraIssueKeys == null || jiraIssueKeys.isEmpty()) {
            return "";
        }

        try {
            var jiraCreds = linkedAccountService.resolveJira(userId);
            if (jiraCreds.isEmpty()) {
                LOG.warnf("No Jira credentials found for user %s, skipping Jira enrichment", userId);
                return "";
            }

            List<JiraService.JiraIssue> issues = new ArrayList<>();
            for (String issueKey : jiraIssueKeys) {
                try {
                    JiraService.JiraIssue issue = jiraService.getIssue(issueKey, jiraCreds.get());
                    if (issue != null) {
                        issues.add(issue);
                    }
                } catch (Exception e) {
                    LOG.warnf("Failed to fetch Jira issue %s: %s", issueKey, e.getMessage());
                }
            }

            if (issues.isEmpty()) {
                return "";
            }

            StringBuilder section = new StringBuilder();
            section.append("## Jira Issues in Context\n\n");

            for (JiraService.JiraIssue issue : issues) {
                section.append("### ").append(issue.key()).append(": ").append(issue.summary()).append("\n");
                
                if (issue.status() != null) {
                    section.append("**Status**: ").append(issue.status()).append("\n");
                }
                
                if (issue.issueType() != null) {
                    section.append("**Type**: ").append(issue.issueType()).append("\n");
                }
                
                if (issue.description() != null && !issue.description().trim().isEmpty()) {
                    // Truncate description if too long
                    String description = issue.description().trim();
                    if (description.length() > 500) {
                        description = description.substring(0, 500) + "...";
                    }
                    section.append("**Description**: ").append(description).append("\n");
                }
                
                section.append("\n");
            }

            return section.toString();

        } catch (Exception e) {
            LOG.warnf("Failed to enrich Jira issues: %s", e.getMessage());
            return "";
        }
    }

    /**
     * Enriches Aikido issue context with full issue details.
     */
    public String enrichAikidoIssues(List<Integer> aikidoIssueIds) {
        if (aikidoIssueIds == null || aikidoIssueIds.isEmpty()) {
            return "";
        }

        if (!aikidoService.isEnabled()) {
            LOG.warn("Aikido service not enabled, skipping Aikido enrichment");
            return "";
        }

        try {
            List<AikidoIssueInfo> issues = new ArrayList<>();
            for (Integer issueId : aikidoIssueIds) {
                try {
                    // Note: We would need an individual fetch method in AikidoService
                    // For now, this is a placeholder that could be implemented
                    // AikidoIssueInfo issue = aikidoService.getIssueById(issueId);
                    // if (issue != null) {
                    //     issues.add(issue);
                    // }
                } catch (Exception e) {
                    LOG.warnf("Failed to fetch Aikido issue %d: %s", issueId, e.getMessage());
                }
            }

            if (issues.isEmpty()) {
                return "";
            }

            StringBuilder section = new StringBuilder();
            section.append("## Aikido Security Issues in Context\n\n");

            for (AikidoIssueInfo issue : issues) {
                section.append("### Issue #").append(issue.issueGroupId()).append("\n");
                section.append("**Type**: ").append(issue.issueType()).append("\n");
                section.append("**Severity**: ").append(issue.severity()).append("\n");
                
                if (issue.packageName() != null) {
                    section.append("**Package**: ").append(issue.packageName()).append("\n");
                }
                
                if (issue.cveId() != null) {
                    section.append("**CVE**: ").append(issue.cveId()).append("\n");
                }
                
                if (issue.repoName() != null) {
                    section.append("**Repository**: ").append(issue.repoName()).append("\n");
                }
                
                section.append("\n");
            }

            return section.toString();

        } catch (Exception e) {
            LOG.warnf("Failed to enrich Aikido issues: %s", e.getMessage());
            return "";
        }
    }

    /**
     * Enriches Confluence document context with full document details.
     */
    public String enrichConfluenceDocs(List<String> confluenceDocIds, String userId) {
        if (confluenceDocIds == null || confluenceDocIds.isEmpty()) {
            return "";
        }

        try {
            var confluenceCreds = linkedAccountService.resolveConfluence(userId);
            if (confluenceCreds.isEmpty()) {
                LOG.warnf("No Confluence credentials found for user %s, skipping Confluence enrichment", userId);
                return "";
            }

            List<ConfluenceService.ConfluencePage> pages = new ArrayList<>();
            for (String pageId : confluenceDocIds) {
                try {
                    // Note: We would need an individual fetch method in ConfluenceService
                    // For now, this is a placeholder that could be implemented
                    // ConfluenceService.ConfluencePage page = confluenceService.getPageById(pageId, confluenceCreds.get());
                    // if (page != null) {
                    //     pages.add(page);
                    // }
                } catch (Exception e) {
                    LOG.warnf("Failed to fetch Confluence page %s: %s", pageId, e.getMessage());
                }
            }

            if (pages.isEmpty()) {
                return "";
            }

            StringBuilder section = new StringBuilder();
            section.append("## Confluence Documents in Context\n\n");

            for (ConfluenceService.ConfluencePage page : pages) {
                section.append("### ").append(page.title()).append("\n");
                section.append("**Page ID**: ").append(page.pageId()).append("\n");
                
                if (page.url() != null) {
                    section.append("**URL**: ").append(page.url()).append("\n");
                }
                
                section.append("\n");
            }

            return section.toString();

        } catch (Exception e) {
            LOG.warnf("Failed to enrich Confluence documents: %s", e.getMessage());
            return "";
        }
    }
}
