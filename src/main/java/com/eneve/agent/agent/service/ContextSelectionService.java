package com.eneve.agent.agent.service;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.mcp.LinkedAccountService;
import com.eneve.agent.model.ContextItem;
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
import java.util.stream.Collectors;

/**
 * Service for fetching context items for conversation context selection dialogs.
 * Provides simplified representations of customers, products, Aikido issues, 
 * Jira issues, and Confluence documents.
 */
@ApplicationScoped
public class ContextSelectionService {

    private static final Logger LOG = Logger.getLogger(ContextSelectionService.class);

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

    public List<ContextItem.CustomerContextItem> getCustomersForContext(int limit) {
        try {
            return customerRegistryStore.listCustomers().stream()
                    .limit(limit)
                    .map(this::mapToCustomerContextItem)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.errorf("Failed to fetch customers for context: %s", e.getMessage());
            return List.of();
        }
    }

    public List<ContextItem.ProductContextItem> getProductsForContext(int limit) {
        try {
            return customerRegistryStore.listAllProducts().stream()
                    .limit(limit)
                    .map(this::mapToProductContextItem)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.errorf("Failed to fetch products for context: %s", e.getMessage());
            return List.of();
        }
    }

    public List<ContextItem.AikidoIssueContextItem> getAikidoIssuesForContext(String repoSlug, int limit) {
        try {
            if (!aikidoService.isEnabled()) {
                LOG.warn("Aikido service not enabled, returning empty list");
                return List.of();
            }
            
            List<AikidoIssueInfo> issues;
            if (repoSlug != null && !repoSlug.isBlank()) {
                issues = aikidoService.findActionableIssuesForRepo(repoSlug);
            } else {
                // If no repoSlug provided, we can't easily get all issues
                // Return empty list or implement a different approach
                return List.of();
            }
            
            return issues.stream()
                    .limit(limit)
                    .map(this::mapToAikidoIssueContextItem)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.errorf("Failed to fetch Aikido issues for context: %s", e.getMessage());
            return List.of();
        }
    }

    public List<ContextItem.JiraIssueContextItem> getJiraIssuesForContext(String query, String productId, int limit) {
        try {
            String userId = resolveUserId();
            LOG.debugf("Fetching Jira issues for user: %s", userId);
            
            var jiraCreds = linkedAccountService.resolveJira(userId);
            
            if (jiraCreds.isEmpty()) {
                LOG.warnf("No Jira credentials found for user %s, returning empty list", userId);
                return List.of();
            }
            
            LOG.debugf("Found Jira credentials for user %s: baseUrl=%s, username=%s", 
                userId, jiraCreds.get().baseUrl(), jiraCreds.get().username());
            
            // Use JQL to search for recent issues, with optional text search
            String jql = query.isBlank() 
                ? "ORDER BY created DESC" 
                : "text ~ \"" + escapeJql(query) + "\" OR summary ~ \"" + escapeJql(query) + "\" ORDER BY created DESC";
            
            LOG.debugf("Executing Jira JQL: %s", jql);
            var issues = jiraService.searchIssues(jql, limit, jiraCreds.get());
            
            LOG.debugf("Retrieved %d Jira issues for user %s", issues.size(), userId);
            return issues.stream()
                    .map(this::mapToJiraIssueContextItem)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.errorf("Failed to fetch Jira issues for context: %s", e.getMessage());
            return List.of();
        }
    }

