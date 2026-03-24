package com.eneve.agent.security;

import java.util.EnumSet;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Resolves the effective {@link AppPermission} set for a collection of {@link AppRole}s
 * by computing the union of each role's static permission grant.
 */
@ApplicationScoped
public class PermissionResolver {

    public Set<AppPermission> resolve(Set<AppRole> roles) {
        Set<AppPermission> permissions = EnumSet.noneOf(AppPermission.class);
        for (AppRole role : roles) {
            permissions.addAll(permissionsFor(role));
        }
        return permissions;
    }

    private static Set<AppPermission> permissionsFor(AppRole role) {
        return switch (role) {
            case USER          -> EnumSet.of(AppPermission.USE_CHAT, AppPermission.EXECUTE_ANALYSIS);
            case STAFF         -> EnumSet.of(AppPermission.USE_CHAT, AppPermission.EXECUTE_ANALYSIS);
            case DEVELOPER     -> EnumSet.of(AppPermission.USE_CHAT, AppPermission.EXECUTE_ANALYSIS,
                                             AppPermission.EXECUTE_FIX_JOBS, AppPermission.EXECUTE_PLAN_JOBS);
            case ADMINISTRATOR -> EnumSet.allOf(AppPermission.class);
        };
    }
}
