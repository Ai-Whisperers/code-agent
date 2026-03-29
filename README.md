# Code Agent Runner

A self-hosted coding agent that automates issue fixing, dependency upgrades, and AI-powered code reviews. Built with Quarkus 3.23.0.CR1 (Java 17), it clones your repos from Bitbucket Cloud or Azure DevOps, uses Claude (Anthropic) in an agentic tool-use loop to make changes, validates with Maven/npm/dotnet, creates pull requests, and keeps JIRA and Teams in sync.

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

### Automation Hooks

| Method | Path | Description |
|--------|------|-------------|
| GET | `/settings/hooks` | List all automation hooks |
| GET | `/settings/hooks/{name}` | Get a specific automation hook |
| POST | `/settings/hooks` | Create a new automation hook |
| PUT | `/settings/hooks/{name}` | Update an existing automation hook |
| PATCH | `/settings/hooks/{name}/enable` | Enable an automation hook |
| PATCH | `/settings/hooks/{name}/disable` | Disable an automation hook |
| DELETE | `/settings/hooks/{name}` | Delete an automation hook |

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

## Database Schema

The application uses PostgreSQL with Flyway migrations. Current tables:

### agent_comments
Tracks AI-generated review comments on pull requests.
```sql
comment_id    BIGINT PRIMARY KEY
pr_id         TEXT NOT NULL
workspace     TEXT NOT NULL
repo_slug     TEXT NOT NULL
project       TEXT NOT NULL DEFAULT ''
file_path     TEXT
line_number   INTEGER
category      TEXT
severity      TEXT
finding_text  TEXT NOT NULL
review_job_id TEXT
created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
```

### ai_calls
Stores telemetry data for all AI API calls for cost tracking and analysis.
```sql
id                          BIGSERIAL PRIMARY KEY
job_id                      TEXT
job_type                    TEXT
model                       TEXT NOT NULL
iteration                   INTEGER
input_tokens                BIGINT NOT NULL DEFAULT 0
output_tokens               BIGINT NOT NULL DEFAULT 0
cache_creation_input_tokens BIGINT NOT NULL DEFAULT 0
cache_read_input_tokens     BIGINT NOT NULL DEFAULT 0
stop_reason                 TEXT
tool_names                  TEXT
duration_ms                 BIGINT NOT NULL DEFAULT 0
is_error                    BOOLEAN NOT NULL DEFAULT FALSE
error_message               TEXT
created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
```

### review_memory
Stores learned team preferences and patterns for consistent PR reviews.
```sql
id                BIGSERIAL PRIMARY KEY
workspace         TEXT NOT NULL
repo_slug         TEXT NOT NULL
memory_text       TEXT NOT NULL
category          TEXT
source            TEXT NOT NULL
source_comment_id BIGINT
source_pr_id      TEXT
is_active         BOOLEAN NOT NULL DEFAULT TRUE
created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
created_by        TEXT
```

### repo_settings
Per-repository configuration for automated reviews and rules.
```sql
id              BIGSERIAL PRIMARY KEY
workspace       TEXT NOT NULL
repo_slug       TEXT NOT NULL
review_enabled  BOOLEAN NOT NULL DEFAULT TRUE
rule_names      TEXT
review_prompt   TEXT
disabled_hooks  TEXT
created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
UNIQUE(workspace, repo_slug)
```

### automation_hooks
Configurable automation triggers for PR events and scheduled tasks.
```sql
id              BIGSERIAL PRIMARY KEY
name            TEXT NOT NULL UNIQUE
description     TEXT
enabled         BOOLEAN NOT NULL DEFAULT TRUE
trigger_type    TEXT NOT NULL
pr_event        TEXT
branch_pattern  TEXT
cron_expr       TEXT
action_type     TEXT NOT NULL
prompt          TEXT NOT NULL
rule_names      TEXT
extra_rules     TEXT
target_branch   TEXT
commit_direct   BOOLEAN NOT NULL DEFAULT FALSE
created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
```

---

## Configuration Properties

All configuration is done via environment variables. Key properties:

### Anthropic (Claude)
- `ANTHROPIC_API_KEY` - Claude API key (required)
- `ANTHROPIC_MODEL` - Model name (default: `claude-sonnet-4-20250514`)
- `ANTHROPIC_MAX_TOKENS` - Max response tokens (default: `8192`)
- `ANTHROPIC_PRICING_INPUT` - Input cost per million tokens (default: `3.0`)
- `ANTHROPIC_PRICING_OUTPUT` - Output cost per million tokens (default: `15.0`)
- `ANTHROPIC_PRICING_CACHE_WRITE` - Cache write cost per million tokens (default: `3.75`)
- `ANTHROPIC_PRICING_CACHE_READ` - Cache read cost per million tokens (default: `0.30`)

