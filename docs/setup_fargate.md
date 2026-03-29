# AWS ECS Fargate Setup

This guide covers deploying the Code Agent on AWS ECS Fargate. It focuses on the container configuration and the environment variables required to bootstrap the application.

> **Settings vs. Environment Variables**
> Most configuration (AI models, JIRA transitions, linter toggles, etc.) is stored in the `agent_settings` database table and managed through the Settings UI. Only the small set of values that must be present *before* the application can connect to the database or decrypt secrets need to be supplied as environment variables or Secrets Manager references in the task definition.

---

## Architecture Overview

```
Internet
  │
  ▼
Application Load Balancer (HTTPS :443)
  │  ↳ target group → port 8080
  ▼
ECS Service (Fargate)
  └─ Task: code-agent
       ├─ Container: code-agent          (this image, port 8080)
       └─ Sidecar optional: log router   (FireLens / awslogs)

Supporting services (same VPC, private subnets):
  ├─ RDS PostgreSQL 15+ with pgvector   (port 5432)
  ├─ AWS Secrets Manager                (credentials at boot)
  └─ ECR                                (image registry)
```

---

## Container Requirements

The image is built from the provided `Dockerfile`. The runtime container includes:

| Component | Version | Purpose |
|-----------|---------|---------|
| Eclipse Temurin JDK | 21 (LTS) | Application runtime |
| Apache Maven | 3.9.14 | Building target repositories |
| Node.js | 20.x | ESLint, Mermaid CLI |
| Chromium (headless) | system | Mermaid diagram rendering to PNG |
| .NET SDK | 9.0 | `dotnet-format`, coverage reports |
| Git | system | Repository clone / push |

The application listens on **port 8080** and runs as non-root user `appuser` (UID 1001).

### Recommended Task Size

| Profile | vCPU | Memory | Notes |
|---------|------|--------|-------|
| Minimum | 1 | 3 GB | Light load, no concurrent builds |
| Standard | 2 | 6 GB | 2–3 concurrent fix jobs |
| Recommended | 4 | 12 GB | Maven + Node + .NET builds in parallel |

Set `RUN_FIX_MAX_CONCURRENT_JOBS` to match the number of vCPUs you allocate.

---

## Required Environment Variables

These variables **must** be present in the task definition (or injected via Secrets Manager) because they are needed before the application can read anything from the database.

### Bootstrap (task-definition level)

| Variable | Description | Example |
|----------|-------------|---------|
| `DATABASE_URL` | JDBC URL for the PostgreSQL instance | `jdbc:postgresql://code-agent.cluster-xxx.eu-central-1.rds.amazonaws.com:5432/code_agent` |
| `DATABASE_USER` | Database username | `code_agent` |
| `DATABASE_PASSWORD` | Database password | → Secrets Manager |
| `SETTINGS_ENCRYPTION_KEY` | 64-char hex key (AES-256-GCM) for encrypting secrets stored in `agent_settings`. Generate with `openssl rand -hex 32`. **Required at first boot; never change after data is written.** | → Secrets Manager |
| `API_KEY` | Shared bearer token protecting all REST endpoints. Leave blank only in isolated dev environments. | → Secrets Manager |

### Initial Seed (first boot only)

On a fresh database the application creates the schema via Flyway, but the settings table starts empty. To avoid a chicken-and-egg situation for the AI key (needed before you can open the UI), supply these at task-definition level too. Once you have saved them via the Settings UI you can remove them from the task definition and manage them through the database.

| Variable | Description |
|----------|-------------|
| `ANTHROPIC_API_KEY` | Anthropic Claude API key |
| `ANTHROPIC_MODEL` | Primary model, e.g. `claude-sonnet-4-20250514` |

---

## Secrets Manager Integration

Store all sensitive values as individual secrets and reference them in the task definition using the `secrets` field. This avoids plaintext values in CloudFormation / Terraform and keeps them out of ECS logs.

**Recommended secrets structure:**

```
/code-agent/prod/
├── database/password
├── settings-encryption-key
├── api-key
├── anthropic/api-key
├── bitbucket/app-password
├── jira/api-token
├── teams/webhook-url
└── webhook-secrets/bitbucket
```

**Task definition snippet (JSON):**

```json
"secrets": [
  {
    "name": "DATABASE_PASSWORD",
    "valueFrom": "arn:aws:secretsmanager:eu-central-1:123456789:secret:/code-agent/prod/database/password"
  },
  {
    "name": "SETTINGS_ENCRYPTION_KEY",
    "valueFrom": "arn:aws:secretsmanager:eu-central-1:123456789:secret:/code-agent/prod/settings-encryption-key"
  },
  {
    "name": "API_KEY",
    "valueFrom": "arn:aws:secretsmanager:eu-central-1:123456789:secret:/code-agent/prod/api-key"
  },
  {
    "name": "ANTHROPIC_API_KEY",
    "valueFrom": "arn:aws:secretsmanager:eu-central-1:123456789:secret:/code-agent/prod/anthropic/api-key"
  }
]
```

---

## Minimal Task Definition

Below is the minimal `containerDefinitions` entry for a working deployment. All other settings (JIRA, SCM, linter, SLA, Aikido, etc.) are configured through the Settings UI after first boot.

