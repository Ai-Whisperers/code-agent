package com.eneve.agent.agent.model;

import java.time.Instant;
import java.util.List;

public record WebhookAuditEntry(
        long id,
        String platform,
        String eventType,
        String workspace,
        String repoSlug,
        String prId,
        String author,
        String action,
        List<String> hooksExecuted,
        String payload,
        Instant receivedAt
) {
    public static WebhookAuditEntry create(
            String platform,
            String eventType,
            String workspace,
            String repoSlug,
            String prId,
            String author,
            String action,
            List<String> hooksExecuted,
            String payload) {
        return new WebhookAuditEntry(0, platform, eventType, workspace, repoSlug,
                prId, author, action, hooksExecuted, payload, Instant.now());
    }
}
