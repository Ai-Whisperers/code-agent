# Code Agent Runner

A self-hosted coding agent that automates issue fixing and dependency upgrades. Built with Quarkus (Java 21), it clones your repos from Bitbucket Cloud, uses Claude (Anthropic) in an agentic tool-use loop to make changes, validates with Maven, creates pull requests, and keeps JIRA and Teams in sync.

## Architecture

```
n8n  ──POST /run-fix──►  Runner  ──clone──►  Workspace
                           │                      │
                           │◄──Claude tool loop────┘
                           │
                           ├── mvn test
                           ├── git commit & push
                           ├──► Bitbucket Cloud (create PR)
                           ├──► JIRA Cloud (comment, transition, worklog)
                           ├──► Teams (notification)
                           └──► n8n (webhook: success/failed)
```

n8n then orchestrates the approval flow: sends Teams notification, waits for human decision, calls `POST /jobs/{jobId}/approve` or `/reject`.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/run-fix` | Submit a new fix job (returns 202 with jobId) |
| GET | `/status/{jobId}` | Poll job status |
| POST | `/jobs/{jobId}/approve` | Merge the PR, transition JIRA to Done |
| POST | `/jobs/{jobId}/reject` | Decline the PR, add JIRA comment |
| GET | `/health` | Health check with available job slots |

### POST /run-fix

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

Required fields: `repoUrl`, `branchName`, `jiraKey`, `prompt`. All others are optional.

### POST /jobs/{jobId}/reject

```json
{
  "reason": "Changes are too broad"
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
src/main/java/com/code/agent/
├── RunFixResource.java          # REST endpoints
├── agent/
│   ├── AgentRunner.java         # Job orchestrator
│   ├── ClaudeToolUseLoop.java   # Agentic tool-use loop
│   ├── ToolDefinitions.java     # Tool schemas for Claude
│   └── JobStore.java            # In-memory job store
├── bitbucket/
│   └── BitbucketCloudService.java
├── jira/
│   └── JiraService.java
├── model/
│   ├── RunFixRequest.java
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
