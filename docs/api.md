# API Documentation

The Code Agent Runner provides a comprehensive REST API for managing automated code fixes, reviews, and system configuration. All endpoints return JSON responses and support standard HTTP status codes. Authentication is provided via API keys and webhook signature verification.

## Base URL

- **Local Development**: `http://localhost:8080`
- **Production**: `https://your-agent-host`
- **Swagger UI**: `{base_url}/q/swagger-ui`

## Authentication

### API Key Authentication
Most REST endpoints require an API key when `API_KEY` environment variable is set:

```bash
curl -H "X-API-Key: your-api-key" https://your-agent-host/run-fix
```

**Excluded endpoints**: Health checks (`/health`, `/q/health/*`), Swagger UI (`/q/*`), and webhooks (which use signature verification).

### Webhook Signature Verification
Webhook endpoints verify incoming payloads using HMAC-SHA256:
- **Bitbucket**: `X-Hub-Signature` header with `sha256=` prefix
- **Azure DevOps**: Basic authentication or custom signature
- **JIRA**: Custom signature verification

## Fix & Upgrade Endpoints

### POST /run-fix

Submit a comprehensive fix job with full parameter control.

**Request Body:**
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

**Required Fields:**
- `repoUrl`: Git repository URL
- `branchName`: Target branch name for changes
- `jiraKey`: Associated JIRA ticket identifier

**Optional Fields:**
- `prompt`: Custom instructions (defaults to JIRA description)
- `targetBranch`: Base branch (defaults to `main`)
- `n8nWebhookUrl`: Notification webhook URL
- `rulesRepoUrl`: Shared coding rules repository
- `ruleNames`: Specific rules to load from rules repository
- `extraRules`: Additional inline instructions

**Response:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Status Codes:**
- `202`: Job accepted and queued
- `400`: Missing required fields
- `429`: Job queue is full

### POST /quick-fix

Simplified fix job requiring only JIRA key and repository URL.

**Request Body:**
```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "jiraKey": "JTP-10967"
}
```

**Response:**
```json
{
  "jobId": "550e8400-...",
  "branch": "agent/JTP-10967-upgrade-cxf-xjc-boolean"
}
```

**Behavior:**
- Auto-generates branch name from JIRA summary
- Uses `develop` as base branch
- Fetches prompt from JIRA description
- Applies default coding rules

### POST /aikido-fix

Aikido Security integrated fix with automatic vulnerability context resolution.

**Request Body:**
```json
{
  "jiraKey": "JTP-10967",
  "aikidoGroupId": 22926095,
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "ruleNames": ["java-conventions", "security-standards"],
  "extraRules": "Ensure backward compatibility with Java 17"
}
```

**Required Fields:**
- Either `jiraKey` or `aikidoGroupId` must be provided

**Optional Fields:**
- `repoUrl`: Override repository URL from Aikido
- `ruleNames`: Specific coding rules to apply
- `extraRules`: Additional instructions

**Response:**
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

**Resolution Strategy:**
1. Search Aikido for issues linked to JIRA key
2. Parse JIRA description for Aikido URLs
3. Use provided `aikidoGroupId` directly

### POST /sync-jira

Bulk synchronization of JIRA issues with agent label.

**Request Body:** None required

**Response:**
```json
{
  "found": 5,
  "queued": 2,
  "queuedJobs": [
    {
      "key": "JTP-10967",
      "jobId": "550e8400-...",
      "branch": "agent/JTP-10967-upgrade-log4j"
    }
  ],
  "skipped": [
    {
      "key": "JTP-10950",
      "reason": "Active job exists"
    }
  ]
}
```

**Behavior:**
- Searches for open issues with configured agent label (`WALL-E`)
- Queues fix jobs for issues without active jobs
- Prioritizes Aikido-enriched context when available

## Code Review Endpoints

### POST /review-pr

Submit a pull request for comprehensive AI code review.

**Request Body:**
```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "prId": "42",
  "targetBranch": "main",
  "jiraKey": "PROJ-123",
  "rulesRepoUrl": "https://bitbucket.org/workspace/cursor-rules.git",
  "ruleNames": ["java-conventions"],
  "extraRules": "Pay special attention to thread safety"
}
```

