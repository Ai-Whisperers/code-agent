package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A single member-role assignment within a team.
 *
 * <p>{@code keycloakUserId} is the stable Keycloak {@code sub} / user ID.
 * The display fields ({@code username}, {@code email}, {@code firstName},
 * {@code lastName}) are denormalized at read time from Keycloak and are
 * never persisted to the database.
 */
@Schema(description = "A Keycloak user assigned to a team with a specific role")
public record TeamMemberEntry(

        @Schema(required = true, description = "Keycloak user ID (sub claim)",
                example = "5f8b2c1a-3e4d-0a00-1234-abcdef012345")
        String keycloakUserId,

        @Schema(required = true,
                description = "Role within the team. Valid values: productOwner, engineering, devops, operations, qa, security, supportQueue",
                example = "engineering")
        String role,

        @Schema(description = "Keycloak username", example = "arjan.de.vries")
        String username,

        @Schema(description = "Work email address", example = "arjan@example.com")
        String email,

        @Schema(description = "First name", example = "Arjan")
        String firstName,

        @Schema(description = "Last name", example = "de Vries")
        String lastName
) {}