### JIRA Integration
- `JIRA_BASE_URL` - JIRA Cloud base URL (default: `https://eneve.atlassian.net`)
- `JIRA_USER` - JIRA username/email
- `JIRA_API_TOKEN` - JIRA API token
- `JIRA_TRANSITION_IN_REVIEW` - Transition ID for "In Review" status
- `JIRA_TRANSITION_DONE` - Transition ID for "Done" status
- `JIRA_TRANSITION_REJECTED` - Transition ID for "Rejected" status
- `JIRA_DEFAULT_WORKLOG` - Default worklog time (default: `30m`)
- `JIRA_AGENT_ASSIGNEE` - Agent user for auto-triggering
- `JIRA_AGENT_LABEL` - Label for issue sync (default: `WALL-E`)
- `JIRA_AGENT_DEFAULT_REPO_URL` - Default repo URL for issues

### Git Platform
- `GIT_PLATFORM` - Platform selection: `bitbucket` or `azuredevops` (default: `bitbucket`)

### Bitbucket Cloud
- `BITBUCKET_BASE_URL` - API base URL (default: `https://api.bitbucket.org/2.0`)
- `BITBUCKET_WORKSPACE` - Workspace slug
- `BITBUCKET_USER` - Username
- `BITBUCKET_APP_PASSWORD` - App password

### Azure DevOps
- `AZUREDEVOPS_BASE_URL` - API base URL (default: `https://dev.azure.com`)
- `AZUREDEVOPS_PAT` - Personal Access Token
- `AZUREDEVOPS_AGENT_USER` - Agent user name

### Git Credentials
- `GIT_USERNAME` - Git username (defaults to Bitbucket user)
- `GIT_PASSWORD` - Git password (defaults to Bitbucket app password)
- `GIT_AUTHOR_NAME` - Commit author name (default: `code-agent`)
- `GIT_AUTHOR_EMAIL` - Commit author email

### Integrations
- `TEAMS_WEBHOOK_URL` - Microsoft Teams webhook URL (optional)
- `N8N_WEBHOOK_URL` - n8n webhook URL (optional)
- `RULES_REPO_URL` - Shared cursor rules repository URL (optional)
- `RULES_REPO_CACHE_DIR` - Local cache directory (default: `/tmp/cursor-rules-cache`)
- `RULES_AUTO_READ_TARGET_REPO` - Auto-read target repo rules (default: `true`)

### Aikido Security
- `AIKIDO_BASE_URL` - Aikido base URL (default: `https://app.aikido.dev`)
- `AIKIDO_CLIENT_ID` - OAuth client ID
- `AIKIDO_CLIENT_SECRET` - OAuth client secret
- `AIKIDO_CI_API_SECRET` - CI API secret for triggering scans

### Security
- `API_KEY` - Shared API key for REST endpoints (optional)
- `WEBHOOK_SECRET_BITBUCKET` - HMAC-SHA256 secret for Bitbucket webhooks (optional)
- `WEBHOOK_SECRET_AZUREDEVOPS` - HMAC-SHA256 secret for Azure DevOps webhooks (optional)
- `WEBHOOK_SECRET_JIRA` - HMAC-SHA256 secret for JIRA webhooks (optional)

### Job Queue & Performance
- `RUN_FIX_MAX_CONCURRENT_JOBS` - Max parallel jobs (default: `3`)
- `RUN_FIX_MAX_QUEUE_SIZE` - Queue capacity (default: `20`)
- `REVIEW_WEBHOOK_SKIP_AUTHORS` - Skip PR reviews from these authors (default: `code-agent`)
- `REVIEW_WEBHOOK_REQUIRE_TITLE_KEYWORD` - Only review PRs with this keyword in title (default: none)

### Agent Guardrails
- `RUN_FIX_BLOCKED_PATHS` - Paths agent cannot modify (default: `src/main/security,src/main/billing,.github,.env`)
- `RUN_FIX_ALLOWED_COMMANDS` - Allowed shell commands (default: `mvn,git diff,git status,ls,find,cat,dotnet,npm,npx`)
- `RUN_FIX_MAX_FILES_CHANGED` - Max files per job (default: `10`)
- `RUN_FIX_MAX_LINES_CHANGED` - Max lines per job (default: `500`)
- `RUN_FIX_MAX_LOOP_ITERATIONS` - Max AI tool-use iterations (default: `50`)
- `RUN_FIX_JOB_TIMEOUT_MINUTES` - Job timeout (default: `30`)

### PostgreSQL Database
- `DATABASE_URL` - JDBC URL (default: `jdbc:postgresql://localhost:5432/code_agent`)
- `DATABASE_USER` - Database username (default: `code_agent`)
- `DATABASE_PASSWORD` - Database password

