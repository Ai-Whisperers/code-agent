workspace "Code Agent Runner" "Self-hosted AI coding agent: clones repos, runs a Claude tool-use loop, validates builds, creates pull requests, and updates Jira." {

    model {

        # ── External Actors ─────────────────────────────────────────────────────

        developer = person "Developer" "Interacts with the agent via the web UI or an MCP-enabled IDE to trigger jobs, review PRs, and chat with the AI assistant."
        admin = person "Admin" "Configures the system: repo settings, customer registry, cloud accounts, knowledge sources, and encryption keys."
        aiAgent = person "AI Agent (MCP Client)" "External AI agent (e.g. Claude Desktop) that calls the agent's MCP-compatible REST tools over HTTPS."

        # ── External Software Systems ────────────────────────────────────────────

        gitPlatform = softwareSystem "Git Platform" "Hosts source code repositories and raises webhook events on pull-request and push activity. Supports Bitbucket, GitHub, GitLab, and Azure DevOps." {
            tags "External"
        }

        jiraSystem = softwareSystem "Jira" "Issue tracker. The agent reads issues for context, posts review comments, transitions tickets, and logs work." {
            tags "External"
        }

        confluenceSystem = softwareSystem "Confluence" "Wiki. The agent reads pages for knowledge indexing and publishes generated documentation." {
            tags "External"
        }

        anthropicApi = softwareSystem "Anthropic Claude API" "Provides the Claude large-language model for all AI inference: code review, fix generation, chat, planning, and intent classification." {
            tags "External"
        }

        awsBedrock = softwareSystem "AWS Bedrock" "Generates vector embeddings for code and knowledge chunks (Cohere / Titan models) and performs reranking (amazon.rerank-v1)." {
            tags "External"
        }

        awsTranscribe = softwareSystem "AWS Transcribe Streaming" "Real-time speech-to-text service used by the /speech/transcribe endpoint." {
            tags "External"
        }

        awsCloudWatch = softwareSystem "AWS CloudWatch" "Customer-account observability: log groups, log events, and CloudWatch metrics. Accessed cross-account via STS AssumeRole." {
            tags "External"
        }

        awsEcs = softwareSystem "AWS ECS" "Customer-account container management. The agent can list clusters, services, and tasks for diagnostics." {
            tags "External"
        }

        awsRds = softwareSystem "AWS RDS" "Customer-account relational database metadata (instances and clusters)." {
            tags "External"
        }

        awsSts = softwareSystem "AWS STS" "Security Token Service. Used to assume cross-account read-only IAM roles in customer AWS accounts." {
            tags "External"
        }

        awsS3 = softwareSystem "AWS S3" "Object storage for chat attachment files uploaded by users." {
            tags "External"
        }

        awsSes = softwareSystem "AWS SES" "Simple Email Service for outbound notifications (optional)." {
            tags "External"
        }

        keycloak = softwareSystem "Keycloak" "OIDC identity provider. Issues JWT Bearer tokens for the REST API and the web UI." {
            tags "External"
        }

        aikido = softwareSystem "Aikido Security" "SAST / SCA / secret-scanning platform. The agent reads open security findings and can trigger CI scans after fixes are merged." {
            tags "External"
        }

        scytale = softwareSystem "Scytale" "SOC II compliance platform. The agent uploads evidence payloads (audit trail + compliance checks) after fix jobs are approved and merged." {
            tags "External"
        }

        teamsWebhook = softwareSystem "Microsoft Teams" "Receives Adaptive Card notifications when jobs complete, fail, or require approval." {
            tags "External"
        }

        n8nWebhook = softwareSystem "n8n" "Workflow automation platform. Receives job completion callbacks via HTTP webhook for downstream automation." {
            tags "External"
        }

        mavenCentral = softwareSystem "Maven Central / npm / Packagist" "Public package registries queried by the Upgrade Scheduler to determine the latest framework versions." {
            tags "External"
        }

        xray = softwareSystem "Xray (Jira)" "Jira-native test management add-on. The agent creates test executions and updates test run statuses." {
            tags "External"
        }

        # ── Main Software System ─────────────────────────────────────────────────

        codeAgent = softwareSystem "Code Agent Runner" "Quarkus-based self-hosted service that orchestrates AI-powered code review, automated fixes, knowledge indexing, quality reporting, and compliance evidence generation." {

            # ── Containers ──────────────────────────────────────────────────────

            apiService = container "API Service" "Quarkus 3 JAX-RS application. Exposes REST endpoints for job management, chat, plans, quality reports, webhooks, speech, and MCP tooling. Secured by Keycloak OIDC and API-key filters." "Java 21 / Quarkus 3 / RESTEasy Reactive" {
                tags "Service"

                # REST resources
                webhookHandler = component "Webhook Handlers" "Receives and validates signed webhook events from the Git platform, Jira, and Aikido. Enqueues the appropriate job type."
                jobsApi = component "Jobs API" "CRUD and lifecycle endpoints for agent jobs: list, diff, commits, approve, reject, evidence upload."
                chatApi = component "Chat API" "POST /chat — streams SSE ChatEvents (text deltas, tool events) from a Claude tool-use loop. Supports multi-turn conversation history."
                speechApi = component "Speech API" "POST /speech/transcribe — forwards raw PCM audio to AWS Transcribe Streaming and returns the transcript."
                attachmentsApi = component "Attachments API" "Multipart upload and presigned-URL download for chat attachment files stored in S3."
                planApi = component "Plan API" "CRUD, generation, approval/rejection, and SSE-streaming progress for AI execution plans."
                qualityApi = component "Quality Report API" "On-demand trigger and historical retrieval of per-repository quality snapshots."
                mcpApi = component "MCP Tools API" "Model Context Protocol compatible REST tools (Jira, Confluence, Xray, agent control) consumed by external AI agents."
                customerApi = component "Customer Registry API" "Manages customer → product → environment → cloud account mappings."
                settingsApi = component "Settings API" "Encrypted key-value store for system and per-product runtime configuration."

                # Core agent machinery
                jobQueue = component "Job Queue" "In-memory priority queue with per-category concurrency semaphores. Dispatches JobRecords to the AgentRunner. Refills review jobs from the database every 10 s."
                agentRunner = component "Agent Runner" "Resolves the matching JobHandler for each job type and delegates execution. Handles approve/reject lifecycle including PR merge and Scytale evidence upload."
                claudeLoop = component "Claude Tool-Use Loop" "Iterative agentic loop: sends messages to Claude with tool definitions, executes tool calls (in parallel when read-only), and repeats until a final answer or iteration cap is reached."
                toolRegistry = component "Tool Registry" "CDI registry of all ToolExecutor beans. Routes tool-call names from Claude to the correct executor."

                # Job handlers
                reviewHandler = component "Review Handler" "Clones repo, fetches PR diff, runs linters and static analysis, then asks Claude to produce inline review comments posted back to the Git platform."
                fixHandler = component "Fix / Fix-PR / Fix-Comment Handlers" "Clones repo, runs the Claude tool-use loop in write mode to apply code changes, validates the build, and opens or updates a pull request."
                generateTestsHandler = component "Generate Tests Handler" "Generates unit tests for changed code and commits them to a new PR."
                generateDocsHandler = component "Generate Docs Handler" "Generates or updates documentation pages and optionally publishes them to Confluence."
                qualityReportHandler = component "Quality Report Handler" "Collects coverage, linter findings, complexity, and security metrics; stores a quality snapshot in the database."
                planOrchestratorSvc = component "Plan Orchestrator Service" "Phase-by-phase execution of approved execution plans. Listens for JobCompletedEvents and submits the next phase."

                # Supporting services
                knowledgeIndexer = component "Knowledge Indexer" "Indexes Jira issues, Confluence pages, web documentation, and S3-hosted static files into the knowledge_embeddings vector table."
                embeddingIndexer = component "Embedding Indexer" "Extracts class and method-level symbol chunks from Java / C# / TypeScript / PHP source files and stores embeddings in the code_embeddings table."
                codeGraphSvc = component "Code Graph Service" "Builds and queries a call-graph index (code_graph table) using JavaParser AST analysis. Supports cross-repo impact analysis."
                upgradeService = component "Upgrade Service" "Checks framework version registries, generates AI upgrade plans for outdated repos, and auto-executes them."
                aikidoSvc = component "Aikido Service" "OAuth2 client for the Aikido Security API. Fetches open security findings and triggers CI scans."
                scytaleSvc = component "Scytale Service" "HTTP client that uploads SOC II compliance evidence payloads to the Scytale API after fix jobs are approved."
                linterService = component "Linter Service" "Runs PMD, SpotBugs, ESLint, PHPStan, and dotnet-format against changed files; produces a diff-scoped findings report."
                chatService = component "Chat Service" "Builds system prompts, loads conversation history, selects read-only or write tools, runs the streaming Claude loop, and persists messages."
                webDocsCrawler = component "Web Docs Crawler" "BFS crawler that fetches public documentation sites and feeds pages to the knowledge indexer."
                teamsNotifier = component "Teams Notifier" "Posts Adaptive Card notifications to the Microsoft Teams incoming webhook URL."
                n8nNotifier = component "n8n Notifier" "HTTP POST callback to n8n when a job completes."
                securityFilters = component "Security Filters" "API-key filter, OIDC bearer token validation, webhook HMAC-SHA256 signature verification, and SSRF guard."
            }

            database = container "PostgreSQL Database" "Stores all persistent state: jobs, reviews, comments, conversations, knowledge embeddings, code embeddings, code graph, quality reports, plans, settings, customer registry, and audit log." "PostgreSQL 16 + pgvector" {
                tags "Database"
            }

            s3Bucket = container "S3 Attachment Bucket" "Object storage for chat attachment files (images, PDFs, text files)." "AWS S3" {
                tags "Database"
            }
        }

        # ── Relationships: Persons → System ─────────────────────────────────────

        developer -> codeAgent "Submits jobs, chats with AI, reviews plans, approves/rejects PRs" "HTTPS/REST + SSE"
        admin -> codeAgent "Configures repos, customers, cloud accounts, knowledge sources" "HTTPS/REST"
        aiAgent -> codeAgent "Calls MCP-compatible agent tools" "HTTPS/REST"

        # ── Relationships: System → External Systems ─────────────────────────────

        codeAgent -> gitPlatform "Clones repos, creates branches, opens/merges/declines PRs, posts review comments, syncs webhooks" "HTTPS/Git"
        codeAgent -> jiraSystem "Reads issues, posts comments, transitions tickets, logs worklogs" "HTTPS/REST"
        codeAgent -> confluenceSystem "Reads pages for knowledge indexing, publishes generated docs" "HTTPS/REST"
        codeAgent -> anthropicApi "Sends messages and tool definitions; receives streamed responses" "HTTPS"
        codeAgent -> awsBedrock "Generates vector embeddings for code and knowledge; reranks search results" "AWS SDK v2 / HTTPS"
        codeAgent -> awsTranscribe "Streams raw PCM audio; receives real-time transcription events" "AWS SDK v2 / HTTP2"
        codeAgent -> awsCloudWatch "Reads log groups, log events, and metrics for customer accounts" "AWS SDK v2 / HTTPS"
        codeAgent -> awsEcs "Lists clusters, services, and tasks for customer accounts" "AWS SDK v2 / HTTPS"
        codeAgent -> awsRds "Describes DB instances and clusters for customer accounts" "AWS SDK v2 / HTTPS"
        codeAgent -> awsSts "Assumes cross-account read-only IAM roles" "AWS SDK v2 / HTTPS"
        codeAgent -> awsS3 "Stores and retrieves chat attachment files" "AWS SDK v2 / HTTPS"
        codeAgent -> awsSes "Sends outbound email notifications" "AWS SDK v2 / HTTPS"
        codeAgent -> keycloak "Validates OIDC Bearer tokens; manages users via Admin REST Client" "HTTPS/OIDC"
        codeAgent -> aikido "Fetches open security findings, triggers CI scans" "HTTPS/REST"
        codeAgent -> scytale "Uploads SOC II compliance evidence after fix merges" "HTTPS/REST"
        codeAgent -> teamsWebhook "Sends Adaptive Card job-completion notifications" "HTTPS"
        codeAgent -> n8nWebhook "Sends job-completion callback payloads" "HTTPS"
        codeAgent -> mavenCentral "Queries latest framework versions for upgrade checks" "HTTPS/REST"
        codeAgent -> xray "Creates test executions, updates test run statuses" "HTTPS/REST"

        gitPlatform -> codeAgent "Delivers PR and push webhook events" "HTTPS"
        jiraSystem -> codeAgent "Delivers issue-created and issue-updated webhook events" "HTTPS"
        aikido -> codeAgent "Delivers security-finding webhook events" "HTTPS"

        # ── Relationships: Containers ────────────────────────────────────────────

        developer -> apiService "Uses" "HTTPS/REST + SSE"
        admin -> apiService "Configures" "HTTPS/REST"
        aiAgent -> apiService "Invokes MCP tools" "HTTPS/REST"

        apiService -> database "Reads and writes all state" "JDBC / SQL"
        apiService -> s3Bucket "Uploads and downloads chat attachment files" "AWS SDK v2"
        apiService -> anthropicApi "Sends inference requests" "HTTPS"
        apiService -> awsBedrock "Generates embeddings and reranks results" "AWS SDK v2"
        apiService -> awsTranscribe "Streams audio for transcription" "AWS SDK v2"
        apiService -> awsCloudWatch "Reads customer CloudWatch data via assumed role" "AWS SDK v2"
        apiService -> awsEcs "Reads customer ECS data via assumed role" "AWS SDK v2"
        apiService -> awsRds "Reads customer RDS metadata via assumed role" "AWS SDK v2"
        apiService -> awsSts "Assumes cross-account IAM roles" "AWS SDK v2"
        apiService -> gitPlatform "Clones repos, manages PRs and comments" "HTTPS/Git"
        apiService -> jiraSystem "Reads and updates issues" "HTTPS/REST"
        apiService -> confluenceSystem "Reads and publishes pages" "HTTPS/REST"
        apiService -> keycloak "Validates Bearer tokens; proxies user management" "HTTPS/OIDC"
        apiService -> aikido "Reads findings; triggers CI scans" "HTTPS/REST"
        apiService -> scytale "Uploads compliance evidence" "HTTPS/REST"
        apiService -> teamsWebhook "Posts notifications" "HTTPS"
        apiService -> n8nWebhook "Posts callbacks" "HTTPS"
        apiService -> mavenCentral "Queries version registries" "HTTPS/REST"
        apiService -> xray "Manages test executions" "HTTPS/REST"
        apiService -> awsS3 "Stores static knowledge-base files" "AWS SDK v2"
        apiService -> awsSes "Sends emails" "AWS SDK v2"

        gitPlatform -> apiService "Pushes PR/push webhook events" "HTTPS"
        jiraSystem -> apiService "Pushes issue webhook events" "HTTPS"
        aikido -> apiService "Pushes security-finding webhook events" "HTTPS"

        # ── Relationships: Components ────────────────────────────────────────────

        securityFilters -> chatApi "Guards all endpoints" "CDI filter chain"
        securityFilters -> jobsApi "Guards all endpoints" "CDI filter chain"
        securityFilters -> webhookHandler "Validates HMAC-SHA256 signatures" "CDI filter chain"

        webhookHandler -> jobQueue "Enqueues jobs on webhook events" "CDI / in-process"
        jobsApi -> jobQueue "Submits and queries jobs" "CDI / in-process"
        chatApi -> chatService "Delegates streaming chat" "CDI / in-process"
        chatApi -> attachmentsApi "References attachment IDs" "CDI / in-process"
        attachmentsApi -> s3Bucket "Uploads and downloads files" "AWS SDK v2"
        speechApi -> awsTranscribe "Streams audio" "AWS SDK v2"
        planApi -> planOrchestratorSvc "Triggers plan execution" "CDI / in-process"
        mcpApi -> jiraSystem "Reads/writes Jira on behalf of user" "HTTPS/REST"
        mcpApi -> confluenceSystem "Reads/writes Confluence on behalf of user" "HTTPS/REST"
        mcpApi -> xray "Manages test executions" "HTTPS/REST"

        jobQueue -> agentRunner "Dispatches JobRecords" "CDI / in-process"
        agentRunner -> reviewHandler "Delegates REVIEW jobs" "CDI / in-process"
        agentRunner -> fixHandler "Delegates FIX/FIX_PR/FIX_COMMENT jobs" "CDI / in-process"
        agentRunner -> generateTestsHandler "Delegates GENERATE_TESTS jobs" "CDI / in-process"
        agentRunner -> generateDocsHandler "Delegates GENERATE_DOCS jobs" "CDI / in-process"
        agentRunner -> qualityReportHandler "Delegates QUALITY_REPORT jobs" "CDI / in-process"
        agentRunner -> scytaleSvc "Uploads evidence on fix-job merge" "CDI / in-process"

        reviewHandler -> claudeLoop "Runs review prompt" "CDI / in-process"
        reviewHandler -> linterService "Runs diff-scoped linting" "CDI / in-process"
        reviewHandler -> gitPlatform "Posts inline review comments" "HTTPS/REST"
        fixHandler -> claudeLoop "Runs fix prompt in write mode" "CDI / in-process"
        fixHandler -> gitPlatform "Opens / updates pull request" "HTTPS/Git"
        generateTestsHandler -> claudeLoop "Generates tests via AI loop" "CDI / in-process"
        generateDocsHandler -> claudeLoop "Generates docs via AI loop" "CDI / in-process"
        generateDocsHandler -> confluenceSystem "Publishes generated pages" "HTTPS/REST"
        qualityReportHandler -> linterService "Collects linter findings" "CDI / in-process"
        qualityReportHandler -> database "Stores quality snapshots" "JDBC"

        claudeLoop -> anthropicApi "Sends messages and tool schemas; receives streamed responses" "HTTPS"
        claudeLoop -> toolRegistry "Dispatches tool calls by name" "CDI / in-process"

        toolRegistry -> jiraSystem "Reads/writes Jira issues (search_knowledge, jira tools)" "HTTPS/REST"
        toolRegistry -> confluenceSystem "Reads/writes Confluence pages" "HTTPS/REST"
        toolRegistry -> awsBedrock "Semantic code and knowledge search" "AWS SDK v2"
        toolRegistry -> awsCloudWatch "Reads customer logs and metrics" "AWS SDK v2"
        toolRegistry -> awsEcs "Reads customer ECS data" "AWS SDK v2"
        toolRegistry -> awsRds "Reads customer RDS metadata" "AWS SDK v2"
        toolRegistry -> gitPlatform "Reads repo files via SCM API" "HTTPS/REST"
        toolRegistry -> database "Reads code graph, embeddings, knowledge" "JDBC"

        planOrchestratorSvc -> jobQueue "Submits plan step jobs" "CDI / in-process"

        knowledgeIndexer -> jiraSystem "Fetches issues and attachments" "HTTPS/REST"
        knowledgeIndexer -> confluenceSystem "Fetches pages" "HTTPS/REST"
        knowledgeIndexer -> awsBedrock "Generates text embeddings" "AWS SDK v2"
        knowledgeIndexer -> awsS3 "Downloads static knowledge files" "AWS SDK v2"
        knowledgeIndexer -> database "Persists knowledge_embeddings" "JDBC"

        embeddingIndexer -> awsBedrock "Generates code symbol embeddings" "AWS SDK v2"
        embeddingIndexer -> database "Persists code_embeddings" "JDBC"

        codeGraphSvc -> database "Reads and writes code_graph table" "JDBC"

        upgradeService -> mavenCentral "Queries latest framework versions" "HTTPS/REST"
        upgradeService -> planOrchestratorSvc "Creates and executes upgrade plans" "CDI / in-process"
        upgradeService -> aikidoSvc "Appends security findings to upgrade spec" "CDI / in-process"

        aikidoSvc -> aikido "Fetches findings; triggers CI scans" "HTTPS/REST"
        scytaleSvc -> scytale "Uploads evidence payloads" "HTTPS/REST"

        chatService -> claudeLoop "Runs streaming chat loop" "CDI / in-process"
        chatService -> database "Loads and persists conversation history" "JDBC"

        webDocsCrawler -> database "Persists crawled web pages as knowledge chunks" "JDBC"
        webDocsCrawler -> awsBedrock "Generates embeddings for crawled pages" "AWS SDK v2"

        teamsNotifier -> teamsWebhook "Sends Adaptive Card notifications" "HTTPS"
        n8nNotifier -> n8nWebhook "Sends job-completion callbacks" "HTTPS"
    }

    # ── Views ────────────────────────────────────────────────────────────────────

    views {

        systemContext codeAgent "SystemContext" {
            include *
            autoLayout lr
            title "Code Agent Runner – System Context"
            description "The Code Agent Runner and all external systems and actors it interacts with."
        }

        container codeAgent "Containers" {
            include *
            autoLayout lr
            title "Code Agent Runner – Containers"
            description "Deployable units inside the Code Agent Runner system."
        }

        component apiService "Components_ApiService" {
            include *
            autoLayout tb
            title "API Service – Components"
            description "Key components inside the Quarkus API Service container."
        }

        styles {
            element "Person" {
                shape Person
                background #1168bd
                color #ffffff
            }
            element "Database" {
                shape Cylinder
                background #f5a623
                color #000000
            }
            element "External" {
                background #999999
                color #ffffff
            }
            element "Queue" {
                shape Pipe
            }
            element "Service" {
                background #1168bd
                color #ffffff
            }
            element "softwareSystem" {
                background #1168bd
                color #ffffff
            }
            element "container" {
                background #438dd5
                color #ffffff
            }
            element "component" {
                background #85bbf0
                color #000000
            }
        }
    }
}