**Required Fields:**
- `repoUrl`: Repository URL
- `prId`: Pull request identifier

**Review Coverage:**
- Security vulnerabilities and auth issues
- Design principles (SOLID, separation of concerns)
- Code quality (naming, complexity, error handling)
- Testing coverage and quality
- Performance considerations
- Framework-specific best practices

### POST /fix-pr

Automatically address review comments on a pull request.

**Request Body:**
```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "prId": "42",
  "jiraKey": "PROJ-123"
}
```

**Behavior:**
- Fetches existing review comments from PR
- Uses AI to address each comment with code changes
- Creates new PR targeting original PR's source branch

### POST /generate-tests

Generate unit tests for specified source files or classes.

**Request Body:**
```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "branchName": "agent/generate-tests",
  "targetBranch": "main",
  "sourceFiles": [
    "src/main/java/com/example/UserService.java",
    "src/main/java/com/example/PaymentProcessor.java"
  ],
  "ruleNames": ["testing-standards"],
  "extraRules": "Use JUnit 5 and Mockito"
}
```

**Optional Fields:**
- `sourceFiles`: Specific files to test (auto-discovery if omitted)
- Higher iteration and timeout limits than standard fix jobs

### POST /generate-docs

Generate comprehensive documentation for a repository.

**Request Body:**
```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "branchName": "agent/generate-docs",
  "targetBranch": "main",
  "publishConfluence": true,
  "commitDirect": false,
  "ruleNames": ["documentation-standards"],
  "extraRules": "Include Mermaid diagrams where helpful"
}
```

**Documentation Generated:**
- Architecture overview with component diagrams
- API documentation with endpoint specifications
- Data model with entity relationships
- Getting started guide for developers
- Key business flows with sequence diagrams
- Configuration reference

## Job Management Endpoints

### GET /status/{jobId}

Poll the current status of a job.

**Path Parameters:**
- `jobId`: UUID returned from job submission

**Response:**
```json
{
  "jobId": "550e8400-...",
  "status": "RUNNING",
  "summary": "Upgrading log4j dependencies and fixing compatibility issues",
  "prUrl": "https://bitbucket.org/workspace/repo/pull-requests/123",
  "prId": "123",
  "filesChanged": 3,
  "linesChanged": 45,
  "jiraKey": "PROJ-123",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:35:00Z",
  "queuePosition": null,
  "errorMessage": null
}
```

**Job Status Values:**
- `QUEUED`: Waiting in queue
- `RUNNING`: Currently executing
- `AWAITING_APPROVAL`: PR created, pending human review
- `APPROVED`: PR merged successfully
- `REJECTED`: PR declined
- `FAILED`: Job failed with error

### POST /jobs/{jobId}/approve

Approve and merge a job's pull request.

**Path Parameters:**
- `jobId`: Job identifier

**Response:**
```json
{
  "status": "merged",
  "jobId": "550e8400-..."
}
```

**Behavior:**
- Merges PR in git platform
- Transitions JIRA issue to Done
- Adds completion comment
- Updates job status to APPROVED

### POST /jobs/{jobId}/reject

Reject and decline a job's pull request.

**Request Body:**
```json
{
  "reason": "Changes are too broad for this ticket"
}
```

**Response:**
```json
{
  "status": "rejected",
  "jobId": "550e8400-..."
}
```

**Behavior:**
- Declines PR in git platform
- Adds JIRA comment with rejection reason
- Updates job status to REJECTED

## Repository Management Endpoints

### GET /settings/repos

List all configured repositories and their settings.

**Response:**
```json
{
  "repositories": [
    {
      "workspace": "my-workspace",
      "repoSlug": "backend-service",
      "reviewEnabled": true,
      "vectorEnabled": false,
      "ruleNames": ["java-conventions", "security-standards"],
      "reviewPrompt": null,
      "createdAt": "2024-01-01T00:00:00Z",
      "updatedAt": "2024-01-15T10:00:00Z"
    }
  ]
}
```

### GET /settings/repos/{workspace}/{repoSlug}

