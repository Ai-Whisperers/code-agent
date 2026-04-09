# Deployment notes — AIW Code Agent on Swarm

> Running at `72.61.44.159` on the AIW Swarm cluster alongside Vete,
> clinica-duerksen, and friends.
>
> **Public HTTPS:** `https://code-agent.sunstein.cloud/api/webhooks/github/*`
> (webhook-only routing via Traefik; every other path returns 404 at the
> edge). End-to-end verified April 2026 against photos-to-kml PR #4 and
> Vete PR #64.
>
> **For first-time setup from scratch:** see `docs/RUNBOOK.md`.
> **For day-to-day commands:** see `docs/OPERATIONS.md`.
> **For gotchas + workarounds:** see `docs/KNOWN-ISSUES.md`.
> **For the phased roadmap:** see `docs/NEXT-STEPS.md`.

## Current state (as of end-of-session, April 2026)

- Stack name: `aiw-code-agent`
- Services: `aiw-code-agent_app` (1/1) + `aiw-code-agent_postgres` (1/1)
- Image: `ai-whisperers/code-agent:aiw-dev` (built locally on the VPS)
- DB: pgvector/pgvector:pg16, schema current at migration V108
- Internal port: 8080 (inside the swarm overlay, not published to host)
- LLM gateway: `http://litellm:4000` via the `aiw-llm-net` attachable overlay
- DB auth: hardcoded `aiw_code_agent:aiw_code_agent` in the stack file
- Dev auth bypass: ENABLED (`DEV_AUTH_BYPASS=true`) — production hardening
  is Phase 3 (Supabase Auth)

## The non-obvious bits

### 1. Swarm service DNS collisions

`postgres_postgres` is another top-level Swarm stack on the same
`agent-net` overlay, so resolving `postgres` from inside the agent's
stack may hit the wrong service. **Always use the fully-qualified form**
`aiw-code-agent_postgres` in `DATABASE_URL`.

### 2. `host.docker.internal` doesn't work in Swarm

The `extra_hosts: host.docker.internal:host-gateway` trick is Compose-
specific. Swarm overlay networks are isolated from host networking, so
the container cannot reach `localhost:4000` or even the VPS's public IP
on port 4000 — the docker-proxy only listens on the host's network stack.

**Workaround (in effect now):** Created a dedicated attachable Swarm
overlay `aiw-llm-net` and connected the `litellm` container to it:

```bash
docker network create -d overlay --attachable aiw-llm-net
docker network connect aiw-llm-net litellm
```

The agent's stack declares `aiw-llm-net` as an external network and
uses `http://litellm:4000` in `ANTHROPIC_BASE_URL`. This works because
`litellm` resolves via swarm DNS on the shared overlay.

### 3. `env_file` interpolation is weird

In Swarm, `${VAR}` inside the stack file is interpolated from the
**shell environment at `docker stack deploy` time**, NOT from the
`env_file` referenced by a service. This caused an early DB password
mismatch where the stack file evaluated `${DATABASE_PASSWORD:-default}`
to the default, while the app container later loaded a different value
from `env_file:`. **Fix:** hardcode DB credentials in the stack file.

### 3b. `env_file:` is only re-read on `docker stack deploy` (NEW, April 2026)

**The biggest footgun of the session.** Swarm's `env_file:` directive
is evaluated **client-side at `docker stack deploy` time**. The file
contents are **inlined** into the service spec at that moment. Subsequent
changes to the file on disk do NOT propagate to the running service.

- `docker service update --force aiw-code-agent_app` → restarts the
  container but keeps the **stale** env from the last stack deploy.
- `docker service update --env-add FOO=bar` → adds to the spec, but
  anything already in the spec from the old env_file snapshot is
  preserved — and you can't bulk-reload.
- `docker stack deploy -c docker-stack.aiw.yml aiw-code-agent` → re-reads
  the env_file and re-deploys with fresh values. **This is the only way
  to push `.env` changes.**

