package com.eneve.agent.security;

import java.util.Map;
import java.util.Set;

import com.eneve.agent.settings.SettingsService;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

/**
 * Authenticates REST API requests using either:
 *   1. A Keycloak OIDC Bearer token (when quarkus.oidc.tenant-enabled=true), or
 *   2. A shared API key in the X-API-Key header (legacy / external callers).
 *
 * When an API key is valid, a synthetic {@link SecurityIdentity} with the {@code app_admin}
 * role is set so that {@code @Authenticated} and {@code @RolesAllowed} annotations work
 * transparently for both OIDC users and API key callers.
 *
 * Skips public paths (/health, /q/*) and webhook paths (handled by WebhookSignatureFilter).
 * When api.key is blank and OIDC is disabled, authentication is fully disabled (dev mode).
 */
public class ApiKeyFilter {

    private static final Logger LOG = Logger.getLogger(ApiKeyFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_PRINCIPAL = "api-key-caller";

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "health",
            "q/"
    );

    @Inject
    SettingsService settingsService;

    @Inject
    CurrentIdentityAssociation identityAssociation;

    // AIW: preMatching=true makes this filter run before Quarkus @Authenticated
    // enforcement. The upstream version ran at Priorities.USER (5000), which is
    // AFTER auth (1000) — meaning the filter's `identityAssociation.setIdentity`
    // never got a chance to save the request, and @Authenticated endpoints
    // always 401'd in dev (OIDC-disabled) mode. Pre-matching fixes both modes.
    @ServerRequestFilter(preMatching = true)
    public Uni<Response> filter(ContainerRequestContext ctx) {
        // Always let CORS preflight through — OPTIONS carries no credentials
        if ("OPTIONS".equalsIgnoreCase(ctx.getMethod())) {
            return pass();
        }

        String path = ctx.getUriInfo().getPath();

        if (PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith)) {
            return pass();
        }
        if (path.startsWith("webhooks/")) {
            return pass();
        }

        return identityAssociation.getDeferredIdentity()
                .onItem().transformToUni(identity -> {
                    // Allow requests already authenticated by Keycloak OIDC
                    if (!identity.isAnonymous()) {
                        return pass();
                    }

                    // AIW: dev-mode bypass. When dev.auth.bypass=true (set via the
                    // DEV_AUTH_BYPASS env var), stamp a synthetic admin identity on
                    // every anonymous request so @Authenticated endpoints are
                    // reachable without a Keycloak token or API key. Intended for
                    // local smoke tests only — do NOT enable in production. The
                    // proper production fix lands with integration:supabase-auth.
                    if ("true".equalsIgnoreCase(settingsService.get("dev.auth.bypass", "false"))) {
                        LOG.debugf("dev.auth.bypass=true: stamping synthetic admin identity on /%s", path);
                        identityAssociation.setIdentity(synthAdmin("dev-bypass"));
                        return pass();
                    }

                    // Fall back to API key check (read per-request to support live rotation)
                    String apiKey = settingsService.getSecret("api.key");
                    if (isNotConfigured(apiKey)) {
                        return pass();
                    }

                    String provided = ctx.getHeaderString(API_KEY_HEADER);
                    if (apiKey.equals(provided)) {
                        identityAssociation.setIdentity(synthAdmin(API_KEY_PRINCIPAL));
                        return pass();
                    }

                    LOG.warnf("Rejected request to /%s — invalid or missing credentials", path);
                    return Uni.createFrom().item(
                            Response.status(Response.Status.UNAUTHORIZED)
                                    .entity(Map.of("error", "Invalid or missing credentials"))
                                    .build()
                    );
                });
    }

    // null item = pass through; non-null Response = abort with that response
    private static Uni<Response> pass() {
        return Uni.createFrom().nullItem();
    }

    /**
     * Builds a synthetic app_admin SecurityIdentity. Used for both API key
     * callers and the dev-mode bypass. The Principal carries the source name
     * (e.g. "dev-bypass", "api-key") for audit logging.
     */
    private static SecurityIdentity synthAdmin(String principalName) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new java.security.Principal() {
                    @Override public String getName() { return principalName; }
                })
                .addRole(RoleMapper.KC_ADMIN)
                .setAnonymous(false)
                .build();
    }

    private static boolean isNotConfigured(String value) {
        return value == null || value.isBlank() || "-".equals(value.trim());
    }
}
