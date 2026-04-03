package com.eneve.agent.agent;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.GitConfig;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.SelfAnalysisRequest;
import com.eneve.agent.planner.JobCompletedEvent;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Observes {@link JobCompletedEvent} and automatically submits a {@link JobType#SELF_ANALYSIS}
 * job when a monitored job fails.
 *
 * <p>Guards (short-circuit on first failure):
 * <ol>
 *   <li>{@code self-analysis.enabled} must be {@code true}</li>
 *   <li>The completed job must have status {@link JobStatus#FAILED}</li>
 *   <li>The failed job type must be in {@code self-analysis.trigger-job-types}</li>
 *   <li>The failed job must not itself be {@code SELF_ANALYSIS} (loop prevention)</li>
 *   <li>No active SELF_ANALYSIS job for the same {@code failedJobId} (deduplication)</li>
 *   <li>No recent successful SELF_ANALYSIS for the same {@code failedJobId} within cooldown window</li>
 * </ol>
 */
@ApplicationScoped
public class SelfAnalysisTrigger {

    private static final Logger LOG = Logger.getLogger(SelfAnalysisTrigger.class);

    @Inject SettingsService settings;
    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;
    @Inject CustomerRegistryStore customerRegistry;

    public void onJobCompleted(@ObservesAsync JobCompletedEvent event) {
        // Guard 1: feature flag
        if (!Boolean.parseBoolean(settings.get("self-analysis.enabled", "false"))) {
            return;
        }

        // Guard 2: only react to failures
        if (event.status() != JobStatus.FAILED) {
            return;
        }

        String failedJobId = event.jobId();

        // Look up the failed job record to get its type
        Optional<JobRecord> failedJobOpt = jobStore.get(failedJobId);
        if (failedJobOpt.isEmpty()) {
            // Job may have been archived already — try to proceed without type check
            LOG.debugf("Self-analysis trigger: failed job %s not found in active store, skipping", failedJobId);
            return;
        }
        JobRecord failedJob = failedJobOpt.get();

        // Guard 3: job type must be in trigger-job-types
        String triggerTypesRaw = settings.get("self-analysis.trigger-job-types", "FIX");
        List<String> triggerTypes = Arrays.stream(triggerTypesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (!triggerTypes.contains(failedJob.getJobType().name())) {
            LOG.debugf("Self-analysis trigger: job type %s not in trigger-job-types (%s), skipping",
                    failedJob.getJobType(), triggerTypesRaw);
            return;
        }

        // Guard 4: prevent SELF_ANALYSIS from triggering itself
        if (failedJob.getJobType() == JobType.SELF_ANALYSIS) {
            LOG.debugf("Self-analysis trigger: skipping SELF_ANALYSIS job to prevent loop");
            return;
        }

        // Guard 5: deduplication — active check
        if (jobStore.hasActiveSelfAnalysisForJob(failedJobId)) {
            LOG.infof("Self-analysis trigger: active SELF_ANALYSIS job already exists for failed job %s, skipping",
                    failedJobId);
            return;
        }

        // Guard 6: deduplication — cooldown check
        int cooldownHours = Integer.parseInt(settings.get("self-analysis.cooldown-hours", "24"));
        if (cooldownHours > 0 && jobStore.hasRecentSuccessfulSelfAnalysisForJob(failedJobId, cooldownHours)) {
            LOG.infof("Self-analysis trigger: recent successful analysis for job %s within %d-hour cooldown, skipping",
                    failedJobId, cooldownHours);
            return;
        }

        // Resolve product config
        String productId = settings.get("self-analysis.product-id", "");
        if (productId.isBlank()) {
            LOG.warnf("Self-analysis trigger: self-analysis.product-id not configured, cannot trigger for job %s",
                    failedJobId);
            return;
        }

        Optional<ProductConfig> productOpt = customerRegistry.getProduct(productId);
        if (productOpt.isEmpty()) {
            LOG.warnf("Self-analysis trigger: product '%s' not found in registry, cannot trigger for job %s",
                    productId, failedJobId);
            return;
        }
        ProductConfig product = productOpt.get();

        // Resolve repo URL from product git config
        String repoUrl = resolveRepoUrl(product);
        if (repoUrl == null || repoUrl.isBlank()) {
            LOG.warnf("Self-analysis trigger: no repo URL found for product '%s', cannot trigger for job %s",
                    productId, failedJobId);
            return;
        }

        // Resolve customer ID
        String customerId = product.customerId();

        // Resolve environment and log group from settings
        String environmentName = settings.get("self-analysis.environment-name", "");
        String logGroupName = settings.get("self-analysis.log-group-name", "");

        // Resolve Jira project key (optional — proceed even if absent)
        String jiraProjectKey = resolveJiraProjectKey(product);

        SelfAnalysisRequest request = new SelfAnalysisRequest(
                failedJobId,
                repoUrl,
                "develop",
                customerId,
                environmentName,
                logGroupName,
                jiraProjectKey
        );

        JobRecord selfAnalysisJob = new JobRecord(UUID.randomUUID().toString(), request);
        jobQueue.submit(selfAnalysisJob);

        LOG.infof("Self-analysis job %s submitted for failed job %s (product: %s, jira: %s)",
                selfAnalysisJob.getJobId(), failedJobId, productId,
                jiraProjectKey != null ? jiraProjectKey : "(none)");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String resolveRepoUrl(ProductConfig product) {
        GitConfig git = product.git();
        if (git == null || git.workspace() == null) return null;
        if (git.repos() == null || git.repos().isEmpty()) return null;
        String repoSlug = git.repos().get(0);
        String base = (git.baseUrl() != null && !git.baseUrl().isBlank())
                ? git.baseUrl().replaceAll("/$", "")
                : "https://bitbucket.org";
        return base + "/" + git.workspace() + "/" + repoSlug + ".git";
    }

    private String resolveJiraProjectKey(ProductConfig product) {
        try {
            if (product.jira() == null) return null;
            if (product.jira().projects() == null) return null;
            return product.jira().projects().get("engineering");
        } catch (Exception e) {
            LOG.debugf("Could not resolve Jira project key for product %s: %s",
                    product.productId(), e.getMessage());
            return null;
        }
    }
}
