# Token Optimization — AIW Code Agent

> Analysis of our actual token usage + concrete plan to maximize value
> from the credits we pay for (OpenRouter, Mistral) while leaning hard
> on the free tiers we have access to (Groq, Cerebras, HuggingFace,
> Ollama on pc-ale).
>
> **Bottom line up front:** our biggest waste right now is NOT cost,
> it's **retries from provider incompatibility** (5 failed calls out
> of 7 on the REVIEW loop — 71% failure rate). Fix that first, then
> tier the routing, then enable caching. Actual credit spend is
> currently ~$0 since everything flows through Groq's free tier.

## 1. Current state (from real `ai_calls` data)

Query run after the photos-to-kml + Vete smoke tests:

```
 job_type   | calls | total_in | total_out | cache_create | cache_read | avg_in | avg_out | avg_ms | errors
 PR_SUMMARY |   5   |  18,072  |    690    |      0       |     0      |  3,614 |   138   |  2,871 |   0
 REVIEW     |   7   |   8,055  |    341    |      0       |     0      |  1,151 |    49   |    655 |   5
 PLAN       |   3   |     853  |    598    |      0       |     0      |    284 |   199   |  2,534 |   1
```

**Observations:**

1. **Tiny prompts in practice.** Averages are 284–3,614 input tokens.
   The "67k system prompt" I feared earlier was actually the context
   accumulation on Vete's 580K LOC repo during a single in-loop
   iteration, not a static system prompt size.
2. **71% REVIEW failure rate.** 5 of 7 REVIEW loop calls failed with
   `input_tokens=0` — meaning the request was rejected at the provider
   BEFORE any tokens were counted. These are `max_tokens` and
   `ContextWindowExceededError` errors from Groq / SambaNova, not from
   high actual token usage.
3. **Zero cache tokens.** `cache_creation_input_tokens` and
   `cache_read_input_tokens` are both 0 across every row. We're not
   using prompt caching at all. PR #18 disabled it to work around
   non-Anthropic providers rejecting `cache_control`.
4. **$0 in actual credit spend.** Everything currently routes through
   Groq (free tier). The `sk-aiw-code-agent` LiteLLM key has a $25/30d
   budget that is 100% untouched.

**Translation:** the "optimize token costs" framing was wrong for our
situation. The right framing is:
- **Reliability first** — stop the retries, hit the model that handles
  the request on the first try.
- **Tiering second** — use free models for routine work, spend real
  credits only on tasks that need smart reasoning.
- **Caching third** — only pays off for providers that support it AND
  when we have >1 call per prompt per 5 minutes.

## 2. Available models in our LiteLLM gateway

Dumped from `/opt/litellm/config.yaml` via `scripts/apply-aiw-drop-params.py`:

### Free tier (no credit cost, rate-limited)

| Alias | Backend | Context | Notes |
|---|---|---|---|
| `groq-llama-3.3-70b` | Groq llama-3.3-70b-versatile | 128k | ⭐ CURRENT PIN. 300–1500 tok/s. |
| `groq-llama-3.1-8b` | Groq llama-3.1-8b-instant | 128k | Faster but dumber. Good for routing. |
| `groq-qwen-32b` | Groq Qwen3-32b | 32k | Qwen reasoning model. |
| `cerebras-llama-8b` | Cerebras llama3.1-8b | **8k** | Tiny context, fast. |
| `sambanova-llama-8b` | SambaNova Meta-Llama-3.1-8B | **16k** | Slow but free. |
| `nvidia-llama-8b` | NVIDIA NIM llama-3.1-8b | unknown | Free tier unknown limits. |
| `hf-qwen3.5-35b` | HuggingFace Qwen3.5-35B-A3B | 32k | MoE, serverless, rate-limited. |
| `hf-kimi-k2.5` | HuggingFace Kimi K2.5 | 200k | Long context if available. |
| `hf-glm-5` | HuggingFace GLM-5 | 32k | ZhipuAI reasoning. |
| `zai-glm-4-flash` | ZhipuAI GLM-4-Flash | 128k | Free via ZAI API. |
| `ollama-coder` | pc-ale Ollama qwen2.5-coder:7b | 32k | **Truly free, no quota.** |

### Paid tier (credits $)

