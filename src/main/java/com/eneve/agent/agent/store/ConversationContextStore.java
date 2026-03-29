package com.eneve.agent.agent.store;

import com.eneve.agent.model.ConversationContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed store for conversation context data.
 * Stores references to customers, products, issues, and documents
 * associated with chat conversations.
 */
@ApplicationScoped
public class ConversationContextStore {

    private static final Logger LOG = Logger.getLogger(ConversationContextStore.class);
    @Inject ObjectMapper mapper;

    @Inject
    AgroalDataSource dataSource;

    public Optional<ConversationContext> getContext(String conversationId) {
        String sql = """
                SELECT conversation_id, customer_ids, product_ids, aikido_issue_ids, 
                       jira_issue_keys, confluence_doc_ids, created_at, updated_at
                FROM conversation_context
                WHERE conversation_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapContext(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get conversation context for %s: %s", conversationId, e.getMessage());
        }
        return Optional.empty();
    }

    public ConversationContext updateContext(String conversationId, 
                                           List<String> customerIds,
                                           List<String> productIds, 
                                           List<Integer> aikidoIssueIds,
                                           List<String> jiraIssueKeys,
                                           List<String> confluenceDocIds) {
        Instant now = Instant.now();
        
        String upsertSql = """
                INSERT INTO conversation_context (
                    conversation_id, customer_ids, product_ids, aikido_issue_ids,
                    jira_issue_keys, confluence_doc_ids, created_at, updated_at
                ) VALUES (?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                ON CONFLICT (conversation_id) DO UPDATE SET
                    customer_ids = EXCLUDED.customer_ids,
                    product_ids = EXCLUDED.product_ids, 
                    aikido_issue_ids = EXCLUDED.aikido_issue_ids,
                    jira_issue_keys = EXCLUDED.jira_issue_keys,
                    confluence_doc_ids = EXCLUDED.confluence_doc_ids,
                    updated_at = EXCLUDED.updated_at
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            
            ps.setString(1, conversationId);
            ps.setString(2, mapper.writeValueAsString(customerIds != null ? customerIds : List.of()));
            ps.setString(3, mapper.writeValueAsString(productIds != null ? productIds : List.of()));
            ps.setString(4, mapper.writeValueAsString(aikidoIssueIds != null ? aikidoIssueIds : List.of()));
            ps.setString(5, mapper.writeValueAsString(jiraIssueKeys != null ? jiraIssueKeys : List.of()));
            ps.setString(6, mapper.writeValueAsString(confluenceDocIds != null ? confluenceDocIds : List.of()));
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            
            ps.executeUpdate();
            
            return new ConversationContext(
                conversationId,
                customerIds != null ? customerIds : List.of(),
                productIds != null ? productIds : List.of(), 
                aikidoIssueIds != null ? aikidoIssueIds : List.of(),
                jiraIssueKeys != null ? jiraIssueKeys : List.of(),
                confluenceDocIds != null ? confluenceDocIds : List.of(),
                now, now
            );
            
        } catch (Exception e) {
            LOG.errorf("Failed to update conversation context for %s: %s", conversationId, e.getMessage());
            throw new RuntimeException("Failed to update conversation context", e);
        }
    }

    /**
     * Additive upsert: adds {@code newCustomerIds} and {@code newProductIds} to any existing
     * lists for the conversation without touching aikido, jira, or confluence fields.
     * Creates a new row if none exists yet.
     *
     * <p>This is called by tools like {@code lookup_customer_context} to persist AI-resolved
     * context so it survives across sessions.
     */
    public ConversationContext mergeContext(String conversationId,
                                           List<String> newCustomerIds,
                                           List<String> newProductIds) {
        ConversationContext existing = getContext(conversationId).orElse(null);

        List<String> mergedCustomers = mergeLists(
                existing != null ? existing.customerIds() : List.of(), newCustomerIds);
        List<String> mergedProducts = mergeLists(
                existing != null ? existing.productIds() : List.of(), newProductIds);

        return updateContext(
                conversationId,
                mergedCustomers,
                mergedProducts,
                existing != null ? existing.aikidoIssueIds() : List.of(),
                existing != null ? existing.jiraIssueKeys() : List.of(),
                existing != null ? existing.confluenceDocIds() : List.of()
        );
    }

    /** Returns a deduplicated union of two lists, preserving insertion order. */
    private static <T> List<T> mergeLists(List<T> existing, List<T> additions) {
        if (additions == null || additions.isEmpty()) return existing != null ? existing : List.of();
        List<T> result = new ArrayList<>(existing != null ? existing : List.of());
        for (T item : additions) {
            if (item != null && !result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    public boolean deleteContext(String conversationId) {
        String sql = "DELETE FROM conversation_context WHERE conversation_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete conversation context for %s: %s", conversationId, e.getMessage());
            return false;
        }
    }

    private ConversationContext mapContext(ResultSet rs) throws SQLException {
        try {
            List<String> customerIds = mapper.readValue(
                rs.getString("customer_ids"), 
                new TypeReference<List<String>>() {}
            );
            List<String> productIds = mapper.readValue(
                rs.getString("product_ids"),
                new TypeReference<List<String>>() {}
            );
            List<Integer> aikidoIssueIds = mapper.readValue(
                rs.getString("aikido_issue_ids"),
                new TypeReference<List<Integer>>() {}
            );
            List<String> jiraIssueKeys = mapper.readValue(
                rs.getString("jira_issue_keys"),
                new TypeReference<List<String>>() {}
            );
            List<String> confluenceDocIds = mapper.readValue(
                rs.getString("confluence_doc_ids"),
                new TypeReference<List<String>>() {}
            );

            return new ConversationContext(
                rs.getString("conversation_id"),
                customerIds,
                productIds,
                aikidoIssueIds, 
                jiraIssueKeys,
                confluenceDocIds,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            );
        } catch (Exception e) {
            LOG.errorf("Failed to parse conversation context JSON: %s", e.getMessage());
            throw new SQLException("Failed to parse conversation context", e);
        }
    }
}
