# Lobster Integration Analysis

> **Lobster** is `@clawdbot/lobster` — an Openclaw-native TypeScript
> workflow runtime. It's not a prompt-compression tool (LLMLingua was my
> wrong guess). It's better than that for our specific use case.
>
> **The thesis:** replace "LLM plans every step" tool-use loops with
> deterministic pre-coded workflows. The LLM is only invoked for
> subjective judgment (summaries, analysis), not for orchestration.
>
> **For our code-agent, this is potentially a 10x token reduction on
> review jobs.** Deterministic, resumable, safer, and with the
> `approve` checkpoint primitive built in.

## 1. What Lobster actually is

Reading the repo (`github.com/openclaw/lobster`) directly:

- **Language:** TypeScript, Node.js 20+
- **Dependencies:** 2 total — `ajv` (JSON schema validation) + `yaml`
- **Entry point:** `node bin/lobster.js "workflows.run --name <workflow>"`
- **Also exports as SDK:** `import { ... } from '@clawdbot/lobster'`
- **Published as npm:** `@clawdbot/lobster`
- **License:** MIT
- **Design goals** (from their README):
  > "OpenClaw (or any other AI agent) can use `lobster` as a workflow
  > engine and avoid re-planning every step — saving tokens while
  > improving determinism and resumability."
- **The killer feature** (per their VISION.md):
  > "The `approve` primitive isn't a prompt hint — it's a **hard stop**.
  > The workflow literally cannot continue until you resume it."

Example workflow they ship (`github_pr_monitor.ts`):

```typescript
export async function runGithubPrMonitorWorkflow({ args, ctx }) {
  const { repo, pr } = args;
  // 1. DETERMINISTIC: shell out to gh CLI
  const { stdout } = await runProcess('gh', [
    'pr', 'view', String(pr), '--repo', repo, '--json',
    'number,title,url,state,isDraft,mergeable,reviewDecision,...'
  ]);
  const current = JSON.parse(stdout);

  // 2. DETERMINISTIC: diff against last stored snapshot
  const { changed, before } = await diffAndStore({ key, value: current });

  // 3. DETERMINISTIC: structured output
  return {
    kind: 'github.pr.monitor',
    repo, prNumber, key, changed,
    summary: buildPrChangeSummary(before, current),
    prSnapshot: current,
  };
}
```

**Zero LLM calls.** Zero prompts. Zero tokens. Pure deterministic code
that produces a structured JSON output the calling agent can reason
about in ONE follow-up LLM call.

## 2. Why this matters for our code-agent

Look at what our current `ReviewHandler` does (simplified from the
JobLifecycleHelper + ClaudeToolUseLoop trace we captured in the
Vete smoke test):

```
Webhook arrives
 → LLM plans iteration 1: "I should clone the repo"        [~1500 input tokens]
 → Tool call: git clone
 → LLM plans iteration 2: "I should read the diff"          [~2500 input tokens]
 → Tool call: read_file
 → LLM plans iteration 3: "I should list other files"       [~3500 input tokens]
 → Tool call: list_files
 → LLM plans iteration 4: "I should run the tests"          [~4500 input tokens]
 → Tool call: execute_command (npm test)
 → ...
 → LLM plans iteration N: "I should post the comment"       [~N*1000 tokens]
 → Tool call: github_post_comment
```

**Every iteration adds the previous tool result to the next prompt.**
By iteration 10, we're sending ~15k tokens of accumulated context PER
CALL. The agent then reasons "OK what's next?" and decides the
obvious thing (which we could have hardcoded).

**The work the LLM is actually doing well:**
- ✅ Summarizing the diff in the final PR comment
- ✅ Deciding whether the diff is a style fix vs a feature (intent classify)
- ✅ Writing the review body

**The work the LLM is wasting tokens on:**
- ❌ Deciding "I should clone the repo" (we always clone the repo)
- ❌ Deciding "I should read the changed files" (we always read them)
- ❌ Deciding "I should run tests" (if tests exist, we always run them)
- ❌ Deciding "I should post a comment" (review = post a comment)
- ❌ Routing between tools based on repo state (determined by file presence)

Those decisions are **all deterministic** for a well-defined workflow
like "review a pull request". They should be hardcoded. The LLM should
only be invoked when there's actual judgment to make.

**That's exactly what Lobster is designed for.**

## 3. Concrete integration patterns

Our code-agent is Java/Quarkus, Lobster is TypeScript/Node. Four ways
to combine them:

### Option A: Subprocess delegation from Java

Install Node + Lobster on the agent container. A new `LobsterClient`
bean shells out to `node bin/lobster.js workflows.run --name X --args-json Y`
and parses the JSON response.

