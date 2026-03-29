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

## Project Structure

```
src/main/java/com/eneve/agent/
├── RunFixResource.java              # Main fix/upgrade endpoints
├── MemoryResource.java              # Review memory management
├── RepoSettingsResource.java        # Repository settings
├── HooksResource.java               # Automation hooks
├── AiStatsResource.java             # AI call statistics
├── agent/                           # Core agent logic
│   ├── AgentRunner.java             # Main execution engine
│   ├── ClaudeToolUseLoop.java       # AI tool interaction
│   ├── JobQueue.java                # Job queue management
│   ├── JobStore.java                # Job persistence
│   ├── CommentStore.java            # PR comment tracking
│   ├── MemoryStore.java             # Review memory persistence
│   ├── RepoSettingsStore.java       # Repository settings persistence
│   ├── HookStore.java               # Automation hook persistence
│   ├── AiCallStore.java             # AI call telemetry storage
│   ├── BuildValidator.java          # Maven/npm/dotnet validation
│   ├── HookEvaluator.java           # Automation hook evaluation
│   ├── IntentClassifier.java        # Comment intent analysis
│   ├── ReviewCommentProcessor.java  # PR comment processing
│   ├── AgentPromptBuilder.java      # Dynamic prompt construction
│   └── LearningExtractor.java       # Memory extraction from reviews
├── tools/                           # Agent tool implementations
│   ├── ReadFileTool.java            # File reading capability
│   ├── WriteFileTool.java           # File writing capability
│   ├── RunCommandTool.java          # Command execution
│   ├── ListFilesTool.java           # Directory listing
│   ├── ToolExecutor.java            # Tool execution engine
│   ├── ToolRegistry.java            # Tool registration
│   └── GuardrailConfig.java         # Security restrictions
├── scm/                            # Source control management
│   ├── GitPlatformService.java      # SCM abstraction
│   ├── GitPlatformProducer.java     # Platform selection
│   ├── bitbucket/                   # Bitbucket implementation
│   └── azuredevops/                 # Azure DevOps implementation
├── webhooks/                        # Webhook handlers
│   ├── JiraWebhookResource.java
│   ├── BitbucketWebhookResource.java
│   ├── BitbucketCommentWebhookResource.java
│   ├── AzureDevOpsWebhookResource.java
│   └── AzureDevOpsCommentWebhookResource.java
├── jira/                           # JIRA integration
│   └── JiraService.java
├── aikido/                         # Aikido Security integration
│   ├── AikidoService.java
│   └── AikidoIssueInfo.java
├── linter/                         # Static analysis integration
│   ├── LinterService.java           # Main linter coordinator
│   ├── LinterRunner.java            # Execution engine
│   ├── CheckstyleLinter.java        # Java style checking
│   ├── PmdLinter.java               # Java static analysis
│   ├── SpotBugsLinter.java          # Java bug detection
│   ├── EsLintRunner.java            # JavaScript/TypeScript linting
│   └── DotnetFormatLinter.java      # C# formatting
├── rules/                          # Cursor rules management
│   ├── CursorRulesLoader.java       # Rule loading from repos
│   ├── MdcParser.java               # Markdown parsing
│   └── MdcRule.java                 # Rule representation
├── diff/                           # Diff processing
│   ├── DiffParser.java              # Git diff parsing
│   ├── DiffFormatter.java           # Diff formatting
│   ├── ParsedDiffFile.java          # File diff representation
│   ├── DiffHunk.java                # Diff hunk representation
│   └── DiffLine.java                # Individual line changes
├── notifications/                   # External notifications
│   ├── TeamsNotifier.java           # Microsoft Teams integration
│   └── N8nWebhookNotifier.java      # n8n workflow integration
├── security/                        # Security filters
│   ├── ApiKeyFilter.java            # API key authentication
│   └── WebhookSignatureFilter.java  # Webhook HMAC verification
├── workspace/                       # Workspace management
│   └── WorkspaceContext.java        # Execution context
└── model/                          # Data transfer objects
    ├── RunFixRequest.java
    ├── QuickFixRequest.java
    ├── AikidoFixRequest.java
    ├── ReviewPrRequest.java
    ├── FixPrRequest.java
    ├── JobRecord.java
    ├── JobStatus.java
    ├── JobType.java
    └── ...

src/main/resources/
├── application.properties           # Configuration defaults
├── container-repo-mapping.json     # Container to repo mapping
└── db/migration/                   # Flyway database migrations
    ├── V1__create_agent_comments.sql
    ├── V2__create_ai_calls.sql
    ├── V3__create_review_memory.sql
    ├── V4__add_project_to_agent_comments.sql
    ├── V5__create_repo_settings.sql
    └── V6__create_automation_hooks.sql
```

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
- `API_KEY` - Shared API key to protect REST endpoints (optional)
- `WEBHOOK_SECRET_BITBUCKET` - HMAC-SHA256 secret for Bitbucket webhooks (optional)
- `WEBHOOK_SECRET_AZUREDEVOPS` - HMAC-SHA256 secret for Azure DevOps webhooks (optional)
- `WEBHOOK_SECRET_JIRA` - HMAC-SHA256 secret for JIRA webhooks (optional)

