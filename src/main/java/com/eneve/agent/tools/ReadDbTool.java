package com.eneve.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import com.eneve.agent.workspace.WorkspaceContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Read-only Claude tool that executes SELECT queries against the agent's PostgreSQL database.
 *
 * <p>Security guards (applied in order):
 * <ol>
 *   <li>Strip SQL comments ({@code --} and {@code /* * /})</li>
 *   <li>Normalise whitespace and uppercase</li>
 *   <li>Reject if the statement does not start with {@code SELECT}</li>
 *   <li>Reject if the normalised text contains any DML/DDL keyword that could
 *       write data, including write-capable CTEs ({@code WITH ... INSERT ... RETURNING})</li>
 * </ol>
 *
 * <p>Output is capped at {@value MAX_ROWS} rows to prevent runaway result sets.
 */
@ApplicationScoped
public class ReadDbTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(ReadDbTool.class);
    private static final int MAX_ROWS = 200;

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT  = Pattern.compile("--[^\n]*");
    private static final Pattern WHITESPACE    = Pattern.compile("\\s+");

    private static final java.util.Set<String> FORBIDDEN_KEYWORDS = java.util.Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE",
            "CALL", "EXECUTE", "MERGE", "CREATE", "ALTER", "GRANT", "REVOKE"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    AgroalDataSource dataSource;

    @Override
    public String name() {
        return "read_db";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String sql = (String) input.get("sql");
        if (sql == null || sql.isBlank()) {
            return "ERROR: 'sql' parameter is required";
        }

        String guardError = validateSql(sql);
        if (guardError != null) {
            return "ERROR: " + guardError;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setMaxRows(MAX_ROWS);
            try (ResultSet rs = ps.executeQuery()) {
                return formatResults(rs);
            }
        } catch (Exception e) {
            LOG.warnf("read_db query failed: %s", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // ─── SQL guard ────────────────────────────────────────────────────────────

    private String validateSql(String sql) {
        // Strip comments
        String stripped = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        stripped = LINE_COMMENT.matcher(stripped).replaceAll(" ");

        // Normalise whitespace and uppercase for keyword matching
        String normalised = WHITESPACE.matcher(stripped.trim()).replaceAll(" ").toUpperCase();

        if (!normalised.startsWith("SELECT")) {
            return "Only SELECT statements are permitted";
        }

        // Tokenise on word boundaries and check for forbidden keywords
        for (String token : normalised.split("[^A-Z_]+")) {
            if (FORBIDDEN_KEYWORDS.contains(token)) {
                return "Statement contains forbidden keyword: " + token;
            }
        }

        return null;
    }

    // ─── Result formatting ────────────────────────────────────────────────────

    private String formatResults(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();

        ArrayNode rows = objectMapper.createArrayNode();
        int count = 0;
        while (rs.next() && count < MAX_ROWS) {
            ObjectNode row = objectMapper.createObjectNode();
            for (int i = 1; i <= cols; i++) {
                String colName = meta.getColumnLabel(i);
                Object value = rs.getObject(i);
                if (value == null) {
                    row.putNull(colName);
                } else {
                    row.put(colName, value.toString());
                }
            }
            rows.add(row);
            count++;
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("rowCount", count);
        result.set("rows", rows);
        return objectMapper.writeValueAsString(result);
    }
}