```java
@ApplicationScoped
public class LobsterClient {
    public JsonNode run(String workflow, Map<String, Object> args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("node",
            "/opt/lobster/bin/lobster.js",
            "workflows.run", "--name", workflow,
            "--args-json", objectMapper.writeValueAsString(args));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return objectMapper.readTree(out);
    }
}
```

Handlers switch from `claudeLoop.execute(...)` to
`lobsterClient.run("review.pr", ...)` for known job types and fall
back to the full loop for unknown intents.

**Pros:**
- Near-zero Java code change
- Can use Lobster's existing recipes immediately
- Clean language boundary
- Lobster stays upgradable via `pnpm install @clawdbot/lobster@latest`

**Cons:**
- +200 MB to the Docker image (Node 20 runtime)
- Subprocess startup latency (~100-300ms per call)
- Need to package Lobster + its deps into the agent image
- Log lines from two languages mixed in stdout

### Option B: Port Lobster concepts to Java

Build a native Java `WorkflowEngine` + `WorkflowRegistry` + `Workflow`
interface. Define workflows as `@ApplicationScoped` beans. Handlers
dispatch to them.

```java
public interface Workflow {
    String name();
    WorkflowResult run(Map<String, Object> args, WorkflowContext ctx);
}

@ApplicationScoped
public class ReviewPrWorkflow implements Workflow {
    @Override
    public String name() { return "review.pr"; }

    @Override
    public WorkflowResult run(Map<String, Object> args, WorkflowContext ctx) {
        // 1. clone
        var workspace = gitWorkspaceHelper.clone(args.get("repoUrl"), args.get("branch"));
        // 2. diff
        var diff = workspace.diffAgainst(args.get("baseBranch"));
        // 3. detect archetype
        var archetype = archetypeDetector.detect(workspace.root());
        // 4. run linter if present
        var lintResult = buildValidator.runLint(workspace.root(), archetype);
        // 5. summarize via LLM (ONE call)
        var summary = claudeClient.summarize(diff, lintResult);
        // 6. post comment
        githubPlatformService.postReviewComment(summary);
        return WorkflowResult.success(summary);
    }
}
```

**Pros:**
- One language, one runtime, full Quarkus integration
- Type safety across the whole thing
- Direct access to the agent's existing beans (no IPC)
- No container bloat

**Cons:**
- Reinvents Lobster's workflow engine
- No reuse of upstream Lobster recipes (github_pr_monitor, etc.)
- Need to build state management + resume for approval gates
- Divergent from the Openclaw ecosystem — harder to share workflows
  across AIW projects

### Option C: Lobster as a Swarm sidecar HTTP service

Deploy Lobster as a separate container. Expose an HTTP API via a thin
Express server wrapping `lobster.run(...)`. Code-agent calls it via
plain HTTP.

**Pros:**
- Clean separation, language-independent
- Multiple services can share the same Lobster instance
- Lobster logs go to a dedicated container
- Upgrade Lobster without redeploying the agent

**Cons:**
- Lobster doesn't ship an HTTP server mode — we'd need to write one
- Another service to maintain
- Network hop adds latency

### Option D: Hybrid — study Lobster's design, port concepts, skip install

Read their workflow patterns carefully. Implement the same design in
Java within the code-agent, but don't actually depend on Lobster.
Option B + "we learned from Lobster's architecture".

**Pros:**
- No new dependencies
- Fastest to ship the first workflow
- Can evolve independently of upstream Lobster

**Cons:**
- Doesn't contribute back to the Openclaw ecosystem
- Re-does work that Lobster already did
- Miss out on new Lobster recipes as they land upstream

## 4. My recommendation: **Option A, then consider B**

**Phase 1 — Proof of concept (1-2 hours):**

1. Install Lobster on the VPS as a sibling to the code-agent:
   ```bash
   ssh root@72.61.44.159
   mkdir -p /opt/aiw-lobster
   cd /opt/aiw-lobster
   npm init -y
   npm install @clawdbot/lobster
   ```

