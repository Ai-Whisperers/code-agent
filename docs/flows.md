# Key Business Flows

This document outlines the most important workflows in the Code Agent Runner system, illustrating how different components interact to deliver automated code fixes, reviews, and documentation generation.

## Fix Job Lifecycle

The core automation flow that processes JIRA issues and creates pull requests with fixes.

```mermaid
sequenceDiagram
    participant Client
    participant API
    participant Queue
    participant Agent
    participant JIRA
    participant Git
    participant Claude
    participant Tools
    participant SCM
    participant N8N

    Client->>API: POST /run-fix (jiraKey, repoUrl)
    API->>JIRA: Fetch issue details
    JIRA-->>API: Issue summary & description
    API->>Queue: Enqueue fix job
    API-->>Client: Job ID

    Queue->>Agent: Process next job
    Agent->>Git: Clone repository
    Agent->>JIRA: Transition to "In Progress"
    
    loop AI Tool-Use Loop
        Agent->>Claude: Send prompt + available tools
        Claude->>Agent: Request tool execution
        Agent->>Tools: Execute (read_file, search_code, etc.)
        Tools-->>Agent: Results
        Agent->>Claude: Tool results
        Claude->>Agent: Next tool request or completion
        
        alt Need to modify files
            Claude->>Agent: write_file request
            Agent->>Tools: Write file changes
        end
        
        alt Need to run build
            Claude->>Agent: run_command (mvn compile)
            Agent->>Tools: Execute Maven build
            Tools-->>Agent: Build results
        end
    end
    
    Agent->>Git: Commit changes
    Agent->>Git: Push feature branch
    Agent->>SCM: Create pull request
    SCM-->>Agent: PR URL
    Agent->>JIRA: Update with PR link
    Agent->>N8N: Webhook notification
    Agent->>Queue: Job complete (awaiting approval)
```

## Pull Request Review Flow

Automated code review triggered by git platform webhooks.

```mermaid
sequenceDiagram
    participant GitPlatform as Git Platform
    participant Webhook
    participant API
    participant Queue
    participant Agent
    participant Claude
    participant Tools
    participant Memory
    participant SCM
    participant Linter

    GitPlatform->>Webhook: PR created/updated event
    Webhook->>API: Validate signature
    API->>Queue: Enqueue review job
    
    Queue->>Agent: Process review job
    Agent->>GitPlatform: Clone repository
    Agent->>SCM: Get PR diff
    Agent->>Tools: Build code graph
    Agent->>Memory: Load review context
    
    opt Run Static Analysis
        Agent->>Linter: Run Checkstyle, PMD, SpotBugs
        Linter-->>Agent: Security/quality findings
    end
    
    Agent->>Claude: Send diff + context + rules
    Claude->>Agent: Request code analysis
    
    loop Review Analysis
        Agent->>Tools: read_file, search_code
        Tools-->>Agent: Code context
        Agent->>Claude: File contents
        Claude->>Agent: Review findings
    end
    
    Claude-->>Agent: Final review comments
    Agent->>SCM: Post review comments
    Agent->>Memory: Store learned patterns
    
    opt Generate Diagrams
        Agent->>Claude: Generate sequence diagrams
        Claude-->>Agent: Mermaid diagrams
        Agent->>SCM: Post diagram comment
    end
    
    Agent->>Queue: Review complete
```

## Aikido Security Integration Flow

Enhanced vulnerability fix workflow using Aikido Security platform integration.