| Alias | Backend | Input/Output $ per MTok | Best for |
|---|---|---|---|
| `openrouter-claude-haiku` | OpenRouter → Anthropic claude-3.5-haiku | $0.25 / $1.25 | Smart-but-cheap decisions |
| `openrouter-claude-sonnet` | OpenRouter → Anthropic claude-sonnet-4 | $3.00 / $15.00 | Complex reasoning, prompt caching, PR fix generation |
| `openrouter-gemini-flash` | OpenRouter → Google gemini-2.5-flash | ~$0.075 / $0.30 | Cheap alternative for bulk tasks |
| `mistral-small` | Mistral API direct | ~$0.20 / $0.60 | Backup if everything else fails |

### Multi-backend groups (LiteLLM load-balanced)

| Alias | Backends | Min context | Problem |
|---|---|---|---|
| `fast` | groq-8b + cerebras-8b + sambanova-8b | **8k** | ❌ overflows on Vete-size prompts |
| `primary` | groq-70b + groq-qwen-32b + mistral-small | 32k | ⚠ minimum is 32k, usable but risky |
| `reasoning` | groq-qwen-32b + mistral-small | 32k | ⚠ same |

## 3. The real problems in priority order

### Problem #1: `fast` alias overflows on real-world prompts (P0)

The `fast` alias load-balances across 8k (Cerebras) and 16k (SambaNova)
contexts. When the agent runs tool-use iterations on a sizeable repo
(~Vete), conversation context grows past 16k within 2–3 iterations.
LiteLLM's round-robin hits a small-context backend, the provider
rejects the request, the agent retries, the retry also fails.

**Fix:**
- Either (a) remove Cerebras + SambaNova from the `fast` group so only
  Groq 8B (128k) is left, or
- (b) rename `fast` to `fast-small-ctx` and have the agent never use it
  for tool-use loops, or
- (c) delete the group entirely and use `groq-llama-3.1-8b` directly
  when we want "fast."

**My pick:** (a). Fewest side effects. The group keeps its name, but
only routes to 128k-capable backends. Cerebras + SambaNova can still
be used directly via `cerebras-llama-8b` / `sambanova-llama-8b` when
something really is small.

### Problem #2: Groq `max_tokens` constraint on deep-review loop (P1)

The agent's `ClaudeToolUseLoop` requests `max_tokens=65536` (the
default for Claude Sonnet 4's output budget). Groq's llama-3.3-70b
caps output at 32,768 tokens. Request rejected at the provider level.

**Fix:** one-line Java patch in `ClaudeToolUseLoop.java`:

```java
// Cap max_tokens based on config; Groq hard-caps at 32768, most other
// providers accept higher.
int maxTokens = Math.min(
    jobConfig.maxTokens(),
    Integer.parseInt(settingsService.get("anthropic.max-output-tokens", "32000"))
);
```

Then set `ANTHROPIC_MAX_OUTPUT_TOKENS=30000` in the agent's `.env`.
Unlocks the deep review loop on Groq.

### Problem #3: We're ignoring the free Ollama tier (wasted resource)

`ollama-coder` maps to Qwen2.5-coder:7b running on pc-ale (Ivan's
desktop, RTX 2060 SUPER). **Unlimited, no quota, no rate limit.**
It's a coder-specialized model, perfect for file-reading and
navigation tool calls that don't need deep reasoning.

**Use case:** replace the agent's routine tool calls (read_file,
list_files, grep) with ollama-coder. Keep groq-llama-3.3-70b for
reasoning steps.

**Savings:** unlimited retries and exploration without hitting any
provider rate limits.

### Problem #4: Prompt caching is turned off globally

PR #18 added `anthropic.prompt-cache.enabled` which we set to `false`
because non-Anthropic providers reject `cache_control`. That's the
right default for Groq. But it means we **cannot** benefit from
caching when we DO route to Claude via OpenRouter.

**Fix:** make the flag model-aware. When the target model is
`openrouter-claude-*`, attach `cache_control`. When it's Groq / Mistral
/ anything else, don't.

Option A: add the logic in `ClaudeToolUseLoop` — inspect the resolved
model alias and decide. Couples the agent to alias names, brittle.

Option B: set `drop_params` to `false` on `openrouter-claude-*` entries
in LiteLLM config. Keep agent-side sending cache_control always. Let
LiteLLM strip it for non-Anthropic providers (which it already does
per `apply-aiw-drop-params.py`) but pass it through for Anthropic.

