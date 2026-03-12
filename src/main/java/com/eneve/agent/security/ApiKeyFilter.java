package com.eneve.agent.security;

import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Authenticates REST API requests using a shared API key in the X-API-Key header.
 * Skips public paths (/health, /q/*) and webhook paths (handled by WebhookSignatureFilter).
 * When api.key is blank, authentication is disabled (development mode).
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ApiKeyFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "health",
            "q/"
    );

    @ConfigProperty(name = "api.key", defaultValue = "")
    String apiKey;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (isNotConfigured(apiKey)) {
            return;
        }

        String path = ctx.getUriInfo().getPath();

        if (PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith)) {
            return;
        }
        if (path.startsWith("webhooks/")) {
            return;
        }

        String provided = ctx.getHeaderString(API_KEY_HEADER);
        if (apiKey.equals(provided)) {
            return;
        }

        LOG.warnf("Rejected request to /%s — invalid or missing API key", path);
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Invalid or missing API key"))
                .build());
    }

    private static boolean isNotConfigured(String value) {
        return value == null || value.isBlank() || "-".equals(value.trim());
    }
}
