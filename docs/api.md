# API Documentation

The Code Agent Runner exposes a REST API for submitting automation jobs, checking status, and managing configuration. All endpoints support JSON request/response bodies.

## Authentication

### API Key (Optional)
Set the `API_KEY` environment variable to enable API key authentication. Include the key in the `X-API-Key` header:

```bash
curl -H "X-API-Key: your-api-key" https://your-domain/health
```

### Webhook Signature Verification (Optional)
Set webhook secret environment variables to enable HMAC-SHA256 signature verification:
- `WEBHOOK_SECRET_BITBUCKET`
- `WEBHOOK_SECRET_AZUREDEVOPS`
- `WEBHOOK_SECRET_GITLAB`
- `WEBHOOK_SECRET_JIRA`

## Core Job Management

### Submit Fix Job
**POST** `/run-fix`

Submit a comprehensive fix job with full customization options.

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

**Response:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### Quick Fix Job  
**POST** `/quick-fix`

Simplified endpoint that auto-generates branch name and uses JIRA issue as prompt.

**Request Body:**
```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "jiraKey": "PROJ-123"
}
```

**Response:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "branch": "agent/PROJ-123-upgrade-log4j-dependencies"
}
```

### Aikido Security Fix
**POST** `/aikido-fix`

Submit vulnerability fix job with Aikido Security enrichment.

**Request Body:**
```json
{
  "jiraKey": "PROJ-123",
  "aikidoGroupId": 12345,
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "ruleNames": ["security-standards"],
  "extraRules": "Prioritize backwards compatibility"
}
```

**Response:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "branch": "agent/PROJ-123-upgrade-log4j-core-2-23-1",
  "aikidoIssue": {
    "groupId": 12345,
    "package": "log4j-core", 
    "currentVersion": "2.19.0",
    "fixedVersion": "2.23.1",
    "cve": "CVE-2024-12345",
    "severity": "HIGH"
  }
}
```

### Submit PR Review
**POST** `/review-pr`

Queue AI-powered pull request review job.

**Request Body:**
```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "prId": "42",
  "targetBranch": "main",
  "jiraKey": "PROJ-123",
  "ruleNames": ["code-quality"],
  "extraRules": "Pay attention to performance implications",
  "n8nWebhookUrl": "https://n8n.example.com/webhook/abc",
  "headCommitSha": "a1b2c3d4e5f6"
}
```

### Fix PR Issues
**POST** `/fix-pr`

Auto-fix issues identified in pull request review comments.

**Request Body:**
```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "prId": "42",
  "ruleNames": ["java-conventions"],
  "n8nWebhookUrl": "https://n8n.example.com/webhook/abc"
}
```

### Generate Unit Tests
**POST** `/generate-tests`

Generate unit tests for untested code paths.

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
  "ruleNames": ["testing-standards"]
}
```

### Generate Documentation
**POST** `/generate-docs`

Generate comprehensive project documentation with diagrams.

**Request Body:**
```json
{
  "repoUrl": "https://bitbucket.org/workspace/repo.git",
  "branchName": "agent/generate-docs", 
  "targetBranch": "main",
  "publishConfluence": true,
  "commitDirect": false,
  "ruleNames": ["documentation-standards"]
}
```

## Job Status & Control

### Get Job Status
**GET** `/status/{jobId}`

Poll current status of any job.

**Response:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "RUNNING",
  "summary": "Analyzing JIRA issue and building context...",
  "errorMessage": null,
  "prUrl": null,
  "prId": null,
  "filesChanged": 0,
  "linesChanged": 0,
  "queuePosition": null,
  "createdAt": "2024-01-15T10:30:00Z",
  "jobType": "FIX"
}
```

**Job Status Values:**
- `PENDING` - Job queued, waiting to start
- `RUNNING` - Job actively processing
- `AWAITING_APPROVAL` - PR created, waiting for human approval
- `COMPLETED` - Job finished successfully
- `FAILED` - Job encountered an error

### Approve Job
**POST** `/jobs/{jobId}/approve`

Merge the pull request and mark JIRA as Done (called by n8n after approval).

### Reject Job
**POST** `/jobs/{jobId}/reject`

Decline the pull request with optional reason.

**Request Body:**
```json
{
  "reason": "Changes conflict with upcoming refactor"
}
```

## Repository Settings

### Get Repository Settings
**GET** `/settings/repos/{workspace}/{repoSlug}`

**Response:**
```json
{
  "id": 123,
  "workspace": "myorg",
  "repoSlug": "my-service",
  "reviewEnabled": true,
  "vectorEnabled": false,
  "docsEnabled": true,
  "ruleNames": "java-conventions,security-standards", 
  "reviewPrompt": "Focus on security and performance",
  "confluenceSpaceKey": "DEV",
  "confluenceParentPageId": "12345",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:00Z"
}
```

### Create/Update Repository Settings
**PUT** `/settings/repos/{workspace}/{repoSlug}`

**Request Body:**
```json
{
  "reviewEnabled": true,
  "vectorEnabled": false,
  "docsEnabled": true,
  "ruleNames": "java-conventions,security-standards",
  "reviewPrompt": "Focus on security and performance",
  "confluenceSpaceKey": "DEV", 
  "confluenceParentPageId": "12345"
}
```

