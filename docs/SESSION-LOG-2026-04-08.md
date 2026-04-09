# Session Log — 2026-04-08 → 2026-04-09

> Narrative record of the multi-day session that took the code-agent from
> "freshly-cloned upstream Eneve fork" to "production-deployed GitHub
> App bot with verified end-to-end webhook handling on photos-to-kml and
> Vete."
>
> This doc is intentionally conversational. For the precise state snapshot
> see `DEPLOYMENT-NOTES.md`. For how to redo it from scratch see
> `RUNBOOK.md`. For what to do next see `NEXT-STEPS.md`.

## TL;DR

**Input:** cloned upstream Eneve code-agent, zero AIW branding, zero
deployment infrastructure, zero clients connected.

**Output (end of session):**

- 11 PRs merged to `aiw/main` (code, docs, deploy, security, auth)
- Docker Swarm stack running on VPS `72.61.44.159`
- `https://code-agent.sunstein.cloud` live with HTTPS + Let's Encrypt
- GitHub App `hermes-bot-aiwhispereres` installed on all AIW repos
- Systemd timer auto-refreshing installation tokens every 50 minutes
- Postgres backup cron running daily at 03:00
- LiteLLM virtual key with $25/30d budget cap
- Two full end-to-end smoke tests green: photos-to-kml PR #4, Vete PR #64
- Both test PRs closed cleanly after verification
- Three new AIW adaptation docs: RUNBOOK, OPERATIONS, KNOWN-ISSUES
- Original project plan committed as NEXT-STEPS.md

