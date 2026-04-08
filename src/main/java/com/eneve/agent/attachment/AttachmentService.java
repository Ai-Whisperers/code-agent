package com.eneve.agent.attachment;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jboss.logging.Logger;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;


@ApplicationScoped
public class AttachmentService {

    private static final Logger LOG = Logger.getLogger(AttachmentService.class);

    @Inject
    AgroalDataSource dataSource;

    @Inject
    AttachmentConfig config;

    @Inject
    S3Client s3Client;

    @Inject
    S3Presigner s3Presigner;

    /**
     * Upload a file to S3 and store metadata in database
     */
    public ChatAttachment uploadAttachment(
            String conversationId,
            Long messageId,
            String filename,
            String contentType,
            long fileSize,
            InputStream fileContent) {
        
        // Validate file size and type
        if (fileSize > config.getMaxFileSize()) {
            throw new IllegalArgumentException("File size exceeds maximum allowed: " + config.getMaxFileSize());
        }
        
        if (!config.isContentTypeAllowed(contentType)) {
            throw new IllegalArgumentException("Content type not allowed: " + contentType);
        }

        String attachmentId = UUID.randomUUID().toString();
        String s3Key = generateS3Key(conversationId, attachmentId, filename);

        try {
            // Upload to S3
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(config.getS3Bucket())
                    .key(s3Key)
                    .contentType(contentType)
                    .contentLength(fileSize)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(fileContent, fileSize));
            LOG.infof("Uploaded attachment %s to S3: %s", attachmentId, s3Key);

            // Store metadata in database
            ChatAttachment attachment = new ChatAttachment(
                    null, // id will be generated
                    attachmentId,
                    conversationId,
                    messageId,
                    filename,
                    contentType,
                    fileSize,
                    config.getS3Bucket(),
                    s3Key,
                    Instant.now()
            );

            return saveAttachmentMetadata(attachment);

        } catch (Exception e) {
            LOG.errorf("Failed to upload attachment %s: %s", attachmentId, e.getMessage());
            throw new RuntimeException("Failed to upload attachment", e);
        }
    }

    /**
     * Get attachment metadata by attachment ID
     */
    public Optional<ChatAttachment> getAttachment(String attachmentId) {
        String sql = """
                SELECT id, attachment_id, conversation_id, message_id, filename, 
                       content_type, file_size, s3_bucket, s3_key, uploaded_at
                FROM chat_attachments
                WHERE attachment_id = ?
                """;
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, attachmentId);
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapAttachmentFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get attachment %s: %s", attachmentId, e.getMessage());
        }
        
        return Optional.empty();
    }

    /**
     * Get all attachments for a conversation
     */
    public List<ChatAttachment> getAttachmentsByConversation(String conversationId) {
        String sql = """
                SELECT id, attachment_id, conversation_id, message_id, filename, 
                       content_type, file_size, s3_bucket, s3_key, uploaded_at
                FROM chat_attachments
                WHERE conversation_id = ?
                ORDER BY uploaded_at ASC
                """;
        
        List<ChatAttachment> attachments = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    attachments.add(mapAttachmentFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get attachments for conversation %s: %s", conversationId, e.getMessage());
        }
        
        return attachments;
    }

    /**
     * Get attachments for a specific message
     */
    public List<ChatAttachment> getAttachmentsByMessage(Long messageId) {
        String sql = """
                SELECT id, attachment_id, conversation_id, message_id, filename, 
                       content_type, file_size, s3_bucket, s3_key, uploaded_at
                FROM chat_attachments
                WHERE message_id = ?
                ORDER BY uploaded_at ASC
                """;
        
        List<ChatAttachment> attachments = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    attachments.add(mapAttachmentFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get attachments for message %s: %s", messageId, e.getMessage());
        }
        
        return attachments;
    }

    /**
     * Generate a presigned URL for downloading an attachment
     */
    public String generatePresignedDownloadUrl(String attachmentId, int expirationMinutes) {
        Optional<ChatAttachment> attachment = getAttachment(attachmentId);
        if (attachment.isEmpty()) {
            throw new IllegalArgumentException("Attachment not found: " + attachmentId);
        }

        ChatAttachment att = attachment.get();
        
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(att.s3Bucket())
                .key(att.s3Key())
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(java.time.Duration.ofMinutes(expirationMinutes))
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }

    /**
     * Delete an attachment
     */
    public boolean deleteAttachment(String attachmentId) {
        Optional<ChatAttachment> attachment = getAttachment(attachmentId);
        if (attachment.isEmpty()) {
            return false;
        }

        ChatAttachment att = attachment.get();

        try {
            // Delete from S3
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(att.s3Bucket())
                    .key(att.s3Key())
                    .build();
            
            s3Client.deleteObject(deleteRequest);
            LOG.infof("Deleted attachment from S3: %s", att.s3Key());

            // Delete from database
            String sql = "DELETE FROM chat_attachments WHERE attachment_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, attachmentId);
                int deleted = ps.executeUpdate();
                LOG.infof("Deleted attachment metadata: %s", attachmentId);
                return deleted > 0;
            }

        } catch (Exception e) {
            LOG.errorf("Failed to delete attachment %s: %s", attachmentId, e.getMessage());
            return false;
        }
    }

    /**
     * Get attachment content bytes from S3
     */
    public byte[] getAttachmentContent(ChatAttachment attachment) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(attachment.s3Bucket())
                    .key(attachment.s3Key())
                    .build();
            
            return s3Client.getObjectAsBytes(getRequest).asByteArray();
        } catch (Exception e) {
            LOG.errorf("Failed to get attachment content for %s: %s", attachment.attachmentId(), e.getMessage());
            throw new RuntimeException("Failed to fetch attachment content from S3", e);
        }
    }

    /**
     * Update message_id for attachments after they've been sent in a message
     */
    public void linkAttachmentsToMessage(List<String> attachmentIds, Long messageId) {
        if (attachmentIds == null || attachmentIds.isEmpty() || messageId == null) {
            return;
        }
        
        String sql = "UPDATE chat_attachments SET message_id = ? WHERE attachment_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String attachmentId : attachmentIds) {
                ps.setLong(1, messageId);
                ps.setString(2, attachmentId);
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.infof("Linked %d attachments to message %s", attachmentIds.size(), messageId);
        } catch (SQLException e) {
            LOG.errorf("Failed to link attachments to message %s: %s", messageId, e.getMessage());
        }
    }

    private ChatAttachment saveAttachmentMetadata(ChatAttachment attachment) {
        String sql = """
                INSERT INTO chat_attachments 
                (attachment_id, conversation_id, message_id, filename, content_type, 
                 file_size, s3_bucket, s3_key, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, attachment.attachmentId());
            ps.setString(2, attachment.conversationId());
            if (attachment.messageId() != null) {
                ps.setLong(3, attachment.messageId());
            } else {
                ps.setNull(3, java.sql.Types.BIGINT);
            }
            ps.setString(4, attachment.filename());
            ps.setString(5, attachment.contentType());
            ps.setLong(6, attachment.fileSize());
            ps.setString(7, attachment.s3Bucket());
            ps.setString(8, attachment.s3Key());
            ps.setTimestamp(9, Timestamp.from(attachment.uploadedAt()));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Long id = rs.getLong("id");
                    return new ChatAttachment(
                            id,
                            attachment.attachmentId(),
                            attachment.conversationId(),
                            attachment.messageId(),
                            attachment.filename(),
                            attachment.contentType(),
                            attachment.fileSize(),
                            attachment.s3Bucket(),
                            attachment.s3Key(),
                            attachment.uploadedAt()
                    );
                } else {
                    throw new SQLException("Failed to get generated ID");
                }
            }

        } catch (SQLException e) {
            LOG.errorf("Failed to save attachment metadata: %s", e.getMessage());
            throw new RuntimeException("Failed to save attachment metadata", e);
        }
    }

    private ChatAttachment mapAttachmentFromResultSet(ResultSet rs) throws SQLException {
        return new ChatAttachment(
                rs.getLong("id"),
                rs.getString("attachment_id"),
                rs.getString("conversation_id"),
                rs.getObject("message_id", Long.class),
                rs.getString("filename"),
                rs.getString("content_type"),
                rs.getLong("file_size"),
                rs.getString("s3_bucket"),
                rs.getString("s3_key"),
                rs.getTimestamp("uploaded_at").toInstant()
        );
    }

    private String generateS3Key(String conversationId, String attachmentId, String filename) {
        // Generate a structured S3 key: conversations/{conversationId}/attachments/{attachmentId}/{filename}
        return String.format("conversations/%s/attachments/%s/%s", conversationId, attachmentId, filename);
    }
}
