# Next Steps — AIW Code Agent

> Roadmap for taking the code-agent from "running on the VPS with a verified
> chat endpoint" to "actually doing useful work for Solstein and Vete."
>
> Last updated: April 2026. Status snapshot: 6 PRs landed (12-17 merged, #18
> open), end-to-end `/api/chat` → LiteLLM → Groq → streamed response verified
> on the live deployment at `72.61.44.159:8090`.

## Table of contents

1. [Decisions needed from Ivan](#decisions-needed-from-ivan)
2. [Phase 0 — Merge #18 (5 min)](#phase-0--merge-18)
3. [Phase 1 — First real job against a safe repo (2–3 h)](#phase-1--first-real-job-against-a-safe-repo)
4. [Phase 2 — Expose securely via Traefik (1–2 h)](#phase-2--expose-securely-via-traefik)
5. [Phase 3 — Proper auth via Supabase (1–2 days)](#phase-3--proper-auth-via-supabase)
6. [Phase 4 — Onboard Vete (2–4 h)](#phase-4--onboard-vete)
7. [Phase 5 — Onboard Solstein (2–4 h)](#phase-5--onboard-solstein)
8. [Phase 6 — Production hardening (1 week)](#phase-6--production-hardening)
9. [Phase 7 — Optional cleanups](#phase-7--optional-cleanups)
10. [Risk register](#risk-register)
11. [Dependencies graph](#dependencies-graph)

---

## Decisions needed from Ivan

These are the choices I can't make unilaterally. Each phase depends on at least one answer.

| # | Decision | Why it matters | Options | My recommendation |
|---|---|---|---|---|
| D1 | **GitHub PAT scope** — fine-grained or classic, which repos | Classic PATs are simpler but broad. Fine-grained can be locked to Vete + Solstein specifically. | (a) classic with `repo` scope, (b) fine-grained per-repo, (c) one GitHub App | **(b)** — one fine-grained PAT per target repo. More work to rotate but gives a clear blast radius. |
| D2 | **Agent GitHub identity** — dedicated user or reuse personal | Dedicated user shows up as the author on every agent PR. | (a) new `aiw-code-agent` GitHub user, (b) use `IvanWeissVanDerPol` | **(a)** — clear attribution on PRs, easier to audit, easier to revoke. Takes 2 min to create. |
| D3 | **Network exposure** — Tailscale-only or public | The dev auth bypass is dangerous on the public internet. | (a) Tailscale-only until Supabase Auth lands, (b) public with bypass, (c) public without bypass (unusable) | **(a)** — run on Tailscale until Phase 3 is done. Takes 5 min to wire. |
| D4 | **Supabase project** — new or reuse Vete's | Vete already has Supabase. We could share the project or give the agent its own. | (a) new project `code-agent-prod`, (b) shared with Vete in a new schema | **(a)** — clear separation, no coupling to Vete's migrations. Free tier is enough. |
| D5 | **First real task** — review, fix, or plan | Smoke test scope. | (a) review-only (read-only, lowest risk), (b) plan-only (generates plan, doesn't execute), (c) tiny fix (modifies one file) | **(a)** for the very first run, **(b)** for the second, **(c)** only after both succeed. |
| D6 | **Budget cap per job** | Right now there's no spend ceiling. A runaway agent could burn $100. | (a) $0.50/job hard cap via LiteLLM key, (b) $2/job, (c) unlimited for dev | **(a)** — safe default, raise later when we have confidence. |
| D7 | **Notification channel for alerts** | Where does agent output land? | (a) Telegram personal, (b) Telegram group, (c) Discord, (d) email | **(a)** — Telegram to Ivan for Phase 1–3, then per-client channels in Phase 4–5. |

**Action:** Answer these seven in any form (even just "all defaults") before Phase 1 starts. I'll execute the rest of this plan without further confirmation unless the answers change it.

---

## Phase 0 — Merge #18

**Goal:** Clean main branch before starting Phase 1.

**Steps:**

```bash
gh pr merge 18 -R Ai-Whisperers/code-agent --squash --delete-branch
```

**Done when:** `gh pr list -R Ai-Whisperers/code-agent` shows 0 open PRs.

**Effort:** 30 seconds.

**Risk:** None.

---

## Phase 1 — First real job against a safe repo

**Goal:** Prove the agent can clone a repo, plan a tiny change, and (optionally) open a PR — all without touching Vete or Solstein.

**Target repo:** `Ai-Whisperers/photos-to-kml` (public, Python, 2 MB, no beta clients, cheap to break).

**Dependencies:** D1, D2, D3, D5, D6, D7 answered.

### Steps

**1.1 Provision the GitHub identity (5 min)**

```bash
# Assuming D2 = (a) new aiw-code-agent user:
# Create the user manually on github.com (can't be automated via API
# without paying for GitHub Enterprise). Use email: code-agent@aiwhisperers.io.
# Enable 2FA. Add to the Ai-Whisperers org with write access to photos-to-kml.

# Then the PAT (D1 = (b) fine-grained):
gh auth switch  # log in as aiw-code-agent
gh auth refresh -h github.com -s 'repo,workflow'
# Fine-grained PAT can only be created via the web UI. Create one scoped to
# Ai-Whisperers/photos-to-kml with: Contents (RW), Pull requests (RW),
# Issues (RW), Metadata (R). 90-day expiry.
```

**1.2 Stash the PAT in Bitwarden (2 min)**

```bash
ssh root@72.61.44.159
BW_SESSION=$(bw unlock --raw)  # enter master password
bw create item <(jq -n --arg token "<PAT>" '{
  type: 1,
  name: "github-aiw-code-agent-pat-photos-to-kml",
  login: {
    username: "aiw-code-agent",
    password: $token
  },
  notes: "Fine-grained PAT scoped to Ai-Whisperers/photos-to-kml. Created Phase 1 of the code-agent rollout. 90-day expiry."
}')
```

Also create these items while the vault is unlocked:

| Item name | Value |
|---|---|
| `github-aiw-code-agent-pat-photos-to-kml` | (the PAT) |
| `code-agent-api-key` | `openssl rand -hex 32` (for when we disable dev bypass) |
| `github-webhook-secret` | `openssl rand -hex 32` |
| `aiw-telegram-chat-id` | the target chat ID from D7 |

**1.3 Network isolation via Tailscale (5 min)**

Per D3 = (a), we don't want port 8090 exposed to the public internet:

```bash
ssh root@72.61.44.159
# Rebind the published port from 0.0.0.0 to the VPS Tailscale IP
# Edit /opt/aiw-code-agent/docker-compose.aiw.yml:
#   ports:
#     - "100.x.y.z:8090:8080"    # was "8090:8080"
# where 100.x.y.z is the VPS's Tailscale address.
docker compose -f /opt/aiw-code-agent/docker-compose.aiw.yml up -d --force-recreate app
```

Verify:

```bash
# From a non-Tailscale machine: should fail
curl --max-time 3 http://72.61.44.159:8090/q/health  # expected: timeout or refused

# From the Tailscale tailnet: should work
tailscale ssh root@agentzero
curl http://localhost:8090/q/health  # expected: UP
```

**1.4 Update .env on the VPS with real credentials (3 min)**

```bash
ssh root@72.61.44.159
cd /opt/aiw-code-agent

# Pull the PAT from Bitwarden
GITHUB_TOKEN=$(BW_SESSION=$X bw get password github-aiw-code-agent-pat-photos-to-kml)

# Patch .env
sed -i "s|^GITHUB_TOKEN=.*|GITHUB_TOKEN=$GITHUB_TOKEN|" .env
sed -i "s|^GIT_PLATFORM=.*|GIT_PLATFORM=github|" .env

docker compose -f docker-compose.aiw.yml up -d --force-recreate app
```

**1.5 Register `photos-to-kml` in repo_settings via the agent API (2 min)**

```bash
# From inside the Tailscale network:
AGENT=http://100.x.y.z:8090

curl -X PUT "$AGENT/api/repos/Ai-Whisperers/photos-to-kml" \
  -H "Content-Type: application/json" \
  -d '{
    "git_platform": "github",
    "default_branch": "main",
    "clone_url": "https://github.com/Ai-Whisperers/photos-to-kml.git",
    "language": "python",
    "archetype": "python",
    "max_files_changed": 3,
    "max_lines_changed": 100,
    "job_timeout_minutes": 15,
    "self_review_enabled": true,
    "quality_report_enabled": false,
    "upgrade_enabled": false
  }'

curl "$AGENT/api/repos/Ai-Whisperers/photos-to-kml"  # verify
```

**1.6 Submit a review-only job (per D5 = (a)) (1 min)**

```bash
curl -X POST "$AGENT/api/review-pr" \
  -H "Content-Type: application/json" \
  -d '{
    "repoUrl": "https://github.com/Ai-Whisperers/photos-to-kml",
    "prNumber": null,
    "branch": "main",
    "jiraKey": null
  }'
```

(If there's no open PR, the agent should gracefully no-op. Adjust the endpoint
based on the real OpenAPI once the agent is running.)

Or even safer, submit a `/plans` request:

```bash
curl -X POST "$AGENT/api/plans" \
  -H "Content-Type: application/json" \
  -d '{
    "repoUrl": "https://github.com/Ai-Whisperers/photos-to-kml",
    "targetBranch": "main",
    "specification": "Review the README and suggest 3 small improvements. Do not modify any files — just output a plan."
  }'
```

**1.7 Watch the logs (1 min)**

```bash
ssh root@72.61.44.159
docker logs -f aiw-code-agent | grep -iE "clone|plan|claude|error"
```

Expected in the logs:
- `Cloning Ai-Whisperers/photos-to-kml` (GitWorkspaceHelper)
- `Creating shared AnthropicClient routed through gateway: http://host.docker.internal:4000`
- `Agent loop iteration 1/150`
- `Usage: input=NNN output=NNN cache_creation=0 cache_read=0` (cache should be 0 because we disabled it)
- `Plan generated: <plan id>` (eventually)

### Success criteria for Phase 1

- [ ] GitHub PAT created and stashed in bw
- [ ] Port 8090 not reachable from the public internet
- [ ] Port 8090 reachable via Tailscale
- [ ] `repo_settings` row created for photos-to-kml
- [ ] Agent successfully clones the repo (workspace dir exists under `/workspace/`)
- [ ] At least one LLM call visible in `ai_call_records` DB table
- [ ] Plan visible in `execution_plans` DB table
- [ ] Telegram notification received (once Hermes gateway is deployed — until then, just log inspection)
- [ ] Total spend for the job under $0.05 (D6 cap not hit)

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Agent can't clone (auth format wrong) | medium | Test the PAT manually first: `git clone https://aiw-code-agent:$PAT@github.com/Ai-Whisperers/photos-to-kml.git /tmp/test` |
| LiteLLM strips `tools` along with `cache_control` | low | The tool schemas are standard OpenAI format, so no — but verify by watching the AI call records |
| Infinite planning loop consumes budget | medium | D6 caps it, but also the `run-fix.max-loop-iterations=150` default protects us |
| GitWorkspaceHelper expects `git` CLI in PATH | low | The Dockerfile installs git. Already verified. |
| Quarkus file upload size limit rejects the clone | low | Clone is filesystem, not HTTP upload. N/A. |

---

## Phase 2 — Expose securely via Traefik

**Goal:** Give the agent a real HTTPS URL so GitHub can POST webhooks to it, without turning off the dev auth bypass yet.

**Dependencies:** Phase 1 done (we know the agent works) + D3 resolved.

### Steps

**2.1 Add Traefik labels to docker-compose.aiw.yml (10 min)**

```yaml
services:
  app:
    # ... existing config ...
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.aiw-code-agent.rule=Host(`code-agent.aiwhisperers.io`)"
      - "traefik.http.routers.aiw-code-agent.entrypoints=websecure"
      - "traefik.http.routers.aiw-code-agent.tls.certresolver=letsencrypt"
      - "traefik.http.services.aiw-code-agent.loadbalancer.server.port=8080"
      # Only the webhook endpoints are public — everything else still needs the dev bypass
      # (until Phase 3). Use a path-based middleware to enforce this.
      - "traefik.http.routers.aiw-code-agent.middlewares=aiw-webhook-only"
      - "traefik.http.middlewares.aiw-webhook-only.plugin.rewrite.regex=^/(api/webhooks/.*)$"
    networks:
      - aiw-code-agent-net
      - traefik-net  # shared with the existing Traefik container
```

Actually the "webhook-only" path gating is tricky with a single router. Cleaner approach:

**2.2 Split into two Traefik routers (15 min)**

```yaml
labels:
  - "traefik.enable=true"

  # PUBLIC router: only /api/webhooks/** — GitHub needs to POST here
  - "traefik.http.routers.aiw-webhooks.rule=Host(`code-agent.aiwhisperers.io`) && PathPrefix(`/api/webhooks/`)"
  - "traefik.http.routers.aiw-webhooks.entrypoints=websecure"
  - "traefik.http.routers.aiw-webhooks.tls.certresolver=letsencrypt"
  - "traefik.http.routers.aiw-webhooks.service=aiw-code-agent-svc"

  # INTERNAL router: everything else, Tailscale only (via traefik entrypoint bound to tailnet IP)
  - "traefik.http.routers.aiw-internal.rule=Host(`code-agent.tail-xxxxxx.ts.net`)"
  - "traefik.http.routers.aiw-internal.entrypoints=tailscale"
  - "traefik.http.routers.aiw-internal.service=aiw-code-agent-svc"

  - "traefik.http.services.aiw-code-agent-svc.loadbalancer.server.port=8080"
```

**2.3 DNS setup (5 min)**

```bash
# Point code-agent.aiwhisperers.io at the VPS public IP.
# Whichever DNS provider you use — Cloudflare, route53, etc.
dig code-agent.aiwhisperers.io  # verify
```

**2.4 Bring it up (2 min)**

```bash
ssh root@72.61.44.159
cd /opt/aiw-code-agent
docker compose -f docker-compose.aiw.yml up -d
docker logs traefik 2>&1 | grep -i aiw  # confirm Traefik picked it up
```

**2.5 Smoke test (2 min)**

```bash
# Public webhook endpoint — should respond (or 401 with signature error, which is fine)
curl https://code-agent.aiwhisperers.io/api/webhooks/github/pull-request \
  -X POST -H "X-GitHub-Event: ping" -d '{}'

# Public non-webhook — should 404 or not route
curl https://code-agent.aiwhisperers.io/api/chat \
  -X POST -H "Content-Type: application/json" -d '{"message":"hi"}'
# Expected: 404 from Traefik, NOT reaching the app
```

### Success criteria for Phase 2

- [ ] `https://code-agent.aiwhisperers.io/api/webhooks/github/pull-request` reachable from the internet (returns 400 with signature mismatch, but reaches the app)
- [ ] `https://code-agent.aiwhisperers.io/api/chat` returns 404 from Traefik (not reachable)
- [ ] HTTPS cert valid (Let's Encrypt via Traefik's auto-resolver)
- [ ] Internal routes still reachable via Tailscale

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Traefik can't reach the app container (wrong network) | medium | Ensure both Traefik and app are on the shared `traefik-net` |
| Let's Encrypt rate limit on aiwhisperers.io | low | We're well under the limit |
| Path regex doesn't match exactly what GitHub sends | medium | GitHub sends to `/api/webhooks/github/pull-request` — match with `PathPrefix` not exact |
| Tailscale entrypoint not configured in Traefik | high | Need to add a new entrypoint in Traefik's static config bound to the Tailscale IP |

---

## Phase 3 — Proper auth via Supabase

**Goal:** Remove `dev.auth.bypass` and replace Keycloak OIDC with Supabase JWT validation. This closes the "anyone with Tailscale access is an admin" hole.

This is **issue #4** in the customization plan. It's the biggest remaining code change. 1–2 days of focused work.

**Dependencies:** Phase 2 done + D4 resolved.

### Steps

**3.1 Create Supabase project (10 min)**

Per D4 = (a): new project `code-agent-prod` under AIW's Supabase org.

- Create via dashboard
- Create an `admin@aiwhisperers.io` user
- Create an `aiw-code-agent@aiwhisperers.io` service user
- Grab the JWT secret and project URL

**3.2 Java-side changes (4–6 h)**

- Replace `quarkus-oidc` with a custom `HttpAuthenticationMechanism` that validates Supabase JWTs against the project's JWKS endpoint.
- Map Supabase JWT custom claims (`role`, `tenant_id`, etc.) to the agent's `AppRole` enum.
- Drop `quarkus.oidc.*` config from `application.properties`.
- Update `OpenApiSecurityFilter` to publish the Supabase auth scheme.
- Update the FE (`code-agent-ui`) to use `@supabase/auth-helpers-react` instead of `keycloak-js`.

**3.3 Migration test (1 h)**

- Set `DEV_AUTH_BYPASS=false` in `.env`
- Set `SUPABASE_URL` and `SUPABASE_JWT_SECRET`
- Get a real Supabase session token
- `curl -H "Authorization: Bearer <token>" https://.../api/chat` → should pass
- `curl https://.../api/chat` → should 401

**3.4 Merge and deploy (30 min)**

Normal PR flow. Open a PR like `feat(auth): Supabase JWT replaces Keycloak OIDC (closes #4)`.

### Success criteria for Phase 3

- [ ] `DEV_AUTH_BYPASS` can be removed from `.env`
- [ ] Real Supabase-authenticated requests succeed
- [ ] Unauthenticated requests 401
- [ ] FE login works through Supabase
- [ ] Issue #4 closed

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Quarkus OIDC can actually be reconfigured to point at Supabase without code changes | medium | Try this first — set `quarkus.oidc.auth-server-url=https://<project>.supabase.co/auth/v1` and see if it just works. Saves the whole 4–6 h custom mechanism. |
| Supabase JWT format drift | low | Supabase has stable JWT format, but verify against their docs at the time of work |
| FE auth refactor cascades into 1088-line `Chat.tsx` | medium | Scoped to just the auth hook and route guards. If it cascades, defer the FE to a follow-up. |

---

## Phase 4 — Onboard Vete

**Goal:** The agent serves `Ai-Whisperers/Vete` end-to-end. It can be triggered by a GitHub label and either reviews a PR, generates a plan, or opens a fix PR.

**Dependencies:** Phases 1, 2, 3 done.

### Steps

**4.1 Create a dedicated PAT for Vete (5 min)**

Same pattern as Phase 1.5 but scoped to `Ai-Whisperers/Vete` only.

Stash as `github-aiw-code-agent-pat-vete` in bw.

**4.2 Run the bootstrap script (2 min)**

```bash
cd /opt/aiw-code-agent
AGENT_API_KEY=$(bw get password code-agent-api-key) \
  ./scripts/aiw-bootstrap-clients.sh vete
```

This POSTs the full `docs/clients/vete.yaml` to the agent — seeds `repo_settings`, `memories` (reviewer preferences), `hooks` (auto-fix triggers), `quality_reports` (schedules), `notifications` (telegram target).

**4.3 Register the GitHub webhook (2 min)**

```bash
gh api repos/Ai-Whisperers/Vete/hooks \
  --method POST \
  -f name=web \
  -F active=true \
  -f config[url]=https://code-agent.aiwhisperers.io/api/webhooks/github/pull-request \
  -f config[content_type]=json \
  -f config[secret]=$(bw get password github-webhook-secret) \
  -f events[]=pull_request \
  -f events[]=pull_request_review \
  -f events[]=issue_comment \
  -f events[]=issues
```

**4.4 Initial test: review-only on a real PR (10 min)**

- Find a small-but-meaningful open PR on Vete
- Apply the `aiw-agent` label (added by the bootstrap)
- Agent should receive the webhook, clone the repo, run `npm run lint && npm run typecheck && npm run test:unit`, post an inline review comment
- Watch the logs: expected to trigger `/fix-pr` or `/review-pr` resource based on the label event

**4.5 Exercise the tenancy invariants (30 min)**

Create a **dummy feature branch** on Vete that deliberately violates invariants. The agent should REJECT these PRs:

```typescript
// web/app/api/pets/dummy/route.ts
export async function GET() {
  const supabase = await createClient();
  // MISSING tenant_id filter — should trigger invariant check
  const { data } = await supabase.from('pets').select('*');
  return NextResponse.json(data);
}
```

Expected: agent review comment flags the missing `tenant_id`, refuses to auto-fix, marks as P0.

Repeat with:
- Hardcoded Tailwind color (`bg-blue-500`)
- Silent catch block
- Raw SQL in a component
- Missing auth check in an API route

For each, verify the agent caught it. If any slip through, patch `docs/clients/vete.yaml` invariants and re-bootstrap.

**4.6 Exercise the feature-scoped test dispatcher (15 min)**

- Create a PR that only touches `web/app/[clinic]/pets/**`
- Expected: agent runs `npm run test:feature:pets` (not `test:all`)
- Log line: `Using feature test suite: pets`
- Verify runtime is <90s, not the full suite's 15+ min

**4.7 First real auto-fix (30 min)**

Pick a tiny issue on Vete — something like "update stale dep in package.json" — and let the agent open the PR. Human review before merge.

### Success criteria for Phase 4

- [ ] Vete seeded in `repo_settings`, `memories` (12 reviewer prefs), `hooks`, `quality_reports`, `notifications`
- [ ] GitHub webhook registered, HMAC verified
- [ ] Agent clones Vete, detects `nextjs` archetype in `web/` subdir (monorepo support from #16)
- [ ] Agent runs `npm run lint` with the 800-warning baseline
- [ ] Agent runs a feature-scoped test suite correctly
- [ ] Invariant checks catch all 5 deliberate violations
- [ ] Agent opens a real PR that the team can review + merge
- [ ] Telegram alert arrives for the PR

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Agent tries to run `npm run build` (8 GB RAM) and OOMs | high | The profile says don't run build speculatively. Verify `BuildValidator.detectTestCommand` doesn't include build. If it does, add a flag. |
| ESLint warnings above 800 baseline fail the lint step | medium | The profile uses `npm run lint` not `:strict`. Verify. |
| Agent adds new eslint warnings and fails its own self-review | low | Self-review step catches this (it's exactly what it's designed for) |
| Playwright e2e tests need a running dev server | high | For v1, exclude playwright from the agent's test command. Only run vitest. Wire playwright in Phase 6. |
| Tenancy invariant regex has false positives | medium | Test on 10 random existing Vete files first. Tune before enabling P0 rejection. |
| Agent accidentally modifies `.content_data` or numbered migrations | low | Protected paths in the profile prevent this. Verify with a deliberate test. |

---

## Phase 5 — Onboard Solstein

**Goal:** Same as Phase 4, but for Solstein. Tighter caps, stricter rules, Autoresearch Protocol commit messages.

**Dependencies:** Phase 4 done (prove the pattern first).

### Steps mirror Phase 4 but:

- Use `docs/clients/solstein.yaml` instead of `vete.yaml`
- PAT scoped to `Ai-Whisperers/solstein` with target branch `develop` (not master)
- First real task: the `chore: remove committed SQLite fixtures` task flagged in the profile (the `test_integration.db` / `test_perf.sqlite3` issue). Low-risk, high-value cleanup.
- **Invariant to verify:** agent refuses to touch `src/solstein/research/graph/**` (frozen region).
- **Invariant to verify:** every agent commit message includes the `Metrics:` footer with before/after numbers (Autoresearch Protocol).
- Test the ruff delta gate: deliberately add a new `B008` warning in a PR — agent should reject.

### Additional Solstein-specific checks

- [ ] Agent respects `PYTHONPATH=src` env var when running pytest
- [ ] Agent uses `uv sync --all-extras` not `pip install`
- [ ] Agent runs `pytest -m "unit and not slow"` by default (fast subset)
- [ ] Agent's commit messages parse cleanly through `git log --format=%B` and contain the `Metrics:` block
- [ ] Agent picks tasks from `planning/QUEUE.md` top-to-bottom (or from Linear once #3 lands)

---

## Phase 6 — Production hardening

**Goal:** Make the deployment robust enough to leave running unattended.

**Dependencies:** Phase 4 or 5 in active use.

### 6.1 Budget caps (30 min)

Configure LiteLLM per-key spend limits:

```bash
curl -X POST http://72.61.44.159:4000/key/update \
  -H "Authorization: Bearer $MASTER_KEY" \
  -d '{
    "key": "sk-hermes-litellm-sunstein-2026",
    "max_budget": 50.0,
    "budget_duration": "1mo"
  }'
```

Also per-job cap in the agent via a new setting:

```properties
# Abort the job if accumulated cost exceeds this during the tool-use loop
run-fix.max-cost-usd=${RUN_FIX_MAX_COST:1.00}
```

New code in `ClaudeToolUseLoop.doRun`: sum token costs from each AI call, abort if over cap.

### 6.2 Observability (2–4 h)

- Hook Langfuse into `ClaudeToolUseLoop` (Solstein already uses it — shared infra)
- Add OpenTelemetry traces (Solstein uses OTel — shared endpoint)
- Structured logs → Loki (if we have one) or stdout-to-journald
- Prometheus metrics via `/q/metrics` (Quarkus has this built-in)
- Grafana dashboard:
  - Jobs per day per status
  - Average job duration per archetype
  - Cost per job
  - LLM call count per model alias
  - Queue depth

### 6.3 Postgres backups (30 min)

The agent DB contains job history, memories, plans — losing it means losing everything the agent has learned. Daily pg_dump to a Backblaze bucket or similar.

```bash
# Cron on the VPS
0 3 * * * docker exec aiw-code-agent-db pg_dump -U aiw_code_agent aiw_code_agent | \
  gzip | aws s3 cp - s3://aiw-backups/code-agent/$(date +\%Y\%m\%d).sql.gz
```

### 6.4 Rate limiting (1 h)

Add `quarkus-smallrye-fault-tolerance` or use the existing `JobQueue.maxConcurrentJobs=53` setting.
Reduce to 20 for a single-user rollout, raise after load testing.

### 6.5 Alerting (1 h)

- Container health check fails → Telegram alert
- 3 consecutive job failures → Telegram alert
- LLM spend exceeds 80% of monthly budget → Telegram alert
- LiteLLM returning 5xx → Telegram alert

Use the Hermes gateway (once deployed) or a direct Telegram Bot webhook.

### 6.6 Secret rotation policy (document only)

- GitHub PATs: 90-day expiry, rotate via `scripts/aiw-rotate-github-pat.sh`
- Supabase JWT secret: only rotate if compromised
- LiteLLM master key: 6-month rotation
- DB encryption key (`SETTINGS_ENCRYPTION_KEY`): rotate yearly, re-encrypt all `agent_settings` rows

### 6.7 CI for the code-agent itself (1–2 h)

The code-agent is a repo like any other — it should have its own CI pipeline:

- GitHub Actions: `mvn test` on every PR
- Dockerfile build check
- `docker compose -f docker-compose.aiw.yml config` validation
- Trivy scan on the built image

---

## Phase 7 — Optional cleanups

None of these block anything. All are pure code quality / maintenance wins from the original customization plan.

| Issue | Title | Effort | Value |
|---|---|---|---|
| #11 | Rebrand `com.eneve.agent` → `io.aiwhisperers.codeagent` | 1 day | Low — cosmetic |
| #7 | Drop Bitbucket + ADO adapters | 4 h | Medium — ~3000 LOC purge |
| #8 | Drop Aikido → GitHub code scanning | 6 h | Medium — unblocks SOC II screen for AIW clients |
| #9 | Remove AWS SDK deps | 2 h | Low — saves ~30 MB image size |
| #3 | Linear adapter replaces JIRA | 2–3 days | High — our team uses Linear, not JIRA |
| #5 | Supabase Storage replaces S3 | 4 h | Medium |
| #6 | Voyage / Ollama embeddings replace Bedrock | 1 day | Medium |
| FE #3 | i18n Spanish + English | 1 day | High for LatAm clients |
| FE #4 | Refactor `Chat.tsx` (1088 lines) | 1 day | Medium — tech debt |

**Suggested priority order:** #3 (Linear) → #8 (Aikido→GH) → #7 (drop BB/ADO) → #11 (rebrand) → everything else.

**Do NOT do until needed:** #5, #6, #9 — they're infra simplifications, not user value.

---

## Risk register

Cross-phase risks worth keeping in mind.

| Risk | Phase | Severity | Mitigation |
|---|---|---|---|
| Agent opens a bad PR on Vete that breaks beta clients (Terrapet, CavillPet) | 4 | P0 | Start with review-only, manual merge gate, tenancy invariants enforced |
| Runaway LLM spend | 1+ | P0 | LiteLLM budget cap + per-job cost abort (Phase 6.1). Default $0.50/job until confidence. |
| Dev auth bypass accidentally left on in production | 2, 3 | P0 | Traefik only routes `/api/webhooks/*` to the public internet. Also: after Phase 3, `DEV_AUTH_BYPASS=false` becomes the default and is audited in startup logs. |
| Secret leak via agent commit message | 4+ | P1 | Agent already has `SecretScanner` on the FE side (pattern from the study notes). Need to port the same scan to the BE `GitWorkspaceHelper.commit`. |
| Agent gets into a loop of creating → reviewing → fixing its own PRs | 4 | P2 | `review.webhook.skip-authors=aiw-code-agent` in `application.properties` prevents this (upstream already has this). Verify it's set. |
| Flyway migration on DB upgrade fails | 6 | P1 | Take DB backup before every deploy (6.3) |
| LiteLLM config drift (someone edits it and breaks our drop_params) | ongoing | P1 | `/opt/litellm/README-AIW.md` documents the patch. `apply-aiw-drop-params.py` is idempotent and can be re-run. |
| GitHub rate limits the agent's API calls | 4, 5 | P2 | Use the `aiw-code-agent` user's own quota. Monitor `X-RateLimit-Remaining` headers. Back off at 20%. |

---

## Dependencies graph

```
                   ┌──────────────┐
                   │ Decisions    │
                   │ D1..D7       │
                   └──────┬───────┘
                          │
                          ▼
                   ┌──────────────┐
                   │ Phase 0      │
                   │ merge #18    │
                   └──────┬───────┘
                          │
                          ▼
                   ┌──────────────┐
                   │ Phase 1      │
                   │ photos-to-kml│
                   │ (safe smoke) │
                   └──────┬───────┘
                          │
                          ▼
                   ┌──────────────┐
                   │ Phase 2      │
                   │ Traefik+HTTPS│
                   └──┬────────┬──┘
                      │        │
            ┌─────────┘        └─────────┐
            ▼                            ▼
    ┌──────────────┐            ┌──────────────┐
    │ Phase 3      │            │ Phase 4      │
    │ Supabase Auth│            │ Vete         │
    │ (can be done │            │ (can start   │
    │  in parallel)│            │  on Tailscale│
    │              │            │  with dev    │
    │              │            │  bypass)     │
    └──────┬───────┘            └──────┬───────┘
           │                           │
           └────────────┬──────────────┘
                        ▼
                 ┌──────────────┐
                 │ Phase 5      │
                 │ Solstein     │
                 └──────┬───────┘
                        │
                        ▼
                 ┌──────────────┐
                 │ Phase 6      │
                 │ Hardening    │
                 └──────┬───────┘
                        │
                        ▼
                 ┌──────────────┐
                 │ Phase 7      │
                 │ Cleanups     │
                 │ (forever /   │
                 │  background) │
                 └──────────────┘
```

**Critical path** (fastest route to "Vete benefits from the agent"):
Phase 0 → 1 → 2 → 4

**Total critical path effort:** about 1–2 working days, dominated by Phase 1 (first real job validation) and Phase 4 (Vete onboarding).

**Phase 3 can run in parallel** with Phase 4 if you trust the Tailscale + dev bypass perimeter for the first Vete runs. I'd recommend serial: finish Phase 3 before exposing the agent to Vete so there's no window where a compromised Tailscale node could drive the agent against a live SaaS.

---

## Who does what

| Task category | Ivan | The agent (me) |
|---|---|---|
| Decisions D1–D7 | ✓ | — |
| Create GitHub users, PATs, Supabase projects | ✓ | — |
| Store items in Bitwarden | ✓ | (can do once vault is unlocked + session passed) |
| DNS configuration | ✓ | — |
| Writing code | — | ✓ |
| Writing docker-compose, env files | — | ✓ |
| Running commands on the VPS | — | ✓ (with SSH access) |
| Merging PRs | ✓ (final say) | (can self-merge private-repo PRs when explicitly authorized) |
| Reviewing agent-opened PRs on Vete/Solstein | ✓ | — |
| Responding to Telegram alerts | ✓ | — |

---

## When we're done

"Done" for the original goal — *the agent serves Vete and Solstein* — means:

1. ✅ Agent running on the VPS, healthy, monitored
2. ✅ Both Vete and Solstein registered in `repo_settings` with profile-derived config
3. ✅ GitHub webhooks wired for both repos
4. ✅ First real PR opened by the agent on each repo, reviewed and merged
5. ✅ Memories table seeded with reviewer preferences from both profiles
6. ✅ Daily quality reports running on both repos
7. ✅ Budget cap in place
8. ✅ Telegram alerts working
9. ✅ Backup running
10. ✅ No `DEV_AUTH_BYPASS` in production
11. ✅ Zero invariant violations in the last 30 days

That's the finish line. Everything in Phase 7 is optional polish.

---

*Generated 2026-04-08 at the end of a session that took the code-agent from "study material" to "verified end-to-end chat response." See session PRs #12 through #18 for the work that made this plan possible.*
