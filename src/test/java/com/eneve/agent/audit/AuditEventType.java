package com.eneve.agent.audit;

/**
 * Enumeration of audit event types for categorizing different kinds of events.
 */
public enum AuditEventType {
    
    // Authentication and authorization events
    LOGIN("Authentication - User login"),
    LOGOUT("Authentication - User logout"), 
    LOGIN_FAILED("Authentication - Failed login attempt"),
    PERMISSION_DENIED("Authorization - Permission denied"),
    
    // Job and workflow events
    JOB_CREATED("Job - Job created"),
    JOB_STARTED("Job - Job started"),
    JOB_COMPLETED("Job - Job completed"),
    JOB_FAILED("Job - Job failed"),
    JOB_CANCELLED("Job - Job cancelled"),
    
    // Repository and code events
    REPO_CLONED("Repository - Repository cloned"),
    REPO_UPDATED("Repository - Repository updated"),
    PR_CREATED("Repository - Pull request created"),
    PR_REVIEWED("Repository - Pull request reviewed"),
    PR_MERGED("Repository - Pull request merged"),
    
    // System events
    CONFIG_CHANGED("System - Configuration changed"),
    SYSTEM_STARTUP("System - System startup"),
    SYSTEM_SHUTDOWN("System - System shutdown"),
    
    // AI and tool events
    AI_CALL_MADE("AI - AI call made"),
    TOOL_EXECUTED("Tools - Tool executed"),
    
    // Data events
    DATA_CREATED("Data - Data created"),
    DATA_UPDATED("Data - Data updated"),
    DATA_DELETED("Data - Data deleted"),
    DATA_ACCESSED("Data - Data accessed"),
    
    // Security events
    SECURITY_VIOLATION("Security - Security violation"),
    WEBHOOK_RECEIVED("Security - Webhook received"),
    API_KEY_USED("Security - API key used");
    
    private final String description;
    
    AuditEventType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    @Override
    public String toString() {
        return name() + ": " + description;
    }
}