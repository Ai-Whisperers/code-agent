# AIW Customization Plan

> Delta plan for adapting the Eneve `code-agent` to the AI Whisperers stack. Each item below corresponds to a tracked GitHub issue.

## Inventory of Eneve integrations to swap or drop

Source: scan of `src/main/resources/application.properties` (312 lines) and the React UI's API surface.

### Git platforms (4 adapters, all in `src/main/java/.../git/`)
- ✅ **GitHub** — keep, make primary. Currently the 4th-class citizen; promote to default.
- ❌ **Bitbucket Cloud** — drop. We have zero Bitbucket usage.
- ❌ **Azure DevOps** — drop. None of our 39 active repos use ADO.
- ⚠️ **GitLab** — keep behind a feature flag. Not used today but cheap to keep around.

### Issue tracking
- ❌ **JIRA** — drop. 0 hits across the org. Replace with:
- 🆕 **Linear** — adapter shim that maps Eneve's `JiraClient` interface to Linear's GraphQL API. Linear has issue keys (`AIW-123`), states, comments, custom fields — clean fit.
- 🆕 **GitHub Issues** — for repos that don't use Linear, fall back to GH Issues with labels mapped to Linear-style states.

### Wiki / docs
- ❌ **Confluence** — drop. We don't use it.
- 🆕 **Markdown-to-repo** — `publish_docs` tool writes generated docs as PRs to a `docs/` folder in the target repo.
- 🆕 **Notion** (optional, behind flag) — for clients who use Notion.

### Notifications
- ❌ **Microsoft Teams** — drop. We don't use Teams.
- ❌ **n8n webhook** — drop. We use Hermes gateway directly.
- 🆕 **Telegram** — primary. Use Hermes gateway (port 4000 on VPS) — already wired into 40+ AIW repos. Default delivery target: `telegram:ivan` for personal jobs, `telegram:#engineering` for team jobs.
- 🆕 **Discord** — secondary. 3 of our repos already use Discord webhooks.
- 🆕 **WhatsApp** — for client-facing notifications (Vete, clinica-duerksen, etc.) via Hermes gateway.

### Security scanner
- ❌ **Aikido** — drop. Replace with:
- 🆕 **GitHub code scanning** (CodeQL) — read alerts via GH API.
- 🆕 **Dependabot alerts** — read via GH API.
- 🆕 **Trivy** scan in CI — for container/dependency CVEs.

### Auth
- ❌ **Keycloak OIDC** — drop the heavy SSO. Replace with:
- 🆕 **Supabase Auth** — JWT bearer validation. 41 of our repos already use Supabase. Drop in `quarkus.oidc.auth-server-url=https://<project>.supabase.co/auth/v1`.
- 🆕 **Static API key** — keep as fallback for service-to-service.
- ❌ Drop the entire Keycloak realm/client setup ceremony.

