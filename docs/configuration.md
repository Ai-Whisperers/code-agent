# Configuration Reference

The Code Agent Runner is configured through environment variables and application properties. This document provides a comprehensive reference of all available configuration options.

## Required Configuration

### AI Integration
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `anthropic.api.key` | `ANTHROPIC_API_KEY` | Anthropic Claude API key | - | ✅ |
| `anthropic.model` | `ANTHROPIC_MODEL` | Claude model to use | `claude-sonnet-4-20250514` | ✅ |
| `anthropic.max-tokens` | `ANTHROPIC_MAX_TOKENS` | Max output tokens per request | `8192` | - |

### Database
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `quarkus.datasource.jdbc.url` | `DATABASE_URL` | PostgreSQL connection URL | `jdbc:postgresql://localhost:5432/code_agent` | ✅ |
| `quarkus.datasource.username` | `DATABASE_USER` | Database username | `code_agent` | ✅ |
| `quarkus.datasource.password` | `DATABASE_PASSWORD` | Database password | - | ✅ |

### JIRA Integration
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `jira.base.url` | `JIRA_BASE_URL` | JIRA Cloud base URL | `https://eneve.atlassian.net` | ✅ |
| `jira.user` | `JIRA_USER` | JIRA username/email | - | ✅ |
| `jira.api.token` | `JIRA_API_TOKEN` | JIRA API token | - | ✅ |

### Git Platform
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `git.platform` | `GIT_PLATFORM` | Git platform: `bitbucket`, `azuredevops`, `gitlab` | `bitbucket` | ✅ |

## Git Platform Configuration

### Bitbucket Cloud
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `bitbucket.base.url` | `BITBUCKET_BASE_URL` | Bitbucket API base URL | `https://api.bitbucket.org/2.0` | - |
| `bitbucket.workspace` | `BITBUCKET_WORKSPACE` | Bitbucket workspace name | - | ✅ |
| `bitbucket.user` | `BITBUCKET_USER` | Bitbucket username | - | ✅ |
| `bitbucket.app.password` | `BITBUCKET_APP_PASSWORD` | Bitbucket app password | - | ✅ |

### Azure DevOps
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `azuredevops.base.url` | `AZUREDEVOPS_BASE_URL` | Azure DevOps organization URL | `https://dev.azure.com` | ✅ |
| `azuredevops.pat` | `AZUREDEVOPS_PAT` | Personal Access Token | - | ✅ |
| `azuredevops.agent.user` | `AZUREDEVOPS_AGENT_USER` | Agent user display name | - | ✅ |

### GitLab Cloud
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `gitlab.base.url` | `GITLAB_BASE_URL` | GitLab API base URL | `https://gitlab.com/api/v4` | ✅ |
| `gitlab.token` | `GITLAB_TOKEN` | GitLab access token | - | ✅ |
| `gitlab.agent.user` | `GITLAB_AGENT_USER` | Agent user display name | - | ✅ |

## Security Configuration

### API Security
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `api.key` | `API_KEY` | Shared API key for REST endpoints | - | - |

### Webhook Security
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `webhook.secret.bitbucket` | `WEBHOOK_SECRET_BITBUCKET` | HMAC-SHA256 secret for Bitbucket webhooks | - | - |
| `webhook.secret.azuredevops` | `WEBHOOK_SECRET_AZUREDEVOPS` | HMAC-SHA256 secret for Azure DevOps webhooks | - | - |
| `webhook.secret.gitlab` | `WEBHOOK_SECRET_GITLAB` | Token for GitLab webhook authentication | - | - |
| `webhook.secret.jira` | `WEBHOOK_SECRET_JIRA` | HMAC-SHA256 secret for JIRA webhooks | - | - |