Get settings for a specific repository.

**Path Parameters:**
- `workspace`: Git platform workspace/organization
- `repoSlug`: Repository name

### PUT /settings/repos/{workspace}/{repoSlug}

Create or update repository settings.

**Request Body:**
```json
{
  "reviewEnabled": true,
  "vectorEnabled": false,
  "ruleNames": ["java-conventions", "security-standards"],
  "reviewPrompt": "Custom review prompt with {{PR_TITLE}} placeholder"
}
```

**Review Prompt Placeholders:**
- `{{PR_TITLE}}`: Pull request title
- `{{TARGET_BRANCH}}`: Target branch name
- `{{PREVIOUS_COMMENTS}}`: Previous review comments
- `{{MEMORY_SECTION}}`: Team preferences
- `{{FALSE_POSITIVE_SECTION}}`: Known false positives
- `{{IMPACT_SECTION}}`: Code graph impact analysis
- `{{DIFF}}`: Pull request diff

### PATCH /settings/repos/{workspace}/{repoSlug}/enable
### PATCH /settings/repos/{workspace}/{repoSlug}/disable

Enable or disable automated review for a repository.

### PATCH /settings/repos/{workspace}/{repoSlug}/vector/enable
### PATCH /settings/repos/{workspace}/{repoSlug}/vector/disable

Enable or disable semantic vector indexing for a repository.

### DELETE /settings/repos/{workspace}/{repoSlug}

Remove custom settings and revert to defaults.

## Memory & Learning Endpoints

### GET /memory/{workspace}/{repoSlug}

List review memories for a repository.

**Response:**
```json
{
  "memories": [
    {
      "id": 1,
      "workspace": "my-workspace",
      "repoSlug": "backend-service",
      "category": "Code Quality",
      "pattern": "Prefer streams over traditional loops",
      "context": "When processing collections in service methods",
      "isActive": true,
      "createdAt": "2024-01-01T00:00:00Z"
    }
  ]
}
```

### POST /memory

Manually add a review memory.

**Request Body:**
```json
{
  "workspace": "my-workspace",
  "repoSlug": "backend-service",
  "category": "Security",
  "pattern": "Always validate input parameters",
  "context": "In public API methods",
  "findingText": "Missing input validation in user creation endpoint"
}
```

### DELETE /memory/{id}

Deactivate a review memory.

## Code Graph Endpoints

### POST /graph/build-missing

Build code graphs for all repositories that lack one.

**Response:**
```json
{
  "message": "Building code graphs for 3 repositories",
  "repositories": [
    "workspace/repo1",
    "workspace/repo2",
    "workspace/repo3"
  ]
}
```

### POST /graph/rebuild/{workspace}/{repoSlug}

Rebuild the code graph for a specific repository.

**Path Parameters:**
- `workspace`: Git platform workspace
- `repoSlug`: Repository name

## Metrics & Analytics Endpoints

### GET /metrics/review-quality/{workspace}/{repoSlug}

Get review quality metrics for a repository.

