# Key Business Flows

This document describes the most important workflows in the Code Agent Runner system. Each flow is illustrated with sequence diagrams and includes detailed explanations of the process steps and decision points.

## Fix Job Lifecycle

The fix job workflow is the core business process, handling everything from initial job submission through final PR approval and JIRA closure.

### Overview

A fix job takes a JIRA issue and automatically generates code changes to resolve it, creating a pull request for human review and approval.

```mermaid
sequenceDiagram
    participant Client as Client/n8n
    participant API as RunFixResource
    participant Queue as JobQueue
    participant Runner as AgentRunner
    participant Claude as Claude API
    participant Git as Git Platform
    participant JIRA as JIRA Cloud
    participant Teams as Teams/n8n

    Client->>API: POST /run-fix
    API->>Queue: Submit job
    Queue-->>API: Job queued
    API-->>Client: 202 Accepted (jobId)
    
    Queue->>Runner: Execute job
    Runner->>Git: Clone repository
    Runner->>JIRA: Fetch issue details
    Runner->>Claude: Initialize tool-use loop
    
    loop Tool-use iterations
        Claude->>Runner: Use tools (read_file, write_file, run_command)
        Runner->>Claude: Tool results
    end
    
    Runner->>Runner: Validate build (mvn test)
    Runner->>Git: Push branch & create PR
    Runner->>JIRA: Add comment & transition
    Runner->>Teams: Notify completion
    
    Note right of Runner: Job status: AWAITING_APPROVAL
    
    Client->>API: POST /jobs/{jobId}/approve
    API->>Runner: Approve job
    Runner->>Git: Merge PR
    Runner->>JIRA: Transition to Done
    
    Note right of Runner: Job status: APPROVED
```

### Detailed Steps

1. **Job Submission**: Client submits job via REST API with repository URL, branch name, and JIRA key
2. **Queue Processing**: Job enters FIFO queue, processed when slot available
3. **Repository Setup**: Clone repo, checkout target branch, load coding rules
4. **Context Resolution**: Fetch JIRA issue details and any Aikido vulnerability context
5. **AI Processing**: Claude uses tools to explore code, make changes, and validate
6. **Build Validation**: Run Maven tests to ensure changes don't break build
7. **PR Creation**: Push branch and create pull request with detailed description
8. **Notification**: Update JIRA with progress and notify stakeholders
9. **Human Approval**: Job waits in AWAITING_APPROVAL status
10. **Completion**: On approval, merge PR and transition JIRA to Done

### Job States

| State | Description | Next States |
|-------|-------------|-------------|
| `QUEUED` | Waiting in job queue | `RUNNING`, `FAILED` |
| `RUNNING` | Active processing by agent | `AWAITING_APPROVAL`, `FAILED` |
| `AWAITING_APPROVAL` | PR created, waiting for human review | `APPROVED`, `REJECTED` |
| `APPROVED` | PR merged successfully | (final state) |
| `REJECTED` | PR declined by human reviewer | (final state) |
| `FAILED` | Job failed during processing | (final state) |

## Code Review Workflow

The AI-powered code review process analyzes pull requests for security, design quality, and best practices.

### Overview

When a PR is created or updated, the system automatically performs a comprehensive code review and posts findings as inline comments.

```mermaid
sequenceDiagram
    participant Webhook as Git Webhook
    participant Resource as WebhookResource
    participant Runner as AgentRunner
    participant Graph as CodeGraphService
    participant Memory as MemoryStore
    participant Claude as Claude API
    participant Platform as Git Platform
    participant Feedback as CommentStore

    Webhook->>Resource: PR created/updated
    Resource->>Runner: Trigger review job
    
    Runner->>Platform: Fetch PR diff
    Runner->>Graph: Build/update code graph
    Runner->>Graph: Analyze impact of changes
    Runner->>Memory: Load review preferences
    
    Runner->>Claude: Review prompt with context
    Note over Claude: - Diff analysis<br/>- Security review<br/>- Design patterns<br/>- Code quality<br/>- Test coverage
    
    Claude->>Runner: Review findings
    
    loop For each finding
        Runner->>Platform: Post inline comment
        Runner->>Feedback: Track comment for metrics
    end
    
    Runner->>Platform: Post PR summary comment
    
    Note right of Runner: Review complete
```

