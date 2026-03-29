package com.eneve.agent.aikido;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.audit.AuditStore;
import com.eneve.agent.audit.AuditEntry;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.model.RunResult;
import com.eneve.agent.notifications.TeamsNotifier;
import com.eneve.agent.Soc2Policy;
import com.eneve.agent.settings.SettingsService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Orchestrates the full triage lifecycle for a new Aikido security vulnerability:
 * <ol>
 *   <li>Deduplication: skip if an active job already exists for this Aikido group.</li>
 *   <li>JIRA lookup: check whether Aikido already has a linked JIRA issue.</li>
 *   <li>JIRA creation: if no linked ticket, create a Bug with labels=[SOCII] and a due date
 *       calculated from the SLA configuration.</li>
 *   <li>Teams notification: alert the team about the new SOC2 vulnerability.</li>
 *   <li>Job dispatch: create and queue a FIX job with full SLA metadata.</li>
 * </ol>
 *
 * Called from both {@code AikidoWebhookResource} (automatic) and
 * {@code RunFixResource.aikidoFix} (manual API path).
 */
@ApplicationScoped
public class AikidoTriageService {

    private static final Logger LOG = Logger.getLogger(AikidoTriageService.class);

    @Inject AikidoService aikidoService;
    @Inject JiraService jiraService;
    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;
    @Inject TeamsNotifier teamsNotifier;
    @Inject SettingsService settings;
    @Inject AuditStore auditStore;
    @Inject Soc2Policy soc2Policy;

    /**
     * Result of a triage operation. Callers can inspect this to build an appropriate HTTP response.
     */
    public record TriageResult(
            boolean skipped,
            String skipReason,
            String jiraKey,
            boolean jiraCreated,
            String jobId,
            String branchName,
            AikidoIssueInfo issueInfo
    ) {
        public static TriageResult skipped(String reason) {
            return new TriageResult(true, reason, null, false, null, null, null);
        }

        public static TriageResult dispatched(String jiraKey, boolean jiraCreated,
                                               String jobId, String branchName,
                                               AikidoIssueInfo info) {
            return new TriageResult(false, null, jiraKey, jiraCreated, jobId, branchName, info);
        }
    }

