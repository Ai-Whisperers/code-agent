
# Code Agent Runner

A self-hosted coding agent that automates issue fixing, dependency upgrades, AI-powered code reviews, execution planning, documentation generation, and unit test generation. Built with Quarkus (Java 21), it clones your repos from Bitbucket Cloud, Azure DevOps, GitLab, or GitHub, uses Claude (Anthropic) in an agentic tool-use loop to make changes, validates builds, creates pull requests, and keeps JIRA and Teams in sync.

## Architecture

```
              ┌─────────────────────────────────────────────────┐
              │              Entry Points                        │
              │                                                 │
  n8n/JIRA ──►│  POST /run-fix      (full control)              │
              │  POST /quick-fix    (JIRA key + repo)           │
  Aikido ────►│  POST /aikido-fix   (JIRA key only)             │
  JIRA wh ───►│  POST /webhooks/jira (auto on assignment)       │
  BB wh ─────►│  POST /webhooks/bitbucket/pull-request          │
  ADO wh ────►│  POST /webhooks/azuredevops/pull-request        │
  GL wh ─────►│  POST /webhooks/gitlab/merge-request            │
  GH wh ─────►│  POST /webhooks/github/pull-request             │
              │  POST /review-pr    (AI code review)            │
              │  POST /fix-pr       (auto-fix review comments)  │
              │  POST /generate-tests (unit test generation)    │
              │  POST /generate-docs  (documentation gen)       │
              │  POST /plans          (execution plans)         │
              └──────────────┬──────────────────────────────────┘
                             │
               ┌─────────────┼──────────────┐
               ▼             ▼              ▼
         Fix / Upgrade   PR Review     Plan / Docs / Tests
               │             │              │
        AgentRunner    AgentRunner     PlannerService /
        .execute()     .executeReview() AgentRunner
               │             │              │
  ┌────────────┼────────┐    │         ┌────┴────────┐
  ▼            ▼        ▼    ▼         ▼             ▼
Clone repo  Resolve   Load  Compute  Generate     Execute plan
(BB/ADO/    prompt    rules diff     plan via     steps (fix,
 GL/GH)     (JIRA/    (Cursor against  Claude       test, docs)
             Aikido)  rules) target        │
  │            │        │  branch    ┌─────┴─────┐
  └────────────┼────────┘    │       ▼           ▼
               ▼             ▼     DRAFT →   APPROVED →
      Claude tool-use   Index code  Human     Auto-execute
      loop (read/write/ graph +     review    each step
      run/list/search/  impact      & edit
      fetch_url)        analysis
               │             │
               │             ▼
               ▼        Claude review
      mvn test / build  (security, design,
               │        quality, tests,
               ▼        performance)
      git commit & push      │
               │             ▼
        ┌──────┴──────┐ Post inline
        ▼              ▼ comments on PR
 BB/ADO/GL/GH   JIRA + Teams + n8n
 PR (create)    (comment, transition,
                 worklog, notify)
        │
        ▼
 AWAITING_APPROVAL
        │
  ┌─────┴─────┐
  ▼            ▼
POST /approve  POST /reject
(merge PR,     (decline PR,
 JIRA → Done)   JIRA → Rejected)
```


## Endpoints

### Fix & Upgrade

| Method | Path | Description |
|--------|------|-------------|
| POST | `/run-fix` | Submit a fix job with full control over all parameters |
| POST | `/quick-fix` | Simplified: JIRA key + repo URL, prompt from JIRA description |
| POST | `/aikido-fix` | Aikido-driven: resolves everything from JIRA key via Aikido |
| POST | `/sync-jira` | Search JIRA for assigned issues and queue any missing jobs |

### Code Review

| Method | Path | Description |
|--------|------|-------------|
| POST | `/review-pr` | Submit a PR for AI-powered code review |
| POST | `/fix-pr` | Auto-fix a PR based on its review comments |

### Test & Documentation Generation

| Method | Path | Description |
|--------|------|-------------|
| POST | `/generate-tests` | Generate unit tests for a PR or branch |
| POST | `/generate-docs` | Generate documentation for a repository |
| POST | `/sync-confluence` | Sync generated docs to Confluence Cloud |

### Job Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/status/{jobId}` | Poll job status, summary, PR URL, diff stats |
| POST | `/jobs/{jobId}/approve` | Merge the PR, transition JIRA to Done |
| POST | `/jobs/{jobId}/reject` | Decline the PR, add JIRA comment |
| GET | `/health` | Health check with queue status and available slots |

### Execution Plans

| Method | Path | Description |
|--------|------|-------------|
| POST | `/plans` | Generate an execution plan from a specification |
| POST | `/plans/from-jira/{jiraKey}` | Generate a plan from a JIRA ticket |
| POST | `/plans/improve-quality` | Generate a code-quality improvement plan (cyclomatic complexity) |
| GET | `/plans` | List all plans (filterable by status) |
| GET | `/plans/{planId}` | Get a specific plan with all steps |
| PUT | `/plans/{planId}` | Replace plan metadata (title, repo URL, target branch) |
| PATCH | `/plans/{planId}/steps/{stepId}` | Edit a single step (prompt, rules, etc.) |
| POST | `/plans/{planId}/steps` | Add a new step to a plan |
| DELETE | `/plans/{planId}/steps/{stepId}` | Remove a step from a plan |
| POST | `/plans/{planId}/phases` | Add a new phase to a plan |
| DELETE | `/plans/{planId}/phases/{phaseOrder}` | Remove a phase and its steps |
| POST | `/plans/{planId}/approve` | Approve a plan for execution |
| POST | `/plans/{planId}/execute` | Execute an approved plan |
| DELETE | `/plans/{planId}` | Delete a plan |

### Review Memory

| Method | Path | Description |
|--------|------|-------------|
| GET | `/memory/{workspace}/{repoSlug}` | List review memories for a repository |
| POST | `/memory` | Manually add a review memory |
| DELETE | `/memory/{id}` | Deactivate a review memory |

### Repo Settings

| Method | Path | Description |
|--------|------|-------------|
| GET | `/settings/repos` | List all configured repositories and their settings |
| GET | `/settings/repos/{workspace}/{repoSlug}` | Get settings for a specific repository |
| PUT | `/settings/repos/{workspace}/{repoSlug}` | Create or update repository settings |
| PATCH | `/settings/repos/{workspace}/{repoSlug}/enable` | Enable automated review for a repo |
| PATCH | `/settings/repos/{workspace}/{repoSlug}/disable` | Disable automated review for a repo |
| PATCH | `/settings/repos/{workspace}/{repoSlug}/vector/enable` | Enable semantic vector indexing for a repo |
| PATCH | `/settings/repos/{workspace}/{repoSlug}/vector/disable` | Disable semantic vector indexing for a repo |
| PATCH | `/settings/repos/{workspace}/{repoSlug}/docs/enable` | Enable documentation generation for a repo |
| PATCH | `/settings/repos/{workspace}/{repoSlug}/docs/disable` | Disable documentation generation for a repo |
| PATCH | `/settings/repos/{workspace}/{repoSlug}/upgrade/enable` | Enable automatic upgrades for a repo |
| PATCH | `/settings/repos/{workspace}/{repoSlug}/upgrade/disable` | Disable automatic upgrades for a repo |
| DELETE | `/settings/repos/{workspace}/{repoSlug}` | Remove settings (revert to defaults) |

### Automation Hooks

| Method | Path | Description |
|--------|------|-------------|
| GET | `/settings/hooks` | List all automation hooks |
| GET | `/settings/hooks/{name}` | Get a specific hook by name |
| POST | `/settings/hooks` | Create a new automation hook |
| PUT | `/settings/hooks/{name}` | Update an existing hook |
| PATCH | `/settings/hooks/{name}/enable` | Enable a hook |
| PATCH | `/settings/hooks/{name}/disable` | Disable a hook |
| DELETE | `/settings/hooks/{name}` | Delete a hook |

### Prompt Templates

| Method | Path | Description |
|--------|------|-------------|
| GET | `/settings/prompts` | List all prompt templates (defaults + overrides) |
| GET | `/settings/prompts/{key}` | Get a specific prompt template |
| PUT | `/settings/prompts/{key}` | Override a prompt template |
| DELETE | `/settings/prompts/{key}` | Remove override (revert to JSON default) |
| POST | `/settings/prompts/{key}/preview` | Preview a template with placeholder substitution |

### Code Graph

| Method | Path | Description |
|--------|------|-------------|
| POST | `/graph/build-missing` | Build code graphs for all repos that lack one |
| POST | `/graph/rebuild/{workspace}/{repoSlug}` | Rebuild the code graph for a single repository |
| POST | `/graph/detect-archetypes` | Detect framework archetypes for all repos |
| POST | `/graph/detect-archetypes/{workspace}/{repoSlug}` | Detect archetype for a single repo |

### Upgrades

| Method | Path | Description |
|--------|------|-------------|
| POST | `/upgrades/check` | Check all Quarkus repos for available upgrades |
| POST | `/upgrades/check/{workspace}/{repoSlug}` | Check a single repo for upgrades |
| GET | `/upgrades/latest-versions` | Get cached latest versions for tracked frameworks |

