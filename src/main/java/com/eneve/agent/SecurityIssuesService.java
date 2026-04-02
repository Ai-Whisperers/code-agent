package com.eneve.agent;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.ProductSecuritySummary;
import com.eneve.agent.model.RepoSecuritySummary;
import com.eneve.agent.model.SecurityIssueRow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Business logic for the security issues view.
 *
 * <p>Iterates all configured products and their repositories, fetches open Aikido
 * vulnerability issues, enriches them with SLA status and linked job data, and
 * assembles the {@link ProductSecuritySummary} tree.
 *
 * <p>This class is stateless — caching is handled by {@link SecurityIssuesCacheService}.
 */
@ApplicationScoped
public class SecurityIssuesService {

    private static final Logger LOG = Logger.getLogger(SecurityIssuesService.class);

    private static final int CRITICAL_SLA_DAYS = 7;
    private static final int HIGH_SLA_DAYS     = 30;
    private static final int MEDIUM_SLA_DAYS   = 90;

    @Inject CustomerRegistryStore registryStore;
    @Inject AikidoService aikidoService;
    @Inject JobStore jobStore;

    /**
     * Builds the full security summary tree across all products and their repositories.
     * This is the expensive operation — callers should use {@link SecurityIssuesCacheService}
     * to avoid calling this on every request.
     */
    public List<ProductSecuritySummary> buildSnapshot() {
        List<ProductConfig> products = registryStore.listAllProducts();
        LOG.infof("Security issues: scanning %d product(s)", products.size());

        // Collect all repo slugs across all products, mapped back to their product
        java.util.Map<String, ProductConfig> repoToProduct = new java.util.LinkedHashMap<>();
        for (ProductConfig product : products) {
            for (String slug : resolveRepos(product)) {
                repoToProduct.put(slug, product);
            }
        }

        if (repoToProduct.isEmpty()) {
            LOG.infof("Security issues snapshot built: 0 products (no repos configured)");
            return List.of();
        }

        LOG.infof("Security issues: fetching Aikido issues for %d repo(s) in one bulk call",
                repoToProduct.size());

        // Single list call + one detail call per matching group — respects rate limit
        java.util.Map<String, List<AikidoIssueInfo>> issuesByRepo =
                aikidoService.findOpenIssuesForAllRepos(repoToProduct.keySet());

        // Group repo summaries by product
        java.util.Map<String, List<RepoSecuritySummary>> summariesByProduct = new java.util.LinkedHashMap<>();
        for (ProductConfig p : products) {
            summariesByProduct.put(p.productId(), new ArrayList<>());
        }

        for (java.util.Map.Entry<String, List<AikidoIssueInfo>> entry : issuesByRepo.entrySet()) {
            String slug = entry.getKey();
            List<AikidoIssueInfo> issues = entry.getValue();
            if (issues.isEmpty()) continue;

            ProductConfig product = repoToProduct.get(slug);
            RepoSecuritySummary summary = buildRepoSummary(slug, issues);
            summariesByProduct.get(product.productId()).add(summary);
        }

        List<ProductSecuritySummary> result = new ArrayList<>();
        for (ProductConfig product : products) {
            List<RepoSecuritySummary> repos = summariesByProduct.get(product.productId());
            if (repos != null && !repos.isEmpty()) {
                result.add(new ProductSecuritySummary(product.productId(), product.displayName(), repos));
            }
        }

        LOG.infof("Security issues snapshot built: %d products with open issues (out of %d total)",
                result.size(), products.size());
        return result;
    }

    /**
     * Computes total critical and high counts across the given snapshot.
     */
    public record SecurityCounts(int criticals, int highs) {}