**Impact:** The original GitHub App token refresh sidecar used
`docker service update --force` under the theory that it would be
lower-churn than a full stack deploy. In practice, the `GITHUB_TOKEN` it
wrote to `.env` every 50 minutes was NEVER picked up by the running
container — only the OLD token from the first stack deploy was in the
spec. Tokens expired silently, manual `.env` edits vanished, and the
failure mode was opaque because the logs still showed "token refreshed"
from the sidecar even though the container couldn't see it.

**Fix:** `scripts/aiw-refresh-github-token.py` now shells out to
`docker stack deploy` instead. See the `roll_service()` function there.

**General rule for this deployment:** any script or command that needs
to change the runtime environment of `aiw-code-agent_app` MUST run
`docker stack deploy`, NOT `docker service update --force`.

### 4. Traefik v3.5 silently skips services with deprecated labels

**FIXED.** Root cause: having BOTH `traefik.swarm.network=agent-net`
AND `traefik.docker.network=agent-net` labels on the same swarm service
causes Traefik 3.5's swarm provider to log a deprecation warning for
`traefik.docker.*` and then **silently skip the entire service** —
no routers, no logs mentioning the service, nothing. Fun.

**Fix:** use only `traefik.swarm.network=agent-net`. The stack file
now has exactly one network-disambiguation label and Traefik picks up
the labels immediately.

What gets routed publicly via HTTPS:

| Path | Behavior |
|---|---|
| `https://code-agent.sunstein.cloud/api/webhooks/github/*` | ✅ Routes to app, HMAC verified by WebhookSignatureFilter |
| `https://code-agent.sunstein.cloud/api/webhooks/bitbucket/*` | ✅ Same (legacy, unused) |
| `https://code-agent.sunstein.cloud/api/chat` | ❌ 404 at Traefik — not in PathPrefix rule |
| `https://code-agent.sunstein.cloud/api/plans` | ❌ 404 at Traefik |
| `https://code-agent.sunstein.cloud/q/health` | ❌ 404 at Traefik |

Internal admin access stays via SSH + `docker exec`. Phase 3 (Supabase
Auth) will add a proper authenticated HTTPS route for the admin API.

Verified:
```bash
$ curl -X POST https://code-agent.sunstein.cloud/api/webhooks/github/pull-request \
    -H 'Content-Type: application/json' -H 'X-GitHub-Event: pull_request' \
    -d '{"action":"opened","number":1,"pull_request":{"title":"test"}}'
{"reason":"Missing PR number or repository full_name in payload","action":"ignored"}
```

HTTP 200 from the app. TLS cert valid via Let's Encrypt (letsencryptresolver).

## Useful commands (run on the VPS)

```bash
# Status
docker service ls | grep aiw
docker service ps aiw-code-agent_app

# Logs
docker service logs -f aiw-code-agent_app
docker service logs --tail 50 aiw-code-agent_postgres

# Exec into the app container
CTID=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_app)
docker exec -it $CTID bash

# Health check
docker exec $CTID curl -sS http://localhost:8080/q/health

# Submit a plan (dev.auth.bypass handles auth)
docker exec $CTID curl -sS -X POST http://localhost:8080/api/plans \
  -H 'Content-Type: application/json' \
  -d '{"repoUrl":"https://github.com/Ai-Whisperers/photos-to-kml","targetBranch":"main","specText":"Your spec here"}'

# DB exec
PG_CTID=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_postgres)
docker exec $PG_CTID psql -U aiw_code_agent -d aiw_code_agent -c 'SELECT COUNT(*) FROM execution_plans;'

# Redeploy after a config change
cd /opt/aiw-code-agent
docker stack deploy -c docker-stack.aiw.yml aiw-code-agent --with-registry-auth

# Tear down (keeps volumes)
docker stack rm aiw-code-agent

# Nuke the DB volume (will trigger re-initdb on next deploy)
docker volume rm aiw-code-agent_aiw-pg
```

## Environment variables (current)

Set in `/opt/aiw-code-agent/.env` (host side), loaded via `env_file:`:

