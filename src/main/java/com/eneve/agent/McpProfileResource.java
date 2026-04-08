package com.eneve.agent;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.eneve.agent.mcp.AtlassianOAuthService;
import com.eneve.agent.mcp.LinkedAccountService;
import com.eneve.agent.settings.SettingsService;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;
import java.util.Optional;

/**
 * REST API for managing per-user linked Jira and Confluence accounts.
 * All endpoints require a valid Keycloak OIDC Bearer token.
 * Anonymous/API-key-only requests are rejected (HTTP 403).
 *
 * <p>Linked accounts enable the AI agent to perform actions on behalf of the user,
 * respecting their Jira/Confluence permissions.
 *
 * <p>API tokens are encrypted at rest (AES-256-GCM) and are never returned in plaintext.
 */
@Path("/mcp/profiles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "MCP Profiles", description = "Manage per-user linked Jira and Confluence accounts")
public class McpProfileResource {

    @Inject
    LinkedAccountService linkedAccountService;

    @Inject
    AtlassianOAuthService atlassianOAuth;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    JsonWebToken jwt;

    @Inject
    SettingsService settings;

    // ─── List all linked accounts for current user ─────────────────────────────

    @GET
    @Operation(
            operationId = "listMcpProfiles",
            summary = "List all linked accounts for the current user",
            description = "Returns all Jira and Confluence accounts linked to the authenticated user. " +
                    "API tokens are masked as '****' in responses."
    )
    @APIResponse(responseCode = "200", description = "List of linked accounts")
    @APIResponse(responseCode = "403", description = "Anonymous or API-key-only access not allowed")
    public Response list() {
        String userId = resolveUserId();
        if (userId == null) {
            return forbidden("Profile management requires a valid OIDC Bearer token. API-key access is not allowed.");
        }
        return Response.ok(linkedAccountService.listForUser(userId)).build();
    }

    // ─── Get a specific linked account ───────────────────────────────────────────

    @GET
    @Path("/{provider}")
    @Operation(
            operationId = "getMcpProfile",
            summary = "Get a linked account by provider",
            description = "Returns the linked Jira or Confluence account for the current user. " +
                    "API token is masked as '****' in the response."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Linked account found"),
            @APIResponse(responseCode = "403", description = "Anonymous or API-key-only access not allowed"),
            @APIResponse(responseCode = "404", description = "No linked account for this provider")
    })
    public Response get(
            @Parameter(description = "Provider: jira or confluence", required = true)
            @PathParam("provider") String provider) {

        String userId = resolveUserId();
        if (userId == null) {
            return forbidden("Profile management requires a valid OIDC Bearer token. API-key access is not allowed.");
        }

        if (!isValidProvider(provider)) {
            return badRequest("Provider must be 'jira', 'confluence', or 'xray'");
        }

        Optional<LinkedAccountService.AccountView> account = linkedAccountService.findForUser(userId, provider);
        return account
                .map(v -> Response.ok(v).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "No linked account found for provider: " + provider))
                        .build());
    }

    // ─── Create or update a linked account ─────────────────────────────────────

    @PUT
    @Path("/{provider}")
    @Operation(
            operationId = "upsertMcpProfile",
            summary = "Create or update a linked account",
            description = "Links a Jira or Confluence account to the current user. " +
                    "The API token is encrypted with AES-256-GCM before storage."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Account linked successfully"),
            @APIResponse(responseCode = "403", description = "Anonymous or API-key-only access not allowed"),
            @APIResponse(responseCode = "400", description = "Invalid request or provider"),
            @APIResponse(responseCode = "500", description = "Encryption not configured")
    })
    public Response upsert(
            @Parameter(description = "Provider: jira or confluence", required = true)
            @PathParam("provider") String provider,
            @RequestBody(description = "Account credentials", required = true)
            UpsertProfileRequest request) {

        String userId = resolveUserId();
        if (userId == null) {
            return forbidden("Profile management requires a valid OIDC Bearer token. API-key access is not allowed.");
        }

        if (!isValidProvider(provider)) {
            return badRequest("Provider must be 'jira', 'confluence', or 'xray'");
        }

        if (request == null || request.baseUrl() == null || request.baseUrl().isBlank()
                || request.username() == null || request.username().isBlank()
                || request.apiToken() == null || request.apiToken().isBlank()) {
            return badRequest("baseUrl, username, and apiToken are required");
        }

        try {
            linkedAccountService.upsert(
                    userId,
                    provider,
                    request.displayName(),
                    request.baseUrl().trim(),
                    request.username().trim(),
                    request.apiToken()
            );
        } catch (IllegalStateException e) {
            return Response.status(500)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        return Response.ok(Map.of(
                "action", "saved",
                "provider", provider,
                "displayName", request.displayName() != null ? request.displayName() : provider
        )).build();
    }

    // ─── Delete a linked account ───────────────────────────────────────────────

    @DELETE
    @Path("/{provider}")
    @Operation(
            operationId = "deleteMcpProfile",
            summary = "Delete a linked account",
            description = "Removes the linked account for the specified provider."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Account unlinked successfully"),
            @APIResponse(responseCode = "403", description = "Anonymous or API-key-only access not allowed"),
            @APIResponse(responseCode = "404", description = "No linked account found for this provider")
    })
    public Response delete(
            @Parameter(description = "Provider: jira or confluence", required = true)
            @PathParam("provider") String provider) {

        String userId = resolveUserId();
        if (userId == null) {
            return forbidden("Profile management requires a valid OIDC Bearer token. API-key access is not allowed.");
        }

        if (!isValidProvider(provider)) {
            return badRequest("Provider must be 'jira', 'confluence', or 'xray'");
        }

        boolean deleted = linkedAccountService.delete(userId, provider);
        if (!deleted) {
            return Response.status(404)
                    .entity(Map.of("error", "No linked account found for provider: " + provider))
                    .build();
        }

        return Response.ok(Map.of(
                "action", "deleted",
                "provider", provider
        )).build();
    }

    // ─── Test connection before saving ─────────────────────────────────────────

    @POST
    @Path("/{provider}/test")
    @Operation(
            operationId = "testMcpConnection",
            summary = "Test connection to Jira or Confluence",
            description = "Tests the provided credentials without storing them. " +
                    "Returns 200 with ok=true if the connection succeeds, ok=false otherwise."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Connection test completed"),
            @APIResponse(responseCode = "403", description = "Anonymous or API-key-only access not allowed"),
            @APIResponse(responseCode = "400", description = "Invalid request or provider")
    })
    public Response test(
            @Parameter(description = "Provider: jira or confluence", required = true)
            @PathParam("provider") String provider,
            @RequestBody(description = "Account credentials to test (optional — omit to test stored credentials)", required = false)
            TestConnectionRequest request) {

        String userId = resolveUserId();
        if (userId == null) {
            return forbidden("Profile management requires a valid OIDC Bearer token. API-key access is not allowed.");
        }

        if (!isValidProvider(provider)) {
            return badRequest("Provider must be 'jira', 'confluence', or 'xray'");
        }

        boolean hasCredentials = request != null
                && request.baseUrl() != null && !request.baseUrl().isBlank()
                && request.username() != null && !request.username().isBlank()
                && request.apiToken() != null && !request.apiToken().isBlank();

        boolean ok;
        if (hasCredentials) {
            ok = switch (provider) {
                case "jira"       -> linkedAccountService.testJiraConnection(
                        request.baseUrl().trim(), request.username().trim(), request.apiToken());
                case "confluence" -> linkedAccountService.testConfluenceConnection(
                        request.baseUrl().trim(), request.username().trim(), request.apiToken());
                case "xray"       -> linkedAccountService.testXrayConnection(
                        request.baseUrl().trim(), request.username().trim(), request.apiToken());
                default           -> false;
            };
        } else {
            // No credentials supplied — test the already-stored account
            java.util.Optional<Boolean> stored = switch (provider) {
                case "jira"       -> linkedAccountService.testStoredJiraConnection(userId);
                case "confluence" -> linkedAccountService.testStoredConfluenceConnection(userId);
                case "xray"       -> linkedAccountService.testStoredXrayConnection(userId);
                default           -> java.util.Optional.empty();
            };
            if (stored.isEmpty()) {
                return badRequest("No stored credentials for provider '" + provider + "'. Please save an account first.");
            }
            ok = stored.get();
        }

        String message = ok ? "Connection successful" : "Connection failed — check your credentials";
        return Response.ok(Map.of("success", ok, "message", message)).build();
    }

    // ─── OAuth 2.0 authorization URL ────────────────────────────────────────────

    @GET
    @Path("/{provider}/oauth/authorize")
    @Operation(
            operationId = "getOAuthAuthorizeUrl",
            summary = "Get the OAuth 2.0 authorization URL for a provider",
            description = "Returns the URL the user should be redirected to in order to grant access. " +
                    "Currently only 'jira' is supported. Requires atlassian.oauth.client-id to be configured.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Authorization URL generated"),
            @APIResponse(responseCode = "400", description = "OAuth not supported for this provider or not configured"),
            @APIResponse(responseCode = "403", description = "Anonymous or API-key-only access not allowed")
    })
    public Response getOAuthAuthorizeUrl(
            @Parameter(description = "Provider — currently only 'jira' is supported", required = true)
            @PathParam("provider") String provider,
            @Parameter(description = "OAuth redirect URI — must match what is registered in Atlassian", required = true)
            @QueryParam("redirect_uri") String redirectUri) {

        String userId = resolveUserId();
        if (userId == null) {
            return forbidden("Profile management requires a valid OIDC Bearer token.");
        }

        if (!"jira".equals(provider)) {
            return badRequest("OAuth authorization is only supported for the 'jira' provider.");
        }

        if (redirectUri == null || redirectUri.isBlank()) {
            return badRequest("redirect_uri is required.");
        }

        try {
            AtlassianOAuthService.AuthorizeResult result =
                    atlassianOAuth.generateAuthorizationUrl(userId, redirectUri);
            return Response.ok(Map.of("url", result.url(), "state", result.state())).build();
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        }
    }

    // ─── OAuth configured status ─────────────────────────────────────────────────

    @GET
    @Path("/oauth/status")
    @Operation(
            operationId = "getOAuthStatus",
            summary = "Check which providers have OAuth configured on the server")
    @APIResponse(responseCode = "200", description = "Status map returned")
    public Response getOAuthStatus() {
        String userId = resolveUserId();
        if (userId == null) {
            return forbidden("Profile management requires a valid OIDC Bearer token.");
        }
        return Response.ok(Map.of(
                "atlassian", atlassianOAuth.isConfigured()
        )).build();
    }

    // ─── Get defaults for linking (user email from JWT, system URLs from config) ────

    @GET
    @Path("/system-config")
    @Operation(
            operationId = "getSystemMcpConfig",
            summary = "Get defaults for linking accounts",
            description = "Returns the system Jira/Confluence base URLs and the current user's email from the JWT token. " +
                    "Users only need to provide their API token."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Defaults returned"),
            @APIResponse(responseCode = "403", description = "Anonymous or API-key-only access not allowed")
    })
    public Response getSystemConfig() {
        String userId = resolveUserId();
        if (userId == null) {
            return forbidden("Profile management requires a valid OIDC Bearer token. API-key access is not allowed.");
        }
        String email = jwt.getClaim("email");
        if (email == null || email.isBlank()) {
            email = jwt.getClaim("preferred_username"); // fallback
        }
        if (email == null || email.isBlank()) {
            email = userId; // final fallback
        }
        return Response.ok(Map.of(
                "jira", Map.of(
                        "baseUrl", settings.get("jira.base.url", ""),
                        "username", email
                ),
                "confluence", Map.of(
                        "baseUrl", settings.get("confluence.base.url", ""),
                        "username", email
                ),
                "xray", Map.of(
                        "baseUrl", settings.get("xray.base-url", "https://xray.cloud.getxray.app")
                )
        )).build();
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolve the user ID from the JWT 'sub' claim.
     * Returns null if the user is anonymous (OIDC disabled or API-key-only).
     */
    private String resolveUserId() {
        if (securityIdentity.isAnonymous()) {
            return null;
        }
        try {
            String sub = jwt.getClaim("sub");
            if (sub != null && !sub.isBlank()) {
                return sub;
            }
        } catch (Exception ignored) {
        }
        return securityIdentity.getPrincipal().getName();
    }

    private boolean isValidProvider(String provider) {
        return "jira".equals(provider) || "confluence".equals(provider) || "xray".equals(provider);
    }

    private Response forbidden(String message) {
        return Response.status(403)
                .entity(Map.of("error", message))
                .build();
    }

    private Response badRequest(String message) {
        return Response.status(400)
                .entity(Map.of("error", message))
                .build();
    }

    // ─── Request DTOs ───────────────────────────────────────────────────────────

    public record UpsertProfileRequest(
            String displayName,
            String baseUrl,
            String username,
            String apiToken
    ) {}

    public record TestConnectionRequest(
            String baseUrl,
            String username,
            String apiToken
    ) {}
}
