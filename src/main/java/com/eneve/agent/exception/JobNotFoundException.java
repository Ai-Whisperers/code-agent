package com.eneve.agent.exception;

/** Thrown when a job ID does not map to any known job. Maps to HTTP 404. */
public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String message) { super(message); }
}