### Review Quality Metrics

| Method | Path | Description |
|--------|------|-------------|
| GET | `/metrics/review-quality/{workspace}/{repoSlug}` | Resolution rate, false-positive rate, FP breakdown by category, auto-suppressed pattern count |

### AI Statistics

| Method | Path | Description |
|--------|------|-------------|
| GET | `/stats/ai-calls` | Paginated AI call records with filters (jobType, from, to) |
| GET | `/stats/ai-calls/summary` | Aggregated tokens, cost, and duration by model/job type |
| GET | `/stats/ai-calls/by-job/{jobId}` | AI calls for a specific job with cost estimate |
| GET | `/stats/ai-calls/daily` | Daily aggregated stats for time-series charts |

### Webhooks

| Method | Path | Description |
|--------|------|-------------|
| POST | `/webhooks/jira` | JIRA Cloud — auto-triggers jobs on issue assignment |
| POST | `/webhooks/bitbucket/pull-request` | Bitbucket — auto-review on PR create/update |
| POST | `/webhooks/bitbucket/pull-request-comment` | Bitbucket — reply/fix/learn/fp/generate-tests on agent comment reply |
| POST | `/webhooks/azuredevops/pull-request` | Azure DevOps — auto-review on PR create/update |
| POST | `/webhooks/azuredevops/pull-request-comment` | Azure DevOps — reply/fix/learn/fp when developer replies to agent comment |
| POST | `/webhooks/gitlab/merge-request` | GitLab — auto-review on MR create/update |
| POST | `/webhooks/gitlab/merge-request-comment` | GitLab — reply/fix/learn/fp when developer replies to agent note |
| POST | `/webhooks/github/pull-request` | GitHub — auto-review on PR opened/synchronized/reopened; hook evaluation on merge |
| POST | `/webhooks/github/pull-request-comment` | GitHub — reply/fix/learn/fp/generate-tests on agent comment reply |

All submission endpoints queue jobs instead of rejecting them. Jobs are processed FIFO up to `RUN_FIX_MAX_CONCURRENT_JOBS` in parallel. A 429 is only returned when the queue itself is full (default capacity: 20). Poll `/status/{jobId}` to see queue position.

Swagger UI is available at `/q/swagger-ui`.

---

### POST /run-fix (full control)

```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "branchName": "agent/PROJ-123-upgrade-log4j",
  "jiraKey": "PROJ-123",
  "prompt": "Upgrade log4j from 2.19.0 to 2.23.1 in this Maven project",
  "targetBranch": "main",
  "n8nWebhookUrl": "https://n8n.example.com/webhook/abc",
  "rulesRepoUrl": "https://bitbucket.org/workspace/cursor-rules.git",
  "ruleNames": ["java-conventions", "maven-standards"],
  "extraRules": "Do not modify test files"
}
```

Required: `repoUrl`, `branchName`, `jiraKey`. The `prompt` is optional — if omitted, it's fetched from the JIRA ticket description.

### POST /quick-fix (simplified)

```json
{
  "repoUrl": "https://bitbucket.org/csarenergy/ms-meter.git",
  "jiraKey": "JTP-10967"
}
```

Auto-generates branch name from JIRA summary (e.g. `agent/JTP-10967-upgrade-cxf-xjc-boolean`), uses `develop` as base branch, fetches prompt from JIRA description.

### POST /aikido-fix (Aikido Security integration)

```json
{
  "jiraKey": "JTP-10967"
}
```

The simplest endpoint — only a JIRA key is needed. The agent:

1. **Resolves the Aikido issue** using three strategies (in order):
   - Search Aikido open issues linked to the JIRA key
   - Parse the JIRA description for an Aikido URL (e.g. `app.aikido.dev/...?groupId=123`)
   - Use `aikidoGroupId` if provided directly
2. **Fetches vulnerability context** from Aikido: package name, current/fixed versions, CVE details (severity, CVSS, description), and changelog summary
3. **Resolves the repository URL** from Aikido (or override with `repoUrl`)
4. **Builds an enriched prompt** with full vulnerability details for Claude
5. **Auto-generates** the branch name and uses `develop` as base branch

Optional fields: `aikidoGroupId` (skip JIRA lookup), `repoUrl` (override Aikido repo), `ruleNames` (load specific rules from the shared rules repo configured via `RULES_REPO_URL`), `extraRules` (inline additional instructions appended to the system prompt).

Example with rules:
```json
{
  "jiraKey": "JTP-10967",
  "ruleNames": ["java-conventions", "security-standards"],
  "extraRules": "Ensure backward compatibility with Java 17"
}
```

Response:
```json
{
  "jobId": "550e8400-...",
  "branch": "agent/JTP-10967-cxf-xjc-boolean-1.1.0",
  "aikidoIssue": {
    "groupId": 22926095,
    "package": "cxf-xjc-boolean",
    "currentVersion": "1.0.0",
    "fixedVersion": "1.1.0",
    "cve": "CVE-2024-XXXXX",
    "severity": "critical"
  }
}
```

### POST /review-pr (AI code review)

```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "prId": "42",
  "targetBranch": "main",
  "jiraKey": "PROJ-123",
  "rulesRepoUrl": "https://bitbucket.org/workspace/cursor-rules.git",
  "ruleNames": ["java-conventions"],
  "extraRules": "Pay special attention to thread safety in concurrent code"
}
```

Required: `repoUrl`, `prId`. The agent clones the repo, computes the diff against the target branch, and runs an AI-powered review covering security, design, code quality, testing coverage, performance, and best practices. Findings are posted as inline comments on the PR.

### POST /fix-pr (auto-fix review comments)

```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "prId": "42",
  "jiraKey": "PROJ-123"
}
```

Required: `repoUrl`, `prId`. Fetches review comments from the PR, runs the AI agent to address each comment, and pushes fixes to the PR branch.

### POST /jobs/{jobId}/reject

```json
{
  "reason": "Changes are too broad"
}
```

### POST /webhooks/jira (webhook)

Receives JIRA Cloud webhook payloads. No request body to construct — JIRA sends this automatically.

**Trigger:** assign any issue to the agent user in JIRA.

**Response (job triggered):**
```json
{
  "action": "job_triggered",
  "jobId": "...",
  "jiraKey": "JTP-10967",
  "branch": "agent/JTP-10967-cxf-xjc-boolean-fix"
}
```

**Response (ignored):**
```json
{
  "action": "ignored",
  "reason": "Not assigned to agent user"
}
```

### POST /webhooks/bitbucket/pull-request (auto-review)

Receives Bitbucket Cloud webhook payloads for `pullrequest:created` and `pullrequest:updated` events. Automatically triggers an AI code review job. Skips PRs authored by the agent itself (configurable via `REVIEW_WEBHOOK_SKIP_AUTHORS`).

### POST /webhooks/bitbucket/pull-request-comment (conversational reply)

Receives Bitbucket Cloud webhook payloads for `pullrequest:comment_created` events. When a developer replies to one of the agent's review comments, the agent classifies the intent and triggers the appropriate action:

| Reply | Action |
|-------|--------|
| `/fp` or `/false-positive` | Mark as false positive, close thread, trigger auto-suppression if threshold reached |
| `/learn <preference>` | Store team preference in review memory |
| Fix request (natural language) | Modify code and push to branch |
| Discussion (natural language) | Reply conversationally in the same thread |

### GET /metrics/review-quality/{workspace}/{repoSlug}

Returns review quality metrics for a repository:

```json
{
  "workspace": "my-workspace",
  "repoSlug": "my-repo",
  "totalFindings": 142,
  "resolvedByDeveloper": 98,
  "resolutionRate": 0.69,
  "falsePositives": 12,
  "fpRate": 0.085,
  "fpByCategory": {
    "Code Quality": 7,
    "Best Practices": 4,
    "Security": 1
  },
  "autoSuppressedPatterns": 2
}
```

### POST /webhooks/azuredevops/pull-request (auto-review)

Receives Azure DevOps Service Hook payloads for `git.pullrequest.created` and `git.pullrequest.updated` events. Same behavior as the Bitbucket PR webhook.

### POST /webhooks/azuredevops/pull-request-comment (conversational reply)

Receives Azure DevOps Service Hook payloads for PR comment events. Same behavior as the Bitbucket comment webhook.

### POST /sync-jira (active polling)

No request body needed. Searches JIRA for all open issues (`statusCategory != Done`) with the configured label (default: `WALL-E`) and queues fix jobs for any that don't already have an active job in the queue.

Useful as a catch-up mechanism (e.g. via a cron/scheduler) to pick up issues that were labelled while the agent was down, or as a manual trigger. Just add the `WALL-E` label to any JIRA issue to have the agent pick it up on the next sync.

```bash
curl -X POST http://localhost:8080/sync-jira
```

