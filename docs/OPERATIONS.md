# Operations — AIW Code Agent Day-to-Day

> Commands for keeping the agent running. SSH to `root@72.61.44.159` and
> go. This assumes the initial setup from `docs/RUNBOOK.md` is done and
> the Swarm stack is live.

## Quick health check

```bash
ssh root@72.61.44.159 << 'EOF'
echo "=== services ==="
docker service ls | grep aiw
echo
echo "=== app health ==="
CTID=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_app)
docker exec $CTID curl -sS http://localhost:8080/q/health | head -5
echo
echo "=== token timer ==="
systemctl is-active aiw-refresh-github-token.timer
systemctl list-timers aiw-refresh-github-token.timer --no-pager | head -3
echo
echo "=== DB ==="
PG=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_postgres)
docker exec $PG psql -U aiw_code_agent -d aiw_code_agent -tAc \
  "SELECT 'ai_calls=' || count(*) FROM ai_calls"
docker exec $PG psql -U aiw_code_agent -d aiw_code_agent -tAc \
  "SELECT 'repo_settings=' || count(*) FROM repo_settings"
echo
echo "=== public HTTPS ==="
curl -sS -w "HTTP %{http_code}" -X POST \
  https://code-agent.sunstein.cloud/api/webhooks/github/pull-request \
  -H 'X-GitHub-Event: ping' -d '{}'
EOF
```

Expected output: both services 1/1, health UP, timer active, DB row
counts, HTTPS 401 (signature mismatch means the app is reachable and
enforcing).

## Follow live logs

```bash
# All logs
ssh root@72.61.44.159 'docker service logs -f aiw-code-agent_app'

# Filter to job + webhook activity
ssh root@72.61.44.159 "docker service logs -f aiw-code-agent_app 2>&1 |
  grep -iE 'webhook|review|claude|clone|tokens|error|fail|comment posted'"

# Filter to a specific job ID
ssh root@72.61.44.159 "docker service logs aiw-code-agent_app 2>&1 |
  grep '<job-id>'"

# Latest 50 lines, pretty-printed (pipe JSON through jq if installed)
ssh root@72.61.44.159 "docker service logs --tail 50 aiw-code-agent_app 2>&1 |
  python3 -c 'import sys,json
for line in sys.stdin:
    line = line.split(\"|\", 1)[-1].strip()
    try:
        d = json.loads(line)
        print(f\"{d.get(\\\"level\\\",\\\"?\\\"):5} {d.get(\\\"message\\\",\\\"\\\")[:200]}\")
    except:
        print(line[:200])
'"
```

## Deploy a code change

```bash
# 1. Build + push locally (from your dev machine)
cd ~/code-agent
rsync -az --delete \
  --exclude=target --exclude=.git --exclude=node_modules \
  --exclude=.m2 --exclude=.quarkus --exclude='*.log' --exclude=.env \
  . root@72.61.44.159:/opt/aiw-code-agent/

# 2. Rebuild the image on the VPS
ssh root@72.61.44.159 '
  cd /opt/aiw-code-agent
  docker compose -f docker-compose.aiw.yml build app
'

# 3. Roll the Swarm service
# IMPORTANT: use `docker stack deploy`, NOT `docker service update`.
# The env_file: directive is only re-read on stack deploy.
ssh root@72.61.44.159 '
  cd /opt/aiw-code-agent
  docker stack deploy -c docker-stack.aiw.yml aiw-code-agent --with-registry-auth
'

# 4. Watch the update converge
ssh root@72.61.44.159 'docker service ps aiw-code-agent_app --no-trunc | head -5'
```

## Update an environment variable

```bash
# 1. Edit the .env file on the VPS
ssh root@72.61.44.159 'nano /opt/aiw-code-agent/.env'

# 2. Redeploy the stack (NOT docker service update)
ssh root@72.61.44.159 '
  cd /opt/aiw-code-agent
  docker stack deploy -c docker-stack.aiw.yml aiw-code-agent --with-registry-auth
'

# 3. Verify the new value is in the container
ssh root@72.61.44.159 '
  docker exec $(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_app) \
    env | grep YOUR_VAR
'
```

**Gotcha:** `docker service update --force` does NOT re-read `env_file:`.
Only `docker stack deploy` does.

## Rotate the GitHub App installation token manually

The systemd timer does this every 50 minutes automatically, but you can
force it:

```bash
ssh root@72.61.44.159 'python3 /opt/aiw-code-agent/scripts/aiw-refresh-github-token.py'
```

