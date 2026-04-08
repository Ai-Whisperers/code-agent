# Study Notes — Eneve Code Agent Architecture

> Distilled findings from the in-depth analysis of the Eneve `code-agent` (backend) and `code-agent-ui` (frontend). Captured here so we don't lose insight as we customize.

## Overall verdict

A serious production system — **112K LOC Java backend** + **57K LOC TypeScript frontend across 179 files**. Essentially a self-hosted Claude Code for enterprise teams, with hooks into Bitbucket / Azure DevOps / GitLab / GitHub, JIRA, Confluence, Teams, and Aikido security.

The architecture is clean enough to learn from, even though the integration choices are very Eneve-specific. The patterns below are the parts worth keeping in mind during the AIW adaptation.

---

## Backend — patterns to steal

### 1. ClaudeToolUseLoop (the core agent loop)
- **Parallel execution of read-only tools** — when Claude requests multiple read-only tools in one turn, they run concurrently. Big speedup for code exploration.
- **Context compaction** when approaching the token limit — summarizes older turns into a checkpoint and replaces them. Has a circuit breaker so it doesn't spiral.
- **Checkpointing every iteration** — full state (messages, tool history, file modifications) persisted to DB after each Claude call. Jobs can resume from any point if the process crashes.
- **15+ method overloads on `executeLoop()`** — this is the main code smell. Should be a builder pattern.

### 2. Guardrails (production paranoia done right)
- `RUN_FIX_BLOCKED_PATHS` — paths the agent literally cannot write to (e.g. `src/main/security`, `.github`, `.env`).
- `RUN_FIX_ALLOWED_COMMANDS` — explicit allowlist for shell. Default: `mvn,./mvnw,git diff,git status,git log,git add,git commit,git push,git pull,git fetch,git branch,git stash,git restore,git reset,git checkout,ls,find,cat,grep,dotnet,npm,npx`. Anything else → blocked.
- `RUN_FIX_MAX_FILES_CHANGED=10`, `RUN_FIX_MAX_LINES_CHANGED=500` — hard caps that abort the job if exceeded.
- **Shell command security validator** — ported from Claude Code's `bashSecurity.ts`. Catches things like `git push --force` to protected branches, `rm -rf /`, command substitution patterns, suspicious env-var redirects.

### 3. Self-review loop
- After making changes, the agent **reviews its own diff** against a checklist (security, design, tests, performance) before committing. If review fails, agent gets the feedback and another iteration to fix it.
- Capped at `RUN_FIX_SELF_REVIEW_MAX_ITERATIONS=15` and `RUN_FIX_SELF_REVIEW_MAX_DIFF_CHARS=30000` to prevent runaway costs.

### 4. Learning extraction
- After every code review conversation, an LLM call extracts **team preferences** ("we prefer constructor injection", "always use Optional over null") and writes them to a `memories` table.
- Future reviews on the same repo retrieve relevant memories and inject them into the system prompt. Simple, effective, persistent learning.

### 5. Tool authorization gate
- Every tool implements `isReadOnly()`, `isDestructive()`, and `isAuthorized(JobContext)`.
- `isReadOnly()` controls parallel execution.
- `isDestructive()` triggers extra logging + per-job-type approval rules.
- `isAuthorized()` is the per-job permission check (e.g. `chat` mode can't run shell, `fix` mode can).

### 6. Per-job-type tool sets
- Different job types get different tool sets. `chat` gets read-only tools. `fix` gets read+write+shell. `review` gets read+comment. `plan` gets read-only + planning helpers. Clean separation.

## Backend — issues to NOT replicate

1. **15+ method overloads** on `ClaudeToolUseLoop` — should be a builder.
2. **Hardcoded to Anthropic** — no model abstraction. Single biggest reason to migrate to LiteLLM.
3. **No cost controls** — token tracking exists but there's no per-job abort threshold. A runaway agent could burn $100 before anyone notices.
4. **Monolithic** — everything in one Quarkus service. No worker separation. A long-running `generate-docs` job blocks queue capacity.
5. **No OpenTelemetry / distributed tracing.** Just structured JSON logs.
6. **AWS-coupled** for embeddings, S3, Transcribe — increases the surface area we don't need.

---

## Frontend — patterns to steal

### 1. ThinkingPanel
- Shows Claude's tool calls in real-time with status indicators (running / completed / error), duration, and tool parameters.
- **Builds trust** — users can see the agent working instead of staring at a spinner.

### 2. SecretScanner
- **Client-side** detection of AWS keys, GitHub tokens, Anthropic keys, OpenAI keys, private keys, JWTs, DB URLs.
- Warns the user before sending. Auto-redacts on send. Catch secrets *before* they hit the server, not after.

### 3. ClarificationBlock
- Structured questions from the agent: text / single-choice / multi-choice / boolean.
- Agent can ask the user before proceeding instead of free-text back-and-forth.

### 4. Streaming chat
- SSE-based with TanStack Store. Content appears in real-time with mermaid diagram rendering inline.
- Markdown-aware streaming (handles partial code blocks, partial mermaid, etc.).

### 5. Editable Prompts page
- All 39 prompt templates editable through the UI. **No redeploy** to iterate on prompts.
- Huge for iteration speed. Should be one of the first things we keep when we adapt.

### 6. RBAC
- Keycloak + 4 roles (User / Staff / Developer / Admin) with 9 permissions.
- Menu items auto-hide based on permissions — clean UX even with role complexity.

### 7. Plan execution UI
- Visual timeline showing plan phases.
- Each step's status (pending / running / done / failed).
- Approve/reject buttons before execution kicks off.

## Frontend — issues to fix during adaptation

1. **No dark mode toggle visible** — CSS vars exist but no UI control found. Easy fix.
2. **No WebSocket** — uses SSE for streaming. WebSocket would be lower latency for tool-call updates.
3. **`Chat.tsx` is 1088 lines** — single component doing too much. Decompose during the adaptation.
4. **No error boundary components visible** — a crash in any panel takes down the whole UI.
5. **No i18n** — English only. We need Spanish for the PY market.
6. **No keyboard shortcuts.** Power users will want them.
7. **No offline / PWA support.**

---

## Screen inventory (FE — 30+ pages)

**Work:** Dashboard · Jobs (list + detail with diff viewer + AI call inspector) · Plans (multi-phase + timeline) · Pull Requests · Chat (streaming + tool visibility)

**Insights:** Quality Reports · Coverage Trend · Scopes (JIRA issue readiness) · Review Metrics · PR Cycle Time · Developer Scorecard · AI Effectiveness + AI Stats · Knowledge Graph

**Engineering:** Architecture (Structurizr diagrams) · Log Analysis

**QA:** QA Scopes · Test Plans · Test Cases

**SOC II:** Security Issues (Aikido) · SOC II Audit

**Settings:** Repos · Integrations · Hooks · Job Configuration · **Prompts (editable!)** · Memories · Knowledge Index · Customers · Users · Teams · System · Webhook Audit · Audit Log

## Tech stack reference

**Backend:** Quarkus 3.x · Java 21 · Maven · PostgreSQL + Flyway · pgvector (via standalone) · AWS SDK v2 · Keycloak OIDC · OpenAPI/Swagger · Tavily web search · Anthropic SDK

**Frontend:** React 19 · TypeScript · Vite 7.3 · TanStack Router v1.167 · TanStack Query v5.90 · TanStack Store v0.9 · Tailwind v4.2 · Vitest v4.1 · Chart.js v4.5 · Recharts · Mermaid v11.13 · Keycloak JS v26.2 · React Markdown v10.1