**Response:**
```json
{
  "found": 5,
  "queued": 2,
  "queuedJobs": [
    { "key": "JTP-10967", "jobId": "...", "branch": "agent/JTP-10967-upgrade-log4j" },
    { "key": "JTP-10980", "jobId": "...", "branch": "agent/JTP-10980-fix-null-check" }
  ],
  "skipped": [
    { "key": "JTP-10950", "reason": "Active job exists" },
    { "key": "JTP-10960", "reason": "Active job exists" },
    { "key": "JTP-10970", "reason": "No repo URL available" }
  ]
}
```

## Configuration

All config via environment variables (or `application.properties` for local dev):

### Core

| Variable | Description | Default |
|----------|-------------|---------|
| `ANTHROPIC_API_KEY` | Anthropic API key | (required) |
| `ANTHROPIC_MODEL` | Claude model ID | `claude-sonnet-4-20250514` |
| `ANTHROPIC_MAX_TOKENS` | Max tokens per API call | `8192` |
| `ANTHROPIC_FAST_MODEL` | Faster model for binary decisions (intent classification, finding resolution, learning extraction) | `claude-haiku-4-5` |
| `ANTHROPIC_TOKENS_PER_MINUTE` | Rate-limit budget (tokens/min for your API tier) | `80000` |
| `ANTHROPIC_RATE_LIMIT_SAFETY_MARGIN` | Fraction of budget to stay under (proactive throttling) | `0.80` |
| `ANTHROPIC_PRICING_INPUT` | USD per million input tokens | `3.0` |
| `ANTHROPIC_PRICING_OUTPUT` | USD per million output tokens | `15.0` |
| `ANTHROPIC_PRICING_CACHE_WRITE` | USD per million cache-write tokens | `3.75` |
| `ANTHROPIC_PRICING_CACHE_READ` | USD per million cache-read tokens | `0.30` |

### JIRA Cloud

| Variable | Description | Default |
|----------|-------------|---------|
| `JIRA_BASE_URL` | JIRA Cloud URL (e.g. `https://you.atlassian.net`) | (required) |
| `JIRA_USER` | JIRA user email | (required) |
| `JIRA_API_TOKEN` | Atlassian API token | (required) |
| `JIRA_TRANSITION_IN_REVIEW` | Transition ID for "In Review" | (optional) |
| `JIRA_TRANSITION_DONE` | Transition ID for "Done" | (optional) |
| `JIRA_TRANSITION_REJECTED` | Transition ID for rejected | (optional) |
| `JIRA_DEFAULT_WORKLOG` | Default time logged per fix | `30m` |
| `JIRA_AGENT_ASSIGNEE` | Display name, email, or account ID of the agent user in JIRA | (optional) |
| `JIRA_AGENT_LABEL` | JIRA label used by `/sync-jira` to find issues | `WALL-E` |
| `JIRA_AGENT_DEFAULT_REPO_URL` | Default repo URL when not resolvable from Aikido | (optional) |

### Git Platform

| Variable | Description | Default |
|----------|-------------|---------|
| `GIT_PLATFORM` | SCM platform: `bitbucket`, `azuredevops`, `gitlab`, or `github` | `bitbucket` |
| `GIT_USERNAME` | Git clone/push user (defaults to platform user) | |
| `GIT_PASSWORD` | Git clone/push password (defaults to platform token) | |
| `GIT_AUTHOR_NAME` | Git commit author name | `code-agent` |
| `GIT_AUTHOR_EMAIL` | Git commit author email | (optional) |

### Bitbucket Cloud

| Variable | Description | Default |
|----------|-------------|---------|
| `BITBUCKET_BASE_URL` | Bitbucket Cloud API base | `https://api.bitbucket.org/2.0` |
| `BITBUCKET_WORKSPACE` | Bitbucket workspace slug | (required) |
| `BITBUCKET_USER` | Bitbucket username | (required) |
| `BITBUCKET_APP_PASSWORD` | Bitbucket App Password | (required) |

### Azure DevOps

| Variable | Description | Default |
|----------|-------------|---------|
| `AZUREDEVOPS_BASE_URL` | Azure DevOps base URL | `https://dev.azure.com` |
| `AZUREDEVOPS_PAT` | Azure DevOps Personal Access Token | (required) |
| `AZUREDEVOPS_AGENT_USER` | Agent user display name in Azure DevOps | (optional) |

### GitLab

| Variable | Description | Default |
|----------|-------------|---------|
| `GITLAB_BASE_URL` | GitLab API base URL | `https://gitlab.com/api/v4` |
| `GITLAB_TOKEN` | GitLab Personal Access Token or Group Token | (required) |
| `GITLAB_AGENT_USER` | Agent user display name in GitLab | (optional) |

### GitHub

| Variable | Description | Default |
|----------|-------------|---------|
| `GITHUB_BASE_URL` | GitHub API base URL | `https://api.github.com` |
| `GITHUB_TOKEN` | GitHub Personal Access Token or fine-grained token | (required) |
| `GITHUB_AGENT_USER` | Agent user login in GitHub (used to skip self-authored PRs and comments) | (optional) |

### Notifications

| Variable | Description | Default |
|----------|-------------|---------|
| `TEAMS_WEBHOOK_URL` | Teams incoming webhook URL | (optional) |
| `N8N_WEBHOOK_URL` | Default n8n webhook URL | (optional) |

### Cursor Rules

| Variable | Description | Default |
|----------|-------------|---------|
| `RULES_REPO_URL` | Default shared Cursor rules repo | (optional) |
| `RULES_REPO_CACHE_DIR` | Local cache for rules repo | `/tmp/cursor-rules-cache` |
| `RULES_AUTO_READ_TARGET_REPO` | Auto-load `.cursor/rules` from target repo | `true` |

### Aikido Security

| Variable | Description | Default |
|----------|-------------|---------|
| `AIKIDO_CLIENT_ID` | Aikido OAuth2 client ID (Settings > Public API) | (optional) |
| `AIKIDO_CLIENT_SECRET` | Aikido OAuth2 client secret | (optional) |
| `AIKIDO_CI_API_SECRET` | Aikido CI integration token (for post-PR scan) | (optional) |
| `AIKIDO_BASE_URL` | Aikido API base URL | `https://app.aikido.dev` |

### Security

| Variable | Description | Default |
|----------|-------------|---------|
| `API_KEY` | Shared API key to protect REST endpoints (blank = disabled) | (optional) |
| `WEBHOOK_SECRET_BITBUCKET` | HMAC-SHA256 secret for Bitbucket webhooks | (optional) |
| `WEBHOOK_SECRET_AZUREDEVOPS` | HMAC/basic-auth secret for Azure DevOps webhooks | (optional) |
| `WEBHOOK_SECRET_GITLAB` | Secret for GitLab webhook verification | (optional) |
| `WEBHOOK_SECRET_GITHUB` | HMAC-SHA256 secret for GitHub webhooks | (optional) |
| `WEBHOOK_SECRET_JIRA` | Secret for JIRA webhook verification | (optional) |

### PR Review

| Variable | Description | Default |
|----------|-------------|---------|
| `REVIEW_WEBHOOK_SKIP_AUTHORS` | Comma-separated PR authors to skip (avoid self-review) | `code-agent` |
| `REVIEW_WEBHOOK_REQUIRE_TITLE_KEYWORD` | Only review PRs whose title contains this keyword | (optional) |
| `REVIEW_FP_AUTO_SUPPRESS_THRESHOLD` | Number of `/fp` marks on the same pattern before auto-suppression creates a memory entry | `3` |
| `REVIEW_PR_SUMMARY_ENABLED` | Generate a PR summary & walkthrough comment before the detailed review | `true` |
| `REVIEW_SEQUENCE_DIAGRAMS_ENABLED` | Include Mermaid sequence/class diagrams in the PR summary | `true` |
| `PR_SUMMARY_DIAGRAM_UPLOAD_ENABLED` | Render diagrams as PNG via mmdc and upload to Bitbucket Downloads (instead of mermaid.ink URLs) | `true` |

### Planner

| Variable | Description | Default |
|----------|-------------|---------|
| `PLANNER_ENABLED` | Enable execution plan generation | `true` |
| `PLANNER_MAX_TOKENS` | Max tokens for plan generation AI call | `8192` |

### Code Metrics

| Variable | Description | Default |
|----------|-------------|---------|
| `METRICS_CC_THRESHOLD` | Maximum cyclomatic complexity per method before flagging | `10` |
| `METRICS_MAX_ITERATIONS` | Maximum FIX→METRICS improvement iterations per quality plan | `3` |
| `METRICS_MAX_METHODS_PER_FIX` | Maximum high-CC methods per FIX step prompt | `10` |
| `METRICS_JOB_TIMEOUT_MINUTES` | Timeout for cloning and scanning in a METRICS job | `30` |

### Unit Test Generation

| Variable | Description | Default |
|----------|-------------|---------|
| `GENERATE_TESTS_MAX_LOOP_ITERATIONS` | Max agentic loop iterations for test generation | `500` |
| `GENERATE_TESTS_JOB_TIMEOUT_MINUTES` | Overall timeout for test generation jobs | `60` |

### Documentation Generation

