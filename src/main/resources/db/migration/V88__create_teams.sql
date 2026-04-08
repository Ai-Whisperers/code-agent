-- Teams: named groups of Keycloak users with typed roles, assignable to products.
--
-- teams           — one row per named team
-- team_members    — one row per (team, keycloak user, role); a user may appear
--                   multiple times in the same team with different roles
-- product_teams   — M:N join between products and teams

CREATE TABLE teams (
    id          TEXT        PRIMARY KEY,
    name        TEXT        NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE teams IS
    'Named teams composed of Keycloak users. A team can be assigned to one or more products.';

CREATE TABLE team_members (
    id                BIGSERIAL   PRIMARY KEY,
    team_id           TEXT        NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    keycloak_user_id  TEXT        NOT NULL,
    role              TEXT        NOT NULL,
    UNIQUE (team_id, keycloak_user_id, role)
);

COMMENT ON TABLE team_members IS
    'One row per (team, Keycloak user, role). A user may hold multiple roles in the same team.';

CREATE INDEX team_members_team_idx  ON team_members (team_id);
CREATE INDEX team_members_user_idx  ON team_members (keycloak_user_id);

CREATE TABLE product_teams (
    product_id  TEXT NOT NULL,
    team_id     TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, team_id)
);

COMMENT ON TABLE product_teams IS
    'M:N join between products and teams. product_id references products.product_id.';

CREATE INDEX product_teams_product_idx ON product_teams (product_id);
CREATE INDEX product_teams_team_idx    ON product_teams (team_id);