### Review Behavior
- `REVIEW_WEBHOOK_SKIP_AUTHORS` - Skip auto-review for these authors (default: `code-agent`)
- `REVIEW_WEBHOOK_REQUIRE_TITLE_KEYWORD` - Require keyword in PR title for auto-review

### Job Queue
- `RUN_FIX_MAX_CONCURRENT_JOBS` - Max parallel fix jobs (default: `3`)
- `RUN_FIX_MAX_QUEUE_SIZE` - Max job queue size (default: `20`)

### Agent Guardrails
- `RUN_FIX_BLOCKED_PATHS` - Blocked file paths (default: `src/main/security,src/main/billing,.github,.env`)
- `RUN_FIX_ALLOWED_COMMANDS` - Allowed shell commands (default: `mvn,git diff,git status,ls,find,cat,dotnet,npm,npx`)
- `RUN_FIX_MAX_FILES_CHANGED` - Max files per job (default: `10`)
- `RUN_FIX_MAX_LINES_CHANGED` - Max lines changed per job (default: `500`)
- `RUN_FIX_MAX_LOOP_ITERATIONS` - Max AI tool-use iterations (default: `50`)
- `RUN_FIX_JOB_TIMEOUT_MINUTES` - Job timeout (default: `30`)

### Database
- `DATABASE_URL` - PostgreSQL connection URL (default: `jdbc:postgresql://localhost:5432/code_agent`)
- `DATABASE_USER` - Database username (default: `code_agent`)
- `DATABASE_PASSWORD` - Database password

### Linting & SAST
- `LINTER_ENABLED` - Enable linting integration (default: `true`)
- `LINTER_CHECKSTYLE_ENABLED` - Enable Checkstyle for Java (default: `true`)
- `LINTER_PMD_ENABLED` - Enable PMD for Java (default: `true`)
- `LINTER_SPOTBUGS_ENABLED` - Enable SpotBugs for Java (default: `true`)
- `LINTER_ESLINT_ENABLED` - Enable ESLint for JavaScript/TypeScript (default: `true`)
- `LINTER_DOTNET_FORMAT_ENABLED` - Enable dotnet format for C# (default: `true`)
- `LINTER_MAX_FIX_ITERATIONS` - Max linter fix attempts (default: `2`)
- `LINTER_FAIL_ON_NEW_ISSUES` - Fail job on new linter issues (default: `false`)
- `LINTER_TIMEOUT_MINUTES` - Linter timeout (default: `10`)

---

## Deployment

### Docker

The application is containerized with a multi-stage Dockerfile:

```dockerfile
# Stage 1: Build with JDK 21 + Maven
FROM eclipse-temurin:21-jdk AS build
# Build Quarkus application

# Stage 2: Runtime with JRE 21 + build tools
FROM eclipse-temurin:21-jre
# Includes: Git, Maven, Node.js 20, .NET SDK 8.0
```