### Agent Guardrails
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `run-fix.blocked-paths` | `RUN_FIX_BLOCKED_PATHS` | Comma-separated blocked paths | `src/main/security,src/main/billing,.github,.env` | - |
| `run-fix.allowed-commands` | `RUN_FIX_ALLOWED_COMMANDS` | Comma-separated allowed shell commands | `mvn,git diff,git status,git log,ls,find,cat,grep,dotnet,npm,npx` | - |
| `run-fix.max-files-changed` | `RUN_FIX_MAX_FILES_CHANGED` | Maximum files changed per job | `10` | - |
| `run-fix.max-lines-changed` | `RUN_FIX_MAX_LINES_CHANGED` | Maximum lines changed per job | `500` | - |
| `run-fix.max-loop-iterations` | `RUN_FIX_MAX_LOOP_ITERATIONS` | Maximum AI tool-use iterations | `150` | - |
| `run-fix.job-timeout-minutes` | `RUN_FIX_JOB_TIMEOUT_MINUTES` | Job execution timeout | `30` | - |

## AI Configuration

### Cost Estimation (USD per million tokens)
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `anthropic.pricing.input-per-million` | `ANTHROPIC_PRICING_INPUT` | Input token cost | `3.0` | - |
| `anthropic.pricing.output-per-million` | `ANTHROPIC_PRICING_OUTPUT` | Output token cost | `15.0` | - |
| `anthropic.pricing.cache-write-per-million` | `ANTHROPIC_PRICING_CACHE_WRITE` | Cache write cost | `3.75` | - |
| `anthropic.pricing.cache-read-per-million` | `ANTHROPIC_PRICING_CACHE_READ` | Cache read cost | `0.30` | - |

### Semantic Search
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `voyage.api.key` | `VOYAGE_API_KEY` | Voyage AI embeddings API key | - | - |
| `voyage.model` | `VOYAGE_MODEL` | Voyage AI model | `voyage-code-3` | - |
| `voyage.batch-size` | `VOYAGE_BATCH_SIZE` | Batch size for embeddings | `128` | - |
| `embedding.max-source-chars` | `EMBEDDING_MAX_SOURCE_CHARS` | Max source chars per embedding | `16000` | - |

## JIRA Workflow Configuration

### Issue Management
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `jira.transition.in-review` | `JIRA_TRANSITION_IN_REVIEW` | Transition ID for "In Review" status | - | - |
| `jira.transition.done` | `JIRA_TRANSITION_DONE` | Transition ID for "Done" status | - | - |
| `jira.transition.rejected` | `JIRA_TRANSITION_REJECTED` | Transition ID for "Rejected" status | - | - |
| `jira.default.worklog` | `JIRA_DEFAULT_WORKLOG` | Default time spent per job | `30m` | - |

### Agent Integration
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `jira.agent.assignee` | `JIRA_AGENT_ASSIGNEE` | Default assignee for agent jobs | - | - |
| `jira.agent.label` | `JIRA_AGENT_LABEL` | Label for agent-handled issues | `WALL-E` | - |
| `jira.agent.default-repo-url` | `JIRA_AGENT_DEFAULT_REPO_URL` | Default repository URL for sync jobs | - | - |

## Job Queue Configuration

### Capacity Management
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `run-fix.max-concurrent-jobs` | `RUN_FIX_MAX_CONCURRENT_JOBS` | Maximum concurrent jobs | `3` | - |
| `run-fix.max-queue-size` | `RUN_FIX_MAX_QUEUE_SIZE` | Maximum queued jobs | `20` | - |

### Specialized Job Types
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `generate-tests.max-loop-iterations` | `GENERATE_TESTS_MAX_LOOP_ITERATIONS` | Max iterations for test generation | `500` | - |
| `generate-tests.job-timeout-minutes` | `GENERATE_TESTS_JOB_TIMEOUT_MINUTES` | Test generation timeout | `60` | - |
| `generate-docs.max-loop-iterations` | `GENERATE_DOCS_MAX_LOOP_ITERATIONS` | Max iterations for doc generation | `200` | - |

## Pull Request Configuration

### Review Behavior
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `review.webhook.skip-authors` | `REVIEW_WEBHOOK_SKIP_AUTHORS` | Authors to skip in PR reviews | `code-agent` | - |
| `review.webhook.require-title-keyword` | - | Required keyword in PR title | `-` | - |
| `review.pr-summary.enabled` | `REVIEW_PR_SUMMARY_ENABLED` | Enable PR summary comments | `true` | - |
| `review.sequence-diagrams.enabled` | `REVIEW_SEQUENCE_DIAGRAMS_ENABLED` | Generate Mermaid diagrams | `true` | - |

