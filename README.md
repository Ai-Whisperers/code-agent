# Code Agent Runner

A self-hosted coding agent that automates issue fixing and dependency upgrades. Built with Quarkus (Java 21), it clones your repos from Bitbucket Cloud, uses Claude (Anthropic) in an agentic tool-use loop to make changes, validates with Maven, creates pull requests, and keeps JIRA and Teams in sync.

## Architecture

```
              ┌─────────────────────────────────────────────┐
              │              Entry Points                    │
              │                                             │
  n8n/JIRA ──►│  POST /run-fix      (full control)          │
              │  POST /quick-fix    (JIRA key + repo)       │
  Aikido ────►│  POST /aikido-fix   (JIRA key only)         │
  JIRA wh ───►│  POST /webhooks/jira (auto on assignment)   │
              └──────────────┬──────────────────────────────┘
                             │
                             ▼
                    AgentRunner.execute()
                             │
            ┌────────────────┼────────────────────┐
            ▼                ▼                    ▼
      Clone repo      Resolve prompt       Load rules
      (Bitbucket)     (request/JIRA/       (Cursor rules
                       Aikido context)      repo + target)
            │                │                    │
            └────────────────┼────────────────────┘
                             ▼
                   Claude tool-use loop
                   (read/write/run/list)
                             │
                             ▼
                      mvn test / gradle test
                             │
                             ▼
                   git commit & push branch
                             │
                     ┌───────┴───────┐
                     ▼               ▼
              Bitbucket PR     JIRA + Teams + n8n
              (create)         (comment, transition,
                                worklog, notify)
                     │
                     ▼
              AWAITING_APPROVAL
                     │
            ┌────────┴────────┐
            ▼                 ▼
    POST /approve       POST /reject
    (merge PR,          (decline PR,
     JIRA → Done)        JIRA → Rejected)
```

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/run-fix` | Submit a fix job with full control over all parameters |
| POST | `/quick-fix` | Simplified: JIRA key + repo URL, prompt from JIRA description |
| POST | `/aikido-fix` | Aikido-driven: resolves everything from JIRA key via Aikido |
| POST | `/webhooks/jira` | JIRA Cloud webhook — auto-triggers jobs on issue assignment |
| GET | `/status/{jobId}` | Poll job status |
| POST | `/jobs/{jobId}/approve` | Merge the PR, transition JIRA to Done |
| POST | `/jobs/{jobId}/reject` | Decline the PR, add JIRA comment |
| GET | `/health` | Health check with available job slots |

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

Optional fields: `aikidoGroupId` (skip JIRA lookup), `repoUrl` (override Aikido repo).

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

### POST /jobs/{jobId}/reject

```json
{
  "reason": "Changes are too broad"
}
```

### POST /webhooks/jira (webhook)

Receives JIRA Cloud webhook payloads. No request body to construct — JIRA sends this automatically.

**Trigger:** assign a Bug to the agent user in JIRA.

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

## Configuration

All config via environment variables (or `application.properties` for local dev):

| Variable | Description | Default |
|----------|-------------|---------|
| `ANTHROPIC_API_KEY` | Anthropic API key | (required) |
| `ANTHROPIC_MODEL` | Claude model ID | `claude-sonnet-4-20250514` |
| `ANTHROPIC_MAX_TOKENS` | Max tokens per API call | `8192` |
| `JIRA_BASE_URL` | JIRA Cloud URL (e.g. `https://you.atlassian.net`) | (required) |
| `JIRA_USER` | JIRA user email | (required) |
| `JIRA_API_TOKEN` | Atlassian API token | (required) |
| `JIRA_TRANSITION_IN_REVIEW` | Transition ID for "In Review" | (optional) |
| `JIRA_TRANSITION_DONE` | Transition ID for "Done" | (optional) |
| `JIRA_TRANSITION_REJECTED` | Transition ID for rejected | (optional) |
| `JIRA_DEFAULT_WORKLOG` | Default time logged per fix | `30m` |
| `JIRA_AGENT_ASSIGNEE` | Display name, email, or account ID of the agent user in JIRA | (optional) |
| `JIRA_AGENT_ISSUE_TYPES` | Comma-separated issue types to handle (e.g. `Bug,Task`) | `Bug` |
| `JIRA_AGENT_DEFAULT_REPO_URL` | Default repo URL when not resolvable from Aikido | (optional) |
| `BITBUCKET_BASE_URL` | Bitbucket Cloud API base | `https://api.bitbucket.org/2.0` |
| `BITBUCKET_WORKSPACE` | Bitbucket workspace slug | (required) |
| `BITBUCKET_USER` | Bitbucket username | (required) |
| `BITBUCKET_APP_PASSWORD` | Bitbucket App Password | (required) |
| `GIT_USERNAME` | Git clone/push user (defaults to BB user) | |
| `GIT_PASSWORD` | Git clone/push password (defaults to BB app password) | |
| `TEAMS_WEBHOOK_URL` | Teams incoming webhook URL | (optional) |
| `N8N_WEBHOOK_URL` | Default n8n webhook URL | (optional) |
| `RULES_REPO_URL` | Default shared Cursor rules repo | (optional) |
| `RULES_REPO_CACHE_DIR` | Local cache for rules repo | `/tmp/cursor-rules-cache` |
| `RULES_AUTO_READ_TARGET_REPO` | Auto-load .cursor/rules from target repo | `true` |
| `RUN_FIX_BLOCKED_PATHS` | Comma-separated blocked paths | `src/main/security,...` |
| `RUN_FIX_ALLOWED_COMMANDS` | Comma-separated allowed command prefixes | `mvn,gradle,...` |
| `RUN_FIX_MAX_FILES_CHANGED` | Max files the agent may change | `10` |
| `RUN_FIX_MAX_LINES_CHANGED` | Max lines the agent may change | `500` |
| `RUN_FIX_MAX_LOOP_ITERATIONS` | Max agentic loop iterations | `50` |
| `RUN_FIX_JOB_TIMEOUT_MINUTES` | Overall job timeout | `30` |
| `AIKIDO_CLIENT_ID` | Aikido OAuth2 client ID (Settings > Public API) | (optional) |
| `AIKIDO_CLIENT_SECRET` | Aikido OAuth2 client secret | (optional) |
| `AIKIDO_CI_API_SECRET` | Aikido CI integration token (for post-PR scan) | (optional) |
| `AIKIDO_BASE_URL` | Aikido API base URL | `https://app.aikido.dev` |
| `GIT_AUTHOR_NAME` | Git commit author name | `code-agent` |
| `GIT_AUTHOR_EMAIL` | Git commit author email (required for access tokens) | (optional) |

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

