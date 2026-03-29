package com.eneve.agent;

import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;

/**
 * Single source of truth for all SOC II policy settings and helper predicates.
 *
 * <p>Replaces repeated {@code settings.get("soc2.*", "...")} calls scattered across
 * the codebase with typed, documented accessors that share a single default value.
 */
@ApplicationScoped
public class Soc2Policy {

    @Inject SettingsService settings;

    /** Comma-separated list of Jira issue types considered bug-type for SOC II. */
    public String bugIssueTypes() {
        return settings.get("soc2.bug-issue-types", "Bug,Defect");
    }

    /** SLA deadline in days for Critical-priority bugs. */
    public int criticalSlaDays() {
        return parseInt(settings.get("soc2.sla.critical-days", "5"), 5);
    }

    /** SLA deadline in days for High-priority bugs. */
    public int highSlaDays() {
        return parseInt(settings.get("soc2.sla.high-days", "20"), 20);
    }

    /** Comma-separated list of branches that are considered protected/release branches. */
    public String protectedBranchesRaw() {
        return settings.get("soc2.protected-branches", "develop,main,master,production");
    }

    /** The single production branch that triggers Jira/Scytale post-merge actions. */
    public String productionBranch() {
        return settings.get("soc2.production-branch", "main");
    }

    /**
     * Returns {@code true} when {@code issueType} matches one of the configured
     * bug issue types (case-insensitive).
     */
    public boolean isBugType(String issueType) {
        if (issueType == null) return false;
        return Arrays.stream(bugIssueTypes().split("\\s*,\\s*"))
                .anyMatch(t -> t.equalsIgnoreCase(issueType));
    }

    /**
     * Returns {@code true} when {@code branch} is in the configured protected branches list
     * (case-insensitive). Returns {@code false} for a null branch.
     */
    public boolean isProtected(String branch) {
        if (branch == null) return false;
        return Arrays.stream(protectedBranchesRaw().split("\\s*,\\s*"))
                .anyMatch(b -> b.equalsIgnoreCase(branch));
    }

    /** Convenience: all bug issue types as a {@link List}. */
    public List<String> bugIssueTypeList() {
        return Arrays.asList(bugIssueTypes().split("\\s*,\\s*"));
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }
}