    /**
     * Entry point for both webhook and manual API paths.
     *
     * @param groupId   Aikido issue group ID
     * @param repoUrl   repository clone URL (may be null; resolved from Aikido if absent)
     * @param severity  Aikido severity string (e.g. "critical", "high")
     * @param issueType Aikido issue type (e.g. "sca", "sast")
     */
    public TriageResult handleNewIssue(int groupId, String repoUrl, String severity, String issueType) {
        // ── 1. Deduplication ─────────────────────────────────────────────────
        String groupIdStr = String.valueOf(groupId);
        if (jobStore.hasActiveJobForAikidoGroupId(groupIdStr)) {
            LOG.infof("AikidoTriage: active job already exists for group %d — skipping", groupId);
            return TriageResult.skipped("Active job already exists for Aikido group " + groupId);
        }

        // ── 2. Load Aikido issue details ──────────────────────────────────────
        AikidoIssueInfo issueInfo = aikidoService.getIssueGroupDetail(groupId);
        if (issueInfo == null) {
            LOG.warnf("AikidoTriage: could not load details for group %d", groupId);
            return TriageResult.skipped("Could not load Aikido issue details for group " + groupId);
        }

        // Use repo URL from Aikido if not provided
        String effectiveRepoUrl = (repoUrl != null && !repoUrl.isBlank())
                ? repoUrl : issueInfo.repoUrl();
        if (effectiveRepoUrl == null || effectiveRepoUrl.isBlank()) {
            return TriageResult.skipped("No repository URL available for group " + groupId);
        }

        // ── 3. JIRA issue lookup / creation ───────────────────────────────────
        String jiraKey = aikidoService.findLinkedJiraKeyForGroup(groupId);
        boolean jiraCreated = false;

        if (jiraKey == null || jiraKey.isBlank()) {
            jiraKey = createJiraBug(issueInfo, severity);
            if (jiraKey == null) {
                LOG.warnf("AikidoTriage: JIRA creation failed for group %d — continuing with synthetic key", groupId);
                jiraKey = "AIKIDO-" + groupId;
            } else {
                jiraCreated = true;
                LOG.infof("AikidoTriage: created JIRA issue %s for Aikido group %d", jiraKey, groupId);

                // Transition to In Progress immediately
                final String key = jiraKey;
                safeJira(() -> jiraService.transitionToInProgress(key));
                safeJira(() -> jiraService.addComment(key,
                        "Aikido vulnerability detected (group " + groupId + "). "
                        + "Severity: " + issueInfo.severity().toUpperCase()
                        + ", Package: " + issueInfo.packageName()
                        + " " + nullToEmpty(issueInfo.currentVersion())
                        + " → " + nullToEmpty(issueInfo.fixedVersion())
                        + (issueInfo.cveId() != null && !issueInfo.cveId().isBlank()
                                ? " (" + issueInfo.cveId() + ")" : "")
                        + ". Fix job is being started."));
            }

            // Teams notification: new SOC2 issue
            sendNewIssueNotification(jiraKey, issueInfo, effectiveRepoUrl);
        } else {
            // Existing JIRA: also check for active job by JIRA key
            if (jobStore.hasActiveJobForJiraKey(jiraKey)) {
                LOG.infof("AikidoTriage: active job already exists for JIRA key %s — skipping", jiraKey);
                return TriageResult.skipped("Active job already exists for JIRA key " + jiraKey);
            }
        }

        // ── 4. Create and queue FIX job ───────────────────────────────────────
        String prompt = issueInfo.toPromptSection();
        String branchSlug = slugify(issueInfo.packageName() + "-"
                + (issueInfo.fixedVersion() != null ? issueInfo.fixedVersion() : "fix"));
        String branchName = "agent/" + jiraKey + "-" + branchSlug;

        RunFixRequest fixRequest = new RunFixRequest(
                effectiveRepoUrl,
                branchName,
                jiraKey,
                prompt,
                "develop",
                null, null, null, null, null, null
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, fixRequest);
        job.setAikidoIssueId(groupIdStr);
        job.setFixBranchName(branchName);

        // Populate SLA fields from JIRA
        populateSlaFields(job, jiraKey, severity);

        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            LOG.warnf("AikidoTriage: job queue full for group %d / %s", groupId, jiraKey);
            return TriageResult.skipped("Job queue is full");
        }

        // Audit event
        audit("JOBS", "JOB_SUBMITTED", "job", jobId,
                java.util.Map.of("jobType", "AIKIDO_FIX", "jiraKey", jiraKey,
                                 "aikidoGroupId", groupIdStr, "severity", severity));
        audit("SOC2", "SLA_STARTED", "job", jobId,
                java.util.Map.of("jiraKey", jiraKey, "severity", severity,
                                 "aikidoGroupId", groupIdStr));

        LOG.infof("AikidoTriage: dispatched fix job %s for %s (group=%d, branch=%s)",
                jobId, jiraKey, groupId, branchName);

        return TriageResult.dispatched(jiraKey, jiraCreated, jobId, branchName, issueInfo);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String createJiraBug(AikidoIssueInfo info, String severity) {
        String projectKey = settings.get("aikido.jira.default-project", "");
        if (projectKey.isBlank()) {
            LOG.warnf("AikidoTriage: aikido.jira.default-project not configured — cannot create JIRA bug");
            return null;
        }

        String summary = "Security: " + info.packageName()
                + (info.cveId() != null && !info.cveId().isBlank() ? " " + info.cveId() : "")
                + " (" + info.severity().toUpperCase() + ")";

        String description = "Aikido Security detected a vulnerability.\n\n"
                + "Package: " + info.packageName() + "\n"
                + "Current version: " + nullToEmpty(info.currentVersion()) + "\n"
                + "Fixed version: " + nullToEmpty(info.fixedVersion()) + "\n"
                + "Severity: " + info.severity().toUpperCase() + "\n"
                + (info.cveId() != null && !info.cveId().isBlank() ? "CVE: " + info.cveId() + "\n" : "")
                + (info.cveDescription() != null ? "\n" + info.cveDescription() : "");

        LocalDate dueDate = calculateDueDate(severity);

        return jiraService.createIssueSystem(
                projectKey, summary, description, "Bug", null,
                List.of("SOCII"), dueDate);
    }

