# Code Agent — AI Whisperers Adaptation

> ⚠️ **CONFIDENTIAL** — see [CONFIDENTIAL.md](./CONFIDENTIAL.md). Originally Ecedo / Eneve property. Study material only.

## What This Is

A self-hosted AI coding agent that automates issue fixing, dependency upgrades, AI-powered code reviews, execution planning, documentation generation, and unit test generation. Originally built by Eneve in Java/Quarkus, **adapted by AI Whisperers** to fit our stack and clients.

## Two Branches You Care About

| Branch | What's there |
|---|---|
| `upstream-main` / `upstream-develop` | **Pristine Eneve baseline.** Read-only. Do not push. |
| `aiw/main` (this branch) | AIW adaptation: docs, env defaults, integration swaps, branding, customization roadmap. |

The pristine baseline lives next to our work so we can diff against it any time:

```bash
git diff upstream-develop..aiw/main -- path/to/file
```

## Why We're Adapting It

Eneve built this for their own enterprise stack (Bitbucket / JIRA / Confluence / Teams / Aikido / Keycloak / AWS Bedrock / Anthropic-only). Our stack is different:

| Eneve uses | AI Whisperers uses |
|---|---|
| Bitbucket-first (with BB/ADO/GL/GH adapters) | **GitHub-only** (39 of 39 active repos are on GH) |
| JIRA | **Linear** (light) and **GitHub Issues** |
| Confluence | **Markdown in repo** / **Notion** |
| Microsoft Teams | **Telegram** (via Hermes gateway, 40+ repos use it) |
| Aikido security | **GitHub code scanning** + **Dependabot** |
| Keycloak SSO | **Supabase Auth** (41 of our repos use Supabase) |
| Anthropic-only | **LiteLLM gateway** → OpenRouter / Anthropic / Groq / Cerebras (cheap routing) |
| AWS Bedrock embeddings (Cohere + Titan) | **Voyage** / **OpenAI text-embedding-3** / **Ollama** local |
| Java 21 / Quarkus | We're **Python + TypeScript**. We keep this Java for study; production fork would be ported. |

## Quick Start (Eneve baseline, unmodified)

You can still build and run the original code as-is:

```bash
# Backend (requires JDK 21 + Maven)
./mvnw quarkus:dev

# Frontend
cd ../code-agent-ui && npm install && npm run dev
```

Configuration is via env vars — see `src/main/resources/application.properties` for the full list (312 lines, every setting documented).

## Customization Roadmap

See [`docs/AIW-CUSTOMIZATION-PLAN.md`](./docs/AIW-CUSTOMIZATION-PLAN.md) for the full delta plan and [`docs/STUDY-NOTES.md`](./docs/STUDY-NOTES.md) for the architectural analysis (parallel tool execution, context compaction, self-review loop, etc.).

GitHub Issues track each customization workstream (`integration:linear`, `integration:telegram`, `integration:litellm`, `integration:supabase-auth`, `chore:rebrand`, `chore:drop-aws`, etc.).

## Key Patterns Worth Stealing

From the prior in-depth analysis (see `docs/STUDY-NOTES.md`):

**Backend:**
1. Parallel read-only tool execution in `ClaudeToolUseLoop`
2. Context compaction with circuit breaker when approaching token limits
3. Checkpoint + resume after every iteration
4. Self-review of own diff before committing
5. Learning extraction from PR comments → persistent team preferences
6. Tool authorization gate (`isReadOnly() / isDestructive() / isAuthorized()`)
7. Shell command security validator (ported from Claude Code's `bashSecurity.ts`)

**Frontend:**
1. `ThinkingPanel` — real-time tool call visibility (running/done/error + duration)
2. `SecretScanner` — client-side detection of AWS/GitHub/Anthropic/OpenAI keys before send
3. `ClarificationBlock` — structured questions (text / single / multi / boolean)
4. SSE streaming chat with TanStack Store
5. Editable prompts UI — all 39 templates editable, no redeploy
6. Plan execution UI with phase timeline + approval workflow

## Eneve Original README

The full original README (72KB, 1,200+ lines) is preserved as `README.md` on `upstream-develop` and as `README-eneve-original.md` on `aiw/main` for reference. Read it if you want endpoint-by-endpoint detail of what the system does.

---

**Status:** Study/adaptation phase. NOT production-deployed under AIW.
**Owner:** Ivan Weiss van der Pol
**Last updated:** April 2026