## External Integrations

### Notifications
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `teams.webhook.url` | `TEAMS_WEBHOOK_URL` | Microsoft Teams webhook URL | - | - |
| `n8n.webhook.url` | `N8N_WEBHOOK_URL` | n8n workflow webhook URL | - | - |

### Aikido Security
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `aikido.base.url` | `AIKIDO_BASE_URL` | Aikido platform base URL | `https://app.aikido.dev` | - |
| `aikido.client.id` | `AIKIDO_CLIENT_ID` | Aikido OAuth client ID | - | - |
| `aikido.client.secret` | `AIKIDO_CLIENT_SECRET` | Aikido OAuth client secret | - | - |
| `aikido.ci.api.secret` | `AIKIDO_CI_API_SECRET` | Aikido CI API secret | - | - |

### Confluence Publishing
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `confluence.base.url` | `CONFLUENCE_BASE_URL` | Confluence Cloud base URL | - | - |
| `confluence.user` | `CONFLUENCE_USER` | Confluence username | - | - |
| `confluence.api.token` | `CONFLUENCE_API_TOKEN` | Confluence API token | - | - |

## Coding Rules Configuration

### Rules Repository
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `rules.repo.url` | `RULES_REPO_URL` | Git repository with shared coding rules | - | - |
| `rules.repo.cache.dir` | `RULES_REPO_CACHE_DIR` | Local cache directory for rules | `/tmp/cursor-rules-cache` | - |
| `rules.auto-read-target-repo` | `RULES_AUTO_READ_TARGET_REPO` | Auto-read target repo for context | `true` | - |

## Linting & Static Analysis

### Tool Configuration
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `linter.enabled` | `LINTER_ENABLED` | Enable static analysis | `true` | - |
| `linter.checkstyle.enabled` | `LINTER_CHECKSTYLE_ENABLED` | Enable Checkstyle | `true` | - |
| `linter.pmd.enabled` | `LINTER_PMD_ENABLED` | Enable PMD | `true` | - |
| `linter.spotbugs.enabled` | `LINTER_SPOTBUGS_ENABLED` | Enable SpotBugs | `true` | - |
| `linter.eslint.enabled` | `LINTER_ESLINT_ENABLED` | Enable ESLint | `true` | - |
| `linter.dotnet-format.enabled` | `LINTER_DOTNET_FORMAT_ENABLED` | Enable .NET Format | `true` | - |

### Linter Behavior
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `linter.max-fix-iterations` | `LINTER_MAX_FIX_ITERATIONS` | Max attempts to fix linting issues | `2` | - |
| `linter.fail-on-new-issues` | `LINTER_FAIL_ON_NEW_ISSUES` | Fail build on new issues | `false` | - |
| `linter.timeout-minutes` | `LINTER_TIMEOUT_MINUTES` | Linting timeout | `10` | - |

## Git Configuration

### Repository Access
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `git.username` | `GIT_USERNAME` | Git username for cloning | Uses platform-specific user | - |
| `git.password` | `GIT_PASSWORD` | Git password/token | Uses platform-specific password | - |
| `git.author.name` | `GIT_AUTHOR_NAME` | Git commit author name | `code-agent` | - |
| `git.author.email` | `GIT_AUTHOR_EMAIL` | Git commit author email | - | - |

## Code Graph Configuration

### Scheduling
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `code-graph.scheduler.enabled` | `CODE_GRAPH_SCHEDULER_ENABLED` | Enable periodic graph building | `true` | - |
| `code-graph.scheduler.default-branch` | `CODE_GRAPH_SCHEDULER_DEFAULT_BRANCH` | Default branch to analyze | `main` | - |
| `code-graph.scheduler.clone-timeout-minutes` | `CODE_GRAPH_SCHEDULER_CLONE_TIMEOUT` | Repository clone timeout | `10` | - |

## Documentation Tools

### URL Fetching
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `tools.fetch-url.enabled` | `TOOLS_FETCH_URL_ENABLED` | Enable fetch_url tool | `true` | - |
| `tools.fetch-url.timeout-seconds` | `TOOLS_FETCH_URL_TIMEOUT` | HTTP request timeout | `15` | - |
| `tools.fetch-url.allowed-domains` | `TOOLS_FETCH_URL_ALLOWED_DOMAINS` | Allowed domains (comma-separated) | - | - |