### LLM providers
- ⚠️ **Anthropic direct** — keep as one provider, but no longer default.
- 🆕 **LiteLLM gateway** (PRIMARY) — point `anthropic.api.url` at our gateway: `http://172.17.0.1:4000` (on VPS) or `http://100.110.9.12:4000` (Tailscale). Master key already provisioned: `sk-hermes-litellm-sunstein-2026`.
- 🆕 **Smart routing** — short/simple jobs → `groq/llama-3.3-70b` (free, fast). Complex/code jobs → `anthropic/claude-sonnet-4`. Already configured in our LiteLLM.
- 🆕 **Fast model** — point at `groq/llama-3.1-8b-instant` instead of `claude-haiku-4-5` for the planner's quick lookups.
- ❌ Drop hardcoded Anthropic pricing (use LiteLLM's `/spend` endpoint instead).

### Embeddings & vector index
- ❌ **AWS Bedrock** (Cohere multilingual + Titan v2) — drop. We don't use AWS heavily.
- 🆕 **Voyage AI** — `voyage-3` for code embeddings (cheap, good for code).
- 🆕 **OpenAI** — `text-embedding-3-small` as fallback.
- 🆕 **Ollama local** — `nomic-embed-text` running on `pc-ale` (RTX 2060S) for free local embeddings during dev.
- 🆕 **pgvector** — Supabase has it built-in. Use `code_embeddings` and `knowledge_embeddings` tables in Supabase Postgres instead of standalone pgvector.

### Object storage
- ❌ **AWS S3** + LocalStack — drop. Replace with:
- 🆕 **Supabase Storage** — same SDK pattern, already used in `Vete` and `clinica-duerksen`.

### Build / test
- ⚠️ **Maven only** — Eneve is Java-first so this makes sense for them. Add detectors for:
- 🆕 **Node** (`npm`, `pnpm`, `yarn`) — most of our repos.
- 🆕 **Python** (`uv`, `poetry`, `pip`) — 15 of our repos.
- 🆕 **Next.js** detection — first-class for our SaaS work (Vete, clinica, etc.).

### Speech-to-text
- ❌ **Amazon Transcribe Streaming** — drop. Replace with:
- 🆕 **Whisper local** (Ollama on pc-ale) or **Groq Whisper** (free tier).

### Branding & namespacing

| What | From | To |
|---|---|---|
| Java root package | `com.eneve.agent` | `io.aiwhisperers.codeagent` |
| Maven groupId | `com.eneve` | `io.aiwhisperers` |
| Maven artifactId | `agent` | `code-agent` |
| Default git author name | `code-agent` | `aiw-code-agent` |
| Default JIRA label | `WALL-E` | (drop, replaced by Linear label `aiw-agent`) |
| Docker image | `code-agent:latest` | `aiwhisperers/code-agent:latest` |
| Postgres DB name | `code_agent` | `aiw_code_agent` |
| OpenAPI title | `Code Agent Runner API` | `AI Whisperers Code Agent API` |
| Frontend brand | `Code Agent` | `AIW Code Agent` |

### Settings to add (AIW-specific)

```properties
# --- AIW: LiteLLM gateway ---
litellm.gateway.url=${LITELLM_GATEWAY_URL:http://localhost:4000}
litellm.master.key=${LITELLM_MASTER_KEY:}
litellm.routing.short-jobs=${LITELLM_ROUTING_SHORT:groq/llama-3.3-70b}
litellm.routing.complex-jobs=${LITELLM_ROUTING_COMPLEX:anthropic/claude-sonnet-4}

# --- AIW: Hermes gateway notifications ---
hermes.gateway.url=${HERMES_GATEWAY_URL:http://localhost:8765}
hermes.default.target=${HERMES_DEFAULT_TARGET:telegram}

# --- AIW: Supabase ---
supabase.url=${SUPABASE_URL:}
supabase.anon.key=${SUPABASE_ANON_KEY:}
supabase.service.key=${SUPABASE_SERVICE_KEY:}
```

## Workstreams (= GitHub issues to be opened)

1. **`chore:rebrand`** — package rename `com.eneve.agent` → `io.aiwhisperers.codeagent`. Mechanical sed across 551 .java files + pom.xml + log categories. **Risk:** breaks build until done in one shot. **Test:** `./mvnw compile` must pass.
2. **`integration:litellm`** — swap `AnthropicClient` to point at LiteLLM gateway. Add smart routing config. Update `application.properties`.
3. **`integration:telegram`** — replace `TeamsNotifier` with `HermesGatewayNotifier`. Drop `n8nWebhookUrl`.
4. **`integration:linear`** — write `LinearClient` implementing the same interface as `JiraClient`. Map states, labels, comments, transitions. Drop `JiraClient` once parity reached.
5. **`integration:supabase-auth`** — swap Keycloak OIDC for Supabase JWT validation. Update Quarkus security config. Update FE to use `@supabase/auth-helpers-react` instead of `keycloak-js`.
6. **`integration:supabase-storage`** — swap S3 → Supabase Storage. Drop LocalStack from `docker-compose.yml`.
7. **`integration:embeddings`** — swap Bedrock → Voyage / Ollama / OpenAI. Move embeddings into Supabase pgvector.
8. **`chore:drop-bitbucket`** — delete `BitbucketClient`, webhook handlers, settings. Same for ADO.
9. **`chore:drop-aikido`** — delete `AikidoClient`, webhook handler. Add `GitHubCodeScanningClient`.
10. **`feature:multi-language-detect`** — extend `BuildRunner` to detect Node/Python/Next.js projects, not just Maven.
11. **`feature:spanish-ui`** — i18n for the UI. Spanish first (PY market), then EN.
12. **`chore:remove-aws-deps`** — purge `software.amazon.awssdk` from `pom.xml`. Saves ~30MB from the build.

## Suggested execution order

```
Week 1: chore:rebrand (foundation, must come first)
Week 1: integration:litellm (highest immediate value, easy)
Week 2: integration:telegram + chore:drop-aikido + chore:drop-bitbucket
Week 2: integration:supabase-auth (unblocks everything else)
Week 3: integration:linear
Week 3: integration:supabase-storage + integration:embeddings
Week 4: feature:multi-language-detect
Week 4: feature:spanish-ui
Week 5: chore:remove-aws-deps + cleanup + docs
```

## Out of scope (for now)

- Porting to a different language (Java → Python/TS). Maybe later if we want to fold this into our main stack.
- Multi-tenancy beyond what Eneve already has. Their existing `customer_registry` table is fine.
- Replacing Quarkus with a different framework. Quarkus is good — the GraalVM native build is genuinely useful.
