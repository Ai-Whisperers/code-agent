package com.eneve.agent.model;

import java.time.Instant;
import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A named team composed of Keycloak users in typed roles.
 *
 * <p>Teams are first-class entities persisted in the {@code teams} /
 * {@code team_members} tables and can be assigned to one or more products
 * via the {@code product_teams} join table.
 */
@Schema(description = "A named team of Keycloak users with typed roles, assignable to products")
public record Team(

        @Schema(required = true, description = "Unique team identifier (slug)", example = "jules-team")
        String id,

        @Schema(required = true, description = "Human-readable team name", example = "Jules Team")
        String name,

        @Schema(description = "Optional description of the team's purpose")
        String description,

        @Schema(description = "Members of this team with their roles")
        List<TeamMemberEntry> members,

        @Schema(readOnly = true)
        Instant createdAt,

        @Schema(readOnly = true)
        Instant updatedAt
) {}
