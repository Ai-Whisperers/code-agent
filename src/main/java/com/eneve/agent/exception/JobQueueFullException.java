package com.eneve.agent.exception;

/** Thrown when the job queue has reached capacity. Maps to HTTP 429. */
public class JobQueueFullException extends RuntimeException {
    public JobQueueFullException(String message) { super(message); }
}
