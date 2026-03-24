package com.eneve.agent.security;

public enum AppPermission {

    USE_CHAT("Chat", "Use the AI chat assistant"),
    EXECUTE_ANALYSIS("Analysis", "Trigger read-only analysis jobs"),
    EXECUTE_FIX_JOBS("Jobs", "Start fix jobs that modify code"),
    EXECUTE_PLAN_JOBS("Jobs", "Start plan jobs that propose changes"),
    MANAGE_SETTINGS("Administration", "Change global or workspace settings"),
    MANAGE_USERS("Administration", "Manage users and invitations");

    private final String category;
    private final String description;

    AppPermission(String category, String description) {
        this.category = category;
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }
}