mvn quarkus:dev
```

The server starts on `http://localhost:8080`.

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
  code-agent-runner
```

## Cursor Rules Integration

The runner loads coding standards from two sources:

1. **Shared rules repo** — pass `rulesRepoUrl` and optional `ruleNames` in the request. The runner clones/caches the repo and loads `.cursor/rules/{name}.mdc` files by name. If `ruleNames` is omitted, all `alwaysApply: true` rules are loaded.

2. **Target repo** — after cloning the repo being fixed, the runner scans for `.cursor/rules/*.mdc`, `.cursorrules`, and `AGENTS.md`. All `alwaysApply: true` rules are included automatically.

Rules are prepended to the system prompt in order: shared rules, repo rules, inline `extraRules`, then mandatory guardrails. This ensures the agent follows the same conventions your team uses in Cursor IDE.

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

The agent can automatically start fixing issues when they are assigned to a dedicated JIRA user (e.g. "Code Agent").

### How it works

1. A Bug is assigned to the "Code Agent" user in JIRA
2. JIRA fires a webhook to `POST /webhooks/jira`
3. The agent checks: is it an allowed issue type? Is the assignee the agent user?
4. If Aikido is configured, it tries the Aikido-enriched flow (package, CVE, changelog)
5. Otherwise, falls back to JIRA description as the prompt
6. A fix job is submitted automatically

### Setup in JIRA Cloud

1. Create a JIRA user for the agent (e.g. "Code Agent") or use an existing service account
2. Go to **JIRA Settings > System > Webhooks** (admin required)
3. Click **Create a WebHook**:
   - **URL:** `https://your-agent-host:8080/webhooks/jira`
   - **Events:** check `Issue created` and `Issue updated`
   - **JQL filter (optional):** `assignee = "Code Agent" AND issuetype = Bug` to reduce noise
4. Configure in `.env`:
   ```
   JIRA_AGENT_ASSIGNEE=Code Agent
   JIRA_AGENT_ISSUE_TYPES=Bug
   JIRA_AGENT_DEFAULT_REPO_URL=https://bitbucket.org/your-workspace/your-repo.git
   ```

`JIRA_AGENT_ASSIGNEE` can be the display name, email address, or Atlassian account ID.
`JIRA_AGENT_ISSUE_TYPES` is a comma-separated list (e.g. `Bug,Task,Sub-task`).
`JIRA_AGENT_DEFAULT_REPO_URL` is used as a fallback when the repo can't be resolved from Aikido.

## n8n Approval Flow

1. n8n triggers `POST /run-fix` with the job payload
2. Runner processes the job and calls n8n webhook on completion
3. n8n sends a Teams notification with PR details
4. Human reviews the PR in Bitbucket
5. Human approves/rejects via n8n (form, Teams node, or manual trigger)
6. n8n calls `POST /jobs/{jobId}/approve` or `POST /jobs/{jobId}/reject`
7. Runner merges or declines the PR and updates JIRA

## Project Structure

```
src/main/java/com/eneve/agent/
├── RunFixResource.java          # REST endpoints (/run-fix, /quick-fix, /aikido-fix, etc.)
├── agent/
│   ├── AgentRunner.java         # Job orchestrator
│   ├── ClaudeToolUseLoop.java   # Agentic tool-use loop (with rate-limit retry)
│   ├── ToolDefinitions.java     # Tool schemas for Claude
│   └── JobStore.java            # In-memory job store
├── aikido/
│   ├── AikidoService.java       # Aikido REST API client (OAuth2, issues, CVE, CI scan)
│   └── AikidoIssueInfo.java     # Enriched vulnerability context DTO
├── bitbucket/
│   └── BitbucketCloudService.java
├── jira/
│   └── JiraService.java         # JIRA Cloud API (comments, transitions, issue fetch)
├── model/
│   ├── RunFixRequest.java
│   ├── QuickFixRequest.java
│   ├── AikidoFixRequest.java
│   ├── JobRecord.java
│   ├── JobStatus.java
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
├── tools/
│   ├── GuardrailConfig.java
│   ├── ToolExecutor.java
│   ├── ToolRegistry.java
│   ├── ReadFileTool.java
│   ├── WriteFileTool.java
│   ├── RunCommandTool.java
│   └── ListFilesTool.java
└── workspace/
    └── WorkspaceContext.java
```
