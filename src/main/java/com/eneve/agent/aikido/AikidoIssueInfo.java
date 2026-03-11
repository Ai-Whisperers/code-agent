package com.eneve.agent.aikido;

/**
 * Aggregated vulnerability context resolved from Aikido for a single issue group.
 * Used to build an enriched prompt for the agent.
 */
public record AikidoIssueInfo(
        int issueGroupId,
        String severity,
        String packageName,
        String currentVersion,
        String fixedVersion,
        String cveId,
        String cveDescription,
        Double cvssScore,
        String repoName,
        String repoUrl,
        String changelogSummary
) {
    /**
     * Build a detailed prompt section from this vulnerability info.
     */
    public String toPromptSection() {
        var sb = new StringBuilder();
        sb.append("## Security Vulnerability Fix\n\n");

        if (cveId != null && !cveId.isBlank()) {
            sb.append("**CVE:** ").append(cveId);
            if (cvssScore != null) {
                sb.append(" (CVSS ").append(cvssScore).append(" - ").append(severity.toUpperCase()).append(")");
            }
            sb.append("\n");
        } else {
            sb.append("**Severity:** ").append(severity.toUpperCase()).append("\n");
        }

        sb.append("**Package:** ").append(packageName).append("\n");
        sb.append("**Current version:** ").append(currentVersion).append("\n");
        if (fixedVersion != null && !fixedVersion.isBlank()) {
            sb.append("**Fixed version:** ").append(fixedVersion).append("\n");
        }

        if (cveDescription != null && !cveDescription.isBlank()) {
            sb.append("\n### Vulnerability Details\n").append(cveDescription).append("\n");
        }

        if (changelogSummary != null && !changelogSummary.isBlank()) {
            sb.append("\n### Changelog (").append(currentVersion).append(" → ").append(fixedVersion).append(")\n");
            sb.append(changelogSummary).append("\n");
        }

        sb.append("\n### Task\n");
        sb.append("Upgrade ").append(packageName).append(" from ").append(currentVersion);
        if (fixedVersion != null && !fixedVersion.isBlank()) {
            sb.append(" to ").append(fixedVersion);
        } else {
            sb.append(" to the latest secure version");
        }
        sb.append(" in this project.\n");
        sb.append("- Only modify dependency version declarations (pom.xml, build.gradle, package.json, etc.)\n");
        sb.append("- Make minimal code changes ONLY if required for compilation after the version bump\n");
        sb.append("- Run tests to verify nothing is broken\n");

        return sb.toString();
    }
}