**Biggest discovery:** upstream Eneve's webhook signature filter had a
silent P0 bypass bug (PR #21). Caught during smoke testing. Worth
reporting upstream.

## PR tally

| # | Title | Summary |
|---|---|---|
| #12 | feat(litellm) | Route Anthropic SDK through LiteLLM via `baseUrl()` override. |
| #13 | feat(notifications) | `NotificationDispatcher` interface + `HermesGatewayNotifier` implementation. Replaces direct Teams/n8n calls. |
| #14 | feat(detector) | Python project detection (pyproject.toml / uv / poetry / pip). |
| #16 | feat(detector+deploy) | Monorepo subdir auto-detect + Next.js archetype + pgvector image + CRLF fix + Flyway V80 deadlock fix + `ApiKeyFilter.preMatching=true`. |
| #17 | feat(security) | `dev.auth.bypass` flag for local testing without Keycloak. |
| #18 | feat(anthropic) | Optional `cache_control` (gated by config) for multi-provider gateway compatibility. |
| #19 | docs: next-steps | Full phased roadmap (Phase 0 → 7). |
| #20 | feat(deploy): Swarm | Swarm stack + backup cron + deployment notes + Traefik label fix. |
| #21 | fix(security): P0 | Webhook signature verification bypass (path prefix check with leading slash). |
| #22 | feat(auth) | GitHub App installation token refresh sidecar + systemd timer. |
| (this doc) | docs: session closeout | RUNBOOK + OPERATIONS + KNOWN-ISSUES + session log. |

All 11 merged to `aiw/main`. Zero open PRs at end of session.

## Timeline of what I learned

### Day 1 — Infrastructure surprises

**Expected:** plug in LiteLLM URL, swap JIRA for Linear, done. Ship it.

**Actual:** the Anthropic Java SDK sends `cache_control`, `web_search_options`,
`thinking`, and `anthropic_version` fields in every request. LiteLLM
passes these through verbatim to the downstream provider. Groq, Cerebras,
SambaNova, and Mistral all reject any unknown field with 400. The only
way to route through LiteLLM successfully is to add
`drop_params: true` + `additional_drop_params: [...]` to every model
entry in LiteLLM's config. I wrote `apply-aiw-drop-params.py` to
do this idempotently. PR #18 also gates `cache_control` in the Java
code as belt-and-braces.

The upstream Eneve team has never needed this because they run against
real Anthropic, not a multi-provider gateway.

**Expected:** docker-compose deployment with `host.docker.internal` for
reaching LiteLLM from the agent container.

**Actual:** the VPS runs Docker Swarm, not plain Docker. Swarm overlays
are strictly isolated from host networking. `host.docker.internal` does
NOT resolve inside a Swarm service container. I had to create an
`attachable` overlay called `aiw-llm-net` and `docker network connect`
the non-Swarm `litellm` container to it. Then the Swarm service can
reach LiteLLM via its service name.

Also: the VPS already runs `vete_web` as a Swarm stack AND has
`postgres_postgres` as a top-level stack. Swarm DNS is cluster-wide,
so resolving `postgres` from inside my stack may hit the wrong service.
Fix: use fully-qualified `aiw-code-agent_postgres` in `DATABASE_URL`.

**Expected:** Flyway migrations run smoothly on a fresh DB.

**Actual:** migration V80 hung indefinitely because it uses
`CREATE INDEX CONCURRENTLY`, which can't run inside a transaction AND
waits for all open transactions to finish. Flyway holds its advisory
lock inside an open transaction for the duration of the migration
run. Classic deadlock. Fix: drop `CONCURRENTLY` on V80 — the index is
instantaneous on an empty table anyway.

Also: `postgres:16-alpine` doesn't include pgvector, which V12 needs.
Swapped to `pgvector/pgvector:pg16`.

Also also: `entrypoint.sh` and `mvnw` had CRLF line endings. Docker's
kernel interpreted the shebang as `#!/bin/bash\r`, which isn't a valid
interpreter path, and the container died immediately with "no such
file or directory" referring to the entrypoint itself. Stripped CRLF.

### Day 2 — The Traefik mystery

**Expected:** Traefik sees the swarm service labels and auto-routes.

**Actual:** Traefik completely ignored my service. Zero log lines
mentioning it. Labels looked identical to `vete_web`'s working labels.
Router rule, service label, port mapping — all correct. Traefik's
swarm provider just... didn't see it.

After a LOT of debugging, root cause: having BOTH
`traefik.swarm.network=agent-net` AND the deprecated
`traefik.docker.network=agent-net` labels on the same service makes
Traefik 3.5's swarm provider log a deprecation warning for
`traefik.docker.*` — and then **silently skip the entire service**.
Not an error. Not a warning about skipping. Just... nothing.

Removed the `traefik.docker.network` label. Traefik picked it up
within 2 seconds.

Added to KNOWN-ISSUES.md. This one cost the most wall-clock time of
any gotcha in the session.

### Day 3 — The P0 security bug

**Expected:** test the webhook chain with a real GitHub App + real HMAC
signature, everything works, move on.

**Actual:** during the test, I noticed that **any** POST to
`/api/webhooks/github/pull-request` was being accepted by the app, even
with a garbage `X-Hub-Signature-256: sha256=deadbeef...` header. Both
valid and invalid signatures returned 200 with a jobId.

Dug into `WebhookSignatureFilter.filter()`. The code does:

```java
String path = ctx.getUriInfo().getPath();
if (!path.startsWith("webhooks/")) {
    return;  // early return
}
```

On my deployment, `ctx.getUriInfo().getPath()` returns
`/webhooks/github/pull-request` — with a leading slash. That doesn't
match `"webhooks/"`. So the filter early-returns WITHOUT verifying the
signature. Every webhook is accepted.

This is a **P0 in any deployment where Quarkus delivers paths with a
leading slash** — which is the default in many Quarkus versions. I
can't tell if upstream Eneve is affected without testing their deployed
version, but it seems likely.

PR #21: one-line fix (strip leading slashes before the check). Verified
end-to-end: bad signature now returns 401, good signature returns 200.

Worth reporting upstream to Eneve.

### Day 4 — GitHub App dance

**Expected:** create a GitHub App, scp the private key, mint a JWT,
trade for installation token, done.

**Actual:** GitHub's `/app` REST endpoint does NOT return `webhook_url`
or webhook_active fields, so my diagnostic script kept reporting "no
webhook configured" even when it was. Had to use `/app/hook/deliveries`
to verify the webhook was actually being posted. Lesson: trust the
delivery log, not the metadata endpoint.

Also: the `.pem` file was accidentally shared in chat at one point,
causing a security scare. I shredded all local copies and advised the
user to rotate. Then the "rotated" key turned out to be the **jwt.io
test key** (the well-known public example RSA key), not the real one.
So we regenerated, scp'd, and verified the SHA256 fingerprint matched
what GitHub displayed. Lesson: always compare fingerprints.

Bot attribution is a pain: GitHub REST API with installation token
attributes all actions to the installer, not the bot. To get
`hermes-bot-aiwhispereres[bot]` attribution, we'd need to use the
GraphQL API with the App JWT directly. Noted in KNOWN-ISSUES.md as P2.

### Day 5 — The env_file reload footgun

**Expected:** update `.env`, force-restart the service, new env loaded.

**Actual:** no. **Swarm's `env_file:` directive is evaluated
client-side at `docker stack deploy` time.** The file contents are
inlined into the service spec at that moment. `docker service update
--force` restarts the container using the **existing** spec — it does
NOT re-read the file.

This caused:
1. My manual model swap (`ANTHROPIC_MODEL=groq-llama-3.3-70b`) to be
   invisible to the container, so the agent kept hitting SambaNova 16k
   context overflow.
2. The GitHub App token refresh sidecar's updates to `GITHUB_TOKEN` to
   be invisible, so tokens would silently expire.

Fixed by patching `aiw-refresh-github-token.py` to use
`docker stack deploy` instead of `docker service update --force`.
Also documented prominently in DEPLOYMENT-NOTES.md, OPERATIONS.md, and
KNOWN-ISSUES.md because this is a trap that bit me for hours and will
bite future-me if not loud enough.

### Day 5 evening — First real PR review

Opened PR #4 on `photos-to-kml` (the bot opened it via the installation
token + REST API). Webhook arrived, Traefik routed, signature verified,
agent cloned, Claude reasoned, PR comment posted — 5 seconds
end-to-end. Milestone.

