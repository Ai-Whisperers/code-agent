# Code Agent Runner Documentation

The Code Agent Runner is a self-hosted coding agent that automates issue fixing, dependency upgrades, and AI-powered code reviews. Built with Quarkus (Java 21), it integrates with multiple git platforms (Bitbucket, Azure DevOps, GitLab), uses Claude (Anthropic) in an agentic tool-use loop to make code changes, validates with Maven, creates pull requests, and keeps JIRA and Teams in sync.

This comprehensive system provides automated software development workflows, from fixing vulnerabilities reported by security tools like Aikido to conducting thorough PR reviews with contextual repository exploration.

## Documentation Index

### AIW adaptation docs (read these first if you're deploying)

| Document | Description |
|----------|-------------|
| [Runbook](RUNBOOK.md) | **START HERE** — step-by-step deploy from a blank VPS to a running agent. Every command copy-pastable. |
| [Operations](OPERATIONS.md) | Day-to-day commands: health checks, logs, rotating tokens, restoring backups, changing models, adding repos. |
| [Known Issues](KNOWN-ISSUES.md) | Every footgun we've hit, with root causes and fixes. Grep your error here before debugging. |
| [Next Steps](NEXT-STEPS.md) | Phased roadmap from "working agent" to "production-hardened multi-client deployment". |
| [Deployment Notes](DEPLOYMENT-NOTES.md) | Current state of the VPS deployment + the non-obvious bits of the Swarm setup. |
| [Customization Plan](AIW-CUSTOMIZATION-PLAN.md) | The original plan for forking upstream Eneve and adapting to AIW infrastructure. |
| [Study Notes](STUDY-NOTES.md) | Annotated observations about the upstream codebase. |
| [Client Profiles](clients/) | Per-client YAML profiles (Vete, Solstein) loaded by the bootstrap script. |

### Upstream Eneve reference docs

| Document | Description |
|----------|-------------|
| [Architecture Overview](architecture.md) | High-level system design, component interactions, and technology stack |
| [API Documentation](api.md) | Complete REST API reference with endpoints, request/response schemas |
| [Data Model](data-model.md) | Database schema with tables, relationships, and constraints |
| [Getting Started](getting-started.md) | Developer onboarding, setup, and environment configuration |
| [Key Business Flows](flows.md) | Important workflows with sequence diagrams |
| [Configuration Reference](configuration.md) | All configuration properties and environment variables |

## Quick Start

To get the Code Agent Runner up and running:

1. **Prerequisites**: Java 21, PostgreSQL 14+, Maven 3.8+
2. **Build**: `mvn -B package -DskipTests`
3. **Configure**: Set required environment variables (see [Configuration Reference](configuration.md))
4. **Run**: `mvn quarkus:dev` (development) or `java -jar target/quarkus-app/quarkus-run.jar` (production)

The server will start on `http://localhost:8080` with Swagger UI available at `http://localhost:8080/q/swagger-ui`.

## Key Features

- **Automated Issue Fixing**: Queue jobs from JIRA tickets with automatic prompt resolution
- **Security Integration**: Deep integration with Aikido Security for vulnerability remediation
- **AI Code Reviews**: Comprehensive PR analysis covering security, design, quality, and testing
- **Multi-Platform Support**: Works with Bitbucket Cloud, Azure DevOps, and GitLab
- **Code Intelligence**: Built-in code graph analysis and semantic search across repositories
- **Learning System**: Adapts to team preferences through review memory and feedback
- **Approval Workflows**: Human-in-the-loop approval via n8n integration

## Getting Help

- Review the [API Documentation](api.md) for endpoint specifications
- Check the [Configuration Reference](configuration.md) for environment variables
- Follow the [Getting Started](getting-started.md) guide for detailed setup instructions
- Examine the [Key Business Flows](flows.md) to understand system workflows