**Response:**
```json
{
  "workspace": "my-workspace",
  "repoSlug": "backend-service",
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

### GET /stats/ai-calls

Get paginated AI call records with optional filters.

**Query Parameters:**
- `jobType`: Filter by job type (e.g., `RUN_FIX`, `REVIEW_PR`)
- `from`: Start date (ISO format)
- `to`: End date (ISO format)
- `page`: Page number (0-based)
- `size`: Page size

**Response:**
```json
{
  "calls": [
    {
      "id": 1,
      "jobId": "550e8400-...",
      "jobType": "RUN_FIX",
      "model": "claude-sonnet-4-20250514",
      "inputTokens": 1500,
      "outputTokens": 800,
      "cacheCreationTokens": 0,
      "cacheReadTokens": 0,
      "estimatedCost": 0.0165,
      "durationMs": 2340,
      "calledAt": "2024-01-15T10:30:00Z"
    }
  ],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalPages": 5,
    "totalElements": 100
  }
}
```

### GET /stats/ai-calls/summary

Get aggregated AI usage statistics.

**Response:**
```json
{
  "totalCalls": 1250,
  "totalInputTokens": 2500000,
  "totalOutputTokens": 1200000,
  "totalCacheCreationTokens": 150000,
  "totalCacheReadTokens": 300000,
  "estimatedTotalCost": 425.50,
  "averageDurationMs": 1850,
  "byModel": {
    "claude-sonnet-4-20250514": {
      "calls": 1200,
      "inputTokens": 2400000,
      "outputTokens": 1150000,
      "estimatedCost": 410.25
    }
  },
  "byJobType": {
    "RUN_FIX": {
      "calls": 800,
      "estimatedCost": 280.00
    },
    "REVIEW_PR": {
      "calls": 450,
      "estimatedCost": 145.50
    }
  }
}
```

### GET /stats/ai-calls/daily

Get daily aggregated statistics for time-series analysis.

**Response:**
```json
{
  "dailyStats": [
    {
      "date": "2024-01-15",
      "calls": 45,
      "inputTokens": 85000,
      "outputTokens": 42000,
      "estimatedCost": 12.75
    }
  ]
}
```

## Webhook Endpoints

### POST /webhooks/jira

Receive JIRA Cloud webhook events for automatic job triggering.

**Trigger Condition:** Issue assigned to agent user (configured via `JIRA_AGENT_ASSIGNEE`)

**Response (Job Triggered):**
```json
{
  "action": "job_triggered",
  "jobId": "550e8400-...",
  "jiraKey": "JTP-10967",
  "branch": "agent/JTP-10967-fix-vulnerability"
}
```

**Response (Ignored):**
```json
{
  "action": "ignored",
  "reason": "Not assigned to agent user"
}
```

### POST /webhooks/bitbucket/pull-request

Receive Bitbucket Cloud webhooks for automatic PR review.

**Events:** `pullrequest:created`, `pullrequest:updated`

**Skip Conditions:**
- PR authored by agent user (configurable)
- PR title doesn't contain required keyword (if configured)

### POST /webhooks/bitbucket/pull-request-comment

Handle developer replies to agent review comments.

**Events:** `pullrequest:comment_created`

**Supported Reply Types:**
- `/fp` or `/false-positive`: Mark finding as false positive
- `/learn <preference>`: Store team preference
- Natural language fix request: Modify code
- Natural language discussion: Reply conversationally

### POST /webhooks/azuredevops/pull-request
### POST /webhooks/azuredevops/pull-request-comment

Azure DevOps equivalents with same functionality as Bitbucket webhooks.

**Events:** 
- `git.pullrequest.created`, `git.pullrequest.updated`
- `ms.vss-code.git-pullrequest-comment-event`

### POST /webhooks/gitlab/merge-request
### POST /webhooks/gitlab/merge-request-comment

GitLab equivalents for merge request handling.

**Events:**
- `merge_request` (opened, updated)  
- `note` (on merge requests)

## Health & System Endpoints

### GET /health

Application health check with job queue status.

**Response:**
```json
{
  "status": "UP",
  "availableSlots": 3,
  "runningJobs": 0,
  "queuedJobs": 0,
  "maxConcurrentJobs": 3,
  "maxQueueSize": 20
}
```

### GET /q/health/ready
### GET /q/health/live

Kubernetes/container health probes provided by Quarkus.

## Error Handling

All endpoints return consistent error responses:

```json
{
  "error": "Descriptive error message",
  "code": "ERROR_CODE",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

**Common HTTP Status Codes:**
- `200`: Success
- `202`: Accepted (async operations)
- `400`: Bad request (validation errors)
- `401`: Unauthorized (missing/invalid API key)
- `404`: Resource not found
- `409`: Conflict (job not in expected state)
- `429`: Rate limited or queue full
- `500`: Internal server error
- `503`: Service unavailable (external dependency)

## Rate Limiting

The system implements internal rate limiting for AI API calls and external service requests:

- **AI API**: Exponential backoff with jitter for rate limit errors
- **Git Platform APIs**: Respect platform-specific rate limits
- **JIRA API**: Built-in retry logic for transient failures

Job queue acts as a natural rate limiter, preventing system overload while ensuring all requests are eventually processed.