| Variable | Description | Default |
|----------|-------------|---------|
| `GENERATE_DOCS_MAX_LOOP_ITERATIONS` | Max agentic loop iterations for docs generation | `200` |

### Confluence Cloud

| Variable | Description | Default |
|----------|-------------|---------|
| `CONFLUENCE_BASE_URL` | Confluence Cloud REST API base URL | (optional) |
| `CONFLUENCE_USER` | Confluence user email | (optional) |
| `CONFLUENCE_API_TOKEN` | Atlassian API token for Confluence | (optional) |

Per-repo Confluence space key and parent page ID are configured in repo settings.

### Documentation Lookup (fetch_url tool)

| Variable | Description | Default |
|----------|-------------|---------|
| `TOOLS_FETCH_URL_ENABLED` | Enable the `fetch_url` tool during agent loops | `true` |
| `TOOLS_FETCH_URL_TIMEOUT` | HTTP timeout in seconds | `15` |
| `TOOLS_FETCH_URL_ALLOWED_DOMAINS` | Comma-separated domain allowlist (blank = allow all public HTTPS) | `quarkus.io,search.maven.org` |

### Upgrade Scheduler

| Variable | Description | Default |
|----------|-------------|---------|
| `UPGRADE_SCHEDULER_ENABLED` | Enable daily Quarkus upgrade checks | `false` |
| `UPGRADE_SCHEDULER_DEFAULT_BRANCH` | Default branch to compare against | `develop` |
| `UPGRADE_VERSION_CACHE_MINUTES` | Cache duration for Maven Central version lookups | `60` |

### Job Queue & Guardrails

| Variable | Description | Default |
|----------|-------------|---------|
| `RUN_FIX_MAX_CONCURRENT_JOBS` | Max jobs running in parallel | `3` |
| `RUN_FIX_MAX_QUEUE_SIZE` | Max jobs waiting in the queue | `20` |
| `RUN_FIX_BLOCKED_PATHS` | Comma-separated blocked paths | `src/main/security,...` |
| `RUN_FIX_ALLOWED_COMMANDS` | Comma-separated allowed command prefixes | `mvn,git diff,...` |
| `RUN_FIX_MAX_FILES_CHANGED` | Max files the agent may change | `10` |
| `RUN_FIX_MAX_LINES_CHANGED` | Max lines the agent may change | `500` |
| `RUN_FIX_MAX_LOOP_ITERATIONS` | Max agentic loop iterations | `150` |
| `RUN_FIX_JOB_TIMEOUT_MINUTES` | Overall job timeout | `30` |

### AWS Bedrock (Semantic Search)

| Variable | Description | Default |
|----------|-------------|---------|
| `BEDROCK_REGION` | AWS region for Bedrock API calls | `eu-central-1` |
| `BEDROCK_CODE_EMBEDDING_MODEL` | Embedding model for code indexing and code search queries (→ `code_embeddings` table) | `cohere.embed-multilingual-v3` |
| `BEDROCK_TEXT_EMBEDDING_MODEL` | Embedding model for knowledge/docs indexing and knowledge search queries (→ `knowledge_embeddings` table) | `amazon.titan-embed-text-v2:0` |
| `BEDROCK_RERANK_MODEL` | Rerank model for the code semantic search pipeline | `amazon.rerank-v1:0` |
| `EMBEDDING_MAX_SOURCE_CHARS` | Max source code characters per symbol embedding | `16000` |

No API key is required. Credentials are resolved via the standard AWS credential chain (IAM role on ECS/EC2; `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` env vars or `~/.aws` in dev). Vector indexing is controlled per-repo via `repo_settings.vector_enabled` (default: `false`).

Both models produce **1024-dimensional vectors**, matching the existing `vector(1024)` columns — no database migration is needed.

**GDPR / data privacy:** AWS Bedrock does not use customer inputs to train models. All calls stay in the configured region. To fully opt out of any AWS AI service improvement usage, apply an AI services opt-out policy in AWS Organizations (Policies → AI services opt-out policies).

### Code Graph

| Variable | Description | Default |
|----------|-------------|---------|
| `CODE_GRAPH_SCHEDULER_ENABLED` | Enable background graph pre-building | `true` |
| `CODE_GRAPH_SCHEDULER_DEFAULT_BRANCH` | Default branch to clone for graph builds | `main` |
| `CODE_GRAPH_SCHEDULER_CLONE_TIMEOUT` | Clone timeout in minutes for scheduled builds | `10` |
| `CODE_GRAPH_CROSS_REPO_ENABLED` | Enable cross-repo impact analysis and queries | `true` |
| `CODE_GRAPH_CROSS_REPO_CRITICAL_THRESHOLD` | Number of distinct repos using a symbol before labelling it CRITICAL | `3` |

### Linter / SAST

| Variable | Description | Default |
|----------|-------------|---------|
| `LINTER_ENABLED` | Enable linter integration | `true` |
| `LINTER_CHECKSTYLE_ENABLED` | Enable Checkstyle (Java) | `true` |
| `LINTER_PMD_ENABLED` | Enable PMD (Java) | `true` |
| `LINTER_SPOTBUGS_ENABLED` | Enable SpotBugs (Java) | `true` |
| `LINTER_ESLINT_ENABLED` | Enable ESLint (JavaScript/TypeScript) | `true` |
| `LINTER_DOTNET_FORMAT_ENABLED` | Enable dotnet-format (C#) | `true` |
| `LINTER_MAX_FIX_ITERATIONS` | Max iterations for auto-fixing linter issues | `2` |
| `LINTER_FAIL_ON_NEW_ISSUES` | Fail the build on new linter issues | `false` |
| `LINTER_TIMEOUT_MINUTES` | Linter execution timeout | `10` |

### Database

| Variable | Description | Default |
|----------|-------------|---------|
| `DATABASE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/code_agent` |
| `DATABASE_USER` | Database user | `code_agent` |
| `DATABASE_PASSWORD` | Database password | (required) |

### Finding JIRA transition IDs

```bash
curl -u user@email.com:API_TOKEN \
  https://your-domain.atlassian.net/rest/api/3/issue/PROJ-123/transitions
```

## Build

```bash
mvn -B package -DskipTests
```

## Run locally

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export JIRA_BASE_URL=https://your-domain.atlassian.net
export JIRA_USER=you@example.com
export JIRA_API_TOKEN=your-token
export BITBUCKET_USER=your-bb-user
export BITBUCKET_APP_PASSWORD=your-app-password
export BITBUCKET_WORKSPACE=your-workspace
export DATABASE_PASSWORD=your-db-password
# Bedrock uses the AWS credential chain — no API key needed if an IAM role is attached.
# For local dev, ensure ~/.aws/credentials is configured or set AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY.

mvn quarkus:dev
```

**Note:** Semantic search requires pgvector installed in your local PostgreSQL. See [Semantic search — local setup](#semantic-search-cross-repo-vector-search) for instructions.

The server starts on `http://localhost:8080`. Swagger UI is available at `http://localhost:8080/q/swagger-ui`.

## Docker

```bash
docker build -t code-agent-runner .

docker run -p 8080:8080 \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e JIRA_BASE_URL=https://your-domain.atlassian.net \
  -e JIRA_USER=you@example.com \
  -e JIRA_API_TOKEN=... \
  -e BITBUCKET_USER=... \
  -e BITBUCKET_APP_PASSWORD=... \
  -e BITBUCKET_WORKSPACE=... \
  -e DATABASE_URL=jdbc:postgresql://host:5432/code_agent \
  -e DATABASE_USER=code_agent \
  -e DATABASE_PASSWORD=... \
  code-agent-runner
```

## AWS Deployment (ECS Fargate)

This section covers deploying Code Agent Runner on AWS ECS Fargate with proper security practices.

### Prerequisites

- An AWS account with ECS, ECR, Secrets Manager, and RDS access
- A VPC with private subnets and a NAT gateway (the agent needs outbound internet for Bitbucket, JIRA, Anthropic, and Aikido APIs)
- A PostgreSQL database (RDS recommended) accessible from the ECS tasks
- The Docker image pushed to ECR

### 1. Push the Docker image

Build and push to your ECR registry:

```bash
aws ecr get-login-password --region eu-central-1 | docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.eu-central-1.amazonaws.com

docker buildx build --platform=linux/amd64 -t <ACCOUNT_ID>.dkr.ecr.eu-central-1.amazonaws.com/julesenergy/code-agent-runner:1.0.0-SNAPSHOT .

docker push <ACCOUNT_ID>.dkr.ecr.eu-central-1.amazonaws.com/julesenergy/code-agent-runner:1.0.0-SNAPSHOT
```

**Cross-account ECR pull:** If the image lives in a different account, add a resource policy on the source ECR repository:

```bash
aws ecr set-repository-policy \
  --repository-name julesenergy/code-agent-runner \
  --region eu-central-1 \
  --policy-text '{
    "Version": "2012-10-17",
    "Statement": [{
      "Sid": "AllowCrossAccountPull",
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::<TARGET_ACCOUNT_ID>:root" },
      "Action": [
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "ecr:BatchCheckLayerAvailability"
      ]
    }]
  }'
```

### 2. Store secrets in AWS Secrets Manager

Never put credentials in the task definition as plaintext environment variables. Store them in Secrets Manager:

```bash
aws secretsmanager create-secret \
  --name code-agent-runner/secrets \
  --region eu-central-1 \
  --secret-string '{
    "ANTHROPIC_API_KEY": "sk-ant-...",
    # No VOYAGE_API_KEY needed — Bedrock uses the ECS task IAM role for auth.
    "JIRA_API_TOKEN": "ATATT3x...",
    "BITBUCKET_APP_PASSWORD": "ATCTT3x...",
    "DATABASE_PASSWORD": "...",
    "TEAMS_WEBHOOK_URL": "https://...",
    "AIKIDO_CLIENT_SECRET": "AIK_SECRET_...",
    "NEXUS_PASSWORD": "..."
  }'
```

### 3. Create IAM roles

**Execution role** — used by ECS to pull images and inject secrets:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "arn:aws:secretsmanager:eu-central-1:<ACCOUNT_ID>:secret:code-agent-runner/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    }
  ]
}
```

The role's trust policy must allow `ecs-tasks.amazonaws.com` to assume it.

**Task role** — only needed if the application calls AWS services at runtime. A minimal empty role is sufficient for most setups.

### 4. Register the task definition

Key settings for the task definition:

| Setting | Recommended value | Notes |
|---------|-------------------|-------|
| CPU | 2048 (2 vCPU) | Increase to 4096 if running 3+ concurrent jobs on large repos |
| Memory | 4096 (4 GB) | Increase to 8192 for heavy workloads |
| Platform | `LINUX/X86_64` | The Docker image is built for `linux/amd64` |
| Ephemeral storage | 30–50 GB | Default is 20 GB; increase if cloning many large repos concurrently |

Secrets should reference Secrets Manager using `valueFrom` with the secret ARN and JSON key:

```json
"secrets": [
  {
    "name": "ANTHROPIC_API_KEY",
    "valueFrom": "arn:aws:secretsmanager:eu-central-1:<ACCOUNT_ID>:secret:code-agent-runner/secrets:ANTHROPIC_API_KEY::"
  },
  {
    "name": "JIRA_API_TOKEN",
    "valueFrom": "arn:aws:secretsmanager:eu-central-1:<ACCOUNT_ID>:secret:code-agent-runner/secrets:JIRA_API_TOKEN::"
  }
]
```

Non-sensitive configuration (model names, JIRA base URL, guardrail settings, etc.) can be set as plain `environment` entries.

### 5. Health checks

The application includes the `quarkus-smallrye-health` extension. Configure the ECS health check:

```json
"healthCheck": {
  "command": ["CMD-SHELL", "curl -f http://localhost:8080/q/health/ready || exit 1"],
  "interval": 30,
  "timeout": 5,
  "retries": 3,
  "startPeriod": 60
}
```

If using an ALB, point the target group health check at `/q/health/ready`.

### 6. Networking

- Place ECS tasks in **private subnets** with a NAT gateway for outbound access.
- The agent needs outbound HTTPS (443) to: Bitbucket API, JIRA Cloud, Anthropic API, Aikido API, and optionally Teams/n8n webhooks.
- Use a **security group** that allows inbound on port 8080 only from your ALB or VPN.
- The database security group should allow inbound PostgreSQL (5432) only from the ECS task security group.

### 7. Nexus / private Maven mirror

If the repositories the agent builds depend on private artifacts from a Nexus server, the Docker image includes a `settings.xml` that reads Nexus credentials from environment variables at runtime:

| Variable | Description |
|----------|-------------|
| `NEXUS_URL` | Full URL to the Nexus repository (e.g. `https://nexus.company.com/repository/maven-public/`) |
| `NEXUS_USERNAME` | Nexus username |
| `NEXUS_PASSWORD` | Nexus password (store in Secrets Manager) |

