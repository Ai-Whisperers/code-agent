package com.eneve.agent;

import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Returns the currently authenticated user's identity derived from the Keycloak JWT.
 * Used by the React UI to display user info (name, email, roles).
 */
@Path("/me")
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    private static final Logger LOG = Logger.getLogger(UserResource.class);

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    JsonWebToken jwt;

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
        Set<String> roles = securityIdentity.getRoles();

        LOG.debugf("GET /me — user=%s roles=%s", preferredUsername, roles);

        return Response.ok(Map.of(
                "username", preferredUsername,
                "name", name,
                "email", email,
                "roles", roles
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
}
