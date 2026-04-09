package com.eneve.agent.agent.lobster;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result of a Lobster workflow invocation. Four mutually-exclusive states:
 *
 * <ul>
 *   <li><b>SUCCESS</b> — envelope parsed, {@code ok=true}, workflow completed</li>
 *   <li><b>DISABLED</b> — Lobster integration is turned off in settings</li>
 *   <li><b>TIMEOUT</b> — process didn't return within the configured budget</li>
 *   <li><b>FAILURE</b> — non-zero exit, parse error, or envelope {@code ok=false}</li>
 * </ul>
 *
 * <p>Callers inspect {@link #status()} before touching {@link #envelope()}
 * or {@link #output()}. The convenience {@link #isSuccess()} short-circuits
 * the common happy-path check.
 */
public final class LobsterResult {

    public enum Status {
        SUCCESS,
        DISABLED,
        TIMEOUT,
        FAILURE,
    }

    private final Status status;
    private final JsonNode envelope;
    private final String errorMessage;
    private final long durationMs;

    private LobsterResult(Status status, JsonNode envelope, String errorMessage, long durationMs) {
        this.status = status;
        this.envelope = envelope;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
    }

    public static LobsterResult success(JsonNode envelope, long durationMs) {
        return new LobsterResult(Status.SUCCESS, envelope, null, durationMs);
    }

    public static LobsterResult disabled() {
        return new LobsterResult(Status.DISABLED, null, "lobster.enabled=false", 0L);
    }

    public static LobsterResult timeout(int timeoutSeconds) {
        return new LobsterResult(Status.TIMEOUT, null,
                "Lobster workflow timed out after " + timeoutSeconds + "s", 0L);
    }

    public static LobsterResult failure(int exitCode, String message) {
        return new LobsterResult(Status.FAILURE, null,
                "Exit " + exitCode + ": " + (message != null ? message : ""), 0L);
    }

    public static LobsterResult workflowError(String errorType, String errorMessage, JsonNode envelope) {
        return new LobsterResult(Status.FAILURE, envelope,
                errorType + ": " + errorMessage, 0L);
    }

    public Status status() { return status; }

    public boolean isSuccess() { return status == Status.SUCCESS; }

    public boolean isDisabled() { return status == Status.DISABLED; }

    public String errorMessage() { return errorMessage; }

    public long durationMs() { return durationMs; }

    public JsonNode envelope() { return envelope; }

    /**
     * Returns the first element of the envelope's {@code output} array,
     * which is the workflow's primary result payload. Null-safe — returns
     * a {@link com.fasterxml.jackson.databind.node.MissingNode} if the
     * envelope is absent or malformed.
     */
    public JsonNode output() {
        if (envelope == null) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        JsonNode outputNode = envelope.path("output");
        if (outputNode.isArray() && outputNode.size() > 0) {
            return outputNode.get(0);
        }
        return outputNode;
    }

    /**
     * True if the workflow envelope indicates an {@code approve} primitive
     * halted execution waiting for human approval. Callers can persist the
     * resume token and prompt the user via whichever channel is configured.
     */
    public boolean requiresApproval() {
        return envelope != null && !envelope.path("requiresApproval").isNull()
                && !envelope.path("requiresApproval").isMissingNode();
    }

    /**
     * Extracts the resume token from an approval-gated envelope. Null if
     * the workflow didn't halt for approval.
     */
    public String approvalToken() {
        if (!requiresApproval()) return null;
        return envelope.path("requiresApproval").path("token").asText(null);
    }
}