**Runtime Dependencies:**
- **Git** - Repository cloning and operations
- **Maven** - Java project validation 
- **Node.js 20** - ESLint execution for JavaScript/TypeScript
- **.NET SDK 8.0** - dotnet format execution for C#
- **Java 21** - Application runtime

**Build & Deploy:**
```bash
mvn clean package
docker build -t code-agent-runner .
docker run -p 8080:8080 code-agent-runner
```

**Environment Variables:**
Set required environment variables before running:
```bash
export ANTHROPIC_API_KEY=your-claude-key
export JIRA_USER=your@email.com
export JIRA_API_TOKEN=your-jira-token
export BITBUCKET_USER=your-user
export BITBUCKET_APP_PASSWORD=your-app-password
export DATABASE_URL=jdbc:postgresql://db:5432/code_agent
export DATABASE_PASSWORD=your-db-password
```

### Health & Monitoring

- **Health Check:** `GET /q/health` - Readiness and liveness probes
- **Metrics:** Available via Quarkus SmallRye Health integration
- **Swagger UI:** Available at `/q/swagger-ui` for API documentation
- **AI Cost Tracking:** Built-in token usage and cost estimation

---

## Key Features

### 🤖 AI-Powered Code Changes
- **Claude Sonnet 4.0** integration with tool-use capabilities
- **Guardrails** prevent access to sensitive paths and commands
- **Build validation** ensures changes don't break Maven/npm/dotnet builds
- **Cost tracking** monitors token usage and API costs

### 🔍 Code Review & Quality
- **Automated PR reviews** with inline comments
- **Review memory** learns team preferences over time
- **Static analysis** integration (Checkstyle, PMD, SpotBugs, ESLint, dotnet format)
- **Security-focused** reviews with vulnerability detection

### 🚀 Multi-Platform SCM Support
- **Bitbucket Cloud** - Full PR lifecycle management
- **Azure DevOps** - Full PR lifecycle management  
- **Git operations** - Clone, branch, commit, push

### 📋 JIRA Integration
- **Automatic issue sync** via webhooks
- **Status transitions** (In Review → Done/Rejected)
- **Worklog tracking** for time estimation
- **Aikido Security** integration for vulnerability context

### ⚙️ Flexible Automation
- **Automation hooks** for custom triggers (PR events, schedules)
- **Repository settings** for per-repo configuration
- **Cursor rules** integration for coding standards
- **Webhook support** for external integrations

### 📊 Observability
- **Job queue monitoring** with status tracking
- **AI call telemetry** with cost analytics
- **Review statistics** and team insights
- **Health checks** for container orchestration

---

## Getting Started

1. **Prerequisites:**
   - PostgreSQL database
   - Claude API key from Anthropic
   - JIRA Cloud instance with API access
   - Bitbucket or Azure DevOps with app credentials

2. **Local Development:**
   ```bash
   # Set environment variables
   cp application.properties application-local.properties
   # Edit application-local.properties with your credentials
   
   # Run database migrations
   mvn flyway:migrate
   
   # Start in dev mode
   mvn quarkus:dev
   ```

3. **Production Deployment:**
   ```bash
   # Build container
   mvn clean package
   docker build -t code-agent-runner .
   
   # Run with environment variables
   docker run -d --name code-agent \
     -p 8080:8080 \
     -e ANTHROPIC_API_KEY=your-key \
     -e DATABASE_URL=jdbc:postgresql://db:5432/code_agent \
     code-agent-runner
   ```

4. **First Job:**
   ```bash
   curl -X POST http://localhost:8080/quick-fix \
     -H "Content-Type: application/json" \
     -d '{"jiraKey": "PROJ-123", "repoUrl": "https://bitbucket.org/workspace/repo"}'
   ```

For detailed API documentation, visit `/q/swagger-ui` after starting the application.