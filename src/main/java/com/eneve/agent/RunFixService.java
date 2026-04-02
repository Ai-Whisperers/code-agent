package com.eneve.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.AgentRunner;
import com.eneve.agent.exception.JobConflictException;
import com.eneve.agent.exception.JobNotFoundException;
import com.eneve.agent.exception.JobQueueFullException;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.GitConfig;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.AikidoFixRequest;
import com.eneve.agent.model.FixPrRequest;
import com.eneve.agent.model.GenerateDocsRequest;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.model.QuickFixRequest;
import com.eneve.agent.model.RejectRequest;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.model.SyncConfluenceRequest;
import com.eneve.agent.settings.SettingsService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RunFixService {

    private static final Logger LOG = Logger.getLogger(RunFixService.class);

    @Inject AgentRunner agentRunner;
    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject JiraService jiraService;
    @Inject AikidoService aikidoService;
    @Inject AuditService auditService;
    @Inject SettingsService settings;
    @Inject Soc2Policy soc2Policy;
    @Inject CustomerRegistryStore registryStore;

    // ── RunFix-specific exceptions ────────────────────────────────────────

    public static class AikidoNotConfiguredException extends RuntimeException {
        public AikidoNotConfiguredException(String message) { super(message); }
    }

    public static class Soc2DeletionBlockedException extends RuntimeException {
        public Soc2DeletionBlockedException(String message) { super(message); }
    }

    // ── Result types ──────────────────────────────────────────────────────

    public record QuickFixResult(String jobId, String branch) {}

    public record AikidoFixResult(
            String jobId, String branch,
            int aikidoGroupId, String packageName,
            String currentVersion, String fixedVersion,
            String cve, String severity) {}

    public record SyncJiraResult(
            int found,
            List<Map<String, String>> queuedJobs,
            List<Map<String, String>> skipped) {
        public int queued() { return queuedJobs.size(); }
    }

    // ── Public service methods ────────────────────────────────────────────

    public String runFix(RunFixRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        if (request.branchName() == null || request.branchName().isBlank()) {
            throw new IllegalArgumentException("branchName is required");
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            throw new JobQueueFullException("Job queue is full");
        }

        String effectiveJiraKey = request.jiraKey() != null && !request.jiraKey().isBlank()
                ? request.jiraKey() : "N/A";
        LOG.infof("Job %s accepted for %s", jobId, effectiveJiraKey);
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "FIX", "jiraKey", effectiveJiraKey));
        return jobId;
    }

    public QuickFixResult quickFix(QuickFixRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        if (request.jiraKey() == null || request.jiraKey().isBlank()) {
            throw new IllegalArgumentException("jiraKey is required");
        }

        String summary;
        try {
            summary = jiraService.fetchIssueSummary(request.jiraKey());
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to fetch JIRA issue: " + e.getMessage());
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("Could not fetch JIRA issue " + request.jiraKey());
        }

        String branchName = "agent/" + request.jiraKey() + "-" + slugify(summary);

        RunFixRequest fullRequest = new RunFixRequest(
                request.repoUrl(),
                branchName,
                request.jiraKey(),
                null,
                "develop",
                null, null, null, null, null, null
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, fullRequest);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            throw new JobQueueFullException("Job queue is full");
        }

        LOG.infof("Quick-fix job %s accepted for %s (branch: %s)", jobId, request.jiraKey(), branchName);
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "QUICK_FIX", "jiraKey", request.jiraKey()));
        return new QuickFixResult(jobId, branchName);
    }

    public AikidoFixResult aikidoFix(AikidoFixRequest request) {
        if (!aikidoService.isEnabled()) {
            throw new AikidoNotConfiguredException(
                    "Aikido integration not configured. Set AIKIDO_CLIENT_ID and AIKIDO_CLIENT_SECRET.");
        }

        if ((request.jiraKey() == null || request.jiraKey().isBlank())
                && request.aikidoGroupId() == null) {
            throw new IllegalArgumentException("Either jiraKey or aikidoGroupId is required");
        }

        Integer groupId = request.aikidoGroupId();
        if (groupId == null) {
            groupId = aikidoService.findIssueGroupByJiraKey(request.jiraKey());
        }

        AikidoIssueInfo issueInfo = null;
        if (groupId != null) {
            issueInfo = aikidoService.getIssueGroupDetail(groupId);
        }

        JiraService.JiraDescriptionContext descCtx = null;
        if (issueInfo == null && request.jiraKey() != null) {
            LOG.infof("Aikido API lookup failed for %s, checking JIRA description for Aikido URL", request.jiraKey());
            descCtx = jiraService.extractDescriptionContext(request.jiraKey());
            for (Integer candidateId : descCtx.aikidoCandidateIds()) {
                LOG.infof("Trying Aikido candidate ID: %d", candidateId);
                issueInfo = aikidoService.getIssueGroupDetail(candidateId);
                if (issueInfo != null) {
                    groupId = candidateId;
                    LOG.infof("Aikido issue resolved via JIRA description: group ID %d", groupId);
                    break;
                }
            }
        }

        if (issueInfo == null) {
            throw new IllegalArgumentException("No Aikido issue found for JIRA key: "
                    + request.jiraKey() + ". Checked: Aikido linked issues, JIRA description for Aikido URL.");
        }

        String repoUrl = request.repoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            repoUrl = issueInfo.repoUrl();
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            repoUrl = resolveRepoUrlFromContainer(issueInfo, descCtx, request.jiraKey());
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            repoUrl = resolveRepoUrlFromRegistry(issueInfo.repoName());
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Could not resolve repository URL for '"
                    + issueInfo.repoName() + "'. Provide repoUrl explicitly.");
        }

        String jiraKey = request.jiraKey();
        if (jiraKey == null || jiraKey.isBlank()) {
            jiraKey = "AIKIDO-" + groupId;
        }

        String prompt = issueInfo.toPromptSection();
        String branchSlug = slugify(issueInfo.packageName() + "-" + (issueInfo.fixedVersion() != null
                ? issueInfo.fixedVersion() : "fix"));
        String branchName = "agent/" + jiraKey + "-" + branchSlug;

        RunFixRequest fullRequest = new RunFixRequest(
                repoUrl,
                branchName,
                jiraKey,
                prompt,
                "develop",
                null, null,
                request.ruleNames(),
                request.extraRules(),
                null, null
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, fullRequest);

        job.setAikidoIssueId(String.valueOf(groupId));
        job.setFixBranchName(branchName);

        try {
            String[] slaMeta = jiraService.getIssueSlaMeta(jiraKey);
            if (slaMeta[0] != null && !slaMeta[0].isBlank()) job.setJiraPriority(slaMeta[0]);
            if (slaMeta[1] != null && !slaMeta[1].isBlank()) job.setJiraIssueType(slaMeta[1]);
            if (slaMeta[2] != null && !slaMeta[2].isBlank()) {
                try {
                    job.setJiraCreatedAt(java.time.OffsetDateTime.parse(slaMeta[2]).toInstant());
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOG.warnf("Could not fetch JIRA SLA meta for %s: %s", jiraKey, e.getMessage());
        }

        if (job.getJiraPriority() == null || job.getJiraPriority().isBlank()) {
            String sev = issueInfo.severity();
            if ("critical".equalsIgnoreCase(sev)) job.setJiraPriority("Critical");
            else if ("high".equalsIgnoreCase(sev)) job.setJiraPriority("High");
            else job.setJiraPriority("Medium");
        }
        if (job.getJiraIssueType() == null || job.getJiraIssueType().isBlank()) {
            job.setJiraIssueType("Bug");
        }

        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            throw new JobQueueFullException("Job queue is full");
        }

        LOG.infof("Aikido-fix job %s accepted for %s (group=%d, package=%s, branch=%s)",
                jobId, jiraKey, groupId, issueInfo.packageName(), branchName);
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "AIKIDO_FIX", "jiraKey", jiraKey,
                       "aikidoGroupId", String.valueOf(groupId)));
        auditService.log("SOC2", "SLA_STARTED", "job", jobId,
                Map.of("jiraKey", jiraKey, "severity", issueInfo.severity(),
                       "aikidoGroupId", String.valueOf(groupId)));

        return new AikidoFixResult(
                jobId, branchName, groupId,
                issueInfo.packageName(),
                issueInfo.currentVersion() != null ? issueInfo.currentVersion() : "",
                issueInfo.fixedVersion()   != null ? issueInfo.fixedVersion()   : "",
                issueInfo.cveId()          != null ? issueInfo.cveId()          : "",
                issueInfo.severity());
    }

    public String reviewPr(ReviewPrRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        if (request.prId() == null || request.prId().isBlank()) {
            throw new IllegalArgumentException("prId is required");
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        if (request.prAuthor() != null && !request.prAuthor().isBlank()) {
            job.setPrAuthor(request.prAuthor());
        }
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            throw new JobQueueFullException("Job queue is full");
        }

        LOG.infof("Review job %s accepted for PR #%s on %s", jobId, request.prId(), request.repoUrl());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "REVIEW", "prId", request.prId()));
        return jobId;
    }

    public String fixPr(FixPrRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        if (request.prId() == null || request.prId().isBlank()) {
            throw new IllegalArgumentException("prId is required");
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            throw new JobQueueFullException("Job queue is full");
        }

        LOG.infof("Fix-PR job %s accepted for PR #%s on %s", jobId, request.prId(), request.repoUrl());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "FIX_PR", "prId", request.prId()));
        return jobId;
    }

    public String generateTests(GenerateTestsRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        if (request.branchName() == null || request.branchName().isBlank()) {
            throw new IllegalArgumentException("branchName is required");
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            throw new JobQueueFullException("Job queue is full");
        }

        LOG.infof("GenerateTests job %s accepted for %s (branch: %s)", jobId, request.repoUrl(), request.branchName());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "GENERATE_TESTS", "branch", request.branchName()));
        return jobId;
    }

    public String generateDocs(GenerateDocsRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }

        String branchName = request.branchName();
        if (!request.isCommitDirect() && (branchName == null || branchName.isBlank())) {
            branchName = "agent/generate-docs";
        }

        String jobId = UUID.randomUUID().toString();
        GenerateDocsRequest effective = new GenerateDocsRequest(
                request.repoUrl(), branchName, request.targetBranch(),
                request.ruleNames(), request.extraRules(), request.n8nWebhookUrl(),
                request.commitDirect());
        JobRecord job = new JobRecord(jobId, effective);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            throw new JobQueueFullException("Job queue is full");
        }

        LOG.infof("GenerateDocs job %s accepted for %s", jobId, request.repoUrl());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "GENERATE_DOCS"));
        return jobId;
    }

    public String syncConfluence(SyncConfluenceRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            throw new JobQueueFullException("Job queue is full");
        }

        LOG.infof("SyncConfluence job %s accepted for %s", jobId, request.repoUrl());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "SYNC_CONFLUENCE"));
        return jobId;
    }

    public JobStatusResponse getStatus(String jobId) {
        return jobStore.get(jobId)
                .map(job -> buildStatusResponse(job, jobId))
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));
    }

    public void reject(String jobId, RejectRequest request) {
        String reason = request != null ? request.reason() : null;
        JobRecord job = jobStore.get(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));

        if (job.getStatus() != JobStatus.AWAITING_APPROVAL) {
            throw new JobConflictException(
                    "Job is not awaiting approval. Current status: " + job.getStatus());
        }
        agentRunner.reject(job, reason);
        auditService.log("JOBS", "JOB_REJECTED", "job", jobId,
                reason != null ? Map.of("reason", reason) : null);
    }

    public void cancelJob(String jobId) {
        JobRecord job = jobStore.get(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));

        if (JobStore.isSoc2Applicable(job, soc2Policy.bugIssueTypes())) {
            auditService.log("SOC2", "SOC2_DELETE_BLOCKED", "job", jobId, null);
            throw new Soc2DeletionBlockedException(
                    "SOC II: This job is linked to a Bug ticket and cannot be deleted. "
                    + "Records must be retained for compliance.");
        }

        if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.QUEUED) {
            throw new JobConflictException(
                    "Job cannot be cancelled. Current status: " + job.getStatus());
        }
        if (!jobQueue.cancelJob(jobId)) {
            throw new JobConflictException("Failed to cancel job: " + jobId);
        }
        auditService.log("JOBS", "JOB_CANCELLED", "job", jobId, null);
    }

    public String rerunJob(String jobId) {
        JobRecord job = jobStore.get(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));

        if (job.getStatus() != JobStatus.FAILED && job.getStatus() != JobStatus.SUCCESS) {
            throw new JobConflictException(
                    "Job cannot be rerun. Current status: " + job.getStatus());
        }
        String newJobId = jobQueue.rerunJob(job);
        if (newJobId == null) {
            throw new JobConflictException("Job type cannot be rerun: " + job.getJobType());
        }
        auditService.log("JOBS", "JOB_RERUN", "job", jobId, Map.of("newJobId", newJobId));
        return newJobId;
    }

    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "availableSlots", jobQueue.getAvailableSlots(),
                "runningJobs", jobQueue.getRunningCount(),
                "queuedJobs", jobQueue.getQueueDepth(),
                "maxConcurrentJobs", jobQueue.getMaxConcurrentJobs(),
                "maxQueueSize", jobQueue.getMaxQueueSize()
        );
    }

    public SyncJiraResult syncJira() {
        String label = agentLabel();
        if (label.isBlank()) {
            throw new IllegalArgumentException("jira.agent.label not configured");
        }

        var issues = jiraService.searchIssuesByLabel(label);
        if (issues.isEmpty()) {
            LOG.infof("sync-jira: no open issues found with label %s", label);
            return new SyncJiraResult(0, List.of(), List.of());
        }

        LOG.infof("sync-jira: found %d open issues with label %s:", issues.size(), label);
        for (var issue : issues) {
            LOG.infof("  - %s: %s", issue.key(), issue.summary());
        }

        List<Map<String, String>> queued = new ArrayList<>();
        List<Map<String, String>> skipped = new ArrayList<>();

        for (var issue : issues) {
            if (jobStore.hasActiveJobForJiraKey(issue.key())) {
                skipped.add(Map.of("key", issue.key(), "reason", "Active job exists"));
                continue;
            }

            String repoUrl = null;
            String prompt = null;
            String branchSuffix;

            if (aikidoService.isEnabled()) {
                var enrichment = resolveAikidoContext(issue.key());
                if (enrichment != null) {
                    repoUrl = enrichment.repoUrl;
                    prompt = enrichment.prompt;
                    branchSuffix = enrichment.branchSuffix;
                    LOG.infof("sync-jira: Aikido context resolved for %s", issue.key());
                } else {
                    branchSuffix = slugify(issue.summary());
                }
            } else {
                branchSuffix = slugify(issue.summary());
            }

            if (repoUrl == null || repoUrl.isBlank()) {
                repoUrl = defaultRepoUrl();
            }
            if (repoUrl == null || repoUrl.isBlank()) {
                skipped.add(Map.of("key", issue.key(), "reason", "No repo URL available"));
                continue;
            }

            String branchName = "agent/" + issue.key() + "-" + branchSuffix;
            RunFixRequest fullRequest = new RunFixRequest(
                    repoUrl, branchName, issue.key(), prompt,
                    "develop", null, null, null, null, null, null
            );

            String jobId = UUID.randomUUID().toString();
            JobRecord job = new JobRecord(jobId, fullRequest);
            jobStore.put(job);

            if (!jobQueue.submit(job)) {
                skipped.add(Map.of("key", issue.key(), "reason", "Queue full"));
                break;
            }

            queued.add(Map.of("key", issue.key(), "jobId", jobId, "branch", branchName));
        }

        LOG.infof("sync-jira: found=%d, queued=%d, skipped=%d",
                issues.size(), queued.size(), skipped.size());
        auditService.log("JOBS", "JIRA_SYNC", "jira", null,
                Map.of("found", String.valueOf(issues.size()),
                       "queued", String.valueOf(queued.size()),
                       "skipped", String.valueOf(skipped.size())));

        return new SyncJiraResult(issues.size(), queued, skipped);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private String agentLabel()    { return settings.get("jira.agent.label", "WALL-E"); }
    private String defaultRepoUrl() { return settings.get("jira.agent.default-repo-url", ""); }

    private record AikidoEnrichment(String repoUrl, String prompt, String branchSuffix) {}

    private AikidoEnrichment resolveAikidoContext(String issueKey) {
        Integer groupId = aikidoService.findIssueGroupByJiraKey(issueKey);

        JiraService.JiraDescriptionContext descCtx = null;
        if (groupId == null) {
            descCtx = jiraService.extractDescriptionContext(issueKey);
            for (Integer candidateId : descCtx.aikidoCandidateIds()) {
                AikidoIssueInfo info = aikidoService.getIssueGroupDetail(candidateId);
                if (info != null) {
                    groupId = candidateId;
                    break;
                }
            }
        }

        if (groupId == null) return null;

        AikidoIssueInfo issueInfo = aikidoService.getIssueGroupDetail(groupId);
        if (issueInfo == null) return null;

        String repoUrl = (issueInfo.repoUrl() != null && !issueInfo.repoUrl().isBlank())
                ? issueInfo.repoUrl() : null;
        if (repoUrl == null) {
            repoUrl = resolveRepoUrlFromContainer(issueInfo, descCtx, issueKey);
        }
        if (repoUrl == null) {
            repoUrl = resolveRepoUrlFromRegistry(issueInfo.repoName());
        }

        String prompt = issueInfo.toPromptSection();
        String branchSuffix = slugify(issueInfo.packageName() + "-"
                + (issueInfo.fixedVersion() != null ? issueInfo.fixedVersion() : "fix"));

        return new AikidoEnrichment(repoUrl, prompt, branchSuffix);
    }

    /**
     * Looks up the clone URL for a repo slug by scanning all configured products in the
     * customer registry. Checks both {@code git.repos} (programmatic) and
     * {@code metadata.repos} (UI-managed) to find a matching slug, then constructs the
     * clone URL from the product's git workspace and platform.
     */
    private String resolveRepoUrlFromRegistry(String repoSlug) {
        if (repoSlug == null || repoSlug.isBlank()) return null;
        // Strip org prefix if present (e.g. "julesenergy/fit" → "fit")
        String slug = repoSlug.contains("/")
                ? repoSlug.substring(repoSlug.lastIndexOf('/') + 1)
                : repoSlug;

        GitConfig fallbackGit = null;
        try {
            for (ProductConfig product : registryStore.listAllProducts()) {
                GitConfig git = product.git();
                if (git == null || git.workspace() == null) continue;
                if (fallbackGit == null) fallbackGit = git;

                // Collect slugs from both sources
                java.util.List<String> slugs = new java.util.ArrayList<>();
                if (git.repos() != null) slugs.addAll(git.repos());
                if (product.metadata() != null) {
                    Object raw = product.metadata().get("repos");
                    if (raw instanceof java.util.List<?> list) {
                        for (Object item : list) {
                            if (item instanceof String s) slugs.add(s);
                        }
                    }
                }

                for (String r : slugs) {
                    if (r.equalsIgnoreCase(slug)) {
                        String base = (git.baseUrl() != null && !git.baseUrl().isBlank())
                                ? git.baseUrl().replaceAll("/$", "")
                                : "https://bitbucket.org";
                        String url = base + "/" + git.workspace() + "/" + slug + ".git";
                        LOG.infof("Resolved repo URL for '%s' from registry (product=%s): %s",
                                slug, product.productId(), url);
                        return url;
                    }
                }
            }
            // Repo not listed under any product — assume it lives in the same workspace
            if (fallbackGit != null) {
                String base = (fallbackGit.baseUrl() != null && !fallbackGit.baseUrl().isBlank())
                        ? fallbackGit.baseUrl().replaceAll("/$", "")
                        : "https://bitbucket.org";
                String url = base + "/" + fallbackGit.workspace() + "/" + slug + ".git";
                LOG.infof("Resolved repo URL for '%s' via workspace fallback: %s", slug, url);
                return url;
            }
        } catch (Exception e) {
            LOG.warnf("Failed to resolve repo URL from registry for '%s': %s", slug, e.getMessage());
        }
        return null;
    }

    private String resolveRepoUrlFromContainer(AikidoIssueInfo issueInfo,
                                                JiraService.JiraDescriptionContext descCtx,
                                                String jiraKey) {
        if (issueInfo.containerImage() != null && !issueInfo.containerImage().isBlank()) {
            LOG.infof("Aikido issue references container image '%s', searching for matching code repo",
                    issueInfo.containerImage());
            String url = aikidoService.findCodeRepoUrlForContainer(issueInfo.containerImage());
            if (url != null) return url;
        }

        if (descCtx == null && jiraKey != null) {
            descCtx = jiraService.extractDescriptionContext(jiraKey);
        }
        if (descCtx != null) {
            for (String container : descCtx.containerNames()) {
                LOG.infof("JIRA description references container '%s', searching for matching code repo",
                        container);
                String url = aikidoService.findCodeRepoUrlForContainer(container);
                if (url != null) return url;
            }
        }
        return null;
    }

    private JobStatusResponse buildStatusResponse(JobRecord job, String jobId) {
        int criticalDays = soc2Policy.criticalSlaDays();
        int highDays     = soc2Policy.highSlaDays();
        String bugTypes  = soc2Policy.bugIssueTypes();
        boolean scytaleEnabled = !settings.get("scytale.api.key", "").isBlank();
        List<String> bugList = Arrays.asList(bugTypes.split("\\s*,\\s*"));
        return JobStatusResponse.from(job, jobQueue.getQueuePosition(jobId),
                criticalDays, highDays, bugList, scytaleEnabled);
    }


    private static String slugify(String text) {
        if (text == null || text.isBlank()) return "fix";
        String slug = text.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-$", "");
        }
        return slug;
    }
}
