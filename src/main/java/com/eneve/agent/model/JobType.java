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
    GENERATE_CLOUD_ARCHITECTURE;

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
            case REVIEW_EPIC      ->  15;
            case REVIEW_FEATURE   ->  15;
            case REVIEW_USERSTORY ->  15;
            case SELF_ANALYSIS    ->  10;
        };
    }
}
