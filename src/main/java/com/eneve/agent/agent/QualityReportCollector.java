package com.eneve.agent.agent;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.CodeMetricsCalculator.CodeMetricsSnapshot;
import com.eneve.agent.agent.QualityReport.AikidoSection;
import com.eneve.agent.agent.QualityReport.ComplexitySection;
import com.eneve.agent.agent.QualityReport.LinterSection;
import com.eneve.agent.agent.QualityReport.ReviewSection;
import com.eneve.agent.agent.QualityReport.TestPresenceSection;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.linter.LinterFinding;
import com.eneve.agent.linter.LinterResult;
import com.eneve.agent.linter.LinterService;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Collects all quality metrics for a repository branch and assembles them into a
 * {@link QualityReport} with an aggregate score.
 *
 * <p>Coverage and linter measurements require a cloned workspace (passed in).
 * Aikido data is fetched from the API. Complexity is computed via JavaParser.
 * Review quality is read from the local database.
 */
@ApplicationScoped
public class QualityReportCollector {

    private static final Logger LOG = Logger.getLogger(QualityReportCollector.class);

    @Inject TestPresenceChecker testPresenceChecker;
    @Inject LinterService linterService;
    @Inject AikidoService aikidoService;
    @Inject CodeMetricsCalculator metricsCalculator;
    @Inject CommentStore commentStore;
    @Inject CommentFeedbackStore feedbackStore;

    @ConfigProperty(name = "quality-report.cc-threshold", defaultValue = "10")
    int defaultCcThreshold;

    /**
     * Collects all available quality metrics for the given workspace and returns a complete
     * {@link QualityReport}. All sections are attempted independently — a failure in one
     * does not prevent the others from completing.
     *
     * @param workspace    the cloned workspace context (already checked-out on the target branch)
     * @param workspaceName the logical workspace / organisation name
     * @param repoSlug     the repository slug
     * @param branch       the branch being measured
     */
    public QualityReport collect(WorkspaceContext workspace, String workspaceName, String repoSlug, String branch) {
        LOG.infof("QualityReportCollector: collecting metrics for %s/%s branch=%s", workspaceName, repoSlug, branch);

        TestPresenceSection testPresenceSection = collectTestPresence(workspace, workspaceName, repoSlug);
        LinterSection linterSection = collectLinter(workspace, workspaceName, repoSlug);
        AikidoSection aikidoSection = collectAikido(repoSlug);
        ComplexitySection complexitySection = collectComplexity(workspace, workspaceName, repoSlug, branch);
        ReviewSection reviewSection = collectReview(workspaceName, repoSlug);

        double score = QualityReport.computeScore(testPresenceSection, linterSection, aikidoSection,
                complexitySection, reviewSection);

        return new QualityReport(
                UUID.randomUUID().toString(),
                workspaceName,
                repoSlug,
                branch,
                Instant.now(),
                score,
                null,
                linterSection,
                aikidoSection,
                complexitySection,
                reviewSection,
                testPresenceSection
        );
    }

    // ─── Individual collectors ────────────────────────────────────────────

    private TestPresenceSection collectTestPresence(WorkspaceContext workspace, String workspaceName, String repoSlug) {
        try {
            return testPresenceChecker.check(workspace.getRoot(), workspaceName, repoSlug);
        } catch (Exception e) {
            LOG.warnf("QualityReportCollector: test presence check failed for %s/%s: %s",
                    workspaceName, repoSlug, e.getMessage());
            return null;
        }
    }

