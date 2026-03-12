package com.eneve.agent.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

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

    @ConfigProperty(name = "webhook.secret.bitbucket", defaultValue = "")
    String bitbucketSecret;

    @ConfigProperty(name = "webhook.secret.jira", defaultValue = "")
    String jiraSecret;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String path = ctx.getUriInfo().getPath();
        if (!path.startsWith("webhooks/")) {
            return;
        }

        if (path.contains("bitbucket/")) {
            verifyBitbucket(ctx);
        } else if (path.contains("jira")) {
            verifyJira(ctx);
        }
    }

    /**
     * Bitbucket Cloud signs the payload with HMAC-SHA256 and sends the signature
     * in the X-Hub-Signature header as "sha256=<hex-encoded-digest>".
     */
    private void verifyBitbucket(ContainerRequestContext ctx) throws IOException {
        if (bitbucketSecret.isBlank()) return;

        String signatureHeader = ctx.getHeaderString("X-Hub-Signature");
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            LOG.warn("Bitbucket webhook rejected — missing or malformed X-Hub-Signature header");
            abort(ctx);
            return;
        }

        byte[] body = readAndRestoreBody(ctx);
        String expectedHex = signatureHeader.substring("sha256=".length());
        String computedHex = hmacSha256Hex(body, bitbucketSecret);

        if (!MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                computedHex.getBytes(StandardCharsets.UTF_8))) {
            LOG.warn("Bitbucket webhook rejected — HMAC signature mismatch");
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
        if (jiraSecret.isBlank()) return;

        // Strategy 1: JIRA sends the secret directly in X-Hub-Secret
        String hubSecret = ctx.getHeaderString("X-Hub-Secret");
        if (hubSecret != null) {
            if (MessageDigest.isEqual(
                    jiraSecret.getBytes(StandardCharsets.UTF_8),
                    hubSecret.getBytes(StandardCharsets.UTF_8))) {
                return;
            }
            LOG.warn("JIRA webhook rejected — X-Hub-Secret mismatch");
            abort(ctx);
            return;
        }

        // Strategy 2: HMAC signature in X-Hub-Signature (same format as Bitbucket)
        String signatureHeader = ctx.getHeaderString("X-Hub-Signature");
        if (signatureHeader != null && signatureHeader.startsWith("sha256=")) {
            byte[] body = readAndRestoreBody(ctx);
            String expectedHex = signatureHeader.substring("sha256=".length());
            String computedHex = hmacSha256Hex(body, jiraSecret);

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

    private byte[] readAndRestoreBody(ContainerRequestContext ctx) throws IOException {
        byte[] body = ctx.getEntityStream().readAllBytes();
        ctx.setEntityStream(new ByteArrayInputStream(body));
        return body;
    }

    private static String hmacSha256Hex(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    private static void abort(ContainerRequestContext ctx) {
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Invalid webhook signature"))
                .build());
    }
}