| Var | Purpose |
|---|---|
| `ANTHROPIC_API_KEY` | LiteLLM virtual key `sk-aiw-code-agent` ($25/30d budget, scoped to fast/reasoning/groq-llama-3.3-70b) |
| `ANTHROPIC_MODEL` | `groq-llama-3.3-70b` — **pinned** to the 128k-context single-backend alias. Do NOT set to `fast` (the group includes Cerebras 8k and SambaNova 16k which overflow the agent's ~67k system prompt) |
| `ANTHROPIC_FAST_MODEL` | `groq-llama-3.3-70b` — same model for short and long tasks for now |
| `ANTHROPIC_PROMPT_CACHE_ENABLED` | `false` — gates PR #18 so `cache_control` is never attached. LiteLLM also strips it via `drop_params` — belt and braces |
| `DEV_AUTH_BYPASS` | `true` — dev-only, enables internal `/api/chat` and `/api/plans` without Keycloak. Phase 3 Supabase Auth removes this |
| `GITHUB_TOKEN` | Auto-refreshed every 50 min by `scripts/aiw-refresh-github-token.py` systemd timer. Installation token for the `hermes-bot-aiwhispereres` GitHub App (app_id=3127065, installation=117402087) |
| `WEBHOOK_SECRET_GITHUB` | 64-char hex in `/opt/aiw-code-agent/.github-webhook-secret.txt`, also pasted into the GitHub App's Webhook Secret field |
| `SETTINGS_ENCRYPTION_KEY` | dummy 32-byte hex for dev (rotate to real random hex for prod) |

Hardcoded in `docker-stack.aiw.yml` (not from .env):

| Var | Value | Reason |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://aiw-code-agent_postgres:5432/aiw_code_agent` | Swarm DNS collision fix |
| `DATABASE_USER` | `aiw_code_agent` | Remove interpolation surprise |
| `DATABASE_PASSWORD` | `aiw_code_agent` | Same |
| `ANTHROPIC_BASE_URL` | `http://litellm:4000` | Swarm overlay routing to LiteLLM |

## What was verified end-to-end

### LLM round trips
- `POST /api/chat` → streamed SSE with real Claude response via LiteLLM
- `POST /api/plans` × 3 plans stored in `execution_plans` table:
  - photos-to-kml: README improvement suggestions
  - Vete: CLAUDE.md convention summary
  - solstein: loguru replacement plan with Autoresearch Metrics footer
- `ai_calls` table: 5+ rows, all with `cache_tokens=0` and `is_error=false`

### Real webhook chain (both closed after verification)
- **photos-to-kml PR #4** — bot opened it via the installation token,
  GitHub fired `pull_request.opened`, Traefik routed to the app,
  signature verified, review job ran, agent posted PR summary comment
  in ~5 seconds. Sync event on push worked the same way. See GitHub
  App delivery log (`/app/hook/deliveries`) for the receipts.
- **Vete PR #64** — same flow against the 580K LOC Vete repo. Monorepo
  auto-detect from PR #16 correctly picked up `web/README.md` as the
  diff target. The review handler read the real Vete CLAUDE.md and
  generated a summary that understood the Next.js 15 + Supabase
  multi-tenant context. First run failed on the `fast` alias routing to
  SambaNova (16k context overflow); second run with
  `ANTHROPIC_MODEL=groq-llama-3.3-70b` pinned succeeded.

### Infrastructure health
- Postgres backup cron installed + first backup succeeded (~28K)
- LiteLLM virtual key `sk-aiw-code-agent` created with $25/30d budget
- Systemd timer `aiw-refresh-github-token.timer` enabled, refreshes
  every 50 minutes
- Let's Encrypt cert valid for `code-agent.sunstein.cloud` via Traefik

### Security boundary verified
- Public webhook route: HTTP 200 on valid HMAC, 401 on bad signature
  (PR #21 fix holds)
- Public non-webhook routes: 404 at Traefik (PathPrefix rule holds)
- Dev auth bypass: stamps synthetic admin identity only on internal
  paths, public-facing `/api/webhooks/*` has its own signature check

## What still needs human action (see docs/NEXT-STEPS.md)

1. Debug Traefik swarm provider → unblocks public webhook endpoint
2. Create `aiw-code-agent` GitHub user + fine-grained PATs (D2) → Phase 4 real runs
3. Create Supabase project (D4) → Phase 3 auth migration
4. GitHub webhook registration on Vete + Solstein → Phase 4/5 execution
