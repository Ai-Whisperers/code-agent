# Client profiles

Per-client configuration for the AIW Code Agent. Each file here documents a single target repository's conventions, commands, protected paths, and agent limits. When the agent boots, it seeds `repo_settings` (and related tables) from these profiles.

## Pilot clients

| Client | Repo | Stack | Profile | Purpose |
|---|---|---|---|---|
| **Vete** | `Ai-Whisperers/Vete` | Next.js 15 + Supabase + TS | [`vete.md`](./vete.md) | Multi-tenant SaaS, security-sensitive (tenant isolation) |
| **Solstein** | `Ai-Whisperers/solstein` | Python + FastAPI + pgvector | [`solstein.md`](./solstein.md) | Big refactor candidate in "critical" state, careful mode |

## Why these two first

- **Vete** is our biggest active TypeScript codebase (~580K LOC, 2 live beta clients on Terrapet + CavillPet). It has clear rules already codified in its `CLAUDE.md`, which the agent can adopt verbatim. It's also security-sensitive (multi-tenant RLS) which stress-tests the agent's guardrails in the right way.
- **Solstein** is our biggest active Python codebase (~110K LOC, 636 files). It has an **Autoresearch Protocol** documented in `.hermes.md` that forces small, measured changes — a perfect fit for an AI agent that needs discipline. It also has 132 READY stories waiting in a queue, so there's immediate meaningful work.

## What each profile contains

1. **Repository metadata** — where it lives, default branch, size, stack
2. **Tenancy / architecture rules** — critical invariants the agent must never break
3. **Forbidden things** — hard refusal rules extracted from the repo's own CLAUDE.md / .hermes.md
4. **Language rules** — UI language, commit style, comment language
5. **Build / test / lint commands** — with annotations on which the agent should prefer
6. **Protected paths** — paths the agent MUST NOT write to
7. **Allowed shell commands** — command allowlist for the sandbox
8. **Caps** — max-files, max-lines, max-iterations, timeout
9. **Reviewer preferences** — seed memories for the learning extractor
10. **Issue assignment / auto-fix triggers** — when the agent should act autonomously
11. **Quality-report schedule** — cron cadence
12. **Notifications** — default and escalation Hermes targets
13. **Known pain points** — things the agent WILL trip over, documented up front

## How the agent consumes these

Once the LiteLLM + Linear + Supabase integrations land (see `docs/AIW-CUSTOMIZATION-PLAN.md`), the bootstrap script will:

1. Parse each `.md` profile (or a YAML sidecar for the structured fields) and POST to `/api/repos/{workspace}/{slug}` on the running agent.
2. Seed the `memories` table with the reviewer preferences listed in § 9 — so the learning extractor doesn't have to re-derive them from PR comments.
3. Register webhooks on the target repo pointing at the agent's `/webhooks/github/*` endpoints.
4. Schedule the quality-report cron jobs per § 11.

Until then, these files are **the single source of truth** for how the agent should treat Vete and Solstein. Update them as you learn things.

## Adding a new client

1. Copy one of the existing profiles.
2. Fill in every section — be specific about forbidden things and protected paths.
3. Add an entry to the table at the top of this file.
4. Open a PR on the `code-agent` repo. Ivan reviews.
5. If the repo has a `CLAUDE.md` / `.hermes.md` / `.cursor/rules/`, import the conventions verbatim — don't paraphrase.

## Why not just read the target repo's CLAUDE.md at runtime?

The agent does read it (that's what the `rules.auto-read-target-repo=true` setting is for). But:

- CLAUDE.md is written for interactive human+LLM sessions, not for an autonomous agent — it's missing things like caps, timeouts, and allowed-command lists.
- CLAUDE.md doesn't distinguish "gentle suggestion" from "hard refusal" — the profiles here do.
- The profiles here are the audit trail of **what we told the agent it could do on behalf of our clients**. That's a compliance artefact we want versioned in the `code-agent` repo, not scattered across target repos.