**My pick:** Option B. It means the agent always tries to cache, and
LiteLLM becomes the single point of policy enforcement. Closer to
"provider-oblivious agent."

**Expected savings:** when we start routing to Claude for complex
reviews, a 10-iteration tool-use loop with a 5k system prompt goes
from ~50k billed input tokens to ~9k (5k creation + 4.5k cached reads
at 10%). That's ~82% savings on input tokens for long loops.

At Claude Sonnet 4 rates ($3/MTok input), this is $0.15 → $0.027 per
review. Across 100 reviews/month, $15 → $2.70. Across 1000 reviews,
$150 → $27. Meaningful at scale.

### Problem #5: No smart model routing

Currently everything uses `ANTHROPIC_MODEL=groq-llama-3.3-70b`. Every
job, every sub-task, every tool call. This is fine for free tier
usage but wasteful when we eventually route some work to paid models.

The upstream Eneve code has a `TIER_FAST` vs `TIER_DEFAULT` split
read from `anthropic.fast-model` and `anthropic.model`. Different
job types (PLAN, REVIEW, FIX) should use different tiers.

**Proposed routing:**

| Job | Model | Why |
|---|---|---|
| INTENT_CLASSIFY | `groq-llama-3.1-8b` | Single-token decision, dumbest model is fine |
| PLAN (spec → draft) | `groq-llama-3.3-70b` | Needs reasoning, free |
| PR_SUMMARY | `groq-llama-3.3-70b` | Summary-shaped, simple |
| REVIEW (read-only) | `groq-llama-3.3-70b` | Free, 128k, fast |
| FIX (generates code) | `openrouter-claude-sonnet` | Only place we pay, because it matters |
| SECURITY review | `openrouter-claude-sonnet` | High-stakes, use the smart one |
| Routine tool calls (read/list/grep) | `ollama-coder` | Unlimited, coder-tuned |

**Savings math** (hypothetical 100 reviews/week):

- 100 reviews × ~5k tokens avg = 500k tokens/week on `groq-llama-3.3-70b`
  → $0 (free)
- 20 fix PRs × ~50k tokens avg × prompt caching = ~20 × 9k = 180k tokens
  → on `openrouter-claude-sonnet` = $0.54/week
- 100 security checks × ~3k tokens = 300k tokens/week
  → on `openrouter-claude-sonnet` without caching = $0.90/week

**Total weekly cost: ~$1.44 for 120 high-value tasks per week.**
Monthly: $5.76. Well under the $25/30d budget cap.

Without tiering (everything on `openrouter-claude-sonnet`):
- 200 jobs × ~5k avg = 1M tokens/week = $3/week = $12/month

Still under budget, but wastes ~50% of the cap on work that a free
model could have done. And the free-tier throughput is much higher
than OpenRouter's paid limits.

### Problem #6: System prompt optimization (minor)

Not actually a problem right now — our averages are 284–3614 tokens,
which is small. But when the agent encounters a large repo and starts
reading files into context, the prompt grows fast. Mitigations:

- **Tool result truncation.** Currently we dump full file contents
  into the next iteration's conversation. Truncate to the relevant
  section via `read_file(path, offset, limit)` by default.
- **Iteration summarization.** After every 3 iterations, summarize
  what's been learned so far, drop the raw tool results from the
  conversation, keep only the summary.
- **File-tree compression.** Instead of `list_files` returning a
  full recursive list, return only the top-level + flag to drill
  deeper on demand.

These are all **bigger changes** to the agent's tool-use loop
(`ClaudeToolUseLoop.java`). Out of scope for a single-session fix,
but noted for the medium term.

## 4. The "lobster" question — prompt compression tools

