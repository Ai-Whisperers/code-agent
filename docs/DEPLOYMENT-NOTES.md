# Deployment notes — AIW Code Agent on Swarm

> Running at `72.61.44.159` on the AIW Swarm cluster alongside Vete,
> clinica-duerksen, and friends. No public HTTPS yet (Traefik labels not
> picked up by swarm provider — deferred, see below).

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
| `ANTHROPIC_API_KEY` | LiteLLM master key (was: sk-hermes-litellm-sunstein-2026, rotating to virtual `sk-aiw-code-agent` with $25/mo budget) |
| `ANTHROPIC_MODEL` | `fast` (Groq llama-3.3-70b via LiteLLM) |
| `ANTHROPIC_FAST_MODEL` | `fast` |
| `ANTHROPIC_PROMPT_CACHE_ENABLED` | `false` — required because `fast` routes to Groq which rejects `cache_control` |
| `DEV_AUTH_BYPASS` | `true` — dev-only, remove after Phase 3 |
| `GITHUB_TOKEN` | temporary: Ivan's personal PAT (rotate before Phase 4 real runs) |
| `SETTINGS_ENCRYPTION_KEY` | dummy 32-byte hex |

Hardcoded in `docker-stack.aiw.yml` (not from .env):

| Var | Value | Reason |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://aiw-code-agent_postgres:5432/aiw_code_agent` | Swarm DNS collision fix |
| `DATABASE_USER` | `aiw_code_agent` | Remove interpolation surprise |
| `DATABASE_PASSWORD` | `aiw_code_agent` | Same |
| `ANTHROPIC_BASE_URL` | `http://litellm:4000` | Swarm overlay routing to LiteLLM |

## What was verified end-to-end

- `POST /api/chat` → streamed SSE with real Claude response
- `POST /api/plans` × 3 plans stored in `execution_plans` table:
  - `plan-180bd246-...` for photos-to-kml (README improvement suggestions)
  - `plan-20fabd28-...` for Vete (CLAUDE.md convention summary)
  - `plan-21d7214f-...` for solstein (loguru replacement with Autoresearch metrics footer)
- 3+ rows in `ai_calls` table, all with `cache_tokens=0` and `is_error=false`
- Postgres backup cron installed, first backup succeeded (28K)
- LiteLLM virtual key `sk-aiw-code-agent` created with $25/30d budget

## What still needs human action (see docs/NEXT-STEPS.md)

1. Debug Traefik swarm provider → unblocks public webhook endpoint
2. Create `aiw-code-agent` GitHub user + fine-grained PATs (D2) → Phase 4 real runs
3. Create Supabase project (D4) → Phase 3 auth migration
4. GitHub webhook registration on Vete + Solstein → Phase 4/5 execution
