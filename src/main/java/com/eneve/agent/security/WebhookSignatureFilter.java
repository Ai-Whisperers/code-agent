package com.eneve.agent.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Verifies HMAC-SHA256 signatures on incoming webhook requests.
 *
 * Bitbucket Cloud: sends X-Hub-Signature header as "sha256=<hex>".
 * Azure DevOps: Service Hooks can include a shared secret in a custom header
 *     or use Basic auth; we verify via a simple shared-secret comparison.
 * JIRA Cloud (native webhooks): sends X-Hub-Secret header with the raw secret for
 *     simple comparison, or no signature at all (depends on webhook configuration).
 *
 * When the corresponding secret is blank, verification is skipped for that provider
 * (development mode). In production, always set the secrets.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class WebhookSignatureFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(WebhookSignatureFilter.class);
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long REPLAY_WINDOW_SECONDS = 30;

    @ConfigProperty(name = "webhook.secret.bitbucket")
    Optional<String> bitbucketSecret;

    @ConfigProperty(name = "webhook.secret.azuredevops")
    Optional<String> azureDevOpsSecret;

    @ConfigProperty(name = "webhook.secret.jira")
    Optional<String> jiraSecret;

    @ConfigProperty(name = "webhook.secret.gitlab")
    Optional<String> gitlabSecret;

    @ConfigProperty(name = "webhook.secret.github")
    Optional<String> githubSecret;

    @ConfigProperty(name = "webhook.secret.aikido")
    Optional<String> aikidoSecret;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String path = ctx.getUriInfo().getPath();
        if (!path.startsWith("webhooks/")) {
            return;
        }

        if (path.contains("bitbucket/")) {
            verifyBitbucket(ctx);
        } else if (path.contains("azuredevops/")) {
            verifyAzureDevOps(ctx);
        } else if (path.contains("gitlab/")) {
            verifyGitLab(ctx);
        } else if (path.contains("github/")) {
            verifyGitHub(ctx);
        } else if (path.contains("jira")) {
            verifyJira(ctx);
        } else if (path.contains("aikido")) {
            verifyAikido(ctx);
        }
    }

    /**
     * Bitbucket Cloud signs the payload with HMAC-SHA256 and sends the signature
     * in the X-Hub-Signature header as "sha256=<hex-encoded-digest>".
     */
    private void verifyBitbucket(ContainerRequestContext ctx) throws IOException {
        String secret = bitbucketSecret.orElse("");
        if (isNotConfigured(secret)) return;

        String signatureHeader = ctx.getHeaderString("X-Hub-Signature");
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            LOG.warn("Bitbucket webhook rejected — missing or malformed X-Hub-Signature header");
            abort(ctx);
            return;
        }

        byte[] body = readAndRestoreBody(ctx);
        String expectedHex = signatureHeader.substring("sha256=".length());
        String computedHex = hmacSha256Hex(body, secret);

        if (!MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                computedHex.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("Bitbucket webhook rejected — HMAC signature mismatch");
            abort(ctx);
        }
    }

    /**
     * Azure DevOps Service Hooks can include a Basic-auth password or a shared secret
     * in a custom HTTP header. We compare the Authorization header's password portion
     * (Basic :secret) or fall back to an X-Azure-Signature HMAC check.
     */
    private void verifyAzureDevOps(ContainerRequestContext ctx) throws IOException {
        String secret = azureDevOpsSecret.orElse("");
        if (isNotConfigured(secret)) return;

        String authHeader = ctx.getHeaderString("Authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String decoded = new String(java.util.Base64.getDecoder().decode(
                        authHeader.substring("Basic ".length())), StandardCharsets.UTF_8);
                String password = decoded.contains(":") ? decoded.substring(decoded.indexOf(':') + 1) : decoded;
                if (MessageDigest.isEqual(
                        secret.getBytes(StandardCharsets.UTF_8),
                        password.getBytes(StandardCharsets.UTF_8))) {
                    return;
                }
            } catch (Exception e) {
                LOG.warnf("Azure DevOps webhook — failed to decode Basic auth: %s", e.getMessage());
            }
            LOG.warn("Azure DevOps webhook rejected — Basic auth secret mismatch");
            abort(ctx);
            return;
        }

        String signatureHeader = ctx.getHeaderString("X-Azure-Signature");
        if (signatureHeader != null) {
            byte[] body = readAndRestoreBody(ctx);
            String computedHex = hmacSha256Hex(body, secret);
            if (!MessageDigest.isEqual(
                    signatureHeader.getBytes(StandardCharsets.UTF_8),
                    computedHex.getBytes(StandardCharsets.UTF_8))) {
                LOG.warn("Azure DevOps webhook rejected — HMAC signature mismatch");
                abort(ctx);
            }
            return;
        }

        LOG.warn("Azure DevOps webhook rejected — no recognized auth header found");
        abort(ctx);
    }

    /**
     * GitHub signs the payload with HMAC-SHA256 and sends the signature
     * in the {@code X-Hub-Signature-256} header as {@code sha256=<hex-encoded-digest>}.
     */
    private void verifyGitHub(ContainerRequestContext ctx) throws IOException {
        String secret = githubSecret.orElse("");
        if (isNotConfigured(secret)) return;

        String signatureHeader = ctx.getHeaderString("X-Hub-Signature-256");
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            LOG.warn("GitHub webhook rejected — missing or malformed X-Hub-Signature-256 header");
            abort(ctx);
            return;
        }

        byte[] body = readAndRestoreBody(ctx);
        String expectedHex = signatureHeader.substring("sha256=".length());
        String computedHex = hmacSha256Hex(body, secret);

        if (!MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                computedHex.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("GitHub webhook rejected — HMAC signature mismatch");
            abort(ctx);
        }
    }

    /**
     * GitLab sends the webhook secret token as the {@code X-Gitlab-Token} header value.
     * Verification is a constant-time string comparison (no HMAC — GitLab does not sign
     * the payload body by default for standard webhooks).
     */
    private void verifyGitLab(ContainerRequestContext ctx) {
        String secret = gitlabSecret.orElse("");
        if (isNotConfigured(secret)) return;

        String tokenHeader = ctx.getHeaderString("X-Gitlab-Token");
        if (tokenHeader == null) {
            LOG.warn("GitLab webhook rejected — missing X-Gitlab-Token header");
            abort(ctx);
            return;
        }

        if (!MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                tokenHeader.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("GitLab webhook rejected — X-Gitlab-Token mismatch");
            abort(ctx);
        }
    }

    /**
     * JIRA Cloud native webhooks can be configured with a secret.
     * When set, JIRA includes it as the X-Hub-Secret header value for simple comparison,
     * or it may use a signed approach depending on the integration type.
     * We support both: direct secret comparison and HMAC signature.
     */
    private void verifyJira(ContainerRequestContext ctx) throws IOException {
        String secret = jiraSecret.orElse("");
        if (isNotConfigured(secret)) return;

        String hubSecret = ctx.getHeaderString("X-Hub-Secret");
        if (hubSecret != null) {
            if (MessageDigest.isEqual(
                    secret.getBytes(StandardCharsets.UTF_8),
                    hubSecret.getBytes(StandardCharsets.UTF_8))) {
                return;
            }
            LOG.warn("JIRA webhook rejected — X-Hub-Secret mismatch");
            abort(ctx);
            return;
        }

        String signatureHeader = ctx.getHeaderString("X-Hub-Signature");
        if (signatureHeader != null && signatureHeader.startsWith("sha256=")) {
            byte[] body = readAndRestoreBody(ctx);
            String expectedHex = signatureHeader.substring("sha256=".length());
            String computedHex = hmacSha256Hex(body, secret);

            if (!MessageDigest.isEqual(
                    expectedHex.getBytes(StandardCharsets.UTF_8),
                    computedHex.getBytes(StandardCharsets.UTF_8))) {
                LOG.warn("JIRA webhook rejected — HMAC signature mismatch");
                abort(ctx);
            }
            return;
        }

        // No recognized signature header present — reject
        LOG.warn("JIRA webhook rejected — no X-Hub-Secret or X-Hub-Signature header found");
        abort(ctx);
    }

    /**
     * Aikido sends the payload signed with HMAC-SHA256 and includes the hex digest in
     * the {@code X-Aikido-Webhook-Signature} header (raw hex, no {@code sha256=} prefix).
     * The payload also contains a top-level {@code dispatched_at} epoch timestamp;
     * requests older than {@value #REPLAY_WINDOW_SECONDS} seconds are rejected to prevent
     * replay attacks.
     */
    private void verifyAikido(ContainerRequestContext ctx) throws IOException {
        String secret = aikidoSecret.orElse("");
        if (isNotConfigured(secret)) return;

        String signatureHeader = ctx.getHeaderString("X-Aikido-Webhook-Signature");
        if (signatureHeader == null || signatureHeader.isBlank()) {
            LOG.warn("Aikido webhook rejected — missing X-Aikido-Webhook-Signature header");
            abort(ctx);
            return;
        }

        byte[] body = readAndRestoreBody(ctx);
        String computedHex = hmacSha256Hex(body, secret);

        if (!MessageDigest.isEqual(
                signatureHeader.getBytes(StandardCharsets.UTF_8),
                computedHex.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("Aikido webhook rejected — HMAC signature mismatch");
            abort(ctx);
            return;
        }

        // Replay-attack guard: dispatched_at must be within the allowed window
        try {
            long dispatchedAt = MAPPER.readTree(body).path("dispatched_at").asLong(0L);
            if (!isTimestampFresh(dispatchedAt)) {
                LOG.warnf("Aikido webhook rejected — dispatched_at %d is older than %d seconds",
                        dispatchedAt, REPLAY_WINDOW_SECONDS);
                abort(ctx);
            }
        } catch (Exception e) {
            LOG.warnf("Aikido webhook rejected — could not parse dispatched_at: %s", e.getMessage());
            abort(ctx);
        }
    }

    /**
     * Returns {@code true} when {@code dispatchedAt} (epoch seconds) is within the replay window.
     */
    public static boolean isTimestampFresh(long dispatchedAt) {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        return (nowSeconds - dispatchedAt) <= REPLAY_WINDOW_SECONDS;
    }

    private byte[] readAndRestoreBody(ContainerRequestContext ctx) throws IOException {
        byte[] body = ctx.getEntityStream().readAllBytes();
        ctx.setEntityStream(new ByteArrayInputStream(body));
        return body;
    }

    public static String hmacSha256Hex(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    private static boolean isNotConfigured(String value) {
        return value == null || value.isBlank() || "-".equals(value.trim());
    }

    private static void abort(ContainerRequestContext ctx) {
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Invalid webhook signature"))
                .build());
    }
}