```mermaid
sequenceDiagram
    participant Client
    participant API
    participant JIRA
    participant Aikido
    participant Agent
    participant Claude
    participant Git
    participant SCM

    Client->>API: POST /aikido-fix (jiraKey)
    API->>JIRA: Extract description context
    JIRA-->>API: Issue details + Aikido URLs
    
    API->>Aikido: Search by JIRA key
    alt Direct Link Found
        Aikido-->>API: Issue group details
    else Search by Description URLs
        API->>Aikido: Query candidate group IDs
        Aikido-->>API: Issue group details
    end
    
    API->>Aikido: Get vulnerability details
    Aikido-->>API: Package, CVE, versions, changelog
    
    opt Container Image Mapping
        API->>Aikido: Find code repo for container
        Aikido-->>API: Repository URL
    end
    
    API->>Agent: Enqueue with enriched context
    
    Agent->>Git: Clone repository
    Agent->>Claude: Send vulnerability context
    Note over Claude: Package: log4j-core<br/>Current: 2.19.0<br/>Fixed: 2.23.1<br/>CVE: CVE-2024-XXXXX<br/>Severity: HIGH
    
    loop Fix Implementation
        Claude->>Agent: Analyze dependencies
        Agent->>Tools: read_file (pom.xml)
        Agent->>Tools: search_code (log4j usage)
        Agent->>Tools: write_file (update versions)
        Agent->>Tools: run_command (mvn compile)
    end
    
    Agent->>SCM: Create PR with detailed description
    
    opt Trigger Security Scan
        Agent->>Aikido: Trigger CI scan
        Aikido-->>Agent: Scan results
    end
```

## Webhook-Triggered Automation

Event-driven job execution based on repository events.

```mermaid
sequenceDiagram
    participant GitPlatform as Git Platform
    participant Webhook
    participant API
    participant Rules
    participant Queue
    participant Agent

    GitPlatform->>Webhook: Push to main branch
    Webhook->>API: Validate signature
    API->>Rules: Load automation hooks
    
    loop For Each Matching Hook
        Rules->>Rules: Evaluate trigger condition
        alt Condition Met
            Rules->>Queue: Enqueue hook job
            Note over Queue: Job type: GENERATE_DOCS<br/>Trigger: push to main<br/>Auto-commit: true
        end
    end
    
    Queue->>Agent: Process hook job
    Agent->>GitPlatform: Clone repository
    
    alt Generate Documentation
        Agent->>Claude: Analyze codebase
        Agent->>Tools: Generate docs/*.md files
        Agent->>Tools: publish_confluence
        Agent->>GitPlatform: Commit docs directly
    else Update README
        Agent->>Claude: Update project README
        Agent->>Tools: write_file (README.md)
        Agent->>GitPlatform: Commit changes
    end
```

## JIRA Synchronization Flow

Bulk processing of open JIRA issues with agent labels.

```mermaid
sequenceDiagram
    participant Client
    participant API
    participant JIRA
    participant Aikido
    participant Queue
    participant JobStore

    Client->>API: POST /sync-jira
    API->>JIRA: Search issues with agent label
    JIRA-->>API: Open issues list
    
    loop For Each Issue
        API->>JobStore: Check for active job
        alt No Active Job
            opt Try Aikido Enrichment
                API->>JIRA: Extract Aikido context
                API->>Aikido: Get vulnerability details
                Aikido-->>API: Package/CVE/versions
            end
            
            API->>Queue: Enqueue fix job
            Note over Queue: Branch: agent/PROJ-123-fix<br/>Prompt: From JIRA or Aikido
        else Active Job Exists
            Note over API: Skip issue
        end
    end
    
    API-->>Client: Summary report
    Note over Client: Found: 5 issues<br/>Queued: 2 jobs<br/>Skipped: 3 (active jobs)
```

## Documentation Generation Flow

Comprehensive project documentation creation with Confluence publishing.

```mermaid
sequenceDiagram
    participant Client
    participant API
    participant Queue
    participant Agent
    participant Claude
    participant Tools
    participant Git
    participant Confluence

    Client->>API: POST /generate-docs
    API->>Queue: Enqueue docs job
    Queue->>Agent: Process docs job
    
    Agent->>Git: Clone repository
    Agent->>Claude: Initialize with docs prompt
    
    loop Documentation Discovery
        Claude->>Agent: list_files (explore structure)
        Agent->>Tools: Directory listing
        Claude->>Agent: read_file (pom.xml, README)
        Agent->>Tools: File contents
        Claude->>Agent: search_code (find controllers)
        Agent->>Tools: Code search results
    end
    
    loop Generate Documentation
        Claude->>Agent: write_file (docs/README.md)
        Agent->>Tools: Create index page
        Claude->>Agent: write_file (docs/architecture.md)
        Agent->>Tools: Architecture with diagrams
        Claude->>Agent: write_file (docs/api.md)
        Agent->>Tools: API reference
        Claude->>Agent: write_file (docs/data-model.md)
        Agent->>Tools: Database schema
    end
    
    Agent->>Tools: publish_confluence (README)
    Tools->>Confluence: Create parent page
    
    loop Publish Sub-Pages
        Agent->>Tools: publish_confluence (architecture)
        Tools->>Confluence: Create child page
        Agent->>Tools: publish_confluence (api)
        Tools->>Confluence: Create child page
    end
    
    opt Create Pull Request
        Agent->>Git: Commit docs folder
        Agent->>Git: Push docs branch
        Agent->>SCM: Create PR
    end
```

## Code Graph Building Flow

AST analysis and dependency graph construction for semantic code understanding.

```mermaid
sequenceDiagram
    participant Scheduler
    participant CodeGraph
    participant Git
    participant JavaParser
    participant Database
    participant VoyageAI

    Scheduler->>CodeGraph: Build missing graphs (cron: every 6h)
    CodeGraph->>Database: Find repos without graphs
    
    loop For Each Repository
        CodeGraph->>Git: Clone repository
        CodeGraph->>JavaParser: Analyze source files
        
        loop For Each Java File
            JavaParser->>JavaParser: Parse AST
            JavaParser-->>CodeGraph: Classes, methods, fields
            CodeGraph->>Database: Store graph nodes
            
            JavaParser-->>CodeGraph: Calls, extends, implements
            CodeGraph->>Database: Store graph edges
        end
        
        opt Vector Indexing Enabled
            CodeGraph->>VoyageAI: Generate embeddings
            VoyageAI-->>CodeGraph: 1024-dim vectors
            CodeGraph->>Database: Store code embeddings
        end
        
        CodeGraph->>Git: Cleanup workspace
    end
```

## Error Handling and Recovery

Common error scenarios and recovery mechanisms.

```mermaid
sequenceDiagram
    participant Agent
    participant Tools
    participant Git
    participant Claude
    participant JobStore
    participant Notifications

    Agent->>Tools: run_command (mvn compile)
    Tools-->>Agent: Build failure
    
    alt Retry Logic
        Agent->>Claude: Send build error
        Claude->>Agent: Suggest fixes
        Agent->>Tools: Apply fixes
        Agent->>Tools: run_command (retry build)
        
        alt Build Success
            Tools-->>Agent: Success
        else Max Retries Exceeded
            Agent->>JobStore: Mark job failed
            Agent->>Notifications: Send error alert
        end
    end
    
    alt Workspace Cleanup
        Agent->>Git: Delete temp directory
    end
    
    alt Authentication Failure
        Agent->>JobStore: Mark job failed
        Agent->>Notifications: Auth error alert
        Note over Agent: Do not retry auth errors
    end
    
    alt Rate Limit Hit
        Agent->>Agent: Exponential backoff
        Agent->>Claude: Retry after delay
    end
```

## Key Flow Characteristics

### Asynchronous Processing
- All long-running operations use job queue
- Status polling for real-time updates
- Webhook callbacks for completion notifications

### Error Resilience  
- Automatic retries with exponential backoff
- Graceful degradation when external services unavailable
- Comprehensive error logging and alerting

### Multi-Tenant Security
- Repository-scoped permissions and data isolation
- Webhook signature verification
- API key authentication for sensitive operations

### Scalability Patterns
- Configurable concurrency limits
- Resource cleanup after job completion
- Database connection pooling and indexing

### Integration Points
- **Git Platforms**: Repository cloning, PR management, webhook delivery
- **JIRA**: Issue tracking, status transitions, progress comments  
- **AI Services**: Claude for reasoning, Voyage AI for embeddings
- **Security Tools**: Aikido for vulnerability context, static analyzers
- **Collaboration**: Confluence publishing, Teams notifications, n8n workflows