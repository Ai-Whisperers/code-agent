package com.eneve.agent.exception;

/** Thrown when a state-machine transition is invalid for the job's current status. Maps to HTTP 409. */
public class JobConflictException extends RuntimeException {
    public JobConflictException(String message) { super(message); }
}
