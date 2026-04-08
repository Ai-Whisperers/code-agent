package com.eneve.agent;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.agent.service.RepoSyncService;
import com.eneve.agent.agent.service.WebhookSyncService;
import com.eneve.agent.audit.AuditService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RepoSettingsService {

    private static final Logger LOG = Logger.getLogger(RepoSettingsService.class);

    @Inject
    RepoSettingsStore settingsStore;

    @Inject
    WebhookSyncService webhookSyncService;

    @Inject
    RepoSyncService repoSyncService;

    @Inject
    AuditService auditService;

    // ── Custom exceptions ─────────────────────────────────────────────────

    public static class RepoNotFoundException extends RuntimeException {
        public RepoNotFoundException(String message) { super(message); }
    }

    // ── Public service methods ────────────────────────────────────────────

    public void syncRepos() {
        try {
            repoSyncService.syncRepos();
        } catch (Exception e) {
            LOG.warnf("Manual repo sync failed (non-fatal): %s", e.getMessage());
        }
        auditService.log("REPO_SETTINGS", "REPO_SYNC", "repo_settings", null, null);
    }

    public List<RepoSettings> listAll() {
        return settingsStore.listAll();
    }

    public RepoSettings get(String workspace, String repoSlug) {
        return settingsStore.find(workspace, repoSlug)
                .orElseThrow(() -> new RepoNotFoundException(
                        "No settings found for " + workspace + "/" + repoSlug));
    }

    public void upsert(String workspace, String repoSlug,
                       boolean reviewEnabled, boolean vectorEnabled,
                       boolean docsEnabled, boolean upgradeEnabled,
                       boolean qualityReportEnabled, boolean archived,
                       List<String> ruleNames, String reviewPrompt,
                       List<String> disabledHooks, String confluenceSpaceKey,
                       String confluenceParentPageId, String gitPlatformUrl,
                       String description, String primaryLanguage,
                       List<String> jiraComponents, List<String> tags) {

        settingsStore.upsert(workspace, repoSlug, reviewEnabled, vectorEnabled, docsEnabled,
                upgradeEnabled, qualityReportEnabled, archived, ruleNames, reviewPrompt,
                disabledHooks, confluenceSpaceKey, confluenceParentPageId, gitPlatformUrl,
                description, primaryLanguage, jiraComponents, tags);

        try {
            if (reviewEnabled && !archived) {
                webhookSyncService.ensureWebhooks(workspace, repoSlug);
            } else {
                webhookSyncService.removeWebhooks(workspace, repoSlug);
            }
        } catch (Exception e) {
            LOG.warnf("Webhook sync failed after upsert for %s/%s (non-fatal): %s",
                    workspace, repoSlug, e.getMessage());
        }

        auditService.log("REPO_SETTINGS", "REPO_SETTINGS_SAVED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void enableReview(String workspace, String repoSlug) {
        RepoSettings existing = requireExists(workspace, repoSlug);
        settingsStore.setReviewEnabled(workspace, repoSlug, true);
        if (!existing.archived()) {
            try {
                webhookSyncService.ensureWebhooks(workspace, repoSlug);
            } catch (Exception e) {
                LOG.warnf("Webhook sync failed after enable for %s/%s (non-fatal): %s",
                        workspace, repoSlug, e.getMessage());
            }
        }
        auditService.log("REPO_SETTINGS", "REPO_REVIEW_ENABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void disableReview(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setReviewEnabled(workspace, repoSlug, false);
        try {
            webhookSyncService.removeWebhooks(workspace, repoSlug);
        } catch (Exception e) {
            LOG.warnf("Webhook sync failed after disable for %s/%s (non-fatal): %s",
                    workspace, repoSlug, e.getMessage());
        }
        auditService.log("REPO_SETTINGS", "REPO_REVIEW_DISABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void enableVector(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setVectorEnabled(workspace, repoSlug, true);
        auditService.log("REPO_SETTINGS", "REPO_VECTOR_ENABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void disableVector(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setVectorEnabled(workspace, repoSlug, false);
        auditService.log("REPO_SETTINGS", "REPO_VECTOR_DISABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void enableDocs(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setDocsEnabled(workspace, repoSlug, true);
        auditService.log("REPO_SETTINGS", "REPO_DOCS_ENABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void disableDocs(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setDocsEnabled(workspace, repoSlug, false);
        auditService.log("REPO_SETTINGS", "REPO_DOCS_DISABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void enableUpgrade(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setUpgradeEnabled(workspace, repoSlug, true);
        auditService.log("REPO_SETTINGS", "REPO_UPGRADE_ENABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void disableUpgrade(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setUpgradeEnabled(workspace, repoSlug, false);
        auditService.log("REPO_SETTINGS", "REPO_UPGRADE_DISABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void enableQualityReport(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setQualityReportEnabled(workspace, repoSlug, true);
        auditService.log("REPO_SETTINGS", "REPO_QUALITY_REPORT_ENABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void disableQualityReport(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setQualityReportEnabled(workspace, repoSlug, false);
        auditService.log("REPO_SETTINGS", "REPO_QUALITY_REPORT_DISABLED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void archive(String workspace, String repoSlug) {
        requireExists(workspace, repoSlug);
        settingsStore.setArchived(workspace, repoSlug, true);
        try {
            webhookSyncService.removeWebhooks(workspace, repoSlug);
        } catch (Exception e) {
            LOG.warnf("Webhook sync failed after archive for %s/%s (non-fatal): %s",
                    workspace, repoSlug, e.getMessage());
        }
        auditService.log("REPO_SETTINGS", "REPO_ARCHIVED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void unarchive(String workspace, String repoSlug) {
        RepoSettings existing = requireExists(workspace, repoSlug);
        settingsStore.setArchived(workspace, repoSlug, false);
        if (existing.reviewEnabled()) {
            try {
                webhookSyncService.ensureWebhooks(workspace, repoSlug);
            } catch (Exception e) {
                LOG.warnf("Webhook sync failed after unarchive for %s/%s (non-fatal): %s",
                        workspace, repoSlug, e.getMessage());
            }
        }
        auditService.log("REPO_SETTINGS", "REPO_UNARCHIVED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    public void delete(String workspace, String repoSlug) {
        boolean deleted = settingsStore.delete(workspace, repoSlug);
        if (!deleted) {
            throw new RepoNotFoundException(
                    "No settings found for " + workspace + "/" + repoSlug);
        }
        try {
            webhookSyncService.removeWebhooks(workspace, repoSlug);
        } catch (Exception e) {
            LOG.warnf("Webhook removal failed after delete for %s/%s (non-fatal): %s",
                    workspace, repoSlug, e.getMessage());
        }
        auditService.log("REPO_SETTINGS", "REPO_DELETED", "repo_setting",
                workspace + "/" + repoSlug, null);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private RepoSettings requireExists(String workspace, String repoSlug) {
        return settingsStore.find(workspace, repoSlug)
                .orElseThrow(() -> new RepoNotFoundException(
                        "No settings found for " + workspace + "/" + repoSlug));
    }

    public static Map<String, Object> upsertResponse(String workspace, String repoSlug,
            boolean reviewEnabled, boolean vectorEnabled, boolean docsEnabled,
            boolean upgradeEnabled, boolean qualityReportEnabled, boolean archived) {
        return Map.of(
                "action", "saved",
                "workspace", workspace,
                "repoSlug", repoSlug,
                "reviewEnabled", reviewEnabled,
                "vectorEnabled", vectorEnabled,
                "docsEnabled", docsEnabled,
                "upgradeEnabled", upgradeEnabled,
                "qualityReportEnabled", qualityReportEnabled,
                "archived", archived
        );
    }
}
