package com.eneve.agent.agent.lobster;

import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

/**
 * Thin Java client for the AIW Lobster workflow runtime.
 *
 * <p>Lobster (see docs/LOBSTER-INTEGRATION.md) is an Openclaw-native
 * TypeScript workflow engine that lets us replace expensive LLM-planned
 * tool-use loops with deterministic shell pipelines. This client shells
 * out to the Lobster CLI installed at {@code /opt/aiw-lobster/} and
 * parses the JSON envelope ({@code --mode tool}) back into a
 * {@link LobsterResult}.
 *
 * <p>Every invocation path is a separate wrapper script under
 * {@code /opt/aiw-lobster/workflows/lobster-*.sh}. This keeps nested
 * shell quoting out of Java and gives each workflow a clean
 * version-controlled bash interface we can unit-test independently.
 *
 * <h2>Config</h2>
 * <ul>
 *   <li>{@code lobster.enabled} — if false, every call returns
 *       {@code LobsterResult.disabled()} without spawning a process.
 *       Default false (opt-in).</li>
 *   <li>{@code lobster.workflows.dir} — directory containing the wrapper
 *       scripts. Default {@code /opt/aiw-lobster/workflows}.</li>
 *   <li>{@code lobster.timeout.seconds} — max wall-clock for any single
 *       workflow invocation. Default 300 (5 min).</li>
 * </ul>
 *
 * <h2>Envelope shape (Lobster --mode tool)</h2>
 * <pre>{@code
 * {
 *   "protocolVersion": 1,
 *   "ok": true,
 *   "status": "ok",
 *   "output": [ { ...workflow-specific payload... } ],
 *   "requiresApproval": null,   // or { token, prompt } for approval gates
 *   "requiresInput": null        // or { token, prompt } for human input
 * }
 * }</pre>
 */
@ApplicationScoped
public class LobsterClient {

    private static final Logger LOG = Logger.getLogger(LobsterClient.class);
    private static final String DEFAULT_WORKFLOWS_DIR = "/opt/aiw-lobster/workflows";
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    @Inject SettingsService settings;
    @Inject ObjectMapper objectMapper;

    /**
     * True iff Lobster integration is enabled via {@code lobster.enabled=true}.
     * Callers should check this before constructing any workflow args.
     */
    public boolean isEnabled() {
        return Boolean.parseBoolean(settings.get("lobster.enabled", "false"));
    }

    /**
     * Runs the {@code review.pr} workflow — a deterministic pipeline that
     * clones the repo, diffs against the base branch, detects the archetype,
     * and optionally runs lint. Returns structured data (no LLM calls).
     *
     * @param repoUrl  full clone URL (https://github.com/org/repo.git)
     * @param branch   source branch (PR head)
     * @param base     target branch (PR base, usually main)
     * @param token    GitHub installation token for private-repo clones
     * @param prNumber optional PR number for tagging
     * @return LobsterResult with the parsed envelope
     */
    public LobsterResult runReviewPr(String repoUrl, String branch, String base,
                                     String token, String prNumber) {
        if (!isEnabled()) {
            return LobsterResult.disabled();
        }
        return invoke("lobster-review-pr.sh",
                List.of(repoUrl, branch, base, token,
                        prNumber != null ? prNumber : "null"));
    }

    /**
     * Lower-level entry point: invoke any wrapper script under
     * {@code lobster.workflows.dir} by name + positional args. Used for
     * generic workflow dispatch from handlers that need custom args.
     */
    public LobsterResult invoke(String wrapperScript, List<String> args) {
        String workflowsDir = settings.get("lobster.workflows.dir", DEFAULT_WORKFLOWS_DIR);
        int timeoutSeconds = Integer.parseInt(
                settings.get("lobster.timeout.seconds", String.valueOf(DEFAULT_TIMEOUT_SECONDS)));

        String scriptPath = workflowsDir + "/" + wrapperScript;

        // Build argv: [scriptPath, arg0, arg1, ...]
        String[] argv = new String[args.size() + 1];
        argv[0] = scriptPath;
        for (int i = 0; i < args.size(); i++) {
            argv[i + 1] = args.get(i);
        }

        LOG.infof("Lobster invoke: %s %s", wrapperScript, args.size());
        long startMs = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.errorf("Lobster invoke timed out after %ds: %s", timeoutSeconds, wrapperScript);
                return LobsterResult.timeout(timeoutSeconds);
            }

            int exitCode = process.exitValue();
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            long elapsedMs = System.currentTimeMillis() - startMs;

            if (exitCode != 0) {
                LOG.warnf("Lobster invoke failed (exit=%d, %dms): %s — stderr: %s",
                          exitCode, elapsedMs, wrapperScript, stderr.substring(0, Math.min(500, stderr.length())));
                return LobsterResult.failure(exitCode, stderr);
            }

            // Parse the Lobster --mode tool envelope
            JsonNode envelope = objectMapper.readTree(stdout);
            boolean ok = envelope.path("ok").asBoolean(false);
            if (!ok) {
                String errType = envelope.path("error").path("type").asText("unknown");
                String errMsg = envelope.path("error").path("message").asText("");
                LOG.warnf("Lobster workflow returned ok=false: %s — %s", errType, errMsg);
                return LobsterResult.workflowError(errType, errMsg, envelope);
            }

            LOG.infof("Lobster invoke success (%dms): %s", elapsedMs, wrapperScript);
            return LobsterResult.success(envelope, elapsedMs);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.errorf("Lobster invoke threw: %s — %s", wrapperScript, e.getMessage());
            return LobsterResult.failure(-1, e.getMessage());
        }
    }
}
