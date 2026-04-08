package com.eneve.agent.agent.handlers;

import com.eneve.agent.settings.SettingsService;

/**
 * Maps a raw Jira status string to one of the four canonical roadmap statuses:
 * New, In Progress, QA, Closed.
 * Mapping is configured via {@code roadmap.jira.status-map.*} settings.
 */
final class JiraStatusMapper {

    private JiraStatusMapper() {}

    static String map(String rawStatus, SettingsService settings) {
        if (rawStatus == null || rawStatus.isBlank()) return null;
        String lower = rawStatus.trim().toLowerCase();
        if (matches(lower, settings.get("roadmap.jira.status-map.closed", "Done,Closed,Resolved"))) return "Closed";
        if (matches(lower, settings.get("roadmap.jira.status-map.qa", "In Review,QA,Testing"))) return "QA";
        if (matches(lower, settings.get("roadmap.jira.status-map.in-progress", "In Progress"))) return "In Progress";
        if (matches(lower, settings.get("roadmap.jira.status-map.new", "To Do,Open,New"))) return "New";
        return rawStatus;
    }

    private static boolean matches(String lower, String csv) {
        if (csv == null || csv.isBlank()) return false;
        for (String token : csv.split(",")) {
            if (lower.equals(token.trim().toLowerCase())) return true;
        }
        return false;
    }
}
