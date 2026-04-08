# Client Profile: Vete

> Multi-tenant SaaS veterinary clinic management platform. First pilot client for the AIW Code Agent.

## Repository

| Field | Value |
|---|---|
| **GitHub** | `Ai-Whisperers/Vete` (public) |
| **Default branch** | `main` |
| **Size** | 152 MB (~580K LOC) |
| **Language** | TypeScript |
| **Stack** | Next.js 15 (App Router) · Supabase (PostgreSQL + Auth + Storage) · Drizzle ORM · TanStack Query · Tailwind · Vitest · Playwright |
| **Deploy** | Vercel (primary) + GCP (fallback) |
| **Observability** | Sentry · Vercel Analytics · Vercel Speed Insights · Upstash Redis (rate limiting) |
| **Auth** | Supabase Auth (SSR) |
| **Monorepo layout** | Root has ops/docker/docs; **real app lives in `web/`** |
| **Topics** | healthcare · latam · nextjs · saas · typescript · veterinary |
| **Beta clients** | Terrapet (live), CavillPet (live), PetLife (test tenant) |

## Tenancy model (CRITICAL)

Vete is **multi-tenant** — every data access must filter by `tenant_id`. The routing is `/[clinic]/...` and each tenant has its own subdomain on Vercel.

**Rules the agent MUST enforce on every code change:**

1. **Every DB query includes `.eq("tenant_id", tenantId)`** — no exceptions. Missing tenant filter = data leak = P0 security bug.
2. **Every table has Row-Level Security (RLS)** policies. When creating a migration, it must include RLS setup.
3. **Every API route starts with**: (1) auth check via `supabase.auth.getUser()`, (2) tenant lookup from `profiles`, (3) query scoped by `tenant_id`. See `CLAUDE.md` § "API Routes (Required Pattern)".
4. **Roles**: `owner` (pet owner), `vet`, `admin`. Access gates use `is_staff_of(tenant_id)`.

## Forbidden things (⛔)

From the repo's `CLAUDE.md` — these are hard rules, the agent must refuse PRs that violate them:

- `⛔ NEVER upgrade Tailwind to v4` — JSON scanning breaks the build
- `⛔ NEVER create tables without RLS policies`
- `⛔ NEVER hardcode colors` — always use `var(--primary)`, `var(--bg-card)`, `var(--text-primary)` etc.
- `⛔ NEVER skip tenant_id in queries`
- `⛔ NEVER use raw SQL in components` — use Supabase client
- `⛔ NEVER commit .env files or credentials`
- `⛔ NEVER silently swallow errors` in catch blocks — always log + rethrow, log + return structured error, or log warning + document the fallback

## Language rules

- **All UI text in Spanish** (Paraguay / LatAm market)
- Error messages returned from API: Spanish (e.g., `"No autorizado"`, not `"Unauthorized"`)
- Code comments: English OK
- Commit messages: English, conventional commits

## Build, test, and lint commands

All run inside the `web/` directory.

```bash
cd web/

# Build (needs 8GB RAM — agent's build runner must respect this)
NODE_OPTIONS='--max_old_space_size=8192' npm run build

# Lint — IMPORTANT: use the relaxed target, not --strict
npm run lint                  # eslint --max-warnings 800   ← use this
# npm run lint:strict         # eslint --max-warnings 0     ← do NOT use for agent runs
npm run lint:fix              # auto-fix what we can

# Type check
npm run typecheck             # tsc --noEmit

# Format
npm run format:check          # agent should fail PR if this fails

# Tests — start with :unit, escalate as needed
npm run test                  # = test:unit (fast, covered)
npm run test:unit             # vitest tests/unit tests/services --coverage
npm run test:integration      # vitest integration config
npm run test:api              # vitest api config
npm run test:components       # vitest components config
npm run test:database         # vitest database config
npm run test:e2e              # playwright
npm run test:smoke            # unit + public e2e (fast pre-merge gate)
npm run test:critical         # @critical tagged tests + playwright --grep '@critical'
npm run test:all              # unit + integration + api + e2e (CI-only, slow)

# Feature-scoped tests (agent should use these when PR only touches one area)
npm run test:feature:pets
npm run test:feature:booking      # booking | appointment
npm run test:feature:vaccines
npm run test:feature:inventory
npm run test:feature:finance      # finance | expense
npm run test:feature:medical      # medical-records | prescription
npm run test:feature:store
```

**Agent default test order for a fix PR:**

1. `npm run lint` (must pass, 800-warning baseline)
2. `npm run typecheck` (must pass, 0 errors)
3. Auto-detect changed feature → run `test:feature:*` if possible
4. Otherwise `npm run test:unit`
5. If confidence is low or the change touches `app/api/*`, also run `test:api`
6. Pre-merge only: `test:smoke`

