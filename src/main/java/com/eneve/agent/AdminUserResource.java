package com.eneve.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.eneve.agent.agent.store.TeamStore;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.settings.SettingsService;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.EventRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Admin-only REST resource that proxies the Keycloak Admin REST API for user management.
 *
 * <p>Credentials are read from {@link SettingsService} (DB-backed, configurable via System Settings UI):
 * <ul>
 *   <li>{@code keycloak.admin.server-url} — Keycloak base URL</li>
 *   <li>{@code keycloak.admin.realm} — realm for both authentication and user management (default: {@code master})</li>
 *   <li>{@code keycloak.admin.client-id} — service account client ID</li>
 *   <li>{@code keycloak.admin.client-secret} — service account client secret (encrypted at rest)</li>
 * </ul>
 *
 * <p>The service account must be granted {@code view-users} and {@code manage-users} roles
 * under {@code realm-management} in Keycloak.
 */
@RolesAllowed("app_admin")
@Path("/admin/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminUserResource {

    private static final Logger LOG = Logger.getLogger(AdminUserResource.class);

    @Inject
    SettingsService settings;

    @Inject
    AuditService auditService;

    @Inject
    TeamStore teamStore;

    // ── DTOs ──────────────────────────────────────────────────────────────────────

    public record UserView(
            String id,
            String username,
            String email,
            String firstName,
            String lastName,
            boolean enabled,
            List<String> roles,
            List<String> groups,
            String lastLoginAt
    ) {}

    public record SetEnabledRequest(boolean enabled) {}

    // ── Endpoints ─────────────────────────────────────────────────────────────────

    @GET
    public Response listUsers() {
        try (Keycloak kc = buildClient()) {
            RealmResource realm = kc.realm(targetRealm());
            List<UserRepresentation> users = realm.users().list(0, Integer.MAX_VALUE);

            Map<String, Long> lastLoginMap = buildLastLoginMap(realm);

            List<UserView> views = users.stream()
                    .map(u -> toView(u, realm, lastLoginMap))
                    .collect(Collectors.toList());

            return Response.ok(views).build();
        } catch (IllegalStateException e) {
            return Response.status(503)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorf("Failed to list Keycloak users: %s", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to retrieve users: " + e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{id}/enabled")
    public Response setEnabled(@PathParam("id") String userId, SetEnabledRequest request) {
        if (request == null) {
            return Response.status(400).entity(Map.of("error", "Request body required")).build();
        }
        try (Keycloak kc = buildClient()) {
            RealmResource realm = kc.realm(targetRealm());
            var userResource = realm.users().get(userId);
            UserRepresentation rep = userResource.toRepresentation();
            rep.setEnabled(request.enabled());
            userResource.update(rep);

            String action = request.enabled() ? "USER_UNBLOCKED" : "USER_BLOCKED";
            auditService.log("USERS", action, "user", userId,
                    Map.of("enabled",  String.valueOf(request.enabled()),
                           "username", rep.getUsername() != null ? rep.getUsername() : ""));

            LOG.infof("User %s enabled=%s by admin", userId, request.enabled());
            return Response.ok(Map.of("id", userId, "enabled", request.enabled())).build();
        } catch (IllegalStateException e) {
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Response.status(404)
                    .entity(Map.of("error", "User not found: " + userId))
                    .build();
        } catch (Exception e) {
            LOG.errorf("Failed to update enabled state for user %s: %s", userId, e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to update user: " + e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{id}/teams")
    public Response removeFromAllTeams(@PathParam("id") String userId) {
        try (Keycloak kc = buildClient()) {
            RealmResource realm = kc.realm(targetRealm());
            UserRepresentation rep = realm.users().get(userId).toRepresentation();
            String username = rep != null && rep.getUsername() != null ? rep.getUsername() : "";

            int removed = teamStore.removeUserFromAllTeams(userId);

            auditService.log("USERS", "USER_LEFT_COMPANY", "user", userId,
                    Map.of("username", username, "teamsRemoved", String.valueOf(removed)));

            LOG.infof("User %s removed from all teams (%d memberships)", userId, removed);
            return Response.ok(Map.of("removed", true, "userId", userId, "membershipsDeleted", removed)).build();
        } catch (IllegalStateException e) {
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        } catch (jakarta.ws.rs.NotFoundException e) {
            // User not in Keycloak — still remove from teams
            int removed = teamStore.removeUserFromAllTeams(userId);
            return Response.ok(Map.of("removed", true, "userId", userId, "membershipsDeleted", removed)).build();
        } catch (Exception e) {
            LOG.errorf("Failed to remove user %s from all teams: %s", userId, e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to remove user from teams: " + e.getMessage()))
                    .build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private Keycloak buildClient() {
        String serverUrl    = settings.get("keycloak.admin.server-url");
        String realm        = settings.get("keycloak.admin.realm", "master");
        String clientId     = settings.get("keycloak.admin.client-id");
        String clientSecret = settings.getSecret("keycloak.admin.client-secret");

        if (serverUrl.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Keycloak admin credentials not configured. " +
                    "Set keycloak.admin.server-url, keycloak.admin.client-id, and " +
                    "keycloak.admin.client-secret in System Settings → Security.");
        }

        return KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm(realm)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
    }

    private String targetRealm() {
        return settings.get("keycloak.admin.realm", "master");
    }

    /**
     * Fetches up to 2000 recent LOGIN events in one request and returns a
     * {@code userId → max(eventTime)} map for last-login display.
     */
    private Map<String, Long> buildLastLoginMap(RealmResource realm) {
        try {
            List<EventRepresentation> events = realm.getEvents(
                    List.of("LOGIN"), null, null, null, null, null, 0, 2000
            );
            Map<String, Long> map = new HashMap<>();
            for (EventRepresentation e : events) {
                if (e.getUserId() == null) continue;
                map.merge(e.getUserId(), e.getTime(), Math::max);
            }
            return map;
        } catch (Exception e) {
            LOG.warnf("Could not fetch login events (event store may be disabled): %s", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private UserView toView(UserRepresentation u, RealmResource realm,
                            Map<String, Long> lastLoginMap) {
        List<String> roles = Collections.emptyList();
        List<String> groups = Collections.emptyList();
        try {
            var userResource = realm.users().get(u.getId());

            roles = userResource.roles().realmLevel().listAll()
                    .stream()
                    .map(RoleRepresentation::getName)
                    .filter(name -> !name.startsWith("default-roles-"))
                    .sorted()
                    .collect(Collectors.toList());

            groups = userResource.groups()
                    .stream()
                    .map(GroupRepresentation::getName)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.warnf("Could not fetch roles/groups for user %s: %s", u.getId(), e.getMessage());
        }

        Long lastLoginMs = lastLoginMap.get(u.getId());
        String lastLoginAt = lastLoginMs != null
                ? Instant.ofEpochMilli(lastLoginMs).toString()
                : null;

        return new UserView(
                u.getId(),
                u.getUsername(),
                u.getEmail(),
                u.getFirstName(),
                u.getLastName(),
                Boolean.TRUE.equals(u.isEnabled()),
                roles,
                groups,
                lastLoginAt
        );
    }
}