The `settings.xml` configures Nexus as a mirror for all Maven repositories (`<mirrorOf>*</mirrorOf>`), so any `mvn` command the agent runs against target repos will resolve artifacts through your Nexus.

### 8. Service setup

Create an ECS service with:

- **Desired count:** 1 (the agent handles concurrency internally via its job queue)
- **Deployment:** rolling update (minimum healthy 100%, maximum 200%)
- **Load balancer:** Application Load Balancer with HTTPS listener if you need to receive webhooks from the public internet
- **Auto-scaling:** Generally not needed — scale the internal job queue settings instead (`RUN_FIX_MAX_CONCURRENT_JOBS`)

## Bitbucket Configuration

### Token

The agent uses a GitHub Personal Access Token (classic) or fine-grained token for Git operations and the GitHub REST API.

1. Go to **Settings > Developer settings > Personal access tokens**
2. Create a token with these scopes:
   - **repo** (full control of private repositories)
   - Or for fine-grained tokens: Contents (read/write), Pull requests (read/write), Issues (read)
3. Set the credentials:

| Variable | Value |
|----------|-------|
| `GIT_PLATFORM` | `github` |
| `GITHUB_TOKEN` | The generated token |
| `GITHUB_AGENT_USER` | Your GitHub username or bot login |
| `GIT_USERNAME` | `x-access-token` |
| `GIT_PASSWORD` | Same as `GITHUB_TOKEN` |

### Webhooks

#### PR auto-review webhook

1. Go to **Repository Settings > Webhooks** (or configure at org level)
2. Click **Add webhook**:
   - **Payload URL:** `https://<your-agent-host>/webhooks/github/pull-request`
   - **Content type:** `application/json`
   - **Secret:** A random string (set the same value as `WEBHOOK_SECRET_GITHUB`)
   - **Events:** select `Pull requests`
3. The agent reviews on `opened`, `synchronize`, and `reopened` events. On `closed` (merged), it evaluates automation hooks.

#### PR comment interaction webhook

1. Add another webhook (or add events to the same one):
   - **Payload URL:** `https://<your-agent-host>/webhooks/github/pull-request-comment`
   - **Secret:** Same as above
   - **Events:** select `Pull Request: Comment Created`
2. This enables developers to reply to agent review comments and have the agent respond or fix code

#### JIRA auto-trigger webhook

