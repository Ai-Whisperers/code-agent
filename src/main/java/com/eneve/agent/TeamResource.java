package com.eneve.agent;

import com.eneve.agent.agent.store.TeamStore;
import com.eneve.agent.agent.store.TeamStore.MemberInput;
import com.eneve.agent.model.Team;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * REST resource for managing teams and their product assignments.
 *
 * <p>All endpoints require the {@code app_admin} role.
 */
@RolesAllowed("app_admin")
@Path("/teams")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Teams", description = "Manage teams, members, and product assignments")
public class TeamResource {

    @Inject
    TeamStore teamStore;

    // ── Request DTOs ──────────────────────────────────────────────────────────

    public record UpsertTeamRequest(
            String name,
            String description
    ) {}

    public record SetMembersRequest(
            List<MemberInput> members
    ) {}

    // ── Teams CRUD ────────────────────────────────────────────────────────────

    @GET
    @Operation(operationId = "listTeams", summary = "List all teams with their members")
    public Response listTeams() {
        return Response.ok(teamStore.listAllTeams()).build();
    }

    @GET
    @Path("/{teamId}")
    @Operation(operationId = "getTeam", summary = "Get a team by ID")
    public Response getTeam(
            @Parameter(required = true) @PathParam("teamId") String teamId) {
        return teamStore.getTeam(teamId)
                .map(t -> Response.ok(t).build())
                .orElse(Response.status(404).entity(Map.of("error", "Team not found: " + teamId)).build());
    }

    @PUT
    @Path("/{teamId}")
    @Operation(operationId = "upsertTeam", summary = "Create or update a team")
    public Response upsertTeam(
            @Parameter(required = true) @PathParam("teamId") String teamId,
            UpsertTeamRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return Response.status(400).entity(Map.of("error", "name is required")).build();
        }
        Team team = new Team(teamId, request.name(), request.description(), null, null, null);
        teamStore.upsertTeam(team);
        return teamStore.getTeam(teamId)
                .map(t -> Response.ok(t).build())
                .orElse(Response.ok(Map.of("teamId", teamId)).build());
    }

    @DELETE
    @Path("/{teamId}")
    @Operation(operationId = "deleteTeam", summary = "Delete a team")
    public Response deleteTeam(
            @Parameter(required = true) @PathParam("teamId") String teamId) {
        if (teamStore.deleteTeam(teamId)) {
            return Response.ok(Map.of("deleted", teamId)).build();
        }
        return Response.status(404).entity(Map.of("error", "Team not found: " + teamId)).build();
    }

    // ── Members ───────────────────────────────────────────────────────────────

    @PUT
    @Path("/{teamId}/members")
    @Operation(operationId = "setTeamMembers", summary = "Replace the full member list for a team")
    public Response setMembers(
            @Parameter(required = true) @PathParam("teamId") String teamId,
            SetMembersRequest request) {
        if (teamStore.getTeam(teamId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Team not found: " + teamId)).build();
        }
        List<MemberInput> members = request != null ? request.members() : List.of();
        teamStore.setMembers(teamId, members);
        return teamStore.getTeam(teamId)
                .map(t -> Response.ok(t).build())
                .orElse(Response.ok(Map.of("teamId", teamId)).build());
    }

    // ── Product assignment ────────────────────────────────────────────────────

    @GET
    @Path("/by-product/{productId}")
    @Operation(operationId = "listTeamsForProduct", summary = "List teams assigned to a product")
    public Response listTeamsForProduct(
            @Parameter(required = true) @PathParam("productId") String productId) {
        return Response.ok(teamStore.listTeamsForProduct(productId)).build();
    }

    @PUT
    @Path("/{teamId}/products/{productId}")
    @Operation(operationId = "assignTeamToProduct", summary = "Assign a team to a product")
    public Response assignToProduct(
            @Parameter(required = true) @PathParam("teamId") String teamId,
            @Parameter(required = true) @PathParam("productId") String productId) {
        if (teamStore.getTeam(teamId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Team not found: " + teamId)).build();
        }
        teamStore.assignToProduct(teamId, productId);
        return Response.ok(Map.of("teamId", teamId, "productId", productId, "assigned", true)).build();
    }

    @DELETE
    @Path("/{teamId}/products/{productId}")
    @Operation(operationId = "unassignTeamFromProduct", summary = "Unassign a team from a product")
    public Response unassignFromProduct(
            @Parameter(required = true) @PathParam("teamId") String teamId,
            @Parameter(required = true) @PathParam("productId") String productId) {
        if (teamStore.unassignFromProduct(teamId, productId)) {
            return Response.ok(Map.of("teamId", teamId, "productId", productId, "assigned", false)).build();
        }
        return Response.status(404).entity(Map.of("error", "Assignment not found")).build();
    }
}
