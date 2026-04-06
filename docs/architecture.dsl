workspace "Code Agent Runner" "Self-hosted AI coding agent that clones repositories, runs an agentic tool-use loop, validates builds, creates pull requests, and updates Jira." {

    model {

        # ── External actors ─────────────────────────────────────────────────

        developer = person "Developer" "Software engineer who triggers jobs, reviews AI-generated PRs, and interacts via the chat interface."
        admin = person "Administrator" "Platform admin who manages settings, knowledge sources, teams, and user accounts."

        # ── External systems ────────────────────────────────────────────────

        anthropicApi = softwareSystem "Anthropic Claude API" "LLM provider (Claude Sonnet / Haiku) used for the agentic tool-use loop, code generation, reviews, and chat." {
            tags "External"
        }

        jiraCloud = softwareSystem "Jira Cloud" "Issue tracking system; provides ticket context for fix jobs, receives status transitions and comments, and is used as a knowledge source." {
            tags "External"
        }

        confluenceCloud = softwareSystem "Confluence Cloud" "Wiki platform used as a knowledge source and documentation target for generated docs." {
            tags "External"
        }

        gitPlatform = softwareSystem "Git Platform" "Source control hosting (Bitbucket Cloud, Azure DevOps, GitLab, or GitHub). Receives webhooks, hosts pull requests, and is cloned by the agent." {
            tags "External"
        }

        aikidoSecurity = softwareSystem "Aikido Security" "SAST/SCA vulnerability scanner. Provides security issue context for automated fix jobs and triage workflows." {
            tags "External"
        }

        awsBedrock = softwareSystem "AWS Bedrock" "Managed ML service used for vector embeddings (Cohere, Titan) and semantic reranking." {
            tags "External"
        }

        awsTranscribe = softwareSystem "AWS Transcribe Streaming" "Converts spoken audio to text for the voice input feature." {
            tags "External"
        }

        awsS3 = softwareSystem "AWS S3" "Object storage for knowledge file uploads (PDFs, markdown) and UI static assets." {
            tags "External"
        }

        awsSecretsManager = softwareSystem "AWS Secrets Manager" "Stores database credentials, API keys, and webhook secrets used by the ECS task at startup." {
            tags "External"
        }

        keycloak = softwareSystem "Keycloak" "OIDC identity provider that issues JWT bearer tokens for the REST API and UI authentication." {
            tags "External"
        }

        n8n = softwareSystem "n8n" "Workflow automation platform that orchestrates the human-approval flow (PR approve/reject) via webhook callbacks." {
            tags "External"
        }

        teamsWebhook = softwareSystem "Microsoft Teams" "Receives Adaptive Card notifications for job status updates (started, awaiting approval, completed, failed)." {
            tags "External"
        }

        scytale = softwareSystem "Scytale" "SOC 2 compliance platform that receives automated evidence uploads when approved fix jobs are merged to production." {
            tags "External"
        }

        # ── Main software system ─────────────────────────────────────────────

        codeAgentRunner = softwareSystem "Code Agent Runner" "Quarkus-based service that automates code fixes, AI code reviews, test generation, documentation, and knowledge management through an agentic Claude tool-use loop." {

            # ── Containers ───────────────────────────────────────────────

            apiService = container "API Service" "Quarkus 3 REST application. Exposes all REST endpoints, runs the agentic job loop, and coordinates all integrations." "Java 21 / Quarkus 3 / JAX-RS" {
                tags "Service"

                # REST surface — grouped by domain
                webhookHandlers = component "Webhook Handlers" "Receives HMAC-verified push events from Bitbucket, GitHub, GitLab, Azure DevOps, Jira, Confluence, and Aikido. Enqueues review/fix jobs automatically." "JAX-RS Resources"
                runFixResource = component "RunFix Resource" "REST endpoints: POST /run-fix, /quick-fix, /aikido-fix, /review-pr, /fix-pr, /generate-tests, /generate-docs, /sync-jira." "JAX-RS Resource"
                jobsResource = component "Jobs Resource" "REST endpoints for job lifecycle: list, status, diff, approve, reject, rerun, cancel, comment-chat (SSE)." "JAX-RS Resource"
                chatResource = component "Chat Resource" "POST /chat — SSE-streaming freeform AI assistant backed by a read-mostly tool-use loop." "JAX-RS Resource"
                knowledgeResource = component "Knowledge Resource" "REST endpoints to index Jira/Confluence/web-docs/static files, search the knowledge base, and manage sources." "JAX-RS Resource"
                architectureResource = component "Architecture Resource" "REST endpoints to generate, view, version, pin, and export Structurizr DSL architecture diagrams." "JAX-RS Resource"
                settingsResource = component "Settings Resource" "Manages global agent settings, repo settings, prompt templates, teams, hooks, and customer registry." "JAX-RS Resource"

                # Core agent engine
                agentRunner = component "Agent Runner" "Thin dispatcher: resolves the correct JobHandler for each job type and delegates to it. Also handles PR approve/reject lifecycle." "ApplicationScoped Bean"
                claudeToolUseLoop = component "Claude Tool-Use Loop" "Core agentic loop: sends messages to Claude with tool definitions, dispatches tool calls in parallel where safe, and iterates until a final response or iteration cap." "ApplicationScoped Bean"
                jobQueue = component "Job Queue" "In-memory FIFO queue with configurable capacity and max-concurrency. Submits jobs to a ManagedExecutor thread pool." "ApplicationScoped Bean"

                # Job handlers (one per job type)
                jobHandlers = component "Job Handlers" "Specialised handlers for each JobType: RunFix, Review, FixPr, FixComment, Reply, GenerateTests, GenerateDocs, QualityReport, Metrics, Upgrade, KnowledgeGraph, Architecture, and more." "CDI Beans"

                # Intelligence services
                codeGraphService = component "Code Graph Service" "Builds and queries AST-based call graphs using JavaParser, Tree-sitter (C#, TypeScript, PHP). Supports cross-repo impact analysis." "ApplicationScoped Bean"
                embeddingIndexer = component "Embedding Indexer" "Indexes code symbols (classes, methods) as vectors via AWS Bedrock and stores them in pgvector for semantic code search." "ApplicationScoped Bean"
                knowledgeIndexer = component "Knowledge Indexer" "Indexes Jira issues, Confluence pages, web-doc crawls, and uploaded files as vectors via AWS Bedrock for knowledge search." "ApplicationScoped Bean"
                linterService = component "Linter Service" "Runs Checkstyle, PMD, SpotBugs, ESLint, PHPStan, and dotnet-format. Diffs findings against a pre-change baseline." "ApplicationScoped Bean"
                plannerService = component "Planner Service" "Generates and orchestrates multi-step execution plans for complex Jira epics / user stories using Claude." "ApplicationScoped Bean"

                # Integration clients
                gitPlatformService = component "Git Platform Service" "Pluggable abstraction over Bitbucket, GitHub, GitLab, and Azure DevOps: clone, PR create/merge/decline, inline comments, webhook sync." "ApplicationScoped Bean"
                jiraService = component "Jira Service" "REST client for Jira Cloud: fetch issues, post comments, transition status, add worklogs, create issues." "ApplicationScoped Bean"
                confluenceService = component "Confluence Service" "REST client for Confluence Cloud: read pages, create/update pages, index space content." "ApplicationScoped Bean"
                aikidoService = component "Aikido Service" "OAuth2 REST client for Aikido Security: list open issue groups, fetch CVE details, trigger CI scans." "ApplicationScoped Bean"
                bedrockEmbeddingService = component "Bedrock Embedding Service" "Calls AWS Bedrock InvokeModel for code/text embeddings and BedrockAgentRuntime Rerank API." "ApplicationScoped Bean"

                # Notification & compliance
                notifiers = component "Notifiers" "Sends job result notifications to Microsoft Teams (Adaptive Cards) and n8n webhooks for approval flows." "ApplicationScoped Beans"
                scytaleService = component "Scytale Service" "Uploads SOC 2 compliance evidence (audit trail + compliance checks) to Scytale when fix jobs are merged to production." "ApplicationScoped Bean"

                # Scheduler
                schedulers = component "Schedulers" "Quarkus @Scheduled jobs: code-graph pre-build, knowledge reindex, quality reports, upgrade checks, web-doc crawl, log analysis." "Quarkus Scheduler"
            }

            database = container "PostgreSQL Database" "Primary persistent store. Holds jobs, repo settings, code graph, vector embeddings, knowledge embeddings, audit log, conversations, plans, and more. Uses pgvector extension for similarity search." "PostgreSQL 15 + pgvector" {
                tags "Database"
            }

            frontendUi = container "Frontend UI" "Static React single-page application served from S3 via CloudFront. Provides job dashboard, chat, architecture viewer, settings, and knowledge management." "React / CloudFront + S3" {
                tags "WebApp"
            }

            loadBalancer = container "Application Load Balancer" "AWS ALB that terminates HTTPS and forwards traffic to ECS Fargate tasks on port 8080." "AWS ALB" {
                tags "Infrastructure"
            }
        }

        # ── Relationships: people → system ───────────────────────────────────

        developer -> codeAgentRunner "Triggers fix/review jobs, chats with AI, reviews pull requests, manages settings" "HTTPS/REST + SSE"
        admin -> codeAgentRunner "Configures integrations, manages knowledge sources, user accounts, and system settings" "HTTPS/REST"

        # ── Relationships: external systems → Code Agent Runner ───────────────

        gitPlatform -> codeAgentRunner "Sends PR / comment webhook events (HMAC-SHA256)" "HTTPS/Webhook"
        jiraCloud -> codeAgentRunner "Sends issue-created / issue-updated webhook events" "HTTPS/Webhook"
        confluenceCloud -> codeAgentRunner "Sends page-updated webhook events for knowledge re-index" "HTTPS/Webhook"
        aikidoSecurity -> codeAgentRunner "Sends new vulnerability webhook events" "HTTPS/Webhook"
        n8n -> codeAgentRunner "Calls approve/reject job endpoints after human decision" "HTTPS/REST"

        # ── Relationships: Code Agent Runner → external systems ───────────────

        codeAgentRunner -> anthropicApi "Sends tool-use messages and receives model responses" "HTTPS / Anthropic Java SDK"
        codeAgentRunner -> jiraCloud "Fetches issues, posts comments, transitions status, adds worklogs" "HTTPS/REST"
        codeAgentRunner -> confluenceCloud "Reads and writes wiki pages, indexes space content" "HTTPS/REST"
        codeAgentRunner -> gitPlatform "Clones repos, pushes branches, creates/merges/declines PRs, posts inline comments" "HTTPS/Git + REST"
        codeAgentRunner -> aikidoSecurity "Fetches vulnerability groups and CVE details via OAuth2" "HTTPS/REST"
        codeAgentRunner -> awsBedrock "Generates vector embeddings and reranks search results" "HTTPS / AWS SDK v2"
        codeAgentRunner -> awsTranscribe "Streams PCM audio chunks and receives transcription results" "HTTPS / AWS SDK v2 (WebSocket)"
        codeAgentRunner -> awsS3 "Stores and retrieves knowledge file uploads and static UI assets" "HTTPS / AWS SDK v2"
        codeAgentRunner -> awsSecretsManager "Reads secrets at startup via ECS task execution role" "HTTPS / AWS SDK v2"
        codeAgentRunner -> keycloak "Validates OIDC bearer tokens on each authenticated request" "HTTPS/OIDC"
        codeAgentRunner -> n8n "POSTs job result payloads to trigger approval workflows" "HTTPS/Webhook"
        codeAgentRunner -> teamsWebhook "Sends Adaptive Card notifications for job lifecycle events" "HTTPS/Webhook"
        codeAgentRunner -> scytale "Uploads SOC 2 evidence on approved-and-merged fix jobs" "HTTPS/REST"

        # ── Container-level relationships ─────────────────────────────────────

        developer -> frontendUi "Uses browser-based UI" "HTTPS"
        frontendUi -> loadBalancer "Makes API calls" "HTTPS/REST + SSE"
        loadBalancer -> apiService "Forwards requests to" "HTTP"
        apiService -> database "Reads and writes all persistent state" "JDBC / pgvector"
        apiService -> awsS3 "Stores and retrieves attachment files" "HTTPS / AWS SDK v2"
        apiService -> anthropicApi "Runs agentic tool-use loop" "HTTPS / Anthropic Java SDK"
        apiService -> jiraCloud "Fetches issues, updates status" "HTTPS/REST"
        apiService -> confluenceCloud "Reads and writes pages" "HTTPS/REST"
        apiService -> gitPlatform "Clones repos and manages PRs" "HTTPS/Git + REST"
        apiService -> aikidoSecurity "Fetches security issue data" "HTTPS/REST"
        apiService -> awsBedrock "Generates embeddings and reranks" "HTTPS / AWS SDK v2"
        apiService -> awsTranscribe "Transcribes audio via streaming" "HTTPS / AWS SDK v2"
        apiService -> keycloak "Validates OIDC JWT tokens" "HTTPS/OIDC"
        apiService -> n8n "Sends job result notifications" "HTTPS/Webhook"
        apiService -> teamsWebhook "Sends Teams notifications" "HTTPS/Webhook"
        apiService -> scytale "Uploads compliance evidence" "HTTPS/REST"
        apiService -> awsSecretsManager "Reads bootstrap secrets at startup" "HTTPS / AWS SDK v2"

        # ── Component-level relationships (inside API Service) ────────────────

        webhookHandlers -> jobQueue "Enqueues review and fix jobs" "CDI Event"
        runFixResource -> jobQueue "Submits fix, review, and generation jobs" "Direct call"
        runFixResource -> jiraService "Fetches issue summary and context" "Direct call"
        runFixResource -> aikidoService "Resolves vulnerability context for aikido-fix" "Direct call"
        jobsResource -> agentRunner "Calls approve/reject lifecycle actions" "Direct call"
        jobsResource -> jobQueue "Re-queues or cancels jobs" "Direct call"
        chatResource -> claudeToolUseLoop "Runs streaming chat loop" "Direct call"
        knowledgeResource -> knowledgeIndexer "Triggers indexing and crawling" "Direct call"
        architectureResource -> jobQueue "Submits architecture generation jobs" "Direct call"
        jobQueue -> agentRunner "Dispatches jobs to the correct handler" "ManagedExecutor"
        agentRunner -> jobHandlers "Delegates execution to typed handler" "CDI lookup"
        jobHandlers -> claudeToolUseLoop "Runs the core agentic loop" "Direct call"
        jobHandlers -> gitPlatformService "Clones repos, creates PRs" "Direct call"
        jobHandlers -> jiraService "Updates Jira issue status" "Direct call"
        jobHandlers -> linterService "Runs static analysis before and after changes" "Direct call"
        jobHandlers -> codeGraphService "Indexes and queries code impact graph" "Direct call"
        claudeToolUseLoop -> anthropicApi "Sends messages and tool results to Claude" "Anthropic Java SDK"
        claudeToolUseLoop -> codeGraphService "Executes query_code_graph tool calls" "Direct call"
        claudeToolUseLoop -> embeddingIndexer "Executes semantic_search tool calls" "Direct call"
        claudeToolUseLoop -> knowledgeIndexer "Executes search_knowledge_base tool calls" "Direct call"
        claudeToolUseLoop -> jiraService "Executes Jira tool calls (create, update, search, transition)" "Direct call"
        claudeToolUseLoop -> confluenceService "Executes Confluence tool calls (get, create, update page)" "Direct call"
        embeddingIndexer -> bedrockEmbeddingService "Generates code embeddings" "Direct call"
        knowledgeIndexer -> bedrockEmbeddingService "Generates knowledge embeddings" "Direct call"
        bedrockEmbeddingService -> awsBedrock "Invokes embedding and reranking models" "AWS SDK v2"
        notifiers -> n8n "Sends job result webhook payload" "HTTP POST"
        notifiers -> teamsWebhook "Sends Adaptive Card notification" "HTTP POST"
        agentRunner -> scytaleService "Triggers SOC 2 evidence upload on PR merge" "Direct call"
        scytaleService -> scytale "POSTs evidence payload" "HTTPS/REST"
        schedulers -> jobQueue "Enqueues scheduled jobs (quality reports, upgrades, code graphs)" "Direct call"
        schedulers -> knowledgeIndexer "Triggers periodic knowledge reindex" "Direct call"
        plannerService -> claudeToolUseLoop "Runs plan-generation loop" "Direct call"
    }

    views {

        systemContext codeAgentRunner "SystemContext" {
            include *
            autoLayout lr
            title "Code Agent Runner — System Context"
            description "Shows the Code Agent Runner and all people and external systems it interacts with."
        }

        container codeAgentRunner "Containers" {
            include *
            autoLayout lr
            title "Code Agent Runner — Containers"
            description "The deployable containers and key external dependencies."
        }

        component apiService "Components_APIService" {
            include *
            autoLayout tb
            title "API Service — Components"
            description "Internal components of the Quarkus API service: REST resources, agent engine, intelligence services, and integration clients."
        }

        styles {
            element "Person" {
                shape Person
                background #1168bd
                color #ffffff
            }
            element "Database" {
                shape Cylinder
                background #2e7d32
                color #ffffff
            }
            element "WebApp" {
                shape WebBrowser
                background #e65100
                color #ffffff
            }
            element "Infrastructure" {
                shape RoundedBox
                background #546e7a
                color #ffffff
            }
            element "External" {
                background #999999
                color #ffffff
            }
            element "Service" {
                background #1565c0
                color #ffffff
            }
            element "Queue" {
                shape Pipe
                background #6a1b9a
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
            relationship "Relationship" {
                dashed false
            }
        }
    }
}
