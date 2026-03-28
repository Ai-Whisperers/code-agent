package com.eneve.agent.mcp;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Handles the OAuth 2.0 redirect callback from Atlassian.
 * No authentication annotation is needed here: quarkus.http.auth.proactive=false means
 * unauthenticated browser redirects pass through by default. The OIDC session is
 * identified via the {@code state} parameter that was bound to the user's ID when the flow started.
 */
@Path("/mcp/oauth")
@Tag(name = "MCP Profiles", description = "OAuth callback for linked accounts")
public class McpOAuthCallbackResource {

    private static final Logger LOG = Logger.getLogger(McpOAuthCallbackResource.class);

    @Inject
    AtlassianOAuthService atlassianOAuth;

    /**
     * Atlassian redirects here after the user authorises (or denies) the OAuth request.
     * On success: exchanges the code for tokens, stores the linked account, and
     * redirects the popup window to {@code /oauth/callback?status=success&…}.
     * On error: redirects to {@code /oauth/callback?status=error&message=…}.
     */
    @GET
    @Path("/callback")
    @Produces(MediaType.TEXT_HTML)
    @Operation(
            operationId = "handleOAuthCallback",
            summary = "OAuth 2.0 redirect callback",
            description = "Handles the Atlassian redirect after user authorization. " +
                    "Not intended for direct API calls.")
    public Response handleCallback(
            @QueryParam("code")              String code,
            @QueryParam("state")             String state,
            @QueryParam("error")             String error,
            @QueryParam("error_description") String errorDescription,
            @Context UriInfo uriInfo) {

        String frontendBase = deriveFrontendBase(uriInfo);
        String redirectUri  = deriveCallbackUrl(uriInfo);

        if (error != null && !error.isBlank()) {
            String msg = errorDescription != null ? errorDescription : error;
            LOG.warnf("Atlassian OAuth error returned: %s", msg);
            return redirectToFrontend(frontendBase, "error", "jira", msg);
        }

        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return redirectToFrontend(frontendBase, "error", "jira",
                    "Missing code or state parameter.");
        }

        AtlassianOAuthService.CallbackResult result =
                atlassianOAuth.handleCallback(code, state, redirectUri);

        return redirectToFrontend(frontendBase,
                result.success() ? "success" : "error",
                result.provider(),
                result.message());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Derives the backend callback URL from the current request context. */
    private String deriveCallbackUrl(UriInfo uriInfo) {
        // e.g. http://localhost:8080/api/mcp/oauth/callback
        return uriInfo.getAbsolutePath().toString();
    }

    /**
     * Derives the frontend base URL by stripping the API path prefix.
     * Assumes the API is mounted at {@code /api} on the same origin as the frontend.
     */
    private String deriveFrontendBase(UriInfo uriInfo) {
        String base = uriInfo.getBaseUri().toString(); // e.g. http://localhost:8080/api/
        // Remove trailing /api/ or /api
        return base.replaceAll("/api/?$", "").replaceAll("/$", "");
    }

    private Response redirectToFrontend(String frontendBase, String status,
                                        String provider, String message) {
        String target = frontendBase + "/oauth/callback"
                + "?status="   + enc(status)
                + "&provider=" + enc(provider)
                + "&message="  + enc(message != null ? message : "");
        return Response.seeOther(URI.create(target)).build();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
