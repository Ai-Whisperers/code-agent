package com.eneve.agent.agent.store;

import com.eneve.agent.model.*;
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
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL-backed store for the customer and product registry.
 * Customers and products use a hybrid schema: relational columns for
 * primary keys and well-known fields, JSONB columns for flexible nested
 * structures (environments, git/jira/confluence config).
 * Team membership is managed separately via {@link TeamStore}.
 */
@ApplicationScoped
public class CustomerRegistryStore {

    private static final Logger LOG = Logger.getLogger(CustomerRegistryStore.class);
    @Inject ObjectMapper mapper;

    @Inject
    AgroalDataSource dataSource;

    // ──────────────────────────────────────────────────────────
    // Customers
    // ──────────────────────────────────────────────────────────

    public List<CustomerConfig> listCustomers() {
        String sql = """
                SELECT customer_id, name, cloud_account_id, environments, metadata, created_at, updated_at
                FROM customers
                ORDER BY name
                """;
        List<CustomerConfig> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapCustomer(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list customers: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Case-insensitive partial-name search across customers.
     * Returns all customers whose {@code name} contains {@code name} (ILIKE).
     */
    public List<CustomerConfig> findCustomersByName(String name) {
        String sql = """
                SELECT customer_id, name, cloud_account_id, environments, metadata, created_at, updated_at
                FROM customers
                WHERE name ILIKE ?
                ORDER BY name
                """;
        List<CustomerConfig> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + name + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapCustomer(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to search customers by name '%s': %s", name, e.getMessage());
        }
        return results;
    }

    public Optional<CustomerConfig> getCustomer(String customerId) {
        String sql = """
                SELECT customer_id, name, cloud_account_id, environments, metadata, created_at, updated_at
                FROM customers
                WHERE customer_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapCustomer(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get customer %s: %s", customerId, e.getMessage());
        }
        return Optional.empty();
    }

    public void upsertCustomer(CustomerConfig customer) {
        String sql = """
                INSERT INTO customers (customer_id, name, cloud_account_id, environments, metadata, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, now(), now())
                ON CONFLICT (customer_id) DO UPDATE
                    SET name             = EXCLUDED.name,
                        cloud_account_id = EXCLUDED.cloud_account_id,
                        environments     = EXCLUDED.environments,
                        metadata         = EXCLUDED.metadata,
                        updated_at       = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customer.customerId());
            ps.setString(2, customer.name());
            ps.setString(3, customer.cloudAccountId());
            ps.setString(4, toJson(customer.environments() != null ? customer.environments() : List.of()));
            ps.setString(5, toJson(customer.metadata() != null ? customer.metadata() : Map.of()));
            ps.executeUpdate();
            LOG.debugf("Upserted customer %s", customer.customerId());
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert customer %s: %s", customer.customerId(), e.getMessage());
        }
    }

