package com.eneve.agent.model;

public enum JobType {
    FIX,
    REVIEW,
    FIX_PR,
    REPLY,
    FIX_COMMENT,
    HOOK,
    GENERATE_TESTS,
    GENERATE_DOCS,
    SYNC_CONFLUENCE,
    METRICS,
    QUALITY_REPORT,
    REVIEW_EPIC,
    REVIEW_FEATURE,
    REVIEW_USERSTORY,
    CHAT,
    /** Cherry-pick promotion job: creates promote/{jiraKey} from main, cherry-picks fix commits, raises PR → main. */
    PROMOTE,
    /** Autonomous self-analysis job: triggered after a job fails, analyses logs/DB, attempts a code fix, raises PR. */
    SELF_ANALYSIS,
    /** Generates a Structurizr DSL architecture model for a repository and persists versioned C4 diagrams. */
    GENERATE_ARCHITECTURE,
    /** Discovers AWS ECS/RDS topology for a customer environment and persists versioned cloud architecture diagrams. */
    GENERATE_CLOUD_ARCHITECTURE,
    /** Analyses git history across repos to score engineer expertise per file/service and surface bus-factor risks. */
    KNOWLEDGE_GRAPH,
    /** Combines complexity, coverage, churn, and staleness signals into a per-file technical debt score. */
    TECH_DEBT,
    /** Rewrites or extracts code from a source repository into a target repository.
     *  Supports full cross-language rewrites (e.g. PHP→C#), framework migrations (e.g. Angular→React),
     *  and partial extractions (e.g. monolith→microservice). */
    REWRITE,
    /** Classifies an incoming Jira Service Desk ticket as QUESTION, REQUEST, BUG_REPORT, or OUTAGE_REPORT
     *  (Stage 1: Haiku) and, for bugs and outages, performs a deep AI root-cause analysis
     *  (Stage 2: Sonnet) using the knowledge index. Results are posted as internal (agent-only) comments. */
    SERVICE_DESK_TRIAGE;

    /**
     * Default dispatch priority on a 1–100 scale (higher = dispatched first).
     * These values are compile-time defaults; the live value is read from
     * {@code agent_settings} key {@code job.priority.<type_lowercase>} at
     * runtime, falling back to this method when no DB override exists.
     */
    public int defaultPriority() {
        return switch (this) {
            case CHAT             -> 100;
            case REPLY            ->  80;
            case FIX_COMMENT      ->  75;
            case REVIEW           ->  70;
            case FIX_PR           ->  70;
            case PROMOTE          ->  65;
            case FIX              ->  60;
            case HOOK             ->  50;
            case METRICS          ->  40;
            case QUALITY_REPORT   ->  35;
            case SYNC_CONFLUENCE  ->  30;
            case GENERATE_TESTS   ->  25;
            case GENERATE_DOCS              ->  20;
            case GENERATE_ARCHITECTURE      ->  20;
            case GENERATE_CLOUD_ARCHITECTURE->  20;
            case KNOWLEDGE_GRAPH            ->  15;
            case TECH_DEBT                  ->  15;
            case REVIEW_EPIC      ->  15;
            case REVIEW_FEATURE   ->  15;
            case REVIEW_USERSTORY ->  15;
            case SELF_ANALYSIS         ->  10;
            case REWRITE               ->  55;
            case SERVICE_DESK_TRIAGE   ->  55;
        };
    }
}