### Linter/SAST
- `LINTER_ENABLED` - Enable linting (default: `true`)
- `LINTER_CHECKSTYLE_ENABLED` - Enable Checkstyle (default: `true`)
- `LINTER_PMD_ENABLED` - Enable PMD (default: `true`)
- `LINTER_SPOTBUGS_ENABLED` - Enable SpotBugs (default: `true`)
- `LINTER_ESLINT_ENABLED` - Enable ESLint (default: `true`)
- `LINTER_DOTNET_FORMAT_ENABLED` - Enable dotnet format (default: `true`)
- `LINTER_MAX_FIX_ITERATIONS` - Max fix attempts (default: `2`)
- `LINTER_FAIL_ON_NEW_ISSUES` - Fail build on new issues (default: `false`)
- `LINTER_TIMEOUT_MINUTES` - Linter timeout (default: `10`)

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
    { "key": "JTP-10960", "reason": "Active job exists" }
  ],
  "errors": []
}
```

### Automation Hooks System

**NEW:** The agent now supports configurable automation hooks that can trigger jobs based on PR events or schedules.

#### Built-in Hooks

The system comes with one pre-configured hook:
- `update-readme` - Automatically updates README.md when a PR is merged to develop

#### Hook Configuration

Create a new automation hook:

```json
{
  "name": "security-audit",
  "description": "Run security audit on main branch weekly",
  "enabled": true,
  "triggerType": "pr_event",
  "prEvent": "pullrequest:fulfilled",
  "branchPattern": "^main$",
  "actionType": "FIX",
  "prompt": "Run a comprehensive security audit and fix any issues found",
  "ruleNames": ["security-standards"],
  "targetBranch": "main",
  "commitDirect": false
}
```

**Trigger Types:**
- `pr_event` - Triggered by PR events (create, update, merge, etc.)
- `schedule` - Triggered by cron expression (future enhancement)

**PR Events:**
- `pullrequest:created` - New PR created
- `pullrequest:updated` - PR updated (new commits)
- `pullrequest:fulfilled` - PR merged
- `pullrequest:rejected` - PR declined

**Action Types:**
- `FIX` - Run agent fix job
- `REVIEW` - Run agent review job

**Branch Pattern:** Regex pattern to match target branches (e.g., `^main$`, `^feature/.*$`)

### AI Statistics & Cost Tracking

**NEW:** Track AI usage and costs with detailed analytics:

```bash
# Get recent AI calls with pagination
GET /stats/ai-calls?limit=50&offset=0&jobType=FIX&from=2024-01-01T00:00:00Z&to=2024-01-31T23:59:59Z

# Get aggregated summary by model and job type
GET /stats/ai-calls/summary?from=2024-01-01T00:00:00Z&to=2024-01-31T23:59:59Z

# Get all AI calls for a specific job with cost estimate
GET /stats/ai-calls/by-job/550e8400-e29b-41d4-a716-446655440000

# Get daily aggregation for time-series charts
GET /stats/ai-calls/daily?from=2024-01-01T00:00:00Z&to=2024-01-31T23:59:59Z
```

**Response example:**
```json
{
  "jobId": "550e8400-...",
  "calls": [...],
  "totalCalls": 15,
  "totalInputTokens": 125000,
  "totalOutputTokens": 8500,
  "totalCacheWriteTokens": 50000,
  "totalCacheReadTokens": 75000,
  "totalDurationMs": 45000,
  "estimatedCostUsd": 1.85
}
```

### Container-to-Repository Mapping

**Enhanced:** The system includes a comprehensive mapping of Docker container images to source code repositories for Aikido integration:

```json
{
  "mappings": {
    "julesenergy/ms-meter": {
      "repoUrl": "https://bitbucket.org/csarenergy/ms-meter.git",
      "codeRepo": "ms-meter",
      "confidence": "medium"
    }
  }
}
```

This mapping enables automatic repository resolution when processing Aikido security vulnerabilities.

### Multi-Platform Support

The agent supports both **Bitbucket Cloud** and **Azure DevOps**:

- **Bitbucket Cloud:** Full webhook support for PR events and comments
- **Azure DevOps:** Service hooks for PR events and thread comments
- **Linting:** Supports Java (Checkstyle, PMD, SpotBugs), JavaScript (ESLint), and .NET (dotnet format)
- **Build Systems:** Maven, npm/npx, and .NET CLI

### Health & Monitoring

```bash
GET /health
```

Returns system health including:
- Queue status (active jobs, queue size, available slots)
- Database connectivity
- External service status

### Development

**Java 17** is required. The project uses:
- **Quarkus 3.23.0.CR1** - Reactive web framework
- **PostgreSQL** - Primary database with Flyway migrations
- **Anthropic Claude** - AI model integration
- **Maven** - Build system

**Build:**
```bash
mvn clean package
```

**Run tests:**
```bash
mvn test
```

**Local development:**
```bash
mvn quarkus:dev
```

**Docker build:**
```bash
docker build -t code-agent-runner .
```

The application exposes health checks, metrics, and Swagger UI for API documentation at `/q/swagger-ui`.