```json
{
  "family": "code-agent",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "4096",
  "memory": "12288",
  "executionRoleArn": "arn:aws:iam::123456789:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::123456789:role/code-agent-task-role",
  "containerDefinitions": [
    {
      "name": "code-agent",
      "image": "123456789.dkr.ecr.eu-central-1.amazonaws.com/code-agent:latest",
      "essential": true,
      "portMappings": [
        { "containerPort": 8080, "protocol": "tcp" }
      ],
      "environment": [
        { "name": "DATABASE_URL",  "value": "jdbc:postgresql://code-agent.cluster-xxx.eu-central-1.rds.amazonaws.com:5432/code_agent" },
        { "name": "DATABASE_USER", "value": "code_agent" },
        { "name": "ANTHROPIC_MODEL", "value": "claude-sonnet-4-20250514" }
      ],
      "secrets": [
        { "name": "DATABASE_PASSWORD",      "valueFrom": "arn:aws:secretsmanager:...:secret:/code-agent/prod/database/password" },
        { "name": "SETTINGS_ENCRYPTION_KEY","valueFrom": "arn:aws:secretsmanager:...:secret:/code-agent/prod/settings-encryption-key" },
        { "name": "API_KEY",                "valueFrom": "arn:aws:secretsmanager:...:secret:/code-agent/prod/api-key" },
        { "name": "ANTHROPIC_API_KEY",      "valueFrom": "arn:aws:secretsmanager:...:secret:/code-agent/prod/anthropic/api-key" }
      ],
      "healthCheck": {
        "command": ["CMD-SHELL", "curl -sf http://localhost:8080/q/health/live || exit 1"],
        "interval": 30,
        "timeout": 10,
        "retries": 3,
        "startPeriod": 60
      },
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/code-agent",
          "awslogs-region": "eu-central-1",
          "awslogs-stream-prefix": "code-agent"
        }
      },
      "ulimits": [
        { "name": "nofile", "softLimit": 65536, "hardLimit": 65536 }
      ]
    }
  ]
}
```

---

## IAM Roles

### Execution Role (`ecsTaskExecutionRole`)

Standard ECS execution role, plus Secrets Manager read access:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": ["secretsmanager:GetSecretValue"],
      "Resource": "arn:aws:secretsmanager:eu-central-1:123456789:secret:/code-agent/prod/*"
    }
  ]
}
```

### Task Role (`code-agent-task-role`)

The task role is used at runtime by the application. Grant only what you actually use:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::code-agent-attachments",
        "arn:aws:s3:::code-agent-attachments/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "cloudwatch:GetMetricData",
        "cloudwatch:ListMetrics",
        "ecs:ListTasks",
        "ecs:DescribeTasks",
        "logs:FilterLogEvents"
      ],
      "Resource": "*"
    }
  ]
}
```

> If you do not use S3 attachments or the AWS Tools integration, remove the corresponding statement blocks.

---

## RDS PostgreSQL Setup

The application requires PostgreSQL 14+ with the **pgvector** extension.

**Recommended RDS configuration:**

| Parameter | Value |
|-----------|-------|
| Engine | PostgreSQL 15.x |
| Instance class | `db.t4g.medium` (development) / `db.r8g.large` (production) |
| Storage | 20 GB gp3, auto-scaling enabled |
| Multi-AZ | Yes (production) |
| Extensions | `pgvector` (enable via parameter group: `shared_preload_libraries = pgvector`) |

**Initial database setup:**

```sql
CREATE DATABASE code_agent;
CREATE USER code_agent WITH PASSWORD 'changeme';
GRANT ALL PRIVILEGES ON DATABASE code_agent TO code_agent;
\c code_agent
CREATE EXTENSION IF NOT EXISTS vector;
```

Flyway runs migrations automatically on startup — no manual schema creation is needed beyond this.

---

## Networking

- Deploy the ECS service in **private subnets** with a NAT gateway for outbound internet access (required for Anthropic API, SCM platforms, JIRA, Aikido).
- The ALB lives in public subnets and terminates HTTPS (ACM certificate).
- The RDS security group should allow inbound port 5432 **only** from the ECS task security group.
- The ECS task security group should allow inbound 8080 from the ALB security group only.

---

## Health Checks

| Endpoint | Purpose |
|----------|---------|
| `GET /q/health/live` | Liveness — container is running |
| `GET /q/health/ready` | Readiness — database connected, migrations applied |
| `GET /health` | Custom — includes job queue depth and available slots |

Use `/q/health/ready` as the ALB target group health check path. The container takes ~30–45 seconds to start; configure the ALB with a `healthy threshold = 2` and `interval = 15s` to avoid premature deregistration.

---

## S3 Bucket for Attachments

If you use knowledge documents or diagram uploads, create an S3 bucket and configure it via the Settings UI (AWS / Attachments section) after first boot:

```bash
aws s3api create-bucket \
  --bucket code-agent-attachments \
  --region eu-central-1 \
  --create-bucket-configuration LocationConstraint=eu-central-1

aws s3api put-bucket-versioning \
  --bucket code-agent-attachments \
  --versioning-configuration Status=Enabled
```

Set `attachment.s3.bucket` and `attachment.s3.region` in the Settings UI, or pass them as environment variables in the task definition:

```json
{ "name": "ATTACHMENT_S3_BUCKET", "value": "code-agent-attachments" }
```

---

## Post-Deployment Checklist

Once the task is running and `/q/health/ready` returns 200:

1. Open the Settings UI at `https://your-alb-host/settings`
2. Configure the active Git platform and credentials (Source Control tab)
3. Set JIRA base URL and API token (Integrations tab)
4. Set Aikido client ID / secret and default Jira project (Integrations → Aikido Security)
5. Configure JIRA transition IDs for In Progress / In Review / Done (Integrations → Jira)
6. Set Teams webhook URL for notifications (Integrations → Notifications)
7. Set SOC2 production branch and SLA days (Compliance tab)
8. Register repository webhooks via the SCM platform pointing to `https://your-alb-host/api/webhooks/{platform}/pull-request`
