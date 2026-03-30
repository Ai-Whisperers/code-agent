package com.eneve.agent.agent.store;

import com.anthropic.models.messages.MessageParam;
import com.eneve.agent.agent.MessageSerializer;
import com.eneve.agent.model.ConversationSummary;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.postgresql.util.PGobject;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-backed store for chat conversations and their message histories.
 *
 * <p>Replaces the in-memory {@code ConversationStore}. All operations are scoped to
 * a {@code userId} (the Keycloak JWT {@code sub} claim) so users cannot access each
 * other's conversations.
 */
@ApplicationScoped
public class ConversationRepository {

    private static final Logger LOG = Logger.getLogger(ConversationRepository.class);

    @Inject
    AgroalDataSource dataSource;

    @Inject
    MessageSerializer serializer;

    // ──────────────────────────────────────────────────────────────────────
    // Write operations
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Inserts a new conversation record. No-op if a conversation with the same ID already exists.
     */
    public void createConversation(String userId, String conversationId,
                                   String title, String productId) {
        String sql = """
                INSERT INTO chat_conversations (conversation_id, user_id, title, product_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (conversation_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, userId);
            ps.setString(3, title);
            setNullableString(ps, 4, productId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to create conversation %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Appends messages from {@code fromIndex} (inclusive) to the end of {@code messages} to the
     * {@code chat_messages} table. The sequence numbers match the list indices, making it safe
     * to call repeatedly within a session without re-inserting prior messages.
     *
     * @param conversationId target conversation
     * @param messages       the full accumulated message list for this conversation
     * @param fromIndex      index of the first new message to persist
     */
    public void appendMessages(String conversationId,
                                List<MessageParam> messages,
                                int fromIndex) {
        if (fromIndex >= messages.size()) {
            return;
        }
        String sql = """
                INSERT INTO chat_messages (conversation_id, message_json, sequence_num)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = fromIndex; i < messages.size(); i++) {
                ps.setString(1, conversationId);
                ps.setObject(2, buildPgJson(serializer.toJson(messages.get(i))));
                ps.setInt(3, i);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.errorf("Failed to append messages for conversation %s: %s",
                    conversationId, e.getMessage());
        }
    }

    /**
     * Bumps the {@code updated_at} timestamp on a conversation to surface it at the top of the
     * user's conversation list.
     */
    public void touch(String conversationId) {
        String sql = "UPDATE chat_conversations SET updated_at = now() WHERE conversation_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to touch conversation %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Renames a conversation. Only succeeds if the conversation is owned by {@code userId}.
     *
     * @return {@code true} if the row was updated
     */
    public boolean renameConversation(String conversationId, String userId, String title) {
        String sql = """
                UPDATE chat_conversations
                SET title = ?, updated_at = now()
                WHERE conversation_id = ? AND user_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setString(2, conversationId);
            ps.setString(3, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to rename conversation %s: %s", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes all messages with {@code sequence_num >= fromSequence} from a conversation.
     * Only succeeds if the conversation is owned by {@code userId}.
     *
     * @param conversationId target conversation
     * @param userId         owning user (JWT sub claim)
     * @param fromSequence   first sequence number to delete (inclusive)
     */
    public void truncateMessages(String conversationId, String userId, int fromSequence) {
        String sql = """
                DELETE FROM chat_messages
                WHERE conversation_id = ?
                  AND sequence_num >= ?
                  AND conversation_id IN (
                      SELECT conversation_id FROM chat_conversations
                      WHERE conversation_id = ? AND user_id = ?
                  )
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setInt(2, fromSequence);
            ps.setString(3, conversationId);
            ps.setString(4, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to truncate messages for conversation %s from seq %d: %s",
                    conversationId, fromSequence, e.getMessage());
        }
    }

    /**
     * Deletes a conversation and all its messages (CASCADE). Only succeeds if the conversation
     * is owned by {@code userId}.
     */
    public void deleteConversation(String conversationId, String userId) {
        String sql = "DELETE FROM chat_conversations WHERE conversation_id = ? AND user_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete conversation %s: %s", conversationId, e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Read operations
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a conversation with this ID exists and is owned by {@code userId}.
     */
    public boolean exists(String conversationId, String userId) {
        String sql = """
                SELECT 1 FROM chat_conversations
                WHERE conversation_id = ? AND user_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check conversation %s: %s", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * Loads the full message history for a conversation, ordered by {@code sequence_num}.
     * Returns an empty list if the conversation does not exist or is not owned by {@code userId}.
     */
    public List<MessageParam> loadMessages(String conversationId, String userId) {
        String sql = """
                SELECT cm.message_json
                FROM chat_messages cm
                JOIN chat_conversations cc ON cc.conversation_id = cm.conversation_id
                WHERE cm.conversation_id = ? AND cc.user_id = ?
                ORDER BY cm.sequence_num ASC
                """;
        List<MessageParam> messages = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString("message_json");
                    if (json != null) {
                        messages.add(serializer.fromJson(json));
                    }
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to load messages for conversation %s: %s",
                    conversationId, e.getMessage());
        }
        return messages;
    }

    /**
     * Returns all conversations owned by {@code userId}, ordered by most recently updated first.
     * Includes the total message count per conversation.
     */
    public List<ConversationSummary> listConversations(String userId) {
        String sql = """
                SELECT cc.conversation_id, cc.title, cc.product_id,
                       cc.created_at, cc.updated_at,
                       COUNT(cm.id) AS message_count
                FROM chat_conversations cc
                LEFT JOIN chat_messages cm ON cm.conversation_id = cc.conversation_id
                WHERE cc.user_id = ?
                GROUP BY cc.conversation_id, cc.title, cc.product_id, cc.created_at, cc.updated_at
                ORDER BY cc.updated_at DESC
                """;
        List<ConversationSummary> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapSummaryRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list conversations for user %s: %s", userId, e.getMessage());
        }
        return results;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private ConversationSummary mapSummaryRow(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ConversationSummary(
                rs.getString("conversation_id"),
                rs.getString("title"),
                rs.getString("product_id"),
                createdAt != null ? createdAt.toInstant() : Instant.EPOCH,
                updatedAt != null ? updatedAt.toInstant() : Instant.EPOCH,
                (int) rs.getLong("message_count")
        );
    }

    private PGobject buildPgJson(String json) throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        obj.setValue(json);
        return obj;
    }

    private void setNullableString(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }
}