### Review Context Building

Before conducting the review, the system builds rich context:

1. **Code Graph Analysis**: Identify which symbols are modified and their dependencies
2. **Impact Assessment**: Find all callers and implementations of changed code
3. **Memory Integration**: Load team preferences and learned patterns
4. **False Positive Suppression**: Apply auto-suppression rules from previous feedback

### Review Coverage Areas

The AI review focuses on these key areas:

- **Security**: Injection vulnerabilities, authentication issues, data exposure
- **Design**: SOLID principles, separation of concerns, API design
- **Code Quality**: Naming conventions, complexity, error handling
- **Testing**: Coverage gaps, missing edge cases, test quality
- **Performance**: Inefficient algorithms, resource usage, caching opportunities
- **Best Practices**: Framework conventions, idiomatic patterns

## Developer Interaction Flow

Developers can interact with review comments using special commands and natural language.

### Overview

When a developer replies to an agent review comment, the system classifies the intent and takes appropriate action.

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant Webhook as Comment Webhook
    participant Processor as CommentProcessor
    participant Classifier as IntentClassifier
    participant Memory as MemoryStore
    participant Feedback as FeedbackStore
    participant Claude as Claude API
    participant Platform as Git Platform

    Dev->>Platform: Reply to agent comment
    Platform->>Webhook: Comment created event
    Webhook->>Processor: Process reply
    
    Processor->>Classifier: Classify intent
    
    alt /fp or /false-positive
        Classifier-->>Processor: FALSE_POSITIVE
        Processor->>Feedback: Record false positive
        Processor->>Platform: Mark comment resolved
        Processor->>Feedback: Check auto-suppress threshold
        alt Threshold reached
            Processor->>Memory: Create suppression rule
        end
        
    else /learn <preference>
        Classifier-->>Processor: LEARN
        Processor->>Memory: Store team preference
        Processor->>Platform: Acknowledge learning
        
    else Fix request
        Classifier-->>Processor: FIX_REQUEST
        Processor->>Claude: Generate fix
        Claude-->>Processor: Code changes
        Processor->>Platform: Apply changes to branch
        Processor->>Platform: Reply with fix description
        
    else Discussion
        Classifier-->>Processor: DISCUSSION
        Processor->>Claude: Generate conversational reply
        Claude-->>Processor: Response text
        Processor->>Platform: Post reply
    end
```

### Supported Reply Types

| Reply Pattern | Action | Example |
|---------------|--------|---------|
| `/fp` or `/false-positive` | Mark as false positive, auto-suppress after threshold | `/fp` |
| `/learn <preference>` | Store team preference | `/learn Prefer streams over loops for collection processing` |
| Natural language fix | Generate and apply code fix | `Please add null check here` |
| Natural language discussion | Conversational reply | `Why is this approach better?` |

## Aikido Security Integration Flow

The Aikido-enhanced fix workflow provides vulnerability-specific context for more effective remediation.

### Overview

Starting from a JIRA ticket, the system enriches the context with vulnerability details from Aikido Security.

```mermaid
sequenceDiagram
    participant Client as Client/JIRA
    participant API as RunFixResource
    participant Aikido as AikidoService
    participant JIRA as JIRAService
    participant Runner as AgentRunner
    participant Claude as Claude API

    Client->>API: POST /aikido-fix {jiraKey}
    API->>Aikido: Find issue group by JIRA key
    
    alt Found in Aikido API
        Aikido-->>API: Issue group ID
    else Not found, check JIRA description
        API->>JIRA: Fetch issue description
        JIRA-->>API: Description with Aikido URLs
        API->>Aikido: Extract candidate IDs
        loop For each candidate
            API->>Aikido: Get issue group details
        end
    end
    
    API->>Aikido: Get vulnerability details
    Aikido-->>API: Package info, CVE, changelog
    
    alt Repository URL from Aikido
        Aikido-->>API: Code repo URL
    else Container image reference
        API->>Aikido: Resolve container to repo mapping
        Aikido-->>API: Mapped repo URL
    end
    
    API->>API: Build enriched prompt
    Note over API: - Package name & versions<br/>- CVE severity & description<br/>- Upgrade changelog<br/>- Fix recommendations
    
    API->>Runner: Execute with enriched context
    Runner->>Claude: Process with vulnerability details
    Claude->>Claude: Generate targeted fix