Expected output: 6 lines ending in "done". The script:
1. Mints a new JWT (RS256 with the App's private key)
2. Exchanges it for an installation token via GitHub API
3. Writes the token to `/opt/aiw-code-agent/.env` as `GITHUB_TOKEN=...`
4. Runs `docker stack deploy` to roll the service with the new env

## Rotate the GitHub App private key (emergency)

If the `.pem` is compromised:

1. Go to `https://github.com/organizations/Ai-Whisperers/settings/apps/<app-slug>`
2. Scroll to **Private keys**
3. **Generate a private key** → downloads a new `.pem`
4. SCP it to the VPS and shred the local copy:
   ```bash
   scp ~/Downloads/<new>.pem root@72.61.44.159:/opt/aiw-code-agent/.github-app-key.pem
   ssh root@72.61.44.159 'chmod 600 /opt/aiw-code-agent/.github-app-key.pem'
   shred -u ~/Downloads/<new>.pem
   ```
5. **Delete the OLD key** in the App settings (button next to the
   fingerprint). This is the critical step — until you delete it, an
   attacker with the old bytes can still mint tokens.
6. Run the refresh script to mint a token with the new key:
   ```bash
   ssh root@72.61.44.159 'python3 /opt/aiw-code-agent/scripts/aiw-refresh-github-token.py'
   ```
7. Verify a test call works:
   ```bash
   ssh root@72.61.44.159 '
     CTID=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_app)
     docker exec $CTID curl -sS -H "Authorization: token $(grep ^GITHUB_TOKEN /opt/aiw-code-agent/.env | cut -d= -f2)" \
       https://api.github.com/repos/Ai-Whisperers/photos-to-kml
   '
   ```

## Rotate the webhook HMAC secret

1. Generate new secret on the VPS:
   ```bash
   ssh root@72.61.44.159 '
     openssl rand -hex 32 > /opt/aiw-code-agent/.github-webhook-secret.txt
     chmod 600 /opt/aiw-code-agent/.github-webhook-secret.txt
     cat /opt/aiw-code-agent/.github-webhook-secret.txt
   '
   ```
2. Update `.env`:
   ```bash
   ssh root@72.61.44.159 '
     NEW=$(cat /opt/aiw-code-agent/.github-webhook-secret.txt)
     sed -i "s|^WEBHOOK_SECRET_GITHUB=.*|WEBHOOK_SECRET_GITHUB=$NEW|" /opt/aiw-code-agent/.env
   '
   ```
3. Paste the new value into the GitHub App's Webhook Secret field in
   Developer Settings.
4. Redeploy the stack so the app picks up the new env:
   ```bash
   ssh root@72.61.44.159 '
     cd /opt/aiw-code-agent
     docker stack deploy -c docker-stack.aiw.yml aiw-code-agent --with-registry-auth
   '
   ```
5. Trigger a test webhook by pushing to a PR branch on a monitored repo.
   Should post a comment within 5 seconds.

## Restore from backup

```bash
# Backups live at /opt/aiw-backups/code-agent-YYYY-MM-DD.sql.gz (14-day retention)
ssh root@72.61.44.159 'ls -la /opt/aiw-backups/'

# To restore a specific date:
ssh root@72.61.44.159 '
  # STOP the app first so it doesn'\''t write during restore
  docker service scale aiw-code-agent_app=0
  sleep 5

  PG=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_postgres)

  # Drop + recreate the database
  docker exec $PG psql -U postgres -c "DROP DATABASE aiw_code_agent"
  docker exec $PG psql -U postgres -c "CREATE DATABASE aiw_code_agent OWNER aiw_code_agent"

  # Restore from the selected backup
  gunzip -c /opt/aiw-backups/code-agent-2026-04-08.sql.gz |
    docker exec -i $PG psql -U aiw_code_agent -d aiw_code_agent

  # Scale the app back up
  docker service scale aiw-code-agent_app=1
'
```

## Check LiteLLM budget + spend

```bash
ssh root@72.61.44.159 '
  curl -sS -H "Authorization: Bearer sk-hermes-litellm-sunstein-2026" \
    http://localhost:4000/key/info \
    -H "Content-Type: application/json" \
    -d "{\"keys\":[\"sk-aiw-code-agent\"]}" |
    python3 -m json.tool | grep -E "spend|max_budget|budget_duration"
'
```

## Change the LiteLLM model alias the agent uses

```bash
ssh root@72.61.44.159 '
  sed -i "s/^ANTHROPIC_MODEL=.*/ANTHROPIC_MODEL=groq-llama-3.3-70b/" /opt/aiw-code-agent/.env
  cd /opt/aiw-code-agent
  docker stack deploy -c docker-stack.aiw.yml aiw-code-agent --with-registry-auth
'
```

**Valid aliases (as of session 2026-04):**
- `groq-llama-3.3-70b` — 128k context, pinned backend, RECOMMENDED
- `reasoning` — Qwen3-32b via Groq (smaller context, weaker)
- `fast` — MIXED GROUP with Cerebras 8k / SambaNova 16k — **do not use**,
  context overflows
- `primary` — smart routing, occasionally hits small-context tiers

## Apply the LiteLLM drop_params patch (after a reinstall)

If LiteLLM's config.yaml gets reinstalled from the upstream Eneve setup,
the non-Anthropic providers will start rejecting requests again because
they see `cache_control` / `web_search_options` / etc. Re-apply the
drop_params patch:

```bash
ssh root@72.61.44.159 '
  python3 /opt/litellm/apply-aiw-drop-params.py
  docker restart litellm
'
```

See `/opt/litellm/README-AIW.md` on the VPS for the full rationale.

## Investigate a failed review job

```bash
# Find the job in the DB
ssh root@72.61.44.159 '
  PG=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_postgres)
  docker exec $PG psql -U aiw_code_agent -d aiw_code_agent \
    -c "SELECT job_id, status, job_type, substring(error_message, 1, 120) FROM jobs ORDER BY created_at DESC LIMIT 5"
'

# Then grep the logs for that job_id
ssh root@72.61.44.159 "
  docker service logs aiw-code-agent_app 2>&1 |
    grep '<job-id>' |
    tail -30
"

# Or inspect the AI call records for that job
ssh root@72.61.44.159 '
  PG=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_postgres)
  docker exec $PG psql -U aiw_code_agent -d aiw_code_agent \
    -c "SELECT created_at, model, input_tokens, output_tokens, duration_ms, is_error, substring(error_message, 1, 100) FROM ai_calls WHERE job_id = '\''<job-id>'\'' ORDER BY created_at"
'
```

## Cancel a stuck job

```bash
ssh root@72.61.44.159 '
  CTID=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_app)
  docker exec $CTID curl -sS -X POST \
    http://localhost:8080/api/jobs/<job-id>/cancel
'
```

## Add a new target repo

```bash
ssh root@72.61.44.159 '
  CTID=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_app)

  # Write the repo settings JSON
  cat > /tmp/new-repo.json <<JSON
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

  docker cp /tmp/new-repo.json $CTID:/tmp/new-repo.json
  docker exec $CTID curl -sS -X PUT \
    http://localhost:8080/api/settings/repos/Ai-Whisperers/<NEW-REPO> \
    -H "Content-Type: application/json" \
    -d @/tmp/new-repo.json
'

# Install the GitHub App on the new repo (web UI only)
# github.com/organizations/Ai-Whisperers/settings/installations
# → Configure hermes-bot-aiwhispereres → add repo

# After install, the next webhook will trigger a review.
```

## Pause the agent without stopping the stack

```bash
# Scale to zero replicas
ssh root@72.61.44.159 'docker service scale aiw-code-agent_app=0'

# Webhooks will queue at GitHub (it retries on 503/timeouts).
# To resume:
ssh root@72.61.44.159 'docker service scale aiw-code-agent_app=1'
```

## Emergency stop (kill everything)

```bash
ssh root@72.61.44.159 '
  docker stack rm aiw-code-agent
  # Volumes persist by default — that'\''s fine, next deploy reuses them
'

# To nuke data (fresh start):
ssh root@72.61.44.159 '
  docker stack rm aiw-code-agent
  sleep 10
  docker volume rm aiw-code-agent_aiw-pg aiw-code-agent_aiw-m2 aiw-code-agent_aiw-workspace 2>/dev/null || true
'
```

## Check cron job history

```bash
ssh root@72.61.44.159 '
  tail -30 /var/log/aiw-backup.log
  tail -30 /var/log/syslog | grep aiw-refresh
'
```

## Sanity checklist (after any change)

1. `docker service ls | grep aiw` shows both 1/1
2. `curl /q/health` from inside the container returns UP
3. `curl /api/webhooks/github/pull-request` from outside returns 401
   (signature enforced) or 400 (payload malformed) — but NOT 404
4. `docker exec ... env | grep ANTHROPIC_MODEL` shows the expected model
5. `systemctl is-active aiw-refresh-github-token.timer` returns "active"
6. Latest file in `/opt/aiw-backups/` is less than 25 hours old
7. Latest row in `ai_calls` has `is_error=false`

If any of these fail, see `docs/KNOWN-ISSUES.md`.