    private LocalDate calculateDueDate(String severity) {
        int slaDays = "critical".equalsIgnoreCase(severity)
                ? soc2Policy.criticalSlaDays()
                : soc2Policy.highSlaDays();
        return LocalDate.now().plusDays(slaDays);
    }

    private void populateSlaFields(JobRecord job, String jiraKey, String severity) {
        try {
            String[] slaMeta = jiraService.getIssueSlaMeta(jiraKey);
            if (slaMeta[0] != null && !slaMeta[0].isBlank()) job.setJiraPriority(slaMeta[0]);
            if (slaMeta[1] != null && !slaMeta[1].isBlank()) job.setJiraIssueType(slaMeta[1]);
            if (slaMeta[2] != null && !slaMeta[2].isBlank()) {
                java.time.Instant createdAt = parseTimestamp(slaMeta[2]);
                if (createdAt != null) job.setJiraCreatedAt(createdAt);
            }
        } catch (Exception e) {
            LOG.warnf("AikidoTriage: could not fetch JIRA SLA meta for %s: %s", jiraKey, e.getMessage());
        }
        // Fallback: derive priority from Aikido severity when JIRA didn't provide it
        if (job.getJiraPriority() == null || job.getJiraPriority().isBlank()) {
            job.setJiraPriority(mapSeverityToPriority(severity));
        }
        if (job.getJiraIssueType() == null || job.getJiraIssueType().isBlank()) {
            job.setJiraIssueType("Bug");
        }
        if (job.getJiraCreatedAt() == null) {
            job.setJiraCreatedAt(java.time.Instant.now());
        }
    }

    private void sendNewIssueNotification(String jiraKey, AikidoIssueInfo info, String repoUrl) {
        try {
            String summary = "New SOC2 vulnerability: "
                    + info.packageName() + " " + info.severity().toUpperCase()
                    + (info.cveId() != null && !info.cveId().isBlank() ? " — " + info.cveId() : "")
                    + (info.fixedVersion() != null && !info.fixedVersion().isBlank()
                            ? " | Fix: " + info.currentVersion() + " → " + info.fixedVersion() : "")
                    + " | JIRA: " + jiraKey;

            RunResult notification = new RunResult(
                    null, "AIKIDO_TRIAGE", "STARTED",
                    jiraKey, repoUrl, null, null,
                    summary, null, 0, 0);
            teamsNotifier.sendNotification(notification);
        } catch (Exception e) {
            LOG.warnf("AikidoTriage: Teams notification failed (non-fatal): %s", e.getMessage());
        }
    }

    private static String mapSeverityToPriority(String severity) {
        if (severity == null) return "High";
        return switch (severity.toLowerCase()) {
            case "critical" -> "Critical";
            case "high"     -> "High";
            case "medium"   -> "Medium";
            default         -> "Low";
        };
    }

    private static String slugify(String input) {
        if (input == null) return "fix";
        return input.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static java.time.Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            try { return java.time.Instant.parse(value); } catch (Exception ignored) {}
        }
        return null;
    }

    private void safeJira(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warnf("AikidoTriage: JIRA operation failed (non-fatal): %s", e.getMessage());
        }
    }

    private void audit(String category, String action, String resourceType, String resourceId,
                       java.util.Map<String, Object> detail) {
        Thread.ofVirtual().name("audit-triage-" + action).start(() -> {
            try {
                String detailJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(detail);
                auditStore.save(new AuditEntry(null, "system", category, action,
                        resourceType, resourceId, detailJson, java.time.Instant.now()));
            } catch (Exception ignored) {}
        });
    }
}