    public SecurityCounts computeCounts(List<ProductSecuritySummary> snapshot) {
        int criticals = 0;
        int highs = 0;
        for (ProductSecuritySummary product : snapshot) {
            for (RepoSecuritySummary repo : product.repos()) {
                criticals += repo.criticalCount();
                highs += repo.highCount();
            }
        }
        return new SecurityCounts(criticals, highs);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private RepoSecuritySummary buildRepoSummary(String repoSlug, List<AikidoIssueInfo> aikidoIssues) {
        List<SecurityIssueRow> rows = new ArrayList<>();
        for (AikidoIssueInfo issue : aikidoIssues) {
            rows.add(buildIssueRow(issue));
        }

        int criticalCount = (int) rows.stream()
                .filter(r -> "critical".equalsIgnoreCase(r.severity()))
                .count();
        int highCount = (int) rows.stream()
                .filter(r -> "high".equalsIgnoreCase(r.severity()))
                .count();

        List<String> containers = rows.stream()
                .map(SecurityIssueRow::containerImage)
                .filter(img -> img != null && !img.isBlank())
                .distinct()
                .collect(Collectors.toList());

        return new RepoSecuritySummary(repoSlug, containers, criticalCount, highCount, rows);
    }

    private SecurityIssueRow buildIssueRow(AikidoIssueInfo issue) {
        String groupIdStr = String.valueOf(issue.issueGroupId());

        Instant createdAt = Instant.now(); // Aikido API doesn't expose creation date in AikidoIssueInfo
        Instant slaDeadline = computeSlaDeadline(issue.severity(), createdAt);
        String slaStatus = computeSlaStatus(issue.severity(), createdAt, slaDeadline);

        String linkedJobId = null;
        String linkedJobStatus = null;
        try {
            JobRecord linkedJob = jobStore.findLatestJobForAikidoGroupId(groupIdStr, issue.repoName());
            if (linkedJob != null) {
                linkedJobId = linkedJob.getJobId();
                linkedJobStatus = linkedJob.getStatus().name();
            }
        } catch (Exception e) {
            LOG.warnf("Failed to look up linked job for Aikido group %d: %s",
                    issue.issueGroupId(), e.getMessage());
        }

        return new SecurityIssueRow(
                issue.issueGroupId(),
                issue.issueType(),
                issue.title(),
                issue.severity(),
                issue.packageName(),
                issue.currentVersion(),
                issue.fixedVersion(),
                issue.cveId(),
                issue.cvssScore(),
                issue.repoName(),
                issue.repoUrl(),
                issue.containerImage(),
                createdAt,
                slaDeadline,
                slaStatus,
                linkedJobId,
                linkedJobStatus
        );
    }

    private Instant computeSlaDeadline(String severity, Instant createdAt) {
        int days = slaDays(severity);
        return createdAt.plusSeconds((long) days * 86_400);
    }

    private String computeSlaStatus(String severity, Instant createdAt, Instant deadline) {
        long totalSeconds = (long) slaDays(severity) * 86_400;
        long secondsLeft  = deadline.getEpochSecond() - Instant.now().getEpochSecond();

        if (secondsLeft < 0) return "OVERDUE";
        // AT_RISK = last 20% of the window
        if (secondsLeft <= totalSeconds / 5) return "AT_RISK";
        return "ON_TRACK";
    }

    private int slaDays(String severity) {
        if (severity == null) return MEDIUM_SLA_DAYS;
        return switch (severity.toLowerCase()) {
            case "critical" -> CRITICAL_SLA_DAYS;
            case "high"     -> HIGH_SLA_DAYS;
            default         -> MEDIUM_SLA_DAYS;
        };
    }

    /**
     * Resolves the list of repository slugs for a product.
     *
     * <p>The UI stores selected repos in {@code metadata.repos} (a JSON array of slugs).
     * {@code git.repos} is populated only when set programmatically; for UI-managed products
     * {@code git.repos} is null. We check both sources, preferring {@code metadata.repos}.
     */
    private List<String> resolveRepos(ProductConfig product) {
        // Primary: metadata.repos (set by the CustomerRegistry UI)
        if (product.metadata() != null) {
            Object raw = product.metadata().get("repos");
            if (raw instanceof List<?> list && !list.isEmpty()) {
                return list.stream()
                        .filter(r -> r instanceof String)
                        .map(r -> (String) r)
                        .collect(Collectors.toList());
            }
        }
        // Fallback: git.repos (set programmatically)
        if (product.git() != null && product.git().repos() != null && !product.git().repos().isEmpty()) {
            return product.git().repos();
        }
        return List.of();
    }
}