## Quarkus Framework Configuration

### Application Server
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `quarkus.http.port` | - | HTTP server port | `8080` | - |
| `quarkus.log.level` | - | Global log level | `INFO` | - |
| `quarkus.log.category."com.eneve.agent".level` | - | Application log level | `DEBUG` | - |

### Database Connection Pool
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `quarkus.datasource.jdbc.max-size` | - | Maximum database connections | `20` | - |
| `quarkus.datasource.jdbc.min-size` | - | Minimum database connections | `5` | - |

### Worker Threads
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `quarkus.vertx.max-worker-execute-time` | - | Worker thread timeout | `15m` | - |

### OpenAPI Documentation
| Property | Environment Variable | Description | Default | Required |
|----------|---------------------|-------------|---------|----------|
| `quarkus.smallrye-openapi.info-title` | - | API documentation title | `Code Agent Runner API` | - |
| `quarkus.smallrye-openapi.info-version` | - | API version | `1.0.0` | - |
| `quarkus.swagger-ui.always-include` | - | Include Swagger UI in production | `true` | - |
| `quarkus.swagger-ui.path` | - | Swagger UI path | `/q/swagger-ui` | - |

## Docker Configuration

### Container Build
| Property | Description | Default |
|----------|-------------|---------|
| `docker.image.name` | Docker image name | `julesenergy/code-agent-runner` |
| `docker.image.prefix` | Registry prefix | `450019303360.dkr.ecr.eu-central-1.amazonaws.com` |
| `docker.image.tag` | Image tag | `${project.version}` |
| `docker.exposePort` | Container port mapping | `8183:8183` |

## Configuration Examples

### Development Environment
```bash
# .env file for development
ANTHROPIC_API_KEY=sk-ant-...
DATABASE_PASSWORD=devpassword
JIRA_BASE_URL=https://company.atlassian.net
JIRA_USER=dev@company.com
JIRA_API_TOKEN=ATATT...
BITBUCKET_WORKSPACE=mycompany
BITBUCKET_USER=devuser
BITBUCKET_APP_PASSWORD=ATB...
API_KEY=dev-api-key
```

### Production Environment
```bash
# Environment variables for production deployment
ANTHROPIC_API_KEY=sk-ant-production-key
DATABASE_URL=jdbc:postgresql://prod-db:5432/code_agent
DATABASE_PASSWORD=secure-prod-password
JIRA_BASE_URL=https://company.atlassian.net
JIRA_USER=agent@company.com
JIRA_API_TOKEN=production-token
BITBUCKET_WORKSPACE=company-prod
BITBUCKET_USER=code-agent
BITBUCKET_APP_PASSWORD=production-app-password
API_KEY=secure-production-api-key
WEBHOOK_SECRET_BITBUCKET=webhook-signing-secret
WEBHOOK_SECRET_JIRA=jira-webhook-secret
TEAMS_WEBHOOK_URL=https://company.webhook.office.com/...
AIKIDO_CLIENT_ID=production-client-id
AIKIDO_CLIENT_SECRET=production-client-secret
CONFLUENCE_BASE_URL=https://company.atlassian.net/wiki
CONFLUENCE_USER=docs@company.com
CONFLUENCE_API_TOKEN=confluence-api-token
VOYAGE_API_KEY=voyage-production-key
```

### High-Volume Configuration
```properties
# application.properties for high-volume deployments
run-fix.max-concurrent-jobs=10
run-fix.max-queue-size=100
quarkus.datasource.jdbc.max-size=50
quarkus.datasource.jdbc.min-size=10
quarkus.vertx.max-worker-execute-time=30m
anthropic.max-tokens=4096
voyage.batch-size=256
```

## Configuration Validation

The application validates configuration at startup and logs warnings for:
- Missing required configuration
- Invalid API credentials
- Unreachable external services
- Malformed URLs or secrets

Use the health check endpoint (`/health`) to verify configuration:
```bash
curl http://localhost:8080/health
```