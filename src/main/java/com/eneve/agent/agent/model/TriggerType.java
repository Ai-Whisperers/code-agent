package com.eneve.agent.agent.model;

import java.util.Set;

/**
 * Constants for automation hook trigger types and category helpers.
 */
public final class TriggerType {

    // ── Legacy (backward compatibility) ──────────────────────────────────────
    public static final String PR_EVENT = "pr_event";

    // ── SCM (generic) ─────────────────────────────────────────────────────────
    public static final String SCM_PR_MERGED = "scm.pr_merged";
    public static final String SCM_PR_CREATED = "scm.pr_created";
    public static final String SCM_PR_UPDATED = "scm.pr_updated";
    public static final String SCM_PUSH = "scm.push";

    // ── Jira ──────────────────────────────────────────────────────────────────
    public static final String JIRA_ISSUE_CREATED = "jira.issue_created";
    public static final String JIRA_ISSUE_UPDATED = "jira.issue_updated";
    public static final String JIRA_ISSUE_ASSIGNED = "jira.issue_assigned";

    // ── Confluence ────────────────────────────────────────────────────────────
    public static final String CONFLUENCE_PAGE_CREATED = "confluence.page_created";
    public static final String CONFLUENCE_PAGE_UPDATED = "confluence.page_updated";

    // ── Aikido ────────────────────────────────────────────────────────────────
    public static final String AIKIDO_VULNERABILITY_NEW = "aikido.vulnerability_new";
    public static final String AIKIDO_VULNERABILITY_FIXED = "aikido.vulnerability_fixed";

    // ── Cron ──────────────────────────────────────────────────────────────────
    public static final String CRON = "cron";

    // ── Teams ─────────────────────────────────────────────────────────────────
    public static final String TEAMS_MESSAGE = "teams.message";

    // ── Category sets ─────────────────────────────────────────────────────────

    public static final Set<String> SCM_TRIGGERS = Set.of(
        PR_EVENT, SCM_PR_MERGED, SCM_PR_CREATED, SCM_PR_UPDATED, SCM_PUSH
    );

    public static final Set<String> JIRA_TRIGGERS = Set.of(
        JIRA_ISSUE_CREATED, JIRA_ISSUE_UPDATED, JIRA_ISSUE_ASSIGNED
    );

    public static final Set<String> CONFLUENCE_TRIGGERS = Set.of(
        CONFLUENCE_PAGE_CREATED, CONFLUENCE_PAGE_UPDATED
    );

    public static final Set<String> AIKIDO_TRIGGERS = Set.of(
        AIKIDO_VULNERABILITY_NEW, AIKIDO_VULNERABILITY_FIXED
    );

    public static final Set<String> CRON_TRIGGERS = Set.of(CRON);

    public static final Set<String> TEAMS_TRIGGERS = Set.of(TEAMS_MESSAGE);

    /**
     * Returns true if the trigger type is in the SCM category.
     */
    public static boolean isScm(String triggerType) {
        return SCM_TRIGGERS.contains(triggerType);
    }

    /**
     * Returns true if the trigger type is in the Jira category.
     */
    public static boolean isJira(String triggerType) {
        return JIRA_TRIGGERS.contains(triggerType);
    }

    /**
     * Returns true if the trigger type is in the Confluence category.
     */
    public static boolean isConfluence(String triggerType) {
        return CONFLUENCE_TRIGGERS.contains(triggerType);
    }

    /**
     * Returns true if the trigger type is in the Aikido category.
     */
    public static boolean isAikido(String triggerType) {
        return AIKIDO_TRIGGERS.contains(triggerType);
    }

    /**
     * Returns true if the trigger type is in the Cron category.
     */
    public static boolean isCron(String triggerType) {
        return CRON_TRIGGERS.contains(triggerType);
    }

    /**
     * Returns true if the trigger type is in the Teams category.
     */
    public static boolean isTeams(String triggerType) {
        return TEAMS_TRIGGERS.contains(triggerType);
    }

    private TriggerType() {
        // Utility class
    }
}