    public boolean deleteCustomer(String customerId) {
        String sql = "DELETE FROM customers WHERE customer_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            int rows = ps.executeUpdate();
            LOG.debugf("Deleted customer %s (%d rows)", customerId, rows);
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete customer %s: %s", customerId, e.getMessage());
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────
    // Products
    // ──────────────────────────────────────────────────────────

    /** List all products regardless of customer assignment. */
    public List<ProductConfig> listAllProducts() {
        String sql = """
                SELECT p.product_id, cp.customer_id, p.display_name, p.git, p.jira, p.confluence,
                       p.metadata, p.created_at, p.updated_at
                FROM products p
                LEFT JOIN customer_products cp ON cp.product_id = p.product_id
                ORDER BY p.display_name
                """;
        List<ProductConfig> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapProduct(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list all products: %s", e.getMessage());
        }
        return results;
    }

    /** List products linked to a specific customer. */
    public List<ProductConfig> listProducts(String customerId) {
        String sql = """
                SELECT p.product_id, cp.customer_id, p.display_name, p.git, p.jira, p.confluence,
                       p.metadata, p.created_at, p.updated_at
                FROM products p
                JOIN customer_products cp ON cp.product_id = p.product_id
                WHERE cp.customer_id = ?
                ORDER BY p.display_name
                """;
        List<ProductConfig> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapProduct(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list products for customer %s: %s", customerId, e.getMessage());
        }
        return results;
    }

    public Optional<ProductConfig> getProduct(String productId) {
        String sql = """
                SELECT p.product_id, cp.customer_id, p.display_name, p.git, p.jira, p.confluence,
                       p.metadata, p.created_at, p.updated_at
                FROM products p
                LEFT JOIN customer_products cp ON cp.product_id = p.product_id
                WHERE p.product_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapProduct(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get product %s: %s", productId, e.getMessage());
        }
        return Optional.empty();
    }

    /** Create or update a product (customer link is managed separately via linkProduct). */
    public void upsertProduct(ProductConfig product) {
        String sql = """
                INSERT INTO products (product_id, display_name, git, jira, confluence,
                                      metadata, created_at, updated_at)
                VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, now(), now())
                ON CONFLICT (product_id) DO UPDATE
                    SET display_name = EXCLUDED.display_name,
                        git          = EXCLUDED.git,
                        jira         = EXCLUDED.jira,
                        confluence   = EXCLUDED.confluence,
                        metadata     = EXCLUDED.metadata,
                        updated_at   = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, product.productId());
            ps.setString(2, product.displayName());
            ps.setString(3, toJson(product.git()));
            ps.setString(4, toJson(product.jira()));
            ps.setString(5, toJson(product.confluence()));
            ps.setString(6, toJson(product.metadata() != null ? product.metadata() : Map.of()));
            ps.executeUpdate();
            LOG.debugf("Upserted product %s", product.productId());
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert product %s: %s", product.productId(), e.getMessage());
        }
    }

    public boolean deleteProduct(String productId) {
        String sql = "DELETE FROM products WHERE product_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            int rows = ps.executeUpdate();
            LOG.debugf("Deleted product %s (%d rows)", productId, rows);
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete product %s: %s", productId, e.getMessage());
            return false;
        }
    }

    /** Link an existing product to a customer (idempotent). */
    public void linkProduct(String customerId, String productId) {
        String sql = """
                INSERT INTO customer_products (customer_id, product_id)
                VALUES (?, ?)
                ON CONFLICT (customer_id, product_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            ps.setString(2, productId);
            ps.executeUpdate();
            LOG.debugf("Linked product %s to customer %s", productId, customerId);
        } catch (SQLException e) {
            LOG.errorf("Failed to link product %s to customer %s: %s", productId, customerId, e.getMessage());
        }
    }

    /** Remove the link between a product and a customer. */
    public boolean unlinkProduct(String customerId, String productId) {
        String sql = "DELETE FROM customer_products WHERE customer_id = ? AND product_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customerId);
            ps.setString(2, productId);
            int rows = ps.executeUpdate();
            LOG.debugf("Unlinked product %s from customer %s (%d rows)", productId, customerId, rows);
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to unlink product %s from customer %s: %s", productId, customerId, e.getMessage());
            return false;
        }
    }

    /**
     * Finds the product whose Jira config contains the given project key.
     * Searches across the JSONB jira.projects map for any matching value.
     */
    public Optional<ProductConfig> findByJiraProject(String projectKey) {
        String sql = """
                SELECT p.product_id, cp.customer_id, p.display_name, p.git, p.jira, p.confluence,
                       p.metadata, p.created_at, p.updated_at
                FROM products p
                LEFT JOIN customer_products cp ON cp.product_id = p.product_id
                WHERE p.jira -> 'projects' @> ?::jsonb
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // Match any value in the projects map equal to projectKey
            ps.setString(1, "\"" + projectKey + "\"");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapProduct(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find product by Jira project %s: %s", projectKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Finds the product whose Confluence config contains the given space key.
     */
    public Optional<ProductConfig> findByConfluenceSpace(String spaceKey) {
        String sql = """
                SELECT p.product_id, cp.customer_id, p.display_name, p.git, p.jira, p.confluence,
                       p.metadata, p.created_at, p.updated_at
                FROM products p
                LEFT JOIN customer_products cp ON cp.product_id = p.product_id
                WHERE p.confluence ->> 'spaceKey' = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, spaceKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapProduct(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find product by Confluence space %s: %s", spaceKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Finds the product that a repo_settings row belongs to, via the product_id FK.
     */
    public Optional<ProductConfig> findByRepoSlug(String workspace, String repoSlug) {
        String sql = """
                SELECT p.product_id, cp.customer_id, p.display_name, p.git, p.jira, p.confluence,
                       p.metadata, p.created_at, p.updated_at
                FROM products p
                LEFT JOIN customer_products cp ON cp.product_id = p.product_id
                JOIN repo_settings rs ON rs.product_id = p.product_id
                WHERE rs.workspace = ? AND rs.repo_slug = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapProduct(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find product for repo %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
        return Optional.empty();
    }

    // ──────────────────────────────────────────────────────────
    // Row mappers
    // ──────────────────────────────────────────────────────────

    private CustomerConfig mapCustomer(ResultSet rs) throws SQLException {
        String cloudAccountId = rs.getString("cloud_account_id");
        if (rs.wasNull()) cloudAccountId = null;
        return new CustomerConfig(
                rs.getString("customer_id"),
                rs.getString("name"),
                cloudAccountId,
                fromJson(rs.getString("environments"), new TypeReference<List<EnvironmentConfig>>() {}),
                fromJson(rs.getString("metadata"), new TypeReference<Map<String, Object>>() {}),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private ProductConfig mapProduct(ResultSet rs) throws SQLException {
        String customerId = rs.getString("customer_id");
        if (rs.wasNull()) customerId = null;
        return new ProductConfig(
                rs.getString("product_id"),
                customerId,
                rs.getString("display_name"),
                fromJson(rs.getString("git"), new TypeReference<GitConfig>() {}),
                fromJson(rs.getString("jira"), new TypeReference<JiraProjectConfig>() {}),
                fromJson(rs.getString("confluence"), new TypeReference<ConfluenceProductConfig>() {}),
                fromJson(rs.getString("metadata"), new TypeReference<Map<String, Object>>() {}),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    // ──────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────

    private String toJson(Object value) {
        if (value == null) return "{}";
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            LOG.warnf("Failed to serialize value to JSON: %s", e.getMessage());
            return "{}";
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            LOG.warnf("Failed to deserialize JSON: %s", e.getMessage());
            return null;
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
