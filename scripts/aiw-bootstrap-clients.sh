#!/usr/bin/env bash
# =============================================================================
# aiw-bootstrap-clients.sh
# =============================================================================
# Registers all AIW client profiles with a running Code Agent instance.
# Parses the YAML profiles under docs/clients/*.yaml and POSTs them to the
# agent's repo_settings, memories, hooks, and quality_reports endpoints.
#
# Usage:
#   ./scripts/aiw-bootstrap-clients.sh                # all clients
#   ./scripts/aiw-bootstrap-clients.sh vete           # one client
#   ./scripts/aiw-bootstrap-clients.sh --dry-run      # print what would be sent
#
# Environment:
#   AGENT_BASE_URL  default: http://localhost:8080
#   AGENT_API_KEY   required (from Bitwarden: bw get item code-agent-api-key)
# =============================================================================

set -euo pipefail

AGENT_BASE_URL="${AGENT_BASE_URL:-http://localhost:8080}"
AGENT_API_KEY="${AGENT_API_KEY:-}"
DRY_RUN=false
ONLY_CLIENT=""

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --help|-h)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    -*) echo "Unknown flag: $arg" >&2; exit 1 ;;
    *)  ONLY_CLIENT="$arg" ;;
  esac
done

if [[ -z "$AGENT_API_KEY" && "$DRY_RUN" == "false" ]]; then
  echo "ERROR: AGENT_API_KEY not set. Get it from Bitwarden:" >&2
  echo "  ssh root@72.61.44.159 'BW_SESSION=\$X bw get item code-agent-api-key'" >&2
  exit 1
fi

if ! command -v yq >/dev/null 2>&1; then
  echo "ERROR: yq required. Install: sudo snap install yq  OR  brew install yq" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILES_DIR="$SCRIPT_DIR/../docs/clients"

# -----------------------------------------------------------------------------
# POST helper
# -----------------------------------------------------------------------------
api_post() {
  local path="$1"
  local body="$2"
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "---"
    echo "POST $AGENT_BASE_URL$path"
    echo "$body" | jq .
    return 0
  fi
  curl -sS -X POST "$AGENT_BASE_URL$path" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $AGENT_API_KEY" \
    --data "$body"
  echo ""
}

api_put() {
  local path="$1"
  local body="$2"
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "---"
    echo "PUT $AGENT_BASE_URL$path"
    echo "$body" | jq .
    return 0
  fi
  curl -sS -X PUT "$AGENT_BASE_URL$path" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $AGENT_API_KEY" \
    --data "$body"
  echo ""
}

# -----------------------------------------------------------------------------
# Register one client profile
# -----------------------------------------------------------------------------
register_client() {
  local yaml_file="$1"
  local client_name
  client_name=$(yq '.client' "$yaml_file")
  echo "══════════════════════════════════════════════════════════"
  echo "Registering client: $client_name"
  echo "  profile: $yaml_file"
  echo "══════════════════════════════════════════════════════════"

  local workspace slug
  workspace=$(yq '.repo.workspace' "$yaml_file")
  slug=$(yq '.repo.slug' "$yaml_file")

  # 1. Upsert repo settings
  local repo_settings
  repo_settings=$(yq -o=json '{
    "git_platform":           .repo.git_platform,
    "default_branch":         .repo.default_branch,
    "target_branch":          .repo.target_branch,
    "clone_url":              .repo.clone_url,
    "project_root":           .repo.project_root,
    "archetype":              .repo.archetype,
    "language":               .stack.language,
    "package_manager":        .stack.package_manager,
    "build_command":          .build.build,
    "test_command":           .tests.default,
    "lint_command":           .build.lint,
    "typecheck_command":      .build.typecheck,
    "format_check_command":   .build.format_check,
    "protected_paths":        .protected_paths,
    "allowed_commands":       .allowed_commands,
    "forbidden_commands":     .forbidden_commands,
    "max_files_changed":      .caps.max_files_changed,
    "max_lines_changed":      .caps.max_lines_changed,
    "max_loop_iterations":    .caps.max_loop_iterations,
    "self_review_enabled":    .caps.self_review_enabled,
    "self_review_max_iterations": .caps.self_review_max_iterations,
    "job_timeout_minutes":    .caps.job_timeout_minutes,
    "quality_report_enabled": true,
    "upgrade_enabled":        .auto_fix.enabled
  }' "$yaml_file")
  api_put "/api/repos/$workspace/$slug" "$repo_settings"

  # 2. Seed memories (learning extractor prefills)
  echo ""
  echo "  Seeding memories…"
  yq -o=json '.memories[]?' "$yaml_file" | while read -r memory; do
    local body
    body=$(jq -n --arg w "$workspace" --arg s "$slug" --argjson m "$memory" \
      '{workspace: $w, slug: $s, kind: "preference", content: $m, source: "bootstrap"}')
    api_post "/api/memories" "$body"
  done

  # 3. Auto-fix hooks
  echo ""
  echo "  Configuring auto-fix hooks…"
  local hooks_json
  hooks_json=$(yq -o=json '{
    "workspace": .repo.workspace,
    "slug":      .repo.slug,
    "enabled":   .auto_fix.enabled,
    "triggers":  .auto_fix.triggers
  }' "$yaml_file")
  api_post "/api/hooks" "$hooks_json"

  # 4. Quality report schedules
  echo ""
  echo "  Scheduling quality reports…"
  yq -o=json '.quality_reports[]?' "$yaml_file" | while read -r report; do
    local body
    body=$(jq -n --arg w "$workspace" --arg s "$slug" --argjson r "$report" \
      '$r + {workspace: $w, slug: $s}')
    api_post "/api/quality-reports/schedule" "$body"
  done

  # 5. Notification settings
  echo ""
  echo "  Configuring notifications…"
  local notif_json
  notif_json=$(yq -o=json '{
    "workspace":       .repo.workspace,
    "slug":            .repo.slug,
    "default_target":  .notifications.default_target,
    "escalation":      .notifications.escalation
  }' "$yaml_file")
  api_post "/api/repos/$workspace/$slug/notifications" "$notif_json"

  echo ""
  echo "✓ $client_name registered."
  echo ""
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------
if [[ -n "$ONLY_CLIENT" ]]; then
  profile="$PROFILES_DIR/$ONLY_CLIENT.yaml"
  if [[ ! -f "$profile" ]]; then
    echo "ERROR: no profile at $profile" >&2
    exit 1
  fi
  register_client "$profile"
else
  for profile in "$PROFILES_DIR"/*.yaml; do
    [[ -f "$profile" ]] || continue
    register_client "$profile"
  done
fi

echo "═══════════════════════════════════════════════════════════"
echo "All client profiles registered."
echo "Verify at: $AGENT_BASE_URL/api/repos"
echo "═══════════════════════════════════════════════════════════"