Then same drill on Vete PR #64. 580K LOC repo, Next.js in `web/`
subfolder. Monorepo auto-detect from PR #16 correctly picked up
`web/README.md` as the diff target. Agent read the real Vete CLAUDE.md,
understood it's a Next.js 15 + Supabase multi-tenant platform, and
wrote a PR summary that showed genuine comprehension of the change.

Both test PRs closed without merging. Both branches deleted.

## What worked better than expected

- **The detector pattern** from PR #14/#16 Just Worked on Vete's
  monorepo layout. The subdir walk found `web/package.json` with the
  Next.js dep and the review handler automatically operated there.
- **PR #18's cache_control gating** — combined with LiteLLM's
  `drop_params`, neither half alone would have been enough, but both
  together give robust compatibility across every provider.
- **Swarm's `start-first` update strategy** — zero-downtime rolling
  restarts even on 8-12 GB container updates.
- **The NotificationDispatcher abstraction from PR #13** — when the
  Teams and n8n notifiers both errored on startup (unconfigured URLs),
  the dispatcher cleanly swallowed their exceptions and let the Hermes
  notifier run. Exactly the fan-out failure isolation that was
  designed in.

## What didn't work

- **Load balancing across heterogeneous-context models.** LiteLLM's
  `fast` group has Groq 8k + Cerebras 8k + SambaNova 16k as backends.
  The agent's system prompt is ~67k tokens. Every round-robin to a
  non-Groq-128k backend blows up. Had to pin `groq-llama-3.3-70b`
  instead. Proper fix is either shrinking the system prompt or adding
  a dedicated `large-context` model group in LiteLLM.
- **Deep review loop still fails on Groq `max_tokens`.** PR summary
  comment works (that's the human-visible 90%), but the follow-up loop
  that reads individual files and generates inline line-level comments
  hits Groq's `max_tokens` cap. Open. Fix is trivial (one-line Java
  patch to cap `max_tokens` at Groq's limit) but wasn't done in-session.
- **Bot comment attribution.** Agent comments show as "by
  IvanWeissVanDerPol" instead of "by hermes-bot-aiwhispereres[bot]".
  REST API + installation token is the wrong auth path for bot-style
  attribution. Needs a GraphQL rewrite in `GitHubPlatformService`.

## Open tasks for next session

In rough priority order, from most value to least:

1. **Cap `max_tokens` in `ClaudeToolUseLoop`** so Groq accepts the
   deep-review-loop requests. Unlocks line-level inline comments.
   ~30 min of Java + one deploy.

2. **Solstein smoke test**. Same drill as Vete PR #64 but against
   `Ai-Whisperers/solstein`. Tests Python detector + `uv` + Autoresearch
   Protocol rules. ~15 min.

3. **Bot attribution via GraphQL**. ~60 min. Nice to have, needs proper
   testing.

4. **Phase 3 Supabase Auth**. Remove `DEV_AUTH_BYPASS`. 1–2 days.

5. **Phase 6.2 Observability** — hook Langfuse into the tool-use loop.

6. **Phase 7 cleanups** — rebrand, drop Bitbucket/ADO, drop Aikido,
   Linear adapter. No runtime impact, pure tech debt reduction.

## For the next session runner

1. Read `docs/RUNBOOK.md` start to finish before changing anything on
   the VPS.
2. Read `docs/KNOWN-ISSUES.md` after hitting any error. Don't debug
   from first principles if we already solved it.
3. Read `docs/OPERATIONS.md` for the right command to use when
   updating the env.
4. **Never use `docker service update --force`** on `aiw-code-agent_app`
   without `docker stack deploy` first. Everything breaks silently.
5. **Never paste private keys in chat.** Use scp. I did this once and
   had to burn a key rotation. Learn from my mistake.
6. If something says "fast" alias is failing on context limits, don't
   investigate — just pin `groq-llama-3.3-70b` directly.
7. If Traefik ignores your service, remove `traefik.docker.network`
   from your labels. Always.
8. If the signature filter bypasses silently, check whether the path
   has a leading slash. See PR #21.

---

*Session concluded April 9, 2026. Next session: open tasks above.*
