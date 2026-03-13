# Code Agent Runner

A self-hosted coding agent that automates issue fixing, dependency upgrades, and AI-powered code reviews. Built with Quarkus (Java 21), it clones your repos from Bitbucket Cloud or Azure DevOps, uses Claude (Anthropic) in an agentic tool-use loop to make changes, validates with Maven, creates pull requests, and keeps JIRA and Teams in sync.

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
              │  POST /review-pr    (AI code review)            │
              │  POST /fix-pr       (auto-fix review comments)  │
              └──────────────┬──────────────────────────────────┘
                             │
                    ┌────────┴────────┐
                    ▼                 ▼
             Fix / Upgrade       PR Review
                    │                 │
            AgentRunner         AgentRunner
            .execute()          .executeReview()
                    │                 │
       ┌────────────┼────────┐       │
       ▼            ▼        ▼       ▼
  Clone repo  Resolve   Load     Compute diff
  (BB/ADO)    prompt    rules    against target
              (JIRA/    (Cursor   branch
               Aikido)   rules)       │
       │            │        │       ▼
       └────────────┼────────┘  Claude review
                    ▼           (security, design,
           Claude tool-use      quality, tests,
           loop (read/write/    performance)
           run/list)                 │
                    │                ▼
                    ▼           Post inline
           mvn test / build     comments on PR
                    │                │
                    ▼                ▼
           git commit & push   Track comments
                    │           in CommentStore
             ┌──────┴──────┐
             ▼              ▼
      BB/ADO PR      JIRA + Teams + n8n
      (create)       (comment, transition,
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

### Job Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/status/{jobId}` | Poll job status, summary, PR URL, diff stats |
| POST | `/jobs/{jobId}/approve` | Merge the PR, transition JIRA to Done |
| POST | `/jobs/{jobId}/reject` | Decline the PR, add JIRA comment |
| GET | `/health` | Health check with queue status and available slots |

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
| DELETE | `/settings/repos/{workspace}/{repoSlug}` | Remove settings (revert to defaults) |

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
| POST | `/webhooks/bitbucket/pull-request-comment` | Bitbucket — reply/fix when developer replies to agent comment |
| POST | `/webhooks/azuredevops/pull-request` | Azure DevOps — auto-review on PR create/update |
| POST | `/webhooks/azuredevops/pull-request-comment` | Azure DevOps — reply/fix when developer replies to agent comment |

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

Receives Bitbucket Cloud webhook payloads for `pullrequest:comment_created` events. When a developer replies to one of the agent's review comments, the agent classifies the intent (fix request vs. discussion) and triggers the appropriate job. Supports a `/learn` command to store team preferences for future reviews.

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
| `GIT_PLATFORM` | SCM platform: `bitbucket` or `azuredevops` | `bitbucket` |
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
| `WEBHOOK_SECRET_JIRA` | Secret for JIRA webhook verification | (optional) |

### PR Review Webhooks

| Variable | Description | Default |
|----------|-------------|---------|
| `REVIEW_WEBHOOK_SKIP_AUTHORS` | Comma-separated PR authors to skip (avoid self-review) | `code-agent` |
| `REVIEW_WEBHOOK_REQUIRE_TITLE_KEYWORD` | Only review PRs whose title contains this keyword | (optional) |

### Job Queue & Guardrails

| Variable | Description | Default |
|----------|-------------|---------|
| `RUN_FIX_MAX_CONCURRENT_JOBS` | Max jobs running in parallel | `3` |
| `RUN_FIX_MAX_QUEUE_SIZE` | Max jobs waiting in the queue | `20` |
| `RUN_FIX_BLOCKED_PATHS` | Comma-separated blocked paths | `src/main/security,...` |
| `RUN_FIX_ALLOWED_COMMANDS` | Comma-separated allowed command prefixes | `mvn,git diff,...` |
| `RUN_FIX_MAX_FILES_CHANGED` | Max files the agent may change | `10` |
| `RUN_FIX_MAX_LINES_CHANGED` | Max lines the agent may change | `500` |
| `RUN_FIX_MAX_LOOP_ITERATIONS` | Max agentic loop iterations | `50` |
| `RUN_FIX_JOB_TIMEOUT_MINUTES` | Overall job timeout | `30` |

### Linter / SAST

| Variable | Description | Default |
|----------|-------------|---------|
| `LINTER_ENABLED` | Enable linter integration | `true` |
| `LINTER_CHECKSTYLE_ENABLED` | Enable Checkstyle | `true` |
| `LINTER_PMD_ENABLED` | Enable PMD | `true` |
| `LINTER_SPOTBUGS_ENABLED` | Enable SpotBugs | `true` |
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

mvn quarkus:dev
```

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

## Cursor Rules Integration

The runner loads coding standards from two sources:

1. **Shared rules repo** — pass `rulesRepoUrl` and optional `ruleNames` in the request. The runner clones/caches the repo and loads `.cursor/rules/{name}.mdc` files by name. If `ruleNames` is omitted, all `alwaysApply: true` rules are loaded.

2. **Target repo** — after cloning the repo being fixed, the runner scans for `.cursor/rules/*.mdc`, `.cursorrules`, and `AGENTS.md`. All `alwaysApply: true` rules are included automatically.

Rules are prepended to the system prompt in order: shared rules, repo rules, inline `extraRules`, then mandatory guardrails. This ensures the agent follows the same conventions your team uses in Cursor IDE.

## AI Code Review

The agent performs automated code reviews on pull requests, triggered either via the `/review-pr` endpoint or automatically through Bitbucket/Azure DevOps webhooks.

### What it reviews

- **Security** — injection vulnerabilities, hardcoded secrets, auth issues
- **Design** — SOLID principles, separation of concerns, API design
- **Code quality** — naming, complexity, duplication, error handling
- **Testing** — coverage gaps, missing edge cases, test quality
- **Performance** — N+1 queries, unnecessary allocations, algorithm complexity
- **Best practices** — framework conventions, idiomatic patterns

### Review memory

The agent learns team preferences over time. When a developer replies to a review comment with `/learn <preference>`, the agent stores it and respects it in future reviews of that repository. Memories can also be managed via the `/memory` REST endpoints.

### Comment interaction

When a developer replies to an agent review comment:
1. The agent classifies the intent as either a **fix request** or a **discussion**
2. For fix requests, the agent modifies the code and pushes the change
3. For discussions, the agent replies conversationally in the same thread

### Repo settings

Each repository can be individually configured to control review behavior. Settings are stored in PostgreSQL and managed via the `/settings/repos` REST endpoints.

**Enable/disable review:** Turn automated PR review on or off per repo. Disabled repos silently skip incoming webhooks.

```bash
# Disable review for a repo
curl -X PATCH http://localhost:8080/settings/repos/myworkspace/my-repo/disable

# Re-enable
curl -X PATCH http://localhost:8080/settings/repos/myworkspace/my-repo/enable
```

**Per-repo rule names:** Configure which shared rules to load from the rules repo, instead of relying on request parameters or global defaults.

**Custom review prompt:** Override the default review prompt template for a repo. The template supports placeholders that are substituted at review time: `{{PR_TITLE}}`, `{{TARGET_BRANCH}}`, `{{PREVIOUS_COMMENTS}}`, `{{MEMORY_SECTION}}`, `{{DIFF_NOTE}}`, `{{DIFF}}`.

```bash
curl -X PUT http://localhost:8080/settings/repos/myworkspace/my-repo \
  -H 'Content-Type: application/json' \
  -d '{
    "reviewEnabled": true,
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
| `agent_comments` | Maps comment IDs to agent findings for reply tracking |
| `ai_calls` | AI call metrics (tokens, cost, duration) per job |
| `review_memory` | Team preferences learned during PR reviews |
| `repo_settings` | Per-repo configuration (review enabled, rule names, custom prompt) |

## Project Structure

```
src/main/java/com/eneve/agent/
├── RunFixResource.java          # REST endpoints (/run-fix, /quick-fix, /aikido-fix, /review-pr, /fix-pr)
├── MemoryResource.java          # REST endpoints (/memory)
├── RepoSettingsResource.java    # REST endpoints (/settings/repos)
├── AiStatsResource.java         # REST endpoints (/stats/ai-calls)
├── agent/
│   ├── AgentRunner.java         # Job orchestrator (fix + review)
│   ├── AgentPromptBuilder.java  # System prompt construction
│   ├── ClaudeToolUseLoop.java   # Agentic tool-use loop (with rate-limit retry)
│   ├── ToolDefinitions.java     # Tool schemas for Claude
│   ├── BuildValidator.java      # Maven build validation
│   ├── IntentClassifier.java    # Classifies PR comment replies (fix vs. discussion)
│   ├── ReviewCommentProcessor.java # Turns PR comments into fix instructions
│   ├── LearningExtractor.java   # Extracts learnable patterns from interactions
│   ├── JobQueue.java            # Concurrent job queue
│   ├── JobStore.java            # In-memory job store
│   ├── CommentStore.java        # Tracks agent comments for reply detection
│   ├── MemoryStore.java         # Review memory persistence (PostgreSQL)
│   ├── AiCallStore.java         # AI call metrics persistence (PostgreSQL)
│   ├── RepoSettings.java        # Per-repo settings record
│   ├── RepoSettingsStore.java   # Repo settings persistence (PostgreSQL)
│   └── RepoSyncService.java     # Syncs Bitbucket repos into settings on startup
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
│   ├── LinterService.java       # Orchestrates Checkstyle, PMD, SpotBugs
│   ├── LinterRunner.java        # Linter execution engine
│   ├── CheckstyleLinter.java    # Checkstyle integration
│   ├── PmdLinter.java           # PMD integration
│   └── SpotBugsLinter.java      # SpotBugs integration
├── model/
│   ├── RunFixRequest.java
│   ├── QuickFixRequest.java
│   ├── AikidoFixRequest.java
│   ├── ReviewPrRequest.java
│   ├── FixPrRequest.java
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
├── rules/
│   ├── CursorRulesLoader.java
│   ├── MdcParser.java
│   └── MdcRule.java
├── scm/
│   ├── GitPlatformService.java          # SCM abstraction interface
│   ├── GitPlatformProducer.java         # CDI producer (selects BB or ADO)
│   ├── bitbucket/
│   │   └── BitbucketPlatformService.java
│   └── azuredevops/
│       └── AzureDevOpsPlatformService.java
├── security/
│   ├── ApiKeyFilter.java                # API key authentication filter
│   └── WebhookSignatureFilter.java      # HMAC-SHA256 webhook verification
├── tools/
│   ├── GuardrailConfig.java
│   ├── ToolExecutor.java
│   ├── ToolRegistry.java
│   ├── ReadFileTool.java
│   ├── WriteFileTool.java
│   ├── RunCommandTool.java
│   └── ListFilesTool.java
├── webhooks/
│   ├── JiraWebhookResource.java
│   ├── BitbucketWebhookResource.java
│   ├── BitbucketCommentWebhookResource.java
│   ├── AzureDevOpsWebhookResource.java
│   └── AzureDevOpsCommentWebhookResource.java
└── workspace/
    └── WorkspaceContext.java
```