    private LinterSection collectLinter(WorkspaceContext workspace, String workspaceName, String repoSlug) {
        try {
            List<LinterResult> results = linterService.runAll(workspace.getRoot());
            if (results.isEmpty()) {
                LOG.infof("QualityReportCollector: no linters applicable for %s/%s — skipping", workspaceName, repoSlug);
                return null;
            }

            int totalFindings = 0, errorCount = 0, warningCount = 0, infoCount = 0;
            Map<String, Integer> byLinter = new HashMap<>();
            Map<String, Integer> bySeverity = new HashMap<>();

            for (LinterResult r : results) {
                int count = r.findings().size();
                totalFindings += count;
                byLinter.merge(r.linterName(), count, Integer::sum);

                for (LinterFinding f : r.findings()) {
                    bySeverity.merge(f.severity(), 1, Integer::sum);
                    switch (f.severity()) {
                        case LinterFinding.SEVERITY_ERROR -> errorCount++;
                        case LinterFinding.SEVERITY_WARNING -> warningCount++;
                        case LinterFinding.SEVERITY_INFO -> infoCount++;
                    }
                }
            }

            return new LinterSection(totalFindings, errorCount, warningCount, infoCount, byLinter, bySeverity);
        } catch (Exception e) {
            LOG.warnf("QualityReportCollector: linter collection failed for %s/%s: %s",
                    workspaceName, repoSlug, e.getMessage());
            return null;
        }
    }

    private AikidoSection collectAikido(String repoSlug) {
        try {
            if (!aikidoService.isEnabled()) {
                LOG.debugf("QualityReportCollector: Aikido not configured — skipping security section");
                return null;
            }
            List<AikidoIssueInfo> issues = aikidoService.findOpenIssuesForRepo(repoSlug);
            if (issues == null) return null;

            int critical = 0, high = 0, medium = 0, low = 0;
            int sast = 0, dependency = 0, secret = 0, container = 0, other = 0;
            for (AikidoIssueInfo issue : issues) {
                if (issue.severity() != null) {
                    switch (issue.severity().toLowerCase()) {
                        case "critical" -> critical++;
                        case "high" -> high++;
                        case "medium" -> medium++;
                        case "low" -> low++;
                    }
                }
                String t = issue.issueType() == null ? "unknown" : issue.issueType().toLowerCase();
                switch (t) {
                    case "sast", "code", "static_analysis", "code_security" -> sast++;
                    case "sca", "dependency", "dependencies", "open_source",
                         "software_composition_analysis" -> dependency++;
                    case "secret", "secrets", "exposed_secret", "hardcoded_secret" -> secret++;
                    case "container", "container_image", "docker", "image" -> container++;
                    default -> other++;
                }
            }
            return new AikidoSection(issues.size(), critical, high, medium, low,
                    sast, dependency, secret, container, other);
        } catch (Exception e) {
            LOG.warnf("QualityReportCollector: Aikido collection failed for %s: %s", repoSlug, e.getMessage());
            return null;
        }
    }

    private ComplexitySection collectComplexity(WorkspaceContext workspace, String workspaceName,
                                                String repoSlug, String branch) {
        try {
            CodeMetricsSnapshot snap = metricsCalculator.calculate(
                    workspace.getRoot(), workspaceName, repoSlug, branch, defaultCcThreshold);
            if (snap == null) return null;
            return new ComplexitySection(
                    snap.totalMethods(),
                    snap.methodsAboveThreshold(),
                    snap.avgComplexity(),
                    snap.maxComplexity(),
                    snap.threshold()
            );
        } catch (Exception e) {
            LOG.warnf("QualityReportCollector: complexity collection failed for %s/%s: %s",
                    workspaceName, repoSlug, e.getMessage());
            return null;
        }
    }

    private ReviewSection collectReview(String workspace, String repoSlug) {
        try {
            long totalFindings = commentStore.countTotalFindings(workspace, repoSlug);
            long resolvedFindings = commentStore.countResolvedFindings(workspace, repoSlug);
            long falsePositives = feedbackStore.countFalsePositives(workspace, repoSlug);

            double fpRate = totalFindings > 0
                    ? Math.round((double) falsePositives / totalFindings * 10000.0) / 10000.0
                    : 0.0;
            double resolutionRate = totalFindings > 0
                    ? Math.round((double) resolvedFindings / totalFindings * 10000.0) / 10000.0
                    : 0.0;

            return new ReviewSection(totalFindings, resolvedFindings, resolutionRate, falsePositives, fpRate);
        } catch (Exception e) {
            LOG.warnf("QualityReportCollector: review quality collection failed for %s/%s: %s",
                    workspace, repoSlug, e.getMessage());
            return null;
        }
    }
}
