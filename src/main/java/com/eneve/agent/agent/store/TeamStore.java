package com.eneve.agent.agent.store;

import com.eneve.agent.model.Team;
import com.eneve.agent.model.TeamMemberEntry;
import com.eneve.agent.settings.SettingsService;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.UserRepresentation;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL-backed store for teams, team members, and product-team assignments.
 *
 * <p>Keycloak display data (username, email, firstName, lastName) is fetched at
 * read time using the admin client credentials from {@link SettingsService},
 * mirroring the pattern in {@code AdminUserResource}.
 */
@ApplicationScoped
public class TeamStore {

    private static final Logger LOG = Logger.getLogger(TeamStore.class);

    @Inject
    AgroalDataSource dataSource;

    @Inject
    SettingsService settings;

    // ── Teams CRUD ────────────────────────────────────────────────────────────

    public List<Team> listAllTeams() {
        String sql = """
                SELECT id, name, description, created_at, updated_at
                FROM teams
                ORDER BY name
                """;
        List<Team> teams = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                teams.add(mapTeam(rs, List.of()));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list teams: %s", e.getMessage());
        }
        return enrichAll(teams);
    }

    public Optional<Team> getTeam(String teamId) {
        String sql = """
                SELECT id, name, description, created_at, updated_at
                FROM teams
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Team bare = mapTeam(rs, List.of());
                    return Optional.of(enrich(bare));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get team %s: %s", teamId, e.getMessage());
        }
        return Optional.empty();
    }

    public void upsertTeam(Team team) {
        String sql = """
                INSERT INTO teams (id, name, description, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                ON CONFLICT (id) DO UPDATE
                    SET name        = EXCLUDED.name,
                        description = EXCLUDED.description,
                        updated_at  = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, team.id());
            ps.setString(2, team.name());
            ps.setString(3, team.description());
            ps.executeUpdate();
            LOG.debugf("Upserted team %s", team.id());
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert team %s: %s", team.id(), e.getMessage());
        }
    }

    public boolean deleteTeam(String teamId) {
        String sql = "DELETE FROM teams WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teamId);
            int rows = ps.executeUpdate();
            LOG.debugf("Deleted team %s (%d rows)", teamId, rows);
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete team %s: %s", teamId, e.getMessage());
            return false;
        }
    }

    // ── Members ───────────────────────────────────────────────────────────────

    /**
     * Replaces all members of a team atomically.
     * Each entry is a pair of (keycloakUserId, role).
     */
    public void setMembers(String teamId, List<MemberInput> members) {
        String deleteSql = "DELETE FROM team_members WHERE team_id = ?";
        String insertSql = "INSERT INTO team_members (team_id, keycloak_user_id, role) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                del.setString(1, teamId);
                del.executeUpdate();
            }
            if (members != null && !members.isEmpty()) {
                try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                    for (MemberInput m : members) {
                        ins.setString(1, teamId);
                        ins.setString(2, m.keycloakUserId());
                        ins.setString(3, m.role());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
            conn.commit();
            LOG.debugf("Set %d members for team %s", members == null ? 0 : members.size(), teamId);
        } catch (SQLException e) {
            LOG.errorf("Failed to set members for team %s: %s", teamId, e.getMessage());
        }
    }

    /** Removes all team_members rows for a given Keycloak user across all teams. */
    public int removeUserFromAllTeams(String keycloakUserId) {
        String sql = "DELETE FROM team_members WHERE keycloak_user_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, keycloakUserId);
            int rows = ps.executeUpdate();
            LOG.infof("Removed user %s from all teams (%d rows)", keycloakUserId, rows);
            return rows;
        } catch (SQLException e) {
            LOG.errorf("Failed to remove user %s from all teams: %s", keycloakUserId, e.getMessage());
            return 0;
        }
    }

    // ── Product assignment ────────────────────────────────────────────────────

    public void assignToProduct(String teamId, String productId) {
        String sql = """
                INSERT INTO product_teams (product_id, team_id)
                VALUES (?, ?)
                ON CONFLICT (product_id, team_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            ps.setString(2, teamId);
            ps.executeUpdate();
            LOG.debugf("Assigned team %s to product %s", teamId, productId);
        } catch (SQLException e) {
            LOG.errorf("Failed to assign team %s to product %s: %s", teamId, productId, e.getMessage());
        }
    }

    public boolean unassignFromProduct(String teamId, String productId) {
        String sql = "DELETE FROM product_teams WHERE product_id = ? AND team_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            ps.setString(2, teamId);
            int rows = ps.executeUpdate();
            LOG.debugf("Unassigned team %s from product %s (%d rows)", teamId, productId, rows);
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to unassign team %s from product %s: %s", teamId, productId, e.getMessage());
            return false;
        }
    }

    public List<Team> listTeamsForProduct(String productId) {
        String sql = """
                SELECT t.id, t.name, t.description, t.created_at, t.updated_at
                FROM teams t
                JOIN product_teams pt ON pt.team_id = t.id
                WHERE pt.product_id = ?
                ORDER BY t.name
                """;
        List<Team> teams = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    teams.add(mapTeam(rs, List.of()));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list teams for product %s: %s", productId, e.getMessage());
        }
        return enrichAll(teams);
    }

    // ── Keycloak enrichment ───────────────────────────────────────────────────

    /**
     * Loads bare (unenriched) members for a team from the DB, then enriches
     * them with display data from Keycloak.
     */
    private Team enrich(Team bare) {
        List<TeamMemberEntry> members = loadMembers(bare.id());
        members = enrichMembers(members);
        return new Team(bare.id(), bare.name(), bare.description(), members, bare.createdAt(), bare.updatedAt());
    }

    private List<Team> enrichAll(List<Team> teams) {
        if (teams.isEmpty()) return teams;
        // Load all members for all teams in one pass, then enrich
        Map<String, List<TeamMemberEntry>> membersByTeam = loadAllMembers(
                teams.stream().map(Team::id).toList());
        List<TeamMemberEntry> allMembers = membersByTeam.values().stream()
                .flatMap(Collection::stream).toList();
        Map<String, TeamMemberEntry> enriched = buildEnrichedMap(allMembers);

        List<Team> result = new ArrayList<>();
        for (Team t : teams) {
            List<TeamMemberEntry> members = membersByTeam.getOrDefault(t.id(), List.of())
                    .stream()
                    .map(m -> enriched.getOrDefault(m.keycloakUserId() + ":" + m.role(), m))
                    .toList();
            result.add(new Team(t.id(), t.name(), t.description(), members, t.createdAt(), t.updatedAt()));
        }
        return result;
    }

    private List<TeamMemberEntry> loadMembers(String teamId) {
        String sql = "SELECT keycloak_user_id, role FROM team_members WHERE team_id = ? ORDER BY role";
        List<TeamMemberEntry> members = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.add(new TeamMemberEntry(rs.getString("keycloak_user_id"), rs.getString("role"),
                            null, null, null, null));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to load members for team %s: %s", teamId, e.getMessage());
        }
        return members;
    }

    private Map<String, List<TeamMemberEntry>> loadAllMembers(List<String> teamIds) {
        if (teamIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(teamIds.size(), "?"));
        String sql = "SELECT team_id, keycloak_user_id, role FROM team_members WHERE team_id IN (" + placeholders + ") ORDER BY team_id, role";
        Map<String, List<TeamMemberEntry>> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < teamIds.size(); i++) {
                ps.setString(i + 1, teamIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tid = rs.getString("team_id");
                    result.computeIfAbsent(tid, k -> new ArrayList<>())
                            .add(new TeamMemberEntry(rs.getString("keycloak_user_id"), rs.getString("role"),
                                    null, null, null, null));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to load members for teams: %s", e.getMessage());
        }
        return result;
    }

    private List<TeamMemberEntry> enrichMembers(List<TeamMemberEntry> members) {
        if (members.isEmpty()) return members;
        Map<String, TeamMemberEntry> enrichedMap = buildEnrichedMap(members);
        return members.stream()
                .map(m -> enrichedMap.getOrDefault(m.keycloakUserId() + ":" + m.role(), m))
                .toList();
    }

    /**
     * Fetches display data from Keycloak for a batch of members and returns a
     * map keyed by {@code keycloakUserId:role}.
     */
    private Map<String, TeamMemberEntry> buildEnrichedMap(List<TeamMemberEntry> members) {
        if (members.isEmpty()) return Map.of();
        try (Keycloak kc = buildKeycloakClient()) {
            var realm = kc.realm(targetRealm());
            Map<String, TeamMemberEntry> result = new HashMap<>();
            // Collect unique user IDs to avoid redundant Keycloak calls
            Set<String> userIds = new HashSet<>();
            for (TeamMemberEntry m : members) userIds.add(m.keycloakUserId());

            Map<String, UserRepresentation> kcUsers = new HashMap<>();
            for (String uid : userIds) {
                try {
                    UserRepresentation u = realm.users().get(uid).toRepresentation();
                    if (u != null) kcUsers.put(uid, u);
                } catch (Exception e) {
                    LOG.warnf("Could not fetch Keycloak user %s: %s", uid, e.getMessage());
                }
            }
            for (TeamMemberEntry m : members) {
                UserRepresentation u = kcUsers.get(m.keycloakUserId());
                String key = m.keycloakUserId() + ":" + m.role();
                if (u != null) {
                    result.put(key, new TeamMemberEntry(
                            m.keycloakUserId(), m.role(),
                            u.getUsername(), u.getEmail(),
                            u.getFirstName(), u.getLastName()));
                } else {
                    result.put(key, m);
                }
            }
            return result;
        } catch (IllegalStateException e) {
            LOG.warnf("Keycloak admin not configured, skipping enrichment: %s", e.getMessage());
            return Map.of();
        } catch (Exception e) {
            LOG.warnf("Keycloak enrichment failed: %s", e.getMessage());
            return Map.of();
        }
    }

    private Keycloak buildKeycloakClient() {
        String serverUrl    = settings.get("keycloak.admin.server-url");
        String realm        = settings.get("keycloak.admin.realm", "master");
        String clientId     = settings.get("keycloak.admin.client-id");
        String clientSecret = settings.getSecret("keycloak.admin.client-secret");

        if (serverUrl == null || serverUrl.isBlank()
                || clientId == null || clientId.isBlank()
                || clientSecret == null || clientSecret.isBlank()) {
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

    // ── Row mapper ────────────────────────────────────────────────────────────

    private static Team mapTeam(ResultSet rs, List<TeamMemberEntry> members) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new Team(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                members,
                createdAt != null ? createdAt.toInstant() : null,
                updatedAt != null ? updatedAt.toInstant() : null
        );
    }

    // ── Input DTO ─────────────────────────────────────────────────────────────

    /** Input-only DTO for setting team members — not persisted as a model. */
    public record MemberInput(String keycloakUserId, String role) {}
}