## Protected paths (agent MUST NOT write to these)

```
web/.content_data/**              # JSON-CMS per clinic — touched only by content team
web/db/migrations/[0-9][0-9]_*    # numbered migrations are immutable once merged
web/.env*                         # secrets
supabase/migrations/**            # Supabase migrations mirror
.github/workflows/**              # CI config — only by humans
vercel.json                       # deploy config
sentry.*.config.ts                # observability keys are env-sourced but config is sensitive
scripts/migrate-*                 # one-shot DB scripts
```

## Allowed shell commands

```
npm, npx, pnpm, node,
git (diff|status|log|add|commit|push|pull|fetch|branch|stash|restore|reset|checkout),
ls, find, cat, grep,
supabase (db|migration|gen),
drizzle-kit
```

**Forbidden:** `rm -rf`, `npm run db:clean`, `npm run seed:clear`, `npm run reset-dev` (Windows-only, destroys dev state), any direct `psql` / `pg_dump` / `supabase db reset`.

## Caps for the agent

| Setting | Value | Rationale |
|---|---|---|
| `run-fix.max-files-changed` | **15** | Vete PRs touch more files than a typical bug-fix because of the multi-layer pattern (route → service → hook → component → test) |
| `run-fix.max-lines-changed` | **800** | Same reason |
| `run-fix.max-loop-iterations` | **150** | Default |
| `run-fix.self-review.enabled` | `true` | Mandatory — security-sensitive |
| `run-fix.self-review.max-iterations` | **20** | Higher for security checks on tenant isolation |
| `run-fix.job-timeout-minutes` | **45** | Build alone can take 5+ min |

## Reviewer preferences (seed the learning extractor)

Pre-populate `memories` table with these team preferences so the agent doesn't have to learn them:

- "Prefer Server Components over Client Components. Only add `'use client'` when genuinely needed (state, events, browser APIs)."
- "Use TanStack Query for client-side data fetching, never plain `fetch` + `useEffect`."
- "Use `interface` not `type` for object shapes. Use `type` only for unions and primitives."
- "All colors come from CSS variables. `bg-[var(--bg-card)]`, not `bg-white`."
- "UI text in Spanish. Error messages in Spanish. Code in English."
- "Every catch block: log with `[Module/fn]` prefix, then either rethrow or return structured error. Never silent."
- "Use `BaseService` pattern in `web/lib/services/` — don't put business logic in routes."
- "Database access goes through the Supabase client, never raw SQL in components."
- "Prefer `async/await` over `.then()` chains."
- "Test files live beside the code or in `web/tests/**`. Use `describe`/`it`, not `test()`."

## Issue assignment

Linear team: `VETE` (once Linear integration ships).
GitHub Issues label the agent watches: `aiw-agent` or the Linear mirror label.

Auto-fix triggers on:
- Issues labeled `aiw-agent` or `bug` + `priority:high`
- Dependabot PRs (auto-merge if tests pass and diff is small)
- Sentry issues sent via webhook (once wired)

## Quality-report schedule

| Report | Frequency | Branches |
|---|---|---|
| Quality snapshot | Daily at 06:00 America/Asuncion | `main`, `develop` |
| Coverage trend | Daily | `main` |
| Dependency upgrade plan | Weekly (Monday 08:00) | `develop` |
| Tech-debt heatmap | Weekly | `main` |

## Notifications

Default target: `telegram:@vete-alerts` (Hermes gateway).
Escalation: `telegram:ivan` for P0 security issues (tenant-leak, RLS missing, auth bypass).

## Docs generation

- API docs → `web/docs/api.md` (PR to repo, not Confluence)
- Architecture diagrams → `web/docs/architecture.md` (Mermaid)
- Changelog → `CHANGELOG.md` (conventional commits)

## Known pain points the agent will hit

1. **Build is slow and memory-hungry** (8GB). Don't run `build` speculatively — only when the PR changes code that requires it.
2. **Tests are fragmented across 6+ vitest configs**. Auto-detecting which config to run from the changed files is non-trivial. Start simple: always `test:unit`, escalate if touched files are in `app/api/*` → `test:api`, `components/*` → `test:components`, `db/*` → `test:database`.
3. **ESLint baseline is 800 warnings**. Do not attempt to drive this to zero as part of a bug fix — that's a separate cleanup epic. Just don't add new warnings.
4. **Husky hooks run on commit** — the agent's `git commit` must honor them or use `--no-verify` only for internal checkpoints.
5. **Terrapet has a LOT of test plan / report markdown at the repo root** — the agent should ignore these files when searching for code context (they're in `.cursorindexingignore`).
6. **Playwright tests require a running Next dev server.** Agent's build runner needs to spin one up with `npm run dev &` + health-check before running `test:e2e`.