See [JIRA Webhook (auto-trigger on assignment)](#jira-webhook-auto-trigger-on-assignment) for setup instructions. When the agent creates PRs from JIRA-triggered jobs, it uses the Bitbucket credentials above.

### Security best practices

- **Webhook signature verification:** Always set `WEBHOOK_SECRET_BITBUCKET` in production. The agent verifies incoming webhooks via HMAC-SHA256 using the `X-Hub-Signature` header. Without this, anyone who discovers the webhook URL can trigger jobs.
- **API key protection:** Set `API_KEY` to require an `X-API-Key` header on all REST endpoints. Health checks and Swagger UI are excluded. Webhook endpoints use their own signature verification instead.
- **Least-privilege App Password:** Only grant the Bitbucket App Password the minimum permissions needed (repo read/write, PR read/write). Do not grant admin or webhook management scopes.
- **Secrets management:** Never store API tokens, passwords, or webhook secrets as plaintext environment variables in ECS task definitions. Use AWS Secrets Manager with `valueFrom` references. Rotate credentials periodically.
- **Network isolation:** Run ECS tasks in private subnets. Expose the agent only through an ALB with HTTPS. Restrict the ALB security group to known IP ranges (Bitbucket webhook IPs, your office VPN, JIRA Cloud IPs).
- **Blocked paths:** The agent respects `RUN_FIX_BLOCKED_PATHS` to prevent modifications to sensitive directories (security modules, billing code, CI config, `.env` files). Review and adjust this list for your codebase.
- **Command allowlist:** Only commands matching `RUN_FIX_ALLOWED_COMMANDS` prefixes can be executed by the agent. This prevents arbitrary command execution.
- **Change limits:** `RUN_FIX_MAX_FILES_CHANGED` and `RUN_FIX_MAX_LINES_CHANGED` cap the blast radius of any single job. The agent aborts if it exceeds these limits.
- **Job timeout:** `RUN_FIX_JOB_TIMEOUT_MINUTES` (default 30) prevents runaway jobs from consuming resources indefinitely.
- **Bitbucket IP allowlisting:** If your ALB is public, restrict inbound to [Bitbucket Cloud's IP ranges](https://support.atlassian.com/organization-administration/docs/ip-addresses-and-domains-for-atlassian-cloud-products/) for the webhook paths and your own network for the API paths.

## Cursor Rules Integration

The runner loads coding standards from two sources:

1. **Shared rules repo** — pass `rulesRepoUrl` and optional `ruleNames` in the request. The runner clones/caches the repo and loads `.cursor/rules/{name}.mdc` files by name. If `ruleNames` is omitted, all `alwaysApply: true` rules are loaded.

2. **Target repo** — after cloning the repo being fixed, the runner scans for `.cursor/rules/*.mdc`, `.cursorrules`, and `AGENTS.md`. All `alwaysApply: true` rules are included automatically.

Rules are prepended to the system prompt in order: shared rules, repo rules, inline `extraRules`, then mandatory guardrails. This ensures the agent follows the same conventions your team uses in Cursor IDE.

## AI Code Review

The agent performs automated code reviews on pull requests, triggered either via the `/review-pr` endpoint or automatically through Bitbucket/Azure DevOps/GitLab webhooks.

### PR Summary & Walkthrough

When a PR is reviewed, the agent first generates a CodeRabbit-style summary comment with:

- **High-level summary** — 2-3 sentences describing the purpose and impact of the PR
- **Walkthrough table** — per-file breakdown of what changed and why

This comment is posted before the detailed code review begins, so developers get an immediate overview of the PR while the full review runs. On re-reviews (new commits pushed), the summary comment is updated in-place rather than duplicated.

**Example output:**

> ## PR Summary
>
> ### Walkthrough
> Adds a new caching layer for the user service that reduces database round-trips for frequently accessed profiles. The implementation uses Caffeine as the in-memory cache with configurable TTL and size limits.
>
> ### Changes
> | File | Summary |
> |------|---------|
> | `src/main/java/UserCacheService.java` | New Caffeine-based cache service with TTL and eviction support |
> | `src/main/java/UserService.java` | Updated to delegate reads through the cache layer |
> | `pom.xml` | Added `com.github.ben-manes.caffeine` dependency |
> | `src/test/java/UserCacheServiceTest.java` | Unit tests for cache hit, miss, and eviction scenarios |

Disable per-environment with `REVIEW_PR_SUMMARY_ENABLED=false`.

### What it reviews

- **Security** — injection vulnerabilities, hardcoded secrets, auth issues
- **Design** — SOLID principles, separation of concerns, API design
- **Code quality** — naming, complexity, duplication, error handling
- **Testing** — coverage gaps, missing edge cases, test quality
- **Performance** — N+1 queries, unnecessary allocations, algorithm complexity
- **Best practices** — framework conventions, idiomatic patterns

### Contextual repo exploration

Before generating findings, the agent proactively explores the cloned repository for context. It uses five tools:

- **`search_code`** — grep-based code search over the full repo (e.g. find all callers of a changed method, locate interface implementations). Accepts a `pattern` (required), optional directory `path`, and optional file-type `include` glob (e.g. `*.java`).
- **`query_code_graph`** — query the per-repo code graph to find callers, implementations, or dependents of a specific symbol. See [Code graph](#code-graph-impact-analysis) below.
- **`semantic_search`** — search across all vector-indexed repositories by meaning. Useful for finding library implementations, shared utilities, or similar patterns in other repos. See [Semantic search](#semantic-search-cross-repo-vector-search) below.
- **`read_file`** — read the full contents of any file in the repo (interfaces, base classes, configuration).
- **`list_files`** — directory listing up to 5 levels deep, configurable via an optional `depth` parameter (default 3, max 5).

This prevents false positives that arise from reviewing a diff without its wider context — the same capability BugBot uses to browse the whole repository.

### Code graph (impact analysis)

The agent maintains a per-repo code graph in PostgreSQL, built from AST analysis (Java via JavaParser with symbol resolution for call edges; C#, TypeScript, and PHP via Tree-sitter CST parsing). On each review the graph is refreshed incrementally (only changed files are re-indexed), and an **Impact Analysis** section is injected into the review prompt showing callers, implementations, and dependents of changed symbols.

For Java, stored symbols use fully qualified type names and method signatures (as returned by the symbol solver). After upgrading the agent, run a **full rebuild** of each repo’s graph (`POST /graph/rebuild/{workspace}/{repoSlug}`) or `POST /graph/build-missing` so existing PostgreSQL rows match the new ID format; otherwise impact queries may be sparse until files are re-indexed naturally.

**How it works:**

1. On the first review of a repository, a full index is built from all `.java`, `.cs`, `.ts`, `.tsx`, and `.php` files in the workspace
2. On subsequent reviews, only the files touched in the diff are re-indexed (typically 5–20 files)
3. The agent queries the graph to find code that depends on the changed symbols and injects this as context
4. During the review tool-use loop, the agent can query the graph on-demand via `query_code_graph`

**Available tools during review:**

- **`query_code_graph`** — find callers, implementations, or dependents of a specific symbol. Parameters: `symbol` (Java: fully qualified type or method signature, e.g. `com.example.Foo` or `com.example.Foo.bar(java.lang.String)`; other languages: `Type.member` as indexed), `relation` (`callers`, `implementations`, or `dependents`)

**Background pre-building:** A scheduler runs every 6 hours to pre-build graphs for repos that don't have one yet, so the first review isn't slowed down by a full index. This can also be triggered on demand via `POST /graph/build-missing`.

**Rebuild a single repo:**
```bash
curl -X POST http://localhost:8080/graph/rebuild/myworkspace/my-repo
```

**Supported languages:** Java (full AST via JavaParser with symbol resolution), C# / TypeScript / PHP (Tree-sitter CST — classes, interfaces, enums, methods, inheritance, imports, and call expressions; version-agnostic, no SDK required). Non-supported files are silently skipped.

**Nested .NET layouts:** When a `.sln` or `.csproj` is not at the repository root, the agent walks up to 3 directory levels to find it and uses `dotnet test <path>` accordingly. Multi-solution repos fall back to `dotnet test` at root with a warning logged.

**Tree-sitter native library:** The `ch.usi.si.seart:java-tree-sitter` JNI library bundles pre-compiled native binaries for `linux/amd64`, `linux/aarch64`, and `darwin/arm64` inside the JAR. No additional `apt` packages or Docker changes are required. If the library fails to load (unsupported platform), indexing falls back to the previous regex-based path automatically.

### Semantic search (cross-repo vector search)

The agent can search across all indexed repositories by meaning using vector embeddings, powered by AWS Bedrock and pgvector. This lets Claude find library implementations, shared utilities, base classes, or similar patterns in other repos during reviews — even when the exact terms don't appear in the code.

**How it works:**

1. When vector indexing is enabled for a repo, the agent extracts class and method source code and generates vector embeddings via AWS Bedrock (`cohere.embed-multilingual-v3` by default)
2. Embeddings are stored in PostgreSQL using pgvector (1024-dimensional vectors with IVFFlat indexing)
3. During reviews, Claude can invoke `semantic_search` with a natural language query (e.g. "payment refund handling logic")
4. The query is embedded and matched against all vector-indexed repos using cosine similarity
5. Claude receives the top matching code snippets with file paths, repo names, and line numbers

**Enable vector indexing for a repo:**

```bash
curl -X PATCH http://localhost:8080/settings/repos/myworkspace/shared-lib/vector/enable
```

Then rebuild the graph to generate embeddings:

```bash
curl -X POST http://localhost:8080/graph/rebuild/myworkspace/shared-lib
```

Vector indexing is opt-in per repo (default: disabled). Start by enabling it on shared libraries that other repos depend on.

**Available tools during review:**

- **`semantic_search`** — search across all vector-indexed repos by meaning. Parameters: `query` (required, natural language), `repo` (optional, restrict to one repo), `top_k` (optional, default 10, max 25)

**Local setup — pgvector:**

pgvector must be installed in your PostgreSQL instance. On macOS with Homebrew:

```bash
# Check your PostgreSQL version
brew list | grep postgres

# Build and install pgvector for your version (e.g. postgresql@14)
cd /tmp
git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git
cd pgvector
export PG_CONFIG=/opt/homebrew/opt/postgresql@14/bin/pg_config
make
make install

# Verify
ls /opt/homebrew/share/postgresql@14/extension/vector.control
```

On AWS RDS for PostgreSQL, pgvector is available natively on versions 14.7+, 15.2+, and later — no manual installation needed.

### Review memory

The agent learns team preferences over time. When a developer replies to a review comment with `/learn <preference>`, the agent stores it and respects it in future reviews of that repository. Memories can also be managed via the `/memory` REST endpoints.

### False-positive feedback

When a developer replies to a review comment with `/fp` (or `/false-positive`), the agent:

1. Records the finding as a false positive in the `comment_feedback` table
2. Marks the comment as resolved and closes the thread on the platform
3. Posts an in-thread confirmation reply
4. Checks whether this pattern has now hit the auto-suppress threshold (default: 3 occurrences). If so, a `review_memory` entry is automatically created, suppressing that pattern in all future reviews

False-positive patterns are injected into every subsequent review prompt as a **"Known False Positives"** section, reducing noise before a finding is even generated.

Track false-positive rates and resolution rates per repo at `GET /metrics/review-quality/{workspace}/{repoSlug}`.

### Comment interaction

When a developer replies to an agent review comment:
1. `/fp` or `/false-positive` — marks the finding as a false positive (see above)
2. `/learn <preference>` — stores a team preference for future reviews
3. Natural-language fix request — the agent modifies the code and pushes the change
4. Natural-language discussion — the agent replies conversationally in the same thread

### Repo settings

Each repository can be individually configured to control review behavior. Settings are stored in PostgreSQL and managed via the `/settings/repos` REST endpoints.

**Enable/disable review:** Turn automated PR review on or off per repo. Disabled repos silently skip incoming webhooks.

```bash
# Disable review for a repo
curl -X PATCH http://localhost:8080/settings/repos/myworkspace/my-repo/disable

# Re-enable
curl -X PATCH http://localhost:8080/settings/repos/myworkspace/my-repo/enable
```

**Enable/disable vector indexing:** Turn semantic vector indexing on or off per repo. When enabled, embeddings are generated during graph builds and the repo becomes searchable via `semantic_search`. Default: disabled.

```bash
# Enable vector indexing for a shared library
curl -X PATCH http://localhost:8080/settings/repos/myworkspace/shared-lib/vector/enable

# Disable
curl -X PATCH http://localhost:8080/settings/repos/myworkspace/shared-lib/vector/disable
```

**Per-repo rule names:** Configure which shared rules to load from the rules repo, instead of relying on request parameters or global defaults.

**Custom review prompt:** Override the default review prompt template for a repo. The template supports placeholders that are substituted at review time: `{{PR_TITLE}}`, `{{TARGET_BRANCH}}`, `{{PREVIOUS_COMMENTS}}`, `{{MEMORY_SECTION}}`, `{{FALSE_POSITIVE_SECTION}}`, `{{IMPACT_SECTION}}`, `{{DIFF_NOTE}}`, `{{DIFF}}`.

```bash
curl -X PUT http://localhost:8080/settings/repos/myworkspace/my-repo \
  -H 'Content-Type: application/json' \
  -d '{
    "reviewEnabled": true,
    "vectorEnabled": false,
    "ruleNames": ["java-conventions", "security-standards"],
    "reviewPrompt": "You are reviewing a pull request for {{PR_TITLE}}.\n\n{{MEMORY_SECTION}}\n\nFocus only on security and performance issues.\n\n## Diff\n```\n{{DIFF}}\n```"
  }'
```

**Startup sync:** On application startup, the agent fetches all repositories from the configured Bitbucket workspace and ensures each has a settings row in the database. New repos default to review enabled (opt-out model). Existing settings are left untouched.

### Linter integration

During reviews, the agent can run Checkstyle, PMD, and SpotBugs to surface static analysis findings alongside AI-powered observations. Each linter can be individually enabled/disabled.

## Aikido Security Integration

The `/aikido-fix` endpoint provides a closed-loop vulnerability fix pipeline powered by Aikido Security.

### How it works

1. Aikido detects a vulnerability in your codebase and creates a JIRA ticket
2. You (or automation) call `POST /aikido-fix` with just `{"jiraKey": "JTP-10967"}`
3. The agent resolves the full vulnerability context from Aikido:
   - Package name, current version, fixed version
   - CVE details (severity, CVSS score, description)
   - Changelog summary between versions
4. Builds an enriched prompt with all this context for Claude
5. Claude upgrades the dependency, makes necessary code changes, runs tests
6. A PR is created and JIRA is updated

### Issue resolution strategies

The agent uses three strategies to find the Aikido issue (in order):

1. **Aikido API search** — queries open issue groups for one linked to the JIRA key
2. **JIRA description parsing** — extracts Aikido URLs from the ticket description (supports `groupId=`, `sidebarIssue=`, and `/issues/groups/` URL formats)
3. **Direct ID** — pass `aikidoGroupId` in the request to skip lookup

### Setup

1. In Aikido, go to **Settings > Public API** and create an OAuth client
2. Set `AIKIDO_CLIENT_ID` and `AIKIDO_CLIENT_SECRET` in your `.env`
3. (Optional) For post-PR scan verification, set `AIKIDO_CI_API_SECRET` from **Settings > CI Integration**

## JIRA Webhook (auto-trigger on assignment)

The agent can automatically start fixing issues when they are assigned to a dedicated JIRA user (e.g. "Code Agent"). Any issue type (Bug, Task, Story, etc.) is supported — the webhook triggers based solely on the assignee.

### How it works

1. Any issue is assigned to the "Code Agent" user in JIRA
2. JIRA fires a webhook to `POST /webhooks/jira`
3. The agent checks: is the assignee the agent user? Did the assignee actually change?
4. If Aikido is configured, it tries the Aikido-enriched flow (package, CVE, changelog)
5. Otherwise, falls back to JIRA description as the prompt
6. A fix job is submitted automatically

### Setup in JIRA Cloud

1. Create a JIRA user for the agent (e.g. "Code Agent") or use an existing service account
2. Go to **JIRA Settings > System > Webhooks** (admin required)
3. Click **Create a WebHook**:
   - **URL:** `https://your-agent-host:8080/webhooks/jira`
   - **Events:** check `Issue created` and `Issue updated`
   - **JQL filter (optional):** `assignee = "Code Agent"` to reduce noise
4. Configure in `.env`:
   ```
   JIRA_AGENT_ASSIGNEE=Code Agent
   JIRA_AGENT_DEFAULT_REPO_URL=https://bitbucket.org/your-workspace/your-repo.git
   ```

`JIRA_AGENT_ASSIGNEE` can be the display name, email address, or Atlassian account ID.
`JIRA_AGENT_DEFAULT_REPO_URL` is used as a fallback when the repo can't be resolved from Aikido.

## Bitbucket / Azure DevOps PR Webhooks (auto-review)

The agent can automatically review every pull request created or updated in your repositories.

### Setup in Bitbucket Cloud

1. Go to **Repository Settings > Webhooks**
2. Click **Add webhook**:
   - **URL:** `https://your-agent-host:8080/webhooks/bitbucket/pull-request`
   - **Events:** `Pull Request: Created`, `Pull Request: Updated`
3. (Optional) For comment interaction, add another webhook:
   - **URL:** `https://your-agent-host:8080/webhooks/bitbucket/pull-request-comment`
   - **Events:** `Pull Request: Comment Created`
4. Set `WEBHOOK_SECRET_BITBUCKET` for HMAC-SHA256 verification

### Setup in Azure DevOps

1. Go to **Project Settings > Service Hooks**
2. Create a **Web Hook** subscription:
   - **Event:** `Pull request created` and `Pull request updated`
   - **URL:** `https://your-agent-host:8080/webhooks/azuredevops/pull-request`
3. (Optional) For comment interaction:
   - **Event:** `Pull request comment event`
   - **URL:** `https://your-agent-host:8080/webhooks/azuredevops/pull-request-comment`
4. Set `WEBHOOK_SECRET_AZUREDEVOPS` for request verification

## API Security

The agent supports two security mechanisms:

- **API key** — set `API_KEY` to protect all REST endpoints. Requests must include `X-API-Key` header. Health checks (`/health`), Swagger UI (`/q/*`), and webhook endpoints are excluded.
- **Webhook signatures** — set `WEBHOOK_SECRET_BITBUCKET`, `WEBHOOK_SECRET_AZUREDEVOPS`, or `WEBHOOK_SECRET_JIRA` to verify incoming webhook payloads via HMAC-SHA256.

## n8n Approval Flow

1. n8n triggers `POST /run-fix` with the job payload
2. Runner processes the job and calls n8n webhook on completion
3. n8n sends a Teams notification with PR details
4. Human reviews the PR in Bitbucket
5. Human approves/rejects via n8n (form, Teams node, or manual trigger)
6. n8n calls `POST /jobs/{jobId}/approve` or `POST /jobs/{jobId}/reject`
7. Runner merges or declines the PR and updates JIRA

## Database

The agent uses PostgreSQL for persistent storage. Flyway handles schema migrations automatically at startup.

**Tables:**

| Table | Purpose |
|-------|---------|
| `agent_comments` | Maps comment IDs to agent findings for reply tracking and resolution |
| `ai_calls` | AI call metrics (tokens, cost, duration) per job |
| `review_memory` | Team preferences learned during PR reviews (via `/learn` and auto-suppression) |
| `repo_settings` | Per-repo configuration (review enabled, vector enabled, docs enabled, upgrade enabled, archetype, Confluence settings, rule names, custom prompt, disabled hooks) |
| `automation_hooks` | Configurable triggers that run agent tasks on PR merges or schedules |
| `jobs` | Active job queue with status, PR URL, and diff stats |
| `job_history` | Archived completed jobs |
| `comment_feedback` | Developer feedback on individual findings (`/fp` marks); powers FP rate metrics and auto-suppression |
| `code_graph_nodes` | Code graph symbols (classes, methods, fields, enums) per repo |
| `code_graph_edges` | Code graph relationships (calls, extends, implements, imports) per repo |
| `code_embeddings` | Vector embeddings for semantic code search (pgvector, 1024-dim) per repo |
| `execution_plans` | Execution plans with status, phases, steps, and source references |
| `code_metrics_snapshots` | Cyclomatic complexity snapshots per plan/repo for quality tracking |
| `prompt_templates` | AI prompt template overrides (by key) |

## Project Structure

```
src/main/java/com/eneve/agent/
├── RunFixResource.java          # REST endpoints (/run-fix, /quick-fix, /aikido-fix, /review-pr, /fix-pr, /generate-tests, /generate-docs, /sync-confluence)
├── MemoryResource.java          # REST endpoints (/memory)
├── RepoSettingsResource.java    # REST endpoints (/settings/repos)
├── HooksResource.java           # REST endpoints (/settings/hooks)
├── PromptTemplateResource.java  # REST endpoints (/settings/prompts)
├── CodeGraphResource.java       # REST endpoints (/graph/build-missing, /graph/rebuild, /graph/detect-archetypes)
├── AiStatsResource.java         # REST endpoints (/stats/ai-calls)
├── ReviewMetricsResource.java   # REST endpoints (/metrics/review-quality)
├── agent/
│   ├── AgentRunner.java         # Job orchestrator (fix, review, test gen, docs gen)
│   ├── AgentPromptBuilder.java  # System prompt construction (context, memory, FP sections)
│   ├── ClaudeToolUseLoop.java   # Agentic tool-use loop (with rate-limit retry)
│   ├── ToolDefinitions.java     # Tool schemas for Claude (read_file, search_code, query_code_graph, semantic_search, list_files, run_command, fetch_url)
│   ├── AnthropicClientProducer.java # CDI producer for Anthropic Java SDK client
│   ├── TokenBudgetTracker.java  # Proactive rate-limit throttling (tokens-per-minute budget)
│   ├── BuildValidator.java      # Maven / dotnet build validation
│   ├── FindingResolver.java     # Determines if past findings were addressed in new commits
│   ├── IntentClassifier.java    # Classifies PR comment replies (fix vs. discussion)
│   ├── ReviewCommentProcessor.java # Turns PR comments into fix instructions
│   ├── LearningExtractor.java   # Extracts learnable patterns from interactions
│   ├── JobQueue.java            # Concurrent job queue
│   ├── JobStore.java            # In-memory + PostgreSQL job store
│   ├── CommentStore.java        # Tracks agent comments for reply detection and resolution metrics
│   ├── PrSummaryGenerator.java  # PR description & walkthrough generation (single Claude call)
│   ├── MermaidRenderer.java     # Mermaid diagram generation for PR summaries
│   ├── MermaidPngRenderer.java  # Local mmdc CLI rendering + Bitbucket upload
│   ├── CommentFeedbackStore.java # False-positive feedback persistence (PostgreSQL)
│   ├── CommentFeedbackEntry.java # False-positive feedback record
│   ├── MemoryStore.java         # Review memory persistence (PostgreSQL)
│   ├── AiCallStore.java         # AI call metrics persistence (PostgreSQL)
│   ├── RepoSettings.java        # Per-repo settings record
│   ├── RepoSettingsStore.java   # Repo settings persistence (PostgreSQL)
│   ├── RepoSyncService.java     # Syncs platform repos into settings on startup
│   ├── CodeGraphStore.java      # Code graph persistence (nodes + edges in PostgreSQL)
│   ├── CodeGraphIndexer.java    # JavaParser (Java) + regex (C#) AST indexer
│   ├── CodeGraphQueryService.java # Impact analysis prompt builder from graph
│   ├── CodeGraphBuildService.java # Clone-and-index logic for background/on-demand builds
│   ├── CodeGraphScheduler.java  # Scheduled pre-building of missing code graphs
│   ├── ArchetypeDetector.java   # Detect framework archetype (Quarkus, Spring, .NET, etc.)
│   ├── CodeMetricsCalculator.java # Cyclomatic complexity calculator
│   ├── CodeMetricsStore.java    # Metrics snapshots persistence (PostgreSQL)
│   ├── CoverageReporter.java    # Test coverage reporting
│   ├── VoyageEmbeddingService.java # Voyage AI embeddings HTTP client (batching, document/query types)
│   ├── EmbeddingStore.java      # Vector embedding persistence (pgvector cosine search)
│   ├── EmbeddingIndexer.java    # Extracts symbol source code and generates embeddings
│   ├── PromptTemplateService.java # Prompt template resolution (JSON defaults + DB overrides)
│   ├── PromptTemplateStore.java # Prompt template DB persistence
│   ├── HookStore.java           # Automation hook persistence (PostgreSQL)
│   ├── HookEvaluator.java       # Evaluates hook triggers on events (PR merge, schedule)
│   └── AutomationHook.java      # Automation hook record
├── aikido/
│   ├── AikidoService.java       # Aikido REST API client (OAuth2, issues, CVE, CI scan)
│   └── AikidoIssueInfo.java     # Enriched vulnerability context DTO
├── diff/
│   ├── DiffParser.java          # Unified diff parser
│   ├── DiffFormatter.java       # Formats diffs for Claude review prompt
│   └── ...                      # DiffHunk, DiffLine, ParsedDiffFile, ReviewPromptResult
├── jira/
│   └── JiraService.java         # JIRA Cloud API (comments, transitions, issue fetch)
├── linter/
│   ├── LinterService.java       # Orchestrates all linters
│   ├── LinterRunner.java        # Linter execution engine
│   ├── CheckstyleLinter.java    # Checkstyle integration (Java)
│   ├── PmdLinter.java           # PMD integration (Java)
│   ├── SpotBugsLinter.java      # SpotBugs integration (Java)
│   ├── EsLintRunner.java        # ESLint integration (JS/TS)
│   └── DotnetFormatLinter.java  # dotnet-format integration (C#)
├── model/
│   ├── RunFixRequest.java
│   ├── QuickFixRequest.java
│   ├── AikidoFixRequest.java
│   ├── ReviewPrRequest.java
│   ├── FixPrRequest.java
│   ├── GenerateTestsRequest.java
│   ├── GenerateDocsRequest.java
│   ├── SyncConfluenceRequest.java
│   ├── MetricsJobRequest.java
│   ├── HookJobRequest.java
│   ├── ReplyCommentRequest.java
│   ├── JobRecord.java
│   ├── JobStatus.java
│   ├── JobType.java
│   ├── JobStatusResponse.java
│   ├── RunResult.java
│   ├── RejectRequest.java
│   └── RepoCoordinates.java
├── notifications/
│   ├── TeamsNotifier.java
│   └── N8nWebhookNotifier.java
├── planner/
│   ├── PlanResource.java        # REST endpoints (/plans)
│   ├── PlannerService.java      # AI plan generation from specifications
│   ├── PlanStore.java           # Execution plan persistence (PostgreSQL)
│   └── PlanOrchestratorService.java # Orchestrates plan step execution
├── rules/
│   ├── CursorRulesLoader.java
│   ├── MdcParser.java
│   └── MdcRule.java
├── scm/
│   ├── GitPlatformService.java          # SCM abstraction interface
│   ├── GitPlatformProducer.java         # CDI producer (selects BB, ADO, GitLab, or GitHub)
│   ├── bitbucket/
│   │   └── BitbucketPlatformService.java
│   ├── azuredevops/
│   │   └── AzureDevOpsPlatformService.java
│   ├── gitlab/
│   │   └── GitLabPlatformService.java
│   └── github/
│       └── GitHubPlatformService.java
├── security/
│   ├── ApiKeyFilter.java                # API key authentication filter
│   └── WebhookSignatureFilter.java      # HMAC-SHA256 webhook verification
├── tools/
│   ├── GuardrailConfig.java
│   ├── ToolExecutor.java
│   ├── ToolRegistry.java
│   ├── ReadFileTool.java
│   ├── WriteFileTool.java
│   ├── SearchCodeTool.java              # grep-based code search for review context
│   ├── QueryCodeGraphTool.java          # On-demand code graph queries during review
│   ├── SemanticSearchTool.java          # Cross-repo semantic search via pgvector
│   ├── ListFilesTool.java
│   ├── RunCommandTool.java
│   ├── FetchUrlTool.java                # HTTPS documentation/guide fetcher (SSRF-safe, domain-allowlisted)
│   └── PublishConfluenceTool.java       # Publish Markdown to Confluence during docs generation
├── upgrade/
│   ├── UpgradeResource.java     # REST endpoints (/upgrades)
│   ├── UpgradeService.java      # Framework upgrade detection and plan creation
│   ├── UpgradeScheduler.java    # Periodic upgrade checks
│   └── MavenCentralClient.java  # Maven Central version lookups
├── webhooks/
│   ├── JiraWebhookResource.java
│   ├── BitbucketWebhookResource.java
│   ├── BitbucketCommentWebhookResource.java   # /learn, /fp, /generate-tests, fix, reply
│   ├── AzureDevOpsWebhookResource.java
│   ├── AzureDevOpsCommentWebhookResource.java
│   ├── GitLabWebhookResource.java
│   ├── GitLabCommentWebhookResource.java      # /learn, /fp, fix, reply
│   ├── GitHubWebhookResource.java             # PR review + hook evaluation on merge
│   └── GitHubCommentWebhookResource.java      # /learn, /fp, /generate-tests, fix, reply
└── workspace/
    └── WorkspaceContext.java    # Per-job workspace: clone, branch, commit, push, path traversal protection
```