2. Write a minimal Lobster workflow that does what our current
   ReviewHandler does, but WITHOUT the tool-use loop:

   ```typescript
   // /opt/aiw-lobster/workflows/review-pr.ts
   import { spawn } from 'node:child_process';

   export async function runReviewPrWorkflow({ args, ctx }) {
     const { repoUrl, branch, prNumber, githubToken } = args;

     // 1. clone
     const workspace = `/tmp/review-${Date.now()}`;
     await run('git', ['clone', '--depth=1', '--branch', branch,
                       `https://x-access-token:${githubToken}@${repoUrl.replace('https://','')}`,
                       workspace]);

     // 2. diff vs origin/main
     await run('git', ['fetch', 'origin', 'main'], { cwd: workspace });
     const diffOut = await run('git', ['diff', 'origin/main...HEAD', '--stat'], { cwd: workspace });
     const diffFull = await run('git', ['diff', 'origin/main...HEAD'], { cwd: workspace });

     // 3. detect language
     const hasPackageJson = existsSync(`${workspace}/package.json`);
     const hasPyproject = existsSync(`${workspace}/pyproject.toml`);
     const hasPomXml = existsSync(`${workspace}/pom.xml`);

     // 4. return structured data — code-agent will summarize via LLM
     return {
       kind: 'review.pr',
       repoUrl, branch, prNumber,
       diffStat: diffOut.stdout,
       diffChars: diffFull.stdout.length,
       diffHead: diffFull.stdout.slice(0, 8000),  // first 8k of diff only
       language: hasPackageJson ? 'node' : hasPyproject ? 'python' : hasPomXml ? 'java' : 'unknown',
       workspace,
     };
   }
   ```

3. Add a `LobsterClient` Java bean that invokes it via ProcessBuilder.

4. Modify `ReviewHandler.handle(...)` to:
   - Call `lobsterClient.run("review.pr", args)` first
   - Take the structured result
   - Make ONE Claude call: "summarize this diff given the lint output"
   - Post the comment
   - Skip the ClaudeToolUseLoop entirely for REVIEW jobs

5. Trigger a test PR on photos-to-kml. Compare `ai_calls` before/after:
   - Before: 5-10 LLM calls during the review loop
   - After: 1 LLM call (the summary)

**Expected result:** 5-10x reduction in LLM calls for review jobs.

**Phase 2 — If Phase 1 works:**

Add more Lobster workflows:
- `fix.issue` — the RunFixHandler equivalent
- `plan.from-spec` — the PlannerService equivalent (already mostly
  deterministic, just needs the structured output)
- `quality.daily` — the quality report scheduler
- `upgrade.check` — dependency upgrade detection

Each workflow reduces a multi-iteration tool-use loop to 1-3 LLM calls.

**Phase 3 — Evaluate porting to pure Java:**

If Phase 1+2 show that Lobster is clearly the right design but the
subprocess overhead or Docker image bloat is a problem, port the
workflows to pure Java (Option B). At that point we know exactly
which workflows matter and what their interfaces look like.

## 5. Token savings estimate

**Current state** (Vete PR #64 review, measured):
- PR summary generator: 1 Claude call, ~400 input + 90 output tokens
- ClaudeToolUseLoop iteration 1: ~3900 input + 326 output tokens (then
  failed on Groq max_tokens before iteration 2)

Even truncated at 2 calls, that's ~4300 input + 416 output.

**If the loop had completed** (10 iterations, each carrying accumulated
context from prior iterations): estimated ~40,000 input + 3,000 output
tokens per review. On `openrouter-claude-sonnet` ($3/$15 per MTok):
$0.165 per review.

**With Lobster workflow + 1 summary call:**
- Deterministic clone + diff + lint: 0 tokens (shell commands)
- 1 summary call: ~2,000 input + 300 output tokens (feeding the diff
  stat + top 8k of diff into Claude for summary generation)
- Total: ~2,000 + 300 tokens per review. On `openrouter-claude-sonnet`:
  $0.0105 per review.

**Savings: ~94% per review on token cost. And faster.**

At 100 reviews/week:
- Before: $16.50/week → $66/month
- After: $1.05/week → $4.20/month
- **Saved: ~$62/month**

At the free Groq tier we're currently using, the dollar numbers go to
zero on both sides. But:
- **Latency savings** are still real (~5 seconds → ~1.5 seconds per review)
- **Rate-limit headroom** grows 10x (we can handle 10x more webhooks
  before Groq's 14k/min input-token budget caps us)
- **Reliability** jumps — deterministic code can't fail the way an LLM
  loop can fail ("hmm maybe I should list files again")

## 6. Safety wins (underrated)

Lobster's `approve` primitive is genuinely useful for high-stakes
operations. Imagine `fix.issue` workflow:

```typescript
export async function runFixIssueWorkflow({ args, ctx, approve }) {
  // 1. clone + diff
  // 2. LLM analyzes issue and proposes fix
  // 3. apply fix locally
  // 4. run tests
  // 5. HARD STOP — wait for human to approve
  await approve({
    summary: "Fix for issue #42: change X in file Y",
    diff: localDiff,
    testResults: testOutput,
  });
  // 6. (only runs after approval) commit + push + open PR
}
```

The `approve` call literally pauses execution and persists state to
disk. When the human clicks "approve" in some UI (or posts a comment,
or sends a Telegram reply), the workflow resumes EXACTLY where it
left off.

This is **exactly** the "human-in-the-loop review before agent modifies
a live client repo" guardrail we identified in `docs/NEXT-STEPS.md` as
a P0 risk mitigation.

## 7. Risks and open questions

**R1: Lobster's stability.** `@clawdbot/lobster` is at version
`2026.4.6` (looks like YYYY.M.D versioning — recent). 1097 stars on
GitHub. No security audit I'm aware of. We'd be early adopters.

**Mitigation:** Option A is easy to roll back. If Lobster breaks, we
just stop calling it and fall back to the Claude loop. Nothing is
lost.

**R2: Subprocess overhead.** Node startup is ~100-300ms per invocation.
For a 10-call review loop replaced with 1-call Lobster + 1-call Claude,
we add ~150ms Node startup but save ~5-8 seconds of LLM iteration.
Net win, but worth measuring.

**Mitigation:** if latency matters, we can keep a Node process alive
as a long-running "workflow server" and send RPC to it (Option C).

**R3: Lobster's recipes may not match what we need.** The `github`
recipes shipped are for PR monitoring / diff tracking, not the full
review+fix loop we need. We'll have to write our own workflows
upstream OR as Lobster plugins.

**Mitigation:** write our workflows in `/opt/aiw-lobster/workflows/`
as Lobster plugins. Lobster supports loading external workflow files
via `--workflow-dir`. Upstream them to `openclaw/lobster` as PRs if
they're reusable.

**R4: Is Ivan a maintainer of openclaw/lobster?** The repo is owned
by the `openclaw` GitHub org, not `IvanWeissVanDerPol`. If we need
changes upstream, we'd be submitting PRs to a third-party project.
Response times could be slow.

**Mitigation:** this is manageable. We don't need to modify Lobster
itself for our use case — we just need to write our own workflows
that call Lobster's SDK. Only if we find a genuine Lobster bug do we
need upstream contributions.

## 8. Next-session action plan

If you say "yes, try Lobster integration":

1. **SSH to the VPS, install Lobster** (10 min)
   ```bash
   mkdir -p /opt/aiw-lobster/workflows
   cd /opt/aiw-lobster
   npm init -y
   npm install @clawdbot/lobster
   node node_modules/.bin/lobster --help  # verify install
   ```

2. **Write `review-pr.ts` workflow** (30 min) — deterministic clone +
   diff + lint + structured output. No LLM.

3. **Java side: `LobsterClient` bean** (30 min) — ProcessBuilder wrapper.

4. **Modify `ReviewHandler`** (15 min) — call Lobster first, then
   one Claude call to summarize. Feature-flagged via
   `review.engine=lobster|claude-loop` in application.properties so
   we can roll back instantly.

5. **Smoke test** (10 min) — trigger a PR, measure before/after
   `ai_calls` counts.

**Total: ~90 minutes for a working Phase 1.**

Before/after success criteria:

```sql
-- Before (already measured):
SELECT job_type, COUNT(*), AVG(input_tokens), SUM(input_tokens)
FROM ai_calls WHERE job_type = 'REVIEW' OR job_type = 'PR_SUMMARY'
AND created_at > NOW() - INTERVAL '1 day'
GROUP BY job_type;
-- REVIEW:      7 calls, avg 1151, total 8055
-- PR_SUMMARY:  5 calls, avg 3614, total 18072