    public List<ContextItem.ConfluenceDocContextItem> getConfluenceDocsForContext(String query, String productId, int limit) {
        try {
            String userId = resolveUserId();
            LOG.debugf("Fetching Confluence docs for user: %s", userId);
            
            var confluenceCreds = linkedAccountService.resolveConfluence(userId);
            
            if (confluenceCreds.isEmpty()) {
                LOG.warnf("No Confluence credentials found for user %s, returning empty list", userId);
                return List.of();
            }
            
            LOG.debugf("Found Confluence credentials for user %s: baseUrl=%s, username=%s", 
                userId, confluenceCreds.get().baseUrl(), confluenceCreds.get().username());
            
            // Use CQL to search for pages, with optional text search
            String cql = query.isBlank()
                ? "type=page ORDER BY lastModified DESC"
                : "type=page AND (title ~ \"" + escapeCql(query) + "\" OR text ~ \"" + escapeCql(query) + "\") ORDER BY lastModified DESC";
            
            LOG.debugf("Executing Confluence CQL: %s", cql);
            var pages = confluenceService.searchPages(cql, limit, confluenceCreds.get());
            
            LOG.debugf("Retrieved %d Confluence pages for user %s", pages.size(), userId);
            return pages.stream()
                    .map(this::mapToConfluenceDocContextItem)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.errorf("Failed to fetch Confluence docs for context: %s", e.getMessage());
            return List.of();
        }
    }

    private ContextItem.CustomerContextItem mapToCustomerContextItem(CustomerConfig customer) {
        String metadataSummary = "";
        if (customer.metadata() != null && !customer.metadata().isEmpty()) {
            metadataSummary = customer.metadata().keySet().stream()
                    .limit(3)
                    .collect(Collectors.joining(", "));
        }
        
        return new ContextItem.CustomerContextItem(
            customer.customerId(),
            customer.name(),
            metadataSummary
        );
    }

    private ContextItem.ProductContextItem mapToProductContextItem(ProductConfig product) {
        String customerName = "";
        if (product.customerId() != null) {
            customerName = customerRegistryStore.getCustomer(product.customerId())
                    .map(CustomerConfig::name)
                    .orElse("");
        }
        
        return new ContextItem.ProductContextItem(
            product.productId(),
            product.displayName(),
            product.customerId(),
            customerName
        );
    }

    private ContextItem.AikidoIssueContextItem mapToAikidoIssueContextItem(AikidoIssueInfo issue) {
        return new ContextItem.AikidoIssueContextItem(
            issue.issueGroupId(),
            issue.issueType() != null ? issue.issueType() : "unknown",
            issue.severity(),
            issue.packageName(),
            issue.cveId(),
            issue.repoName()
        );
    }

    private ContextItem.JiraIssueContextItem mapToJiraIssueContextItem(JiraService.JiraIssueDetail issue) {
        return new ContextItem.JiraIssueContextItem(
            issue.key(),
            issue.summary(),
            issue.status() != null ? issue.status() : "Unknown",
            "Task", // Default issue type since it's not available in JiraIssueDetail
            issue.assignee() != null ? issue.assignee() : ""
        );
    }

    private ContextItem.ConfluenceDocContextItem mapToConfluenceDocContextItem(ConfluenceService.ConfluencePage page) {
        // Simple content preview (first 100 chars)
        String contentPreview = page.title(); // ConfluencePage only has pageId, title, url - no content field
        if (contentPreview != null && contentPreview.length() > 100) {
            contentPreview = contentPreview.substring(0, 100) + "...";
        }
        
        return new ContextItem.ConfluenceDocContextItem(
            page.pageId(),
            page.title(),
            "", // spaceKey not available in ConfluencePage
            "", // spaceName not available in ConfluencePage  
            contentPreview
        );
    }

    private String escapeJql(String query) {
        // Basic JQL escaping - escape quotes and special characters
        return query.replace("\"", "\\\"").replace("'", "\\'");
    }
    
    private String escapeCql(String query) {
        // Basic CQL escaping - escape quotes and special characters
        return query.replace("\"", "\\\"").replace("'", "\\'");
    }

    private String resolveUserId() {
        if (securityIdentity.isAnonymous()) {
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }
        // Use the stable 'sub' claim (UUID assigned by Keycloak) rather than
        // preferred_username which may change on user rename.
        try {
            String sub = jwt.getClaim("sub");
            if (sub != null && !sub.isBlank()) {
                return sub;
            }
        } catch (Exception e) {
            LOG.warnf("Failed to extract 'sub' claim from JWT: %s", e.getMessage());
        }
        // Fallback to principal name if 'sub' claim is not available
        return securityIdentity.getPrincipal().getName();
    }
}
