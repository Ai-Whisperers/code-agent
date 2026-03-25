package com.eneve.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.eneve.agent.security.AppPermission;
import com.eneve.agent.security.AppRole;
import com.eneve.agent.security.PermissionService;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Returns the currently authenticated user's identity derived from the Keycloak JWT.
 * Used by the React UI to display user info (name, email, roles, permissions).
 */
@Path("/me")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    private static final Logger LOG = Logger.getLogger(UserResource.class);

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    JsonWebToken jwt;

    @Inject
    PermissionService permissionService;

    @GET
    public Response getCurrentUser() {
        if (securityIdentity.isAnonymous()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Not authenticated"))
                    .build();
        }

        String name = getClaimOrDefault("name", securityIdentity.getPrincipal().getName());
        String email = getClaimOrDefault("email", "");
        String preferredUsername = getClaimOrDefault("preferred_username", securityIdentity.getPrincipal().getName());
        String sub = getClaimOrDefault("sub", "");

        // Raw KC roles from both realm_access and resource_access (combined by Quarkus OIDC)
        Set<String> kcRoles = securityIdentity.getRoles();

        // Groups claim — KC requires an explicit group mapper; default to empty list if absent
        List<String> groups = getGroupsClaim();

        // Derived app-level roles and permissions
        Set<AppRole> appRoles = permissionService.getAppRoles();
        Set<AppPermission> permissions = permissionService.getPermissions();

        List<String> appRoleNames = appRoles.stream()
                .map(AppRole::name)
                .sorted()
                .collect(Collectors.toList());

        List<String> permissionNames = permissions.stream()
                .map(AppPermission::name)
                .sorted()
                .collect(Collectors.toList());

        LOG.debugf("GET /me — user=%s appRoles=%s", preferredUsername, appRoleNames);

        return Response.ok(Map.of(
                "username",    preferredUsername,
                "name",        name,
                "email",       email,
                "sub",         sub,
                "roles",       kcRoles,
                "kcRoles",     kcRoles,
                "groups",      groups,
                "appRoles",    appRoleNames,
                "permissions", permissionNames
        )).build();
    }

    private String getClaimOrDefault(String claim, String defaultValue) {
        try {
            String value = jwt.getClaim(claim);
            return value != null ? value : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private List<String> getGroupsClaim() {
        try {
            Object raw = jwt.getClaim("groups");
            if (raw instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item != null) result.add(item.toString());
                }
                return result;
            }
        } catch (Exception e) {
            // groups claim absent or malformed — not required
        }
        return List.of();
    }
}