You asked about **LLMLingua** (Microsoft's prompt compression tool).
Here's the honest assessment for our specific agent:

**How it works:** a small language model scores each token in the
prompt by "importance" and drops low-importance tokens. Typical
compression ratio: 5–10x.

**For our code-agent: NOT the right tool.** Here's why:

- The agent's prompts are dominated by **structured content** —
  JSON tool schemas, file contents, code blocks. LLMLingua is
  **bad at compressing structured content** because every token
  is semantically meaningful. It's designed for natural-language
  context chunks (RAG / long document summarization).
- Compressing a JSON tool schema would break the schema.
  Compressing a file's source code would introduce syntax errors.
- Our baseline prompts are already small (avg 3614 tokens). 5x
  compression saves ~2900 tokens per call, at Groq's free tier
  = $0 saved. No value.

**Where LLMLingua WOULD help:** RAG-heavy use cases where you're
stuffing 50k tokens of documentation into every prompt. That's not
what we're doing.

**Better alternatives for our agent:**

| Technique | Savings | Effort | Priority |
|---|---|---|---|
| Fix `fast` alias (remove 8-16k backends) | 71% → 0% failure rate | 5 min | ⭐⭐⭐ |
| Cap `max_tokens` for Groq | Unlocks deep review loop | 30 min | ⭐⭐⭐ |
| Enable prompt caching on Claude route only | 80% input token savings on repeat calls | 1 hr | ⭐⭐ |
| Smart tiering (Groq free / Claude paid) | 50% credit savings | 2 hr | ⭐⭐ |
| Tool result truncation + iteration summarization | 30–50% prompt growth reduction on big repos | 4 hr | ⭐ |
| LLMLingua compression | ~10% on natural-language chunks | 4 hr | — not worth it |

## 5. Concrete next-session action plan

Pick the top 3, ship them in one session, measure the impact.

### Action 1: Fix the `fast` alias in LiteLLM (5 min)

```bash
ssh root@72.61.44.159
vim /opt/litellm/config.yaml
# Find:
#   - model_name: fast
#     litellm_params:
#       model: cerebras/llama3.1-8b
#   - model_name: fast
#     litellm_params:
#       model: sambanova/Meta-Llama-3.1-8B-Instruct
# Delete both blocks. Keep only the groq/llama-3.1-8b-instant entry.
docker restart litellm
```

Verify by curl:

```bash
for i in 1 2 3 4 5; do
  curl -sS -H "x-api-key: sk-hermes-litellm-sunstein-2026" \
       -H "anthropic-version: 2023-06-01" \
       -H "content-type: application/json" \
       -d '{"model":"fast","max_tokens":30,"messages":[{"role":"user","content":"Reply with a 20-token answer describing what you are."}]}' \
       http://localhost:4000/v1/messages
  echo "---"
done
```

All 5 responses should succeed (no overflow). If they do, `fast` is
now safe for tool-use loops.

### Action 2: Cap `max_tokens` in `ClaudeToolUseLoop.java` (30 min)

```java
// In ClaudeToolUseLoop.java, before .maxTokens(maxTokens):
int effectiveMaxTokens = Math.min(
    maxTokens,
    Integer.parseInt(settingsService.get("anthropic.max-output-tokens", "32000"))
);
paramsBuilder.maxTokens(effectiveMaxTokens);
```

Add to `application.properties`:

```
# AIW: cap output tokens to Groq's limit (32768) — most providers allow
# higher but Groq llama-3.3-70b rejects anything above this. Safe default.
anthropic.max-output-tokens=${ANTHROPIC_MAX_OUTPUT_TOKENS:32000}
```

Set in `.env` on the VPS:

```
ANTHROPIC_MAX_OUTPUT_TOKENS=32000
```

Deploy via `docker stack deploy -c docker-stack.aiw.yml aiw-code-agent`
(not `service update`, see DEPLOYMENT-NOTES.md 3b).

### Action 3: Flip LiteLLM `drop_params` off for Anthropic routes only (15 min)

```python
# Edit scripts/apply-aiw-drop-params.py:
ANTHROPIC_PASSTHROUGH_MODELS = ("openrouter/anthropic/", "anthropic/")
for model in cfg["model_list"]:
    params = model.setdefault("litellm_params", {})
    backend = params.get("model", "")
    if any(backend.startswith(p) for p in ANTHROPIC_PASSTHROUGH_MODELS):
        # Keep Anthropic fields for the Anthropic route — these are the
        # providers that support cache_control natively.
        params.pop("drop_params", None)
        params.pop("additional_drop_params", None)
    else:
        params["drop_params"] = True
        params["additional_drop_params"] = DROPPED_FIELDS
```

Re-run on the VPS:

```bash
python3 /opt/litellm/apply-aiw-drop-params.py
docker restart litellm
```

Then flip `ANTHROPIC_PROMPT_CACHE_ENABLED=true` in `.env` when you
also switch `ANTHROPIC_MODEL=openrouter-claude-sonnet`. Deploy with
`docker stack deploy`.

**Expected first-run cost:** `openrouter-claude-sonnet` costs real
money. Budget a small amount ($1) for validation. Watch the LiteLLM
`/key/info` endpoint to see spend accumulate.

### Action 4 (optional): Route routine tool calls to Ollama (2 hr)

This is a bigger change — requires a new config like
`anthropic.tool-call-model` that the tool loop uses instead of the
main model when executing routine read-only tool invocations.

The upstream code doesn't support per-tool-call routing. Need to
either:
- (a) Add a new HTTP client instance in `ClaudeToolUseLoop` that
  connects to a different base URL for tool-resolution calls, or
- (b) Use LiteLLM's metadata-based routing to vary the model per
  call based on a header.

Deferred until Action 1 + 2 + 3 prove the pattern works.

## 6. Measurement after each action

After each of the above actions, run this query to see the impact:

```sql
SELECT
    job_type,
    COUNT(*) as calls_after_fix,
    AVG(input_tokens) as avg_in,
    AVG(output_tokens) as avg_out,
    AVG(duration_ms) as avg_ms,
    COUNT(*) FILTER (WHERE is_error) as errors,
    (COUNT(*) FILTER (WHERE is_error)::float / COUNT(*) * 100) as error_rate_pct
FROM ai_calls
WHERE created_at > NOW() - INTERVAL '1 hour'
GROUP BY job_type;
```

**Success criteria for Action 1 + 2:**
- REVIEW error rate drops from 71% to <10%
- Max input_tokens per call stays under 128k (Groq's limit)

**Success criteria for Action 3:**
- `cache_creation_input_tokens` > 0 for at least one call (proves caching ran)
- `cache_read_input_tokens` > 0 on subsequent calls within 5 minutes
- LiteLLM spend on Claude route is measurable via `/key/info`

## 7. Longer-term optimization roadmap

Tracked separately in `docs/NEXT-STEPS.md`, but the token-specific
items are:

1. **Tool schema slicing.** Load only the subset of tools the current
   job needs. Review jobs don't need `git commit` or `create_pr`.
   Plan-only jobs don't need file-modifying tools. Could cut system
   prompt by 30–50%.

2. **Context window tracking.** The agent should know how close it
   is to the model's context limit and proactively summarize older
   iterations before they push it over. Currently it just keeps
   appending and hopes for the best.

3. **LiteLLM-native smart routing.** LiteLLM supports routing based
   on prompt length + model capabilities. Configure it to
   automatically send >16k prompts to 128k-context models only. No
   agent-side code changes needed.

4. **Per-client budget caps.** Currently one `sk-aiw-code-agent` key
   covers everything. When Solstein and Vete are both onboarded,
   give each client its own virtual key with its own budget — lets
   you see per-client spend and prevents one runaway client from
   burning the shared pool.

5. **Response streaming + early termination.** If the agent sees
   the answer in the first 200 output tokens of a 30k-token response,
   it should stop the stream. Saves output tokens on the provider
   side. Only relevant for paid providers.

6. **Semantic caching (LLM-level memoization).** If two review jobs
   ask Claude the same question about the same file, cache the
   response in our own postgres. Separate from Anthropic's prompt
   caching — this is ours, full-response level, works with any
   provider. Saves actual call costs on repeat queries.

## 8. What I'd do in the next 30 minutes

If you gave me 30 minutes autonomously right now:

1. (5 min) Fix `fast` alias in LiteLLM config → restart → verify
2. (20 min) Cap `max_tokens` in Java, deploy, trigger a synchronize
   event on a new test PR, confirm the deep review loop runs green
3. (5 min) Pull the before/after `ai_calls` stats into a follow-up
   comment here

Everything in Action 3 (Anthropic caching) is valuable but spends
credits — I'd wait for an explicit "yes, spend up to $5 on validation"
before touching it.

---

*Last updated: session closeout, April 2026. Measurements from
`ai_calls` table right after the Vete + photos-to-kml smoke tests,
~15 total calls. Will update after the next measurement run.*