### Enable/Disable Features
- **PUT** `/settings/repos/{workspace}/{repoSlug}/enable` - Enable PR reviews
- **PUT** `/settings/repos/{workspace}/{repoSlug}/disable` - Disable PR reviews  
- **PUT** `/settings/repos/{workspace}/{repoSlug}/vector/enable` - Enable semantic search
- **PUT** `/settings/repos/{workspace}/{repoSlug}/vector/disable` - Disable semantic search
- **PUT** `/settings/repos/{workspace}/{repoSlug}/docs/enable` - Enable documentation
- **PUT** `/settings/repos/{workspace}/{repoSlug}/docs/disable` - Disable documentation

### Delete Repository Settings
**DELETE** `/settings/repos/{workspace}/{repoSlug}`

## Code Intelligence

### Code Graph
- **POST** `/graph/build-missing` - Build code graphs for repos without one
- **POST** `/graph/rebuild/{workspace}/{repoSlug}` - Rebuild code graph for specific repo

### AI Statistics
**GET** `/stats/ai-calls` - List all AI API calls with costs and tokens

**Response:**
```json
{
  "calls": [
    {
      "id": 123,
      "jobId": "550e8400-e29b-41d4-a716-446655440000",
      "model": "claude-sonnet-4-20250514",
      "inputTokens": 1500,
      "outputTokens": 800,
      "cacheCreateTokens": 0,
      "cacheReadTokens": 0,
      "estimatedCostUsd": 0.0165,
      "createdAt": "2024-01-15T10:30:00Z"
    }
  ]
}
```

**GET** `/stats/ai-calls/summary` - Aggregate cost and token statistics

**GET** `/stats/ai-calls/by-job/{jobId}` - AI calls for specific job

**GET** `/stats/ai-calls/daily` - Daily AI usage statistics

### Review Metrics
**GET** `/metrics/review-quality/{workspace}/{repoSlug}` - Code review quality metrics

## Review Memory

### Get Review Memory
**GET** `/memory/{workspace}/{repoSlug}`

Retrieve AI review context and learnings for a repository.

**Response:**
```json
{
  "entries": [
    {
      "id": 123,
      "workspace": "myorg", 
      "repoSlug": "my-service",
      "prId": "42",
      "contextType": "architecture_patterns",
      "contextValue": "This service uses hexagonal architecture with ports and adapters",
      "learnedFrom": "PR review of user authentication module",
      "createdAt": "2024-01-15T10:30:00Z"
    }
  ]
}
```

### Delete Memory Entry
**DELETE** `/memory/{id}`

## Automation Hooks

### List Hooks
**GET** `/settings/hooks`

### Get Hook
**GET** `/settings/hooks/{name}`

### Create Hook
**POST** `/settings/hooks/{name}`

**Request Body:**
```json
{
  "description": "Auto-generate docs on main branch push",
  "enabled": true,
  "triggerEvent": "push",
  "triggerCondition": "branch == 'main'",
  "jobType": "GENERATE_DOCS",
  "jobConfig": {
    "publishConfluence": true,
    "commitDirect": true
  }
}
```

### Update Hook
**PUT** `/settings/hooks/{name}`

### Enable/Disable Hook
- **PUT** `/settings/hooks/{name}/enable`
- **PUT** `/settings/hooks/{name}/disable` 

### Delete Hook
**DELETE** `/settings/hooks/{name}`

## Utilities

### Health Check
**GET** `/health`

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

### JIRA Sync
**POST** `/sync-jira`

Search for open JIRA issues with agent label and queue fix jobs.

**Response:**
```json
{
  "found": 5,
  "queued": 2,
  "queuedJobs": [
    {
      "key": "PROJ-123",
      "jobId": "550e8400-e29b-41d4-a716-446655440000",
      "branch": "agent/PROJ-123-upgrade-dependencies"
    }
  ],
  "skipped": [
    {
      "key": "PROJ-124", 
      "reason": "Active job exists"
    }
  ]
}
```

## Webhooks

The API accepts webhooks from multiple platforms for automated job triggering:

### Supported Webhook Events
- **Bitbucket**: `/webhooks/bitbucket/pull-request`, `/webhooks/bitbucket/pull-request-comment`
- **Azure DevOps**: `/webhooks/azuredevops/pull-request`, `/webhooks/azuredevops/pull-request-comment`  
- **GitLab**: `/webhooks/gitlab/merge-request`, `/webhooks/gitlab/merge-request-comment`
- **JIRA**: `/webhooks/jira`

### Webhook Security
Include signatures in headers when secrets are configured:
- Bitbucket: `X-Hub-Signature-256`
- Azure DevOps: `X-Hub-Signature-256`  
- GitLab: `X-Gitlab-Token`
- JIRA: `X-Hub-Signature-256`

## Error Responses

All endpoints return structured error responses:

```json
{
  "error": "repoUrl is required"
}
```

**Common HTTP Status Codes:**
- `200` - Success
- `202` - Job accepted and queued
- `400` - Invalid request parameters
- `401` - Authentication required
- `404` - Resource not found
- `409` - Conflict (e.g. job not in expected state)
- `429` - Job queue full
- `500` - Internal server error
- `503` - Service dependency unavailable