```

### Resolution Strategies

The system uses multiple strategies to resolve Aikido context:

1. **Direct API Lookup**: Search Aikido for issues linked to the JIRA key
2. **JIRA Description Parsing**: Extract Aikido URLs from ticket descriptions
3. **Direct Issue ID**: Use provided `aikidoGroupId` parameter
4. **Container Mapping**: Map container images to source repositories

### Enriched Context

The Aikido integration provides:

- **Package Information**: Name, current version, recommended fix version
- **CVE Details**: Severity score, description, affected components
- **Changelog**: Summary of changes between versions
- **Fix Guidance**: Aikido-specific remediation recommendations

## Webhook Processing Flow

The system handles various webhook events from external platforms to trigger automated actions.

### JIRA Issue Assignment Flow

```mermaid
sequenceDiagram
    participant JIRA as JIRA Cloud
    participant Webhook as JiraWebhookResource
    participant Service as JiraService
    participant Aikido as AikidoService
    participant Queue as JobQueue

    JIRA->>Webhook: Issue assigned to agent
    Webhook->>Webhook: Verify assignee is agent user
    
    alt Valid assignment
        Webhook->>Service: Extract JIRA context
        Service-->>Webhook: Issue summary, description
        
        alt Aikido enabled
            Webhook->>Aikido: Resolve vulnerability context
            Aikido-->>Webhook: Enriched prompt
        else Standard JIRA prompt
            Webhook->>Webhook: Use JIRA description
        end
        
        Webhook->>Queue: Submit fix job
        Queue-->>Webhook: Job queued
        Webhook-->>JIRA: 200 OK {jobId, branch}
        
    else Invalid assignment
        Webhook-->>JIRA: 200 OK {action: ignored}
    end
```

### Git Platform PR Webhook Flow

```mermaid
sequenceDiagram
    participant Platform as Git Platform
    participant Webhook as PRWebhookResource
    participant Runner as AgentRunner
    participant Settings as RepoSettingsStore

    Platform->>Webhook: PR created/updated
    Webhook->>Settings: Check repo review settings
    
    alt Review enabled
        Webhook->>Webhook: Check skip conditions
        Note over Webhook: - Author is agent<br/>- Title missing keyword
        
        alt Should review
            Webhook->>Runner: Queue review job
            Runner-->>Webhook: Job queued
            Webhook-->>Platform: 200 OK {jobId}
        else Skip review
            Webhook-->>Platform: 200 OK {action: skipped}
        end
    else Review disabled
        Webhook-->>Platform: 200 OK {action: disabled}
    end
```

## Vector Search and Code Intelligence Flow

The system builds and maintains code graphs with vector embeddings for semantic search capabilities.

### Code Graph Building Flow

```mermaid
sequenceDiagram
    participant Scheduler as CodeGraphScheduler
    participant Builder as CodeGraphBuildService
    participant Indexer as CodeGraphIndexer
    participant Embedding as EmbeddingIndexer
    participant Store as CodeGraphStore
    participant VectorStore as EmbeddingStore

    Scheduler->>Builder: Build missing graphs (cron)
    Builder->>Builder: Clone repository
    Builder->>Indexer: Parse source files
    
    loop For each source file
        Indexer->>Indexer: Extract AST symbols
        Note over Indexer: - Classes, methods, fields<br/>- Access modifiers<br/>- Line numbers
        Indexer->>Store: Store nodes and edges
    end
    
    alt Vector indexing enabled
        Builder->>Embedding: Generate embeddings
        loop For each symbol
            Embedding->>Embedding: Extract source text
            Embedding->>VectorStore: Generate & store embedding
        end
    end
    
    Builder->>Builder: Cleanup workspace
    Note right of Builder: Graph ready for queries
```

### Semantic Search Flow

```mermaid
sequenceDiagram
    participant Claude as Claude API
    participant Tool as SemanticSearchTool
    participant Embedding as AWSBedrock
    participant Store as EmbeddingStore

    Claude->>Tool: semantic_search("payment refund logic")
    Tool->>Embedding: Generate query embedding
    Embedding-->>Tool: Vector representation
    
    Tool->>Store: Cosine similarity search
    Store-->>Tool: Top K matching symbols
    
    Tool->>Tool: Format results
    Note over Tool: - File paths<br/>- Symbol names<br/>- Source snippets<br/>- Similarity scores
    
    Tool-->>Claude: Search results
