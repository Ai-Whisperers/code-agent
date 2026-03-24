package com.eneve.agent.security;

import java.util.EnumSet;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Maps a set of raw Keycloak role names to internal {@link AppRole}s.
 *
 * <p>Mapping rules (evaluated in order):
 * <ul>
 *   <li>{@code app_admin} → {@link AppRole#ADMINISTRATOR}</li>
 *   <li>{@code app_developer} → {@link AppRole#DEVELOPER} (takes priority over app_staff)</li>
 *   <li>{@code app_staff} (only when app_developer is absent) → {@link AppRole#STAFF}</li>
 *   <li>{@link AppRole#USER} is always added as the baseline role</li>
 * </ul>
 *
 * <p>Effective permissions are the union across all assigned app roles.
 */
@ApplicationScoped
public class RoleMapper {

    public static final String KC_ADMIN     = "app_admin";
    public static final String KC_DEVELOPER = "app_developer";
    public static final String KC_STAFF     = "app_staff";
    public static final String KC_USER      = "app_user";

    public Set<AppRole> map(Set<String> kcRoles) {
        Set<AppRole> result = EnumSet.of(AppRole.USER);

        if (kcRoles.contains(KC_ADMIN)) {
            result.add(AppRole.ADMINISTRATOR);
        }

        if (kcRoles.contains(KC_DEVELOPER)) {
            result.add(AppRole.DEVELOPER);
        } else if (kcRoles.contains(KC_STAFF)) {
            // STAFF only when DEVELOPER is not present; DEVELOPER wins if both are assigned
            result.add(AppRole.STAFF);
        }

        return result;
    }
}