-- After Phase 1, target:
-- REVIEW:      0-1 calls per review (down from 5-10)
-- PR_SUMMARY:  1 call per review (unchanged)
-- Total LLM cost per review: ~25% of current
```

## 9. TL;DR

| Question | Answer |
|---|---|
| Is "lobster" a prompt compression tool? | No. That was my wrong guess (LLMLingua). |
| What is Lobster? | TypeScript workflow runtime that replaces LLM tool-use loops with deterministic pipelines. |
| Does it save tokens? | Yes — by eliminating LLM calls for orchestration, not by compressing prompts. 94% savings estimated on review jobs. |
| Can we use it with our Java code-agent? | Yes, via subprocess (Option A) or by porting concepts (Option B). |
| How much work? | ~90 minutes for a working Phase 1 proof of concept. |
| What does it unlock beyond cost? | Determinism, resumability, and the `approve` human-in-the-loop primitive — which is exactly the P0 risk mitigation we need before touching Vete/Solstein for real. |
| Who maintains it? | `openclaw` org on GitHub. MIT license. Active (version 2026.4.6). |
| Recommendation? | **Phase 1 Option A in the next session.** Low-risk, easy to roll back, high-leverage measurable outcome. |

---

*Written after reading github.com/openclaw/lobster directly. The VISION.md
section "The Problem Lobster Solves" describes our code-agent's current
architecture almost verbatim — this integration is not hypothetical.*