```

## Learning and Quality Improvement Flow

The system continuously learns from developer feedback to improve review quality.

### False Positive Auto-Suppression Flow

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant Processor as CommentProcessor
    participant Feedback as CommentFeedbackStore
    participant Memory as MemoryStore
    participant Future as Future Reviews

    Dev->>Processor: Reply "/fp" to comment
    Processor->>Feedback: Record false positive feedback
    Processor->>Feedback: Count pattern occurrences
    
    alt Threshold reached (e.g., 3 occurrences)
        Processor->>Memory: Create auto-suppression rule
        Note over Memory: Pattern: "Null check on method parameter"<br/>Context: "Private helper methods"<br/>Source: "auto_suppress"
    end
    
    Future->>Memory: Load suppression rules
    Memory-->>Future: Known false positive patterns
    Future->>Future: Inject into review prompt
    Note over Future: "Known False Positives" section<br/>prevents similar findings
```

### Review Quality Metrics Flow

```mermaid
sequenceDiagram
    participant Metrics as ReviewMetricsResource
    participant Feedback as CommentFeedbackStore
    participant Comments as CommentStore

    Metrics->>Comments: Query total findings by repo
    Comments-->>Metrics: Finding counts by category
    
    Metrics->>Feedback: Query feedback by repo
    Feedback-->>Metrics: FP counts, resolution rates
    
    Metrics->>Metrics: Calculate quality metrics
    Note over Metrics: - Resolution rate<br/>- False positive rate<br/>- Category breakdown<br/>- Auto-suppressed patterns
    
    Metrics-->>Metrics: Return quality report
```

## Job Queue and Concurrency Management

The system uses a sophisticated job queue to manage concurrent processing while respecting resource limits.

### Job Queue Processing Flow

```mermaid
sequenceDiagram
    participant API as REST API
    participant Queue as JobQueue
    participant Runner1 as AgentRunner (Slot 1)
    participant Runner2 as AgentRunner (Slot 2)
    participant Runner3 as AgentRunner (Slot 3)

    API->>Queue: Submit Job A
    API->>Queue: Submit Job B
    API->>Queue: Submit Job C
    API->>Queue: Submit Job D
    
    Note over Queue: Max concurrent: 3<br/>Queue capacity: 20
    
    Queue->>Runner1: Execute Job A
    Queue->>Runner2: Execute Job B  
    Queue->>Runner3: Execute Job C
    
    Note over Queue: Job D waits in queue
    
    Runner1->>Queue: Job A completed
    Queue->>Runner1: Execute Job D
    
    Note right of Queue: Jobs processed FIFO<br/>with concurrent execution
```

### Guardrails and Safety Controls

Every job execution is subject to multiple safety controls:

1. **Path Restrictions**: Block modifications to sensitive directories
2. **Command Allowlist**: Only permit safe commands like `mvn`, `git diff`
3. **Change Limits**: Cap maximum files and lines modified
4. **Timeout Controls**: Prevent runaway jobs from consuming resources
5. **Build Validation**: Ensure changes don't break compilation or tests

## Error Handling and Recovery

The system includes comprehensive error handling and recovery mechanisms.

### Job Failure Recovery Flow

```mermaid
sequenceDiagram
    participant Queue as JobQueue  
    participant Runner as AgentRunner
    participant Store as JobStore
    participant JIRA as JIRA Cloud
    participant Teams as Teams Notifier

    Runner->>Runner: Job processing fails
    Runner->>Store: Update status to FAILED
    Runner->>Store: Record error message
    
    Runner->>JIRA: Add failure comment
    Note over JIRA: - Error description<br/>- Troubleshooting tips<br/>- Manual intervention needed
    
    Runner->>Teams: Send failure notification
    Runner->>Queue: Release job slot
    
    Note right of Runner: Job archived for analysis<br/>Queue continues processing
```

This comprehensive flow documentation provides insight into how the Code Agent Runner orchestrates complex workflows while maintaining reliability and quality. Each flow is designed with proper error handling, monitoring, and recovery mechanisms to ensure robust operation in production environments.