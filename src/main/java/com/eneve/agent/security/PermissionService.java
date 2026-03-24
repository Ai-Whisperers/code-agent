package com.eneve.agent.security;

import java.util.Set;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * Request-scoped service that resolves the current user's {@link AppRole}s and
 * {@link AppPermission}s from the active {@link SecurityIdentity}.
 *
 * <p>Used exclusively by {@code UserResource} to populate the {@code /api/me} response.
 * Endpoint access control is handled declaratively via {@code @RolesAllowed}.
 */
@RequestScoped
public class PermissionService {

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    RoleMapper roleMapper;

    @Inject
    PermissionResolver permissionResolver;

    public Set<AppRole> getAppRoles() {
        return roleMapper.map(securityIdentity.getRoles());
    }

    public Set<AppPermission> getPermissions() {
        return permissionResolver.resolve(getAppRoles());
    }
}
