# Known Issues — AIW Code Agent

> Every gotcha we've hit in production. Organized by "where it shows up"
> so you can grep your error and find the fix.
>
> If you hit something new, add it here so the next person doesn't.

## Severity legend

- **P0** — security hole or silent data loss
- **P1** — functional breakage, user-visible
- **P2** — cosmetic / nice-to-have
- **INFRA** — deployment model bug, not in the Java code
- **FIXED** — patched in this repo
- **OPEN** — workaround known, real fix pending

---

## P0-FIXED: Webhook signature verification silently bypassed

**Symptom:** Anyone posting to `/api/webhooks/github/*` can trigger review
jobs without a valid HMAC signature. Send a bogus `X-Hub-Signature-256`
and the agent still processes the payload.

**Root cause:** Upstream `WebhookSignatureFilter` has:

```java
String path = ctx.getUriInfo().getPath();
if (!path.startsWith("webhooks/")) {
    return;  // early return: skip signature verification
}
```

Depending on Quarkus REST path configuration, `getPath()` returns the path
WITH a leading slash (`/webhooks/github/pull-request`), which does NOT
start with `webhooks/`. Filter early-returns, verification is skipped.

**Fix (already merged, PR #21):** Strip leading slashes before the
startsWith check.

```java
String path = ctx.getUriInfo().getPath();
while (path.startsWith("/")) {
    path = path.substring(1);
}
if (!path.startsWith("webhooks/")) { return; }
```

**Upstream impact:** If you're running upstream Eneve in production with
`webhook.secret.*` set, test that a known-bad signature is being rejected.
If you get 200 OK with a jobId instead of 401, you have this bug. Report
upstream.

---

## INFRA-OPEN: Swarm `env_file:` is only re-read on `docker stack deploy`

**Symptom:** Manual edits to `/opt/aiw-code-agent/.env` don't reach the
running container. You change `ANTHROPIC_MODEL=groq-llama-3.3-70b`, force
a restart via `docker service update --force aiw-code-agent_app`, and the
container still has the old value in its env. `docker exec ... env` shows
the stale value.

**Root cause:** Swarm's `env_file:` directive is evaluated **client-side**
at `docker stack deploy` time. The file contents are inlined into the
service spec at that moment. Subsequent changes to the file on disk do NOT
propagate. `docker service update --force` restarts the container using
the **existing** spec — it never re-reads the file.

**Fix:** Use `docker stack deploy -c docker-stack.aiw.yml aiw-code-agent`
instead of `docker service update --force`. The stack deploy re-reads the
stack file AND the env_file every time.

**Where this bit us:** The GitHub App token refresh sidecar originally
used `docker service update --force`. That meant every 50-minute refresh
updated the `GITHUB_TOKEN` in `.env`, but the container never saw the
update because the old env was baked into the spec. Simultaneously,
manual `.env` tweaks I made between sessions were invisible.

**Status:** `scripts/aiw-refresh-github-token.py` updated to use
`docker stack deploy`. This is the canonical way to push `.env` changes
to the running service. Document this prominently.

**Related gotcha:** `docker stack deploy` also interpolates `${VAR}`
references in the stack file at deploy time from the SHELL environment,
NOT from the env_file. So a stack file that says
`POSTGRES_PASSWORD: ${DATABASE_PASSWORD:-default}` will evaluate to
`default` if the shell doesn't have `DATABASE_PASSWORD` set — even if
`env_file: .env` contains it. Either `source .env` before `stack deploy`,
or hardcode critical values directly in the stack file's `environment:`
block.

---

## INFRA-FIXED: Traefik 3.5 silently skips services with deprecated labels

**Symptom:** Your Swarm service has all the right `traefik.http.*` labels
under `deploy.labels`, it's on the same network as Traefik, but Traefik
returns 404 for every request and the Traefik logs don't mention your
service at all.

**Root cause:** Having BOTH `traefik.swarm.network=<net>` AND the
deprecated `traefik.docker.network=<net>` labels on the same Swarm service
makes Traefik 3.5's swarm provider log a deprecation warning for
`traefik.docker.*` and then **silently skip the entire service**. No
routers, no services, no health check, nothing.

**Fix (already merged, PR #20 + Traefik fix commit):** Use ONLY
`traefik.swarm.network=<net>`. Drop `traefik.docker.network` entirely.

**Verification:** If Traefik isn't picking up your service, run:

```bash
ssh root@vps 'docker service logs traefik_traefik 2>&1 | grep -iE "deprecated|your-service-name"'
```

If you see "Labels traefik.docker.* for Swarm provider are deprecated",
that's the bug.

---

## INFRA-OPEN: Swarm service DNS collision with other stacks

**Symptom:** `docker service ls` shows `postgres_postgres` running in a
different stack. Your stack declares `postgres:` as its DB service. Inside
your stack's app, JDBC connects to `postgres:5432` and gets
"password authentication failed for user X". The credentials are correct.

**Root cause:** Swarm DNS is cluster-wide. When your app resolves
`postgres`, it can hit EITHER your stack's postgres OR any other
postgres-named service on a shared overlay network (like `agent-net`).
If it hits the wrong one, passwords don't match, auth fails.

**Fix:** Always use the **fully-qualified** stack-prefixed service name:

```yaml
DATABASE_URL: jdbc:postgresql://aiw-code-agent_postgres:5432/aiw_code_agent
```

NOT:

```yaml
DATABASE_URL: jdbc:postgresql://postgres:5432/aiw_code_agent
```

**Where this bit us:** Wasted ~45 minutes debugging a phantom password
mismatch. md5 hashes matched on both sides, psql auth from inside the
postgres container worked, but the Java app failed. The agent was
connecting to a completely different postgres.

---

## INFRA-OPEN: `host.docker.internal` doesn't work in Swarm overlays

**Symptom:** In docker-compose you could use `extra_hosts:` with
`host.docker.internal:host-gateway` to reach host services from inside
containers. In Swarm, the same technique fails with
`Could not resolve host: host.docker.internal`.

**Root cause:** Swarm overlay networks are isolated from host networking.
The `host-gateway` magic is a compose-only feature.

**Fix:** Create a dedicated attachable overlay and connect the non-Swarm
host service (e.g. LiteLLM, which runs as a standalone docker container)
to it:

```bash
docker network create -d overlay --attachable aiw-llm-net
docker network connect aiw-llm-net litellm
```

Then in the stack file, declare the network as external and attach your
Swarm service to it:

```yaml
services:
  app:
    networks:
      - aiw-llm-net
    environment:
      ANTHROPIC_BASE_URL: http://litellm:4000  # resolves via overlay DNS

networks:
  aiw-llm-net:
    external: true
```

---

## P1-OPEN: LiteLLM `fast` model group round-robins to small-context providers

**Symptom:** Agent review jobs randomly fail with
`ContextWindowExceededError: Current length is 10457 while limit is 8192`
or similar. Sometimes they succeed, sometimes they don't. Failure rate
roughly 2 in 3.

**Root cause:** The LiteLLM `fast` model group has three backends:
- `groq/llama-3.1-8b-instant` (8k context)
- `cerebras/llama3.1-8b` (8k context)
- `sambanova/Meta-Llama-3.1-8B-Instruct` (16k context)

LiteLLM round-robins between them. The code-agent's system prompt is
~67k tokens (default Eneve), which overflows all three. The original
Groq llama-3.3-70b (which has 128k context) would handle it fine, but
it's not in the `fast` group.

**Fix (workaround):** Pin the agent to a single-backend model with
large context:

```
ANTHROPIC_MODEL=groq-llama-3.3-70b
ANTHROPIC_FAST_MODEL=groq-llama-3.3-70b
```

**Proper fix (not done):** Either
1. Shrink the agent's system prompt (it's probably overloaded with
   tool schemas that aren't all needed per job), or
2. Add a dedicated `large-fast` model group in LiteLLM that only has
   128k+ backends, or
3. Remove the 8k/16k backends from the `fast` group.

---

## P1-OPEN: Agent deep-review loop hits Groq `max_tokens` constraint

**Symptom:** PR summary comment posts successfully, but then the deep
review loop fails with:

```
litellm.BadRequestError: GroqException - {"error":{"message":"`max_tokens` must be ..."}}
```

**Root cause:** The code-agent's `ClaudeToolUseLoop` requests
`max_tokens=65536` (default for Claude Sonnet 4's output budget). Groq's
llama-3.3-70b caps output at 32768 tokens. The request is rejected at
the provider level.

**Workaround (not yet applied):** Cap `max_tokens` in
`ClaudeToolUseLoop.java` behind a config flag. One-line patch, but
requires another deployment cycle.

**Impact:** Low. The PR summary comment (the primary human-visible
output) is generated by `PrSummaryGenerator` which uses smaller
`max_tokens` and works fine. The deep review loop would add inline
line-level comments on the PR, which is nice but not critical for v1.

**Status:** Open, tracked. Fix is trivial but requires session time.

---

## P2-OPEN: Bot comment attribution shows installer, not the bot

**Symptom:** PR comments posted by the agent show as "by
IvanWeissVanDerPol" (the person who installed the App) instead of
"by hermes-bot-aiwhispereres[bot]". GitHub does NOT display the bot
name even though the request was authenticated with an installation
token.

**Root cause:** `GitHubPlatformService` uses the REST API with
`Authorization: token <installation-token>`. When GitHub receives a
REST API call authenticated with an installation token, it attributes
the resulting action to the App installer (i.e. the user who clicked
"Install App"), not to the App itself.

**Fix:** Switch to GitHub's GraphQL API with
`Authorization: Bearer <JWT>` using the App's private key directly.
GraphQL comments made with an App JWT are attributed to the App
(`<app-slug>[bot]`). Requires rewriting the comment-creation path in
`GitHubPlatformService`. ~50 lines of Java.

**Impact:** Cosmetic but highly visible — every PR comment this agent
posts looks like Ivan wrote it. Confusing for reviewers. Should be
fixed before any external client sees the output.

---

## INFRA-OPEN: Quarkus SmallRye config rejects empty `${VAR:}` substitutions

**Symptom:** On first boot, the agent crashes with:

```
Failed to load config value of type class java.lang.String from ...
```

**Root cause:** Some Quarkus properties use `${VAR:}` (empty default)
syntax. In certain SmallRye versions / property types, this is
rejected as "no value available" even though an empty string should
be valid.

**Fix:** Set the env var to a non-empty dummy value. For
`SETTINGS_ENCRYPTION_KEY`:

```
SETTINGS_ENCRYPTION_KEY=0000000000000000000000000000000000000000000000000000000000000000
```

(32-byte hex, literal zeros is fine for dev; use real random hex for
production.)

---

## INFRA-FIXED: Flyway + `CREATE INDEX CONCURRENTLY` deadlock

**Symptom:** First boot hangs indefinitely at migration V80
(`CREATE INDEX CONCURRENTLY idx_job_history_payload_gin`). Flyway logs
stop. `psql` shows PID holding advisory lock as `idle in transaction`
while another PID is waiting on `virtualxid`.

**Root cause:** Flyway acquires a pg advisory lock for the duration of
the migration run. That lock is held in a transaction that stays open
until the migration completes. `CREATE INDEX CONCURRENTLY` cannot run
inside a transaction AND waits for all other transactions to finish
before taking its own lock. So: Flyway's lock blocks CIC, CIC never
finishes, Flyway never releases. Deadlock.

**Fix (already merged, PR #16):** Strip `CONCURRENTLY` from V80. On an
empty fresh DB the index is created instantaneously; on an in-place
upgrade it takes a brief exclusive lock on `job_history`. The trade-off
is acceptable for reliable first-boot.

---

## INFRA-FIXED: pgvector missing from `postgres:16-alpine`

**Symptom:** Flyway migration V12 fails with:
```
ERROR: extension "vector" is not available
```

**Root cause:** Upstream `docker-compose.yml` uses `postgres:16-alpine`
which doesn't include the pgvector extension. The agent's knowledge
store + code embeddings depend on pgvector.

**Fix (already merged, PR #16):** Swap to `pgvector/pgvector:pg16` in
`docker-stack.aiw.yml`.

---

## INFRA-FIXED: CRLF line endings break Docker shebangs

**Symptom:** Container starts then immediately dies with:
```
exec /entrypoint.sh: no such file or directory
```

**Root cause:** The repo was edited on Windows at some point, leaving
CRLF line endings on `entrypoint.sh` and `mvnw`. Bash reads the shebang
as `#!/bin/bash\r` which isn't a valid interpreter path.

**Fix (already merged, PR #16):** Strip CRLF from all shell scripts:

```bash
find . -name '*.sh' -o -name 'mvnw' | while read f; do
  file "$f" | grep -q CRLF && sed -i 's/\r$//' "$f"
done
```

Pre-commit hook would prevent recurrence. Not currently configured.

---

## INFRA-OPEN: `docker compose restart` doesn't re-read env files

**Symptom:** You edit `.env`, run `docker compose restart app`, and the
container still has the old env.

**Root cause:** `restart` just signals the container; it doesn't create
a new one. New env requires a fresh container.

**Fix:** Use `docker compose up -d --force-recreate` instead.

**Related:** In Swarm, use `docker stack deploy` (see the env_file
gotcha above).

---

## P1-OPEN: Anthropic SDK sends `cache_control` to non-Anthropic providers

**Symptom:** Agent fails every LLM call with:
```
property 'cache_control' is unsupported
```
when routed through LiteLLM to Groq / Mistral / Cerebras / SambaNova.

**Root cause:** The Anthropic Java SDK always attaches
`cache_control: ephemeral` blocks for prompt caching. Non-Anthropic
providers don't understand this field and reject the request.

**Fix (two layers of defense, both applied):**

1. Agent-side (PR #18): gated `.cacheControl()` calls behind
   `anthropic.prompt-cache.enabled=false` config flag. Set in
   `.env` for the AIW deployment.

2. LiteLLM-side: add `drop_params: true` and
   `additional_drop_params: [cache_control, web_search_options, anthropic_version, thinking]`
   to every model entry in `/opt/litellm/config.yaml`. Use
   `scripts/apply-aiw-drop-params.py` to apply idempotently.

Both layers should be active. If only one fails, the other still works.

---

## Appendix: How to add a new known issue

1. Copy the template below
2. Add it under the right severity heading
3. Commit on a `docs/` branch and open a PR

```markdown
## <SEVERITY>-<STATUS>: Short descriptive title

**Symptom:** What the user sees.

**Root cause:** Why it happens (reference file + line number if code).

**Fix:** Exact steps to resolve. Include commands.

**Impact:** Who is affected, how bad.

**Status:** Fixed in PR #X / workaround applied / open.
```

Keeping this list alphabetical-ish within each severity section helps
with grep.
