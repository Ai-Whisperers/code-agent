package com.eneve.agent.attachment;

import java.time.Instant;

/**
 * Represents a file attachment uploaded to a chat conversation.
 * Attachments are stored in S3 with metadata tracked in the database.
 */
public record ChatAttachment(
        Long id,
        String attachmentId,
        String conversationId,
        Long messageId, // nullable - attachment can exist before being linked to a message
        String filename,
        String contentType,
        long fileSize,
        String s3Bucket,
        String s3Key,
        Instant uploadedAt
) {
    
    /**
     * Create a new attachment without database ID (for insertion)
     */
    public static ChatAttachment create(
            String attachmentId,
            String conversationId,
            Long messageId,
            String filename,
            String contentType,
            long fileSize,
            String s3Bucket,
            String s3Key) {
        return new ChatAttachment(
                null, 
                attachmentId, 
                conversationId, 
                messageId, 
                filename, 
                contentType, 
                fileSize, 
                s3Bucket, 
                s3Key, 
                Instant.now()
        );
    }

    /**
     * Get file extension from filename
     */
    public String getFileExtension() {
        if (filename == null) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "";
    }

    /**
     * Check if this attachment is an image
     */
    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }

    /**
     * Check if this attachment is a text file
     */
    public boolean isText() {
        return contentType != null && (
            contentType.startsWith("text/") ||
            contentType.equals("application/json") ||
            contentType.equals("application/xml")
        );
    }

    /**
     * Get human-readable file size
     */
    public String getFormattedFileSize() {
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
    }
}
