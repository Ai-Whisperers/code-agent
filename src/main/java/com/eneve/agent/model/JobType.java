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
    CHAT;

    /**
     * Dispatch priority for the job queue (lower value = dispatched first).
     * CHAT bypasses the queue entirely and is included only as a safe default.
     */
    public int priority() {
        return switch (this) {
            case HOOK, REPLY, FIX_COMMENT                        -> 1;
            case REVIEW, FIX_PR                                  -> 2;
            case FIX, METRICS, QUALITY_REPORT                    -> 3;
            case GENERATE_TESTS, GENERATE_DOCS, SYNC_CONFLUENCE  -> 4;
            case CHAT                                            -> 4;
        };
    }
}
