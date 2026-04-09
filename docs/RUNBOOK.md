# Runbook — AIW Code Agent Deployment From Scratch

> Step-by-step to take a blank VPS with Docker Swarm + Traefik + LiteLLM to
> a working code-agent serving webhooks on `https://code-agent.sunstein.cloud`.
>
> **Audience:** future you, or anyone who has to rebuild this. Assume you've
> never seen the code-agent before. Every step is a copy-paste-able command.
>
> **Estimated time from clean VPS to first webhook-handled PR:** ~90 minutes
> (30 min build + 30 min first-run debugging + 30 min GitHub App setup).

## 0. Prerequisites

On the **VPS** (called `agentzero`, IP `72.61.44.159`, Ubuntu 24.04):

- Docker Swarm initialized (`docker info` shows `Swarm: active`)
- Traefik running as a Swarm service with `--providers.swarm=true`, `--certificatesresolvers.letsencryptresolver.*` configured
- LiteLLM container running on port 4000, accessible to other containers via the `ai-infra-net` bridge (or equivalent)
- `agent-net` Swarm overlay network exists (Traefik is attached to it)
- Wildcard DNS `*.sunstein.cloud` → VPS public IP (so Let's Encrypt can verify)
- `openssl`, `git`, `rsync`, `python3`, `gh` CLI installed

On your **local machine** (the one running this runbook):

- `gh` CLI authenticated to an account with write access to the AIW org
- SSH key added to the VPS as `root@72.61.44.159`

## 1. Get the source onto the VPS

```bash
# Rsync the repo (exclude build artifacts)
rsync -az --delete \
  --exclude=target --exclude=.git --exclude=node_modules \
  --exclude=.m2 --exclude=.quarkus --exclude=.specstory \
  --exclude='*.log' --exclude=.env \
  /path/to/local/code-agent/ root@72.61.44.159:/opt/aiw-code-agent/

# Fix CRLF on shell scripts (Windows line endings break Docker bash shebangs)
ssh root@72.61.44.159 '
  cd /opt/aiw-code-agent
  for f in entrypoint.sh mvnw $(find scripts -name "*.sh" 2>/dev/null); do
    if file "$f" 2>/dev/null | grep -q CRLF; then
      sed -i "s/\r$//" "$f"
      echo "fixed CRLF: $f"
    fi
  done
'
```

## 2. Build the Docker image

```bash
ssh root@72.61.44.159 '
  cd /opt/aiw-code-agent
  docker compose -f docker-compose.aiw.yml build app
'
```

First build downloads Maven + Node + .NET SDK + Git. Expect ~8-12 minutes.
Subsequent rebuilds are cache-hit and take ~20 seconds. The image is tagged
`ai-whisperers/code-agent:aiw-dev`.

## 3. Set up LiteLLM multi-provider compatibility

The code-agent uses the Anthropic Java SDK v2.15.0 which always sends
`cache_control`, `web_search_options`, and `thinking` fields in every
request. Non-Anthropic providers (Groq, Cerebras, SambaNova, Mistral)
reject these fields with 400 errors.

Fix: add `drop_params: true` + `additional_drop_params` to every entry in
LiteLLM's `model_list`.

```bash
scp scripts/apply-aiw-drop-params.py root@72.61.44.159:/opt/litellm/
ssh root@72.61.44.159 '
  cp /opt/litellm/config.yaml /opt/litellm/config.yaml.bak-aiw
  python3 /opt/litellm/apply-aiw-drop-params.py
  docker restart litellm
'
```

Verify:

```bash
ssh root@72.61.44.159 '
  sleep 10
  curl -sS -X POST http://localhost:4000/v1/messages \
    -H "x-api-key: sk-hermes-litellm-sunstein-2026" \
    -H "anthropic-version: 2023-06-01" \
    -H "content-type: application/json" \
    -d "{\"model\":\"groq-llama-3.3-70b\",\"max_tokens\":30,\"messages\":[{\"role\":\"user\",\"content\":\"Say OK\"}],\"cache_control\":{\"type\":\"ephemeral\"}}"
'
# Expected: {"content":[{"type":"text","text":"OK"}],...}
```

## 4. Create the LiteLLM attachable overlay for Swarm reachability

The code-agent runs as a Swarm service. Swarm overlays can't reach host
ports (`host.docker.internal` doesn't work in Swarm). To let the Swarm
service talk to the non-Swarm LiteLLM container, we create an attachable
overlay and connect LiteLLM to it.

```bash
ssh root@72.61.44.159 '
  docker network create -d overlay --attachable aiw-llm-net
  docker network connect aiw-llm-net litellm
  docker network inspect aiw-llm-net --format "{{range .Containers}}{{.Name}} {{.IPv4Address}}{{println}}{{end}}"
'
# Expected output: "litellm 10.0.X.Y/24"
```

## 5. Create a LiteLLM virtual key with a budget cap

```bash
ssh root@72.61.44.159 '
  curl -sS -X POST http://localhost:4000/key/generate \
    -H "Authorization: Bearer sk-hermes-litellm-sunstein-2026" \
    -H "Content-Type: application/json" \
    -d "{\"key_alias\":\"aiw-code-agent\",\"max_budget\":25,\"budget_duration\":\"30d\",\"models\":[\"groq-llama-3.3-70b\",\"reasoning\",\"fast\"]}"
'
# Response includes a "key" field — save it, you will need it in step 7 below.
```

## 6. Generate the webhook HMAC secret

```bash
ssh root@72.61.44.159 '
  openssl rand -hex 32 > /opt/aiw-code-agent/.github-webhook-secret.txt
  chmod 600 /opt/aiw-code-agent/.github-webhook-secret.txt
  cat /opt/aiw-code-agent/.github-webhook-secret.txt
'
# Copy this 64-char hex value — you will paste it into the GitHub App
# webhook secret field in step 10.
```

## 7. Write the agent's .env file

```bash
ssh root@72.61.44.159 "cat > /opt/aiw-code-agent/.env <<'ENV'
# --- LLM via LiteLLM gateway ---
ANTHROPIC_BASE_URL=http://litellm:4000
ANTHROPIC_API_KEY=<paste-litellm-virtual-key-from-step-5>
ANTHROPIC_MODEL=groq-llama-3.3-70b
ANTHROPIC_FAST_MODEL=groq-llama-3.3-70b
ANTHROPIC_PROMPT_CACHE_ENABLED=false

# --- Dev bypass (remove after Phase 3 Supabase Auth) ---
KEYCLOAK_ENABLED=false
DEV_AUTH_BYPASS=true

# --- GitHub ---
GIT_PLATFORM=github
GITHUB_TOKEN=
GIT_AUTHOR_EMAIL=code-agent@aiwhisperers.io
GIT_AUTHOR_NAME=aiw-code-agent
WEBHOOK_SECRET_GITHUB=<paste-from-step-6>

# --- Optional notifications (leave blank to disable) ---
HERMES_GATEWAY_URL=

# --- Disable everything that would fail without credentials ---
TOOLS_AWS_ENABLED=false
TOOLS_WEB_SEARCH_ENABLED=false
BITBUCKET_WEBHOOK_SYNC_ENABLED=false
KNOWLEDGE_CRAWLER_SCHEDULER_ENABLED=false
QUALITY_REPORT_SCHEDULER_ENABLED=false
UPGRADE_SCHEDULER_ENABLED=false
CODE_GRAPH_SCHEDULER_ENABLED=false

# --- Settings encryption (dummy 32-byte hex, real one for production) ---
SETTINGS_ENCRYPTION_KEY=0000000000000000000000000000000000000000000000000000000000000000
ENV
chmod 600 /opt/aiw-code-agent/.env
"
```

**`GITHUB_TOKEN` stays blank for now** — the token refresher sidecar will
fill it in after step 12.

## 8. Deploy the Swarm stack

```bash
ssh root@72.61.44.159 '
  cd /opt/aiw-code-agent
  docker stack deploy -c docker-stack.aiw.yml aiw-code-agent --with-registry-auth
'

# Wait for the stack to converge (~30 seconds)
sleep 35

# Verify both services are 1/1
ssh root@72.61.44.159 'docker service ls | grep aiw'
```

Expected:
```
aiw-code-agent_app        replicated   1/1   ai-whisperers/code-agent:aiw-dev
aiw-code-agent_postgres   replicated   1/1   pgvector/pgvector:pg16
```

Verify the app booted and Flyway ran all migrations:

```bash
ssh root@72.61.44.159 '
  CTID=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_app)
  docker exec $CTID curl -sS http://localhost:8080/q/health
'
# Expected: {"status": "UP", ...}
```

## 9. Verify Traefik picked up the labels

```bash
# From your local machine (not the VPS)
curl -sS -w '\nHTTP: %{http_code}\n' -X POST \
  https://code-agent.sunstein.cloud/api/webhooks/github/pull-request \
  -H 'Content-Type: application/json' \
  -H 'X-GitHub-Event: ping' \
  -d '{}'
```

Expected: `HTTP 401 {"error":"Invalid webhook signature"}` — the app is
reachable through HTTPS + Traefik AND the signature filter is enforcing.

If you get a Traefik 404, check the swarm labels on the service:

```bash
ssh root@72.61.44.159 '
  docker service inspect aiw-code-agent_app --format "{{range \$k,\$v := .Spec.Labels}}{{\$k}}={{\$v}}{{println}}{{end}}" | grep traefik
'
```

The critical labels must include `traefik.swarm.network=agent-net`. Do NOT
include `traefik.docker.network` — Traefik 3.5 silently skips the service
if both are present (see KNOWN-ISSUES.md).

## 10. Create the GitHub App

This part is a manual web-UI flow. Everything else in this runbook is
scriptable, but GitHub Apps have to be created via browser.

1. Go to `https://github.com/organizations/Ai-Whisperers/settings/apps`
2. Click **New GitHub App**
3. Fill in:
   - **Name:** hermes-bot-aiwhispereres (or whatever)
   - **Homepage:** https://code-agent.sunstein.cloud
   - **Webhook URL:** https://code-agent.sunstein.cloud/api/webhooks/github/pull-request
   - **Webhook secret:** (paste the value from step 6)
   - **Active:** ✓
4. **Permissions** (repository):
   - Contents: Read & write
   - Pull requests: Read & write
   - Issues: Read & write
   - Metadata: Read
5. **Subscribe to events:**
   - Pull request
   - Pull request review
   - Pull request review comment
   - Issue comment
   - Issues
6. **Where can this App be installed:** Only on this account
7. Click **Create GitHub App**
8. On the resulting page: **Generate a private key** → downloads a `.pem`
9. Note the **App ID** (7-digit number at top)
10. Install App (left nav) → Only select repositories → pick the target
    repo(s) → Install
11. After install, the URL is `.../settings/installations/NNNNNNN` — note
    the `NNNNNNN`.

## 11. Upload the GitHub App private key to the VPS

```bash
# From your local machine, immediately after downloading the .pem
scp ~/Downloads/hermes-bot-aiwhispereres.*.private-key.pem \
    root@72.61.44.159:/opt/aiw-code-agent/.github-app-key.pem
ssh root@72.61.44.159 'chmod 600 /opt/aiw-code-agent/.github-app-key.pem'

# Verify it's a valid RSA key
ssh root@72.61.44.159 'openssl rsa -in /opt/aiw-code-agent/.github-app-key.pem -check -noout'
# Expected: "RSA key ok"

# Shred the local copy (never leave .pem in ~/Downloads)
shred -u ~/Downloads/hermes-bot-aiwhispereres.*.private-key.pem
```

## 12. Configure and start the token refresher

```bash
ssh root@72.61.44.159 "cat > /etc/aiw-code-agent/github-app.conf <<CONF
AIW_GITHUB_APP_ID=<paste-from-step-10>
AIW_GITHUB_INSTALLATION_ID=<paste-from-step-10>
AIW_GITHUB_APP_KEY=/opt/aiw-code-agent/.github-app-key.pem
AIW_ENV_FILE=/opt/aiw-code-agent/.env
AIW_SERVICE_NAME=aiw-code-agent_app
CONF
chmod 600 /etc/aiw-code-agent/github-app.conf
"

# Install systemd units
ssh root@72.61.44.159 '
  cp /opt/aiw-code-agent/scripts/systemd/aiw-refresh-github-token.service /etc/systemd/system/
  cp /opt/aiw-code-agent/scripts/systemd/aiw-refresh-github-token.timer /etc/systemd/system/
  systemctl daemon-reload
'

# Run once manually to mint the first token
ssh root@72.61.44.159 'python3 /opt/aiw-code-agent/scripts/aiw-refresh-github-token.py'
```

Expected output:
```
[timestamp] minting JWT for app <id>
[timestamp] requesting installation token for <id>
[timestamp] got installation token, expires_at=..., permissions=[...], repo_selection=all
[timestamp] wrote GITHUB_TOKEN to /opt/aiw-code-agent/.env
[timestamp] redeploying stack to pick up .env
[timestamp] done
```

Then enable the timer for continuous refresh:

```bash
ssh root@72.61.44.159 'systemctl enable --now aiw-refresh-github-token.timer'
ssh root@72.61.44.159 'systemctl list-timers aiw-refresh-github-token.timer'
```

## 13. Install the postgres backup cron

```bash
ssh root@72.61.44.159 '
  chmod +x /opt/aiw-code-agent/scripts/aiw-backup.sh
  (crontab -l 2>/dev/null | grep -v aiw-backup
   echo "0 3 * * * /opt/aiw-code-agent/scripts/aiw-backup.sh >> /var/log/aiw-backup.log 2>&1"
  ) | crontab -
  crontab -l | grep aiw
'

# Test the backup script once manually
ssh root@72.61.44.159 '/opt/aiw-code-agent/scripts/aiw-backup.sh && ls -la /opt/aiw-backups/'
```

## 14. Seed a repo's settings via the internal API

```bash
# Use docker exec to hit the app directly (bypasses Traefik public routing)
ssh root@72.61.44.159 '
  CTID=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_app)

  cat > /tmp/repo.json <<JSON
{
  "reviewEnabled": true,
  "vectorEnabled": false,
  "docsEnabled": false,
  "upgradeEnabled": false,
  "qualityReportEnabled": false,
  "archived": false,
  "primaryLanguage": "typescript"
}
JSON
  docker cp /tmp/repo.json $CTID:/tmp/repo.json
  docker exec $CTID curl -sS -X PUT \
    http://localhost:8080/api/settings/repos/Ai-Whisperers/<REPO-NAME> \
    -H "Content-Type: application/json" \
    -d @/tmp/repo.json
'
```

Replace `<REPO-NAME>` with your target repo.

## 15. Smoke test: open a trivial PR

1. Edit any file in the target repo (e.g. add a comment line to README.md)
2. Create a branch and open a PR
3. Within seconds, the agent posts a PR summary comment
4. Watch the agent logs:

```bash
ssh root@72.61.44.159 '
  docker service logs --since 2m -f aiw-code-agent_app 2>&1 |
    grep -iE "webhook|review|claude|comment posted|signature"
'
```

5. Close the PR without merging (smoke test only)

If the PR summary comment appears, **you are done**. The agent is live.

## 16. What to do if step 15 fails

See `docs/KNOWN-ISSUES.md` for every gotcha we've hit. The most common
ones:

- **No webhook arrives:** check the GitHub App's webhook URL field in
  Developer Settings (GitHub API does NOT expose this, you must check
  the web UI).
- **401 from webhook route:** the HMAC secret in `.github-webhook-secret.txt`
  doesn't match the one you pasted into the App settings. Re-generate and
  paste consistently.
- **Job fails with "Review job ... failed: Agent review loop error":**
  LiteLLM model routing issue. Check the error message for "ContextWindow"
  or "max_tokens" — almost always means the `fast` alias group is
  routing to an 8-16k context provider (Cerebras, SambaNova). Pin
  `ANTHROPIC_MODEL=groq-llama-3.3-70b`.
- **`.env` changes don't take effect:** you used `docker service update`
  which does NOT re-read `env_file:`. Use `docker stack deploy` instead.
- **`host.docker.internal` doesn't resolve:** that's expected in Swarm,
  use the `aiw-llm-net` overlay instead (see step 4).

## 17. What's NOT in this runbook (future hardening)

The following are left for future sessions, tracked in `docs/NEXT-STEPS.md`:

- Phase 3: Supabase Auth (removes `DEV_AUTH_BYPASS`)
- Phase 6.2: Observability (Langfuse hook, Prometheus scrape)
- Phase 6.4: Rate limiting
- Phase 6.5: Alerting on job failures
- Phase 7: Optional cleanups (rebrand, drop BB/ADO/Aikido, Linear adapter)

See the Next Steps doc for priorities and the Deployment Notes doc for
gotchas discovered during runtime.
