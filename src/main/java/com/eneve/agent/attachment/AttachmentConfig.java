package com.eneve.agent.attachment;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AttachmentConfig {

    @ConfigProperty(name = "attachment.s3.bucket")
    String s3Bucket;

    @ConfigProperty(name = "attachment.s3.region", defaultValue = "us-east-1")
    String s3Region;

    @ConfigProperty(name = "attachment.max-file-size", defaultValue = "10485760") // 10MB
    long maxFileSize;

    @ConfigProperty(name = "attachment.allowed-types", defaultValue = "image/jpeg,image/png,image/gif,image/webp,text/plain,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    String allowedContentTypes;

    public String getS3Bucket() {
        return s3Bucket;
    }

    public String getS3Region() {
        return s3Region;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public String[] getAllowedContentTypes() {
        return allowedContentTypes.split(",");
    }

    public boolean isContentTypeAllowed(String contentType) {
        if (contentType == null) return false;
        for (String allowed : getAllowedContentTypes()) {
            if (allowed.trim().equalsIgnoreCase(contentType.trim())) {
                return true;
            }
        }
        return false;
    }
}
