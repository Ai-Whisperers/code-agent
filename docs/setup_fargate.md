# AWS ECS Fargate Setup

This guide covers deploying the Code Agent on AWS ECS Fargate. It focuses on the container configuration and the environment variables required to bootstrap the application.

> **Settings vs. Environment Variables**
> Most configuration (AI models, JIRA transitions, linter toggles, etc.) is stored in the `agent_settings` database table and managed through the Settings UI. Only the small set of values that must be present *before* the application can connect to the database or decrypt secrets need to be supplied as environment variables or Secrets Manager references in the task definition.

---

## Architecture Overview

```
Internet
  │
  ├─▶ CloudFront Distribution (HTTPS)
  │     └─ Origin: S3 bucket (code-agent-ui static files, OAC)
  │
  └─▶ Application Load Balancer (HTTPS :443)
        │  ↳ target group → port 8080
        ▼
      ECS Service (Fargate)
        └─ Task: code-agent
             ├─ Container: code-agent          (this image, port 8080)
             │  (speech-to-text via Amazon Transcribe Streaming — no sidecar)
             ├─ Mount: EFS volume              (/workspace)
             └─ Sidecar optional: log router   (FireLens / awslogs)

Supporting services (same VPC, private subnets):
  ├─ RDS PostgreSQL 15+ with pgvector   (port 5432)
  ├─ EFS File System                    (persistent workspace storage)
  ├─ S3 Bucket (code-agent-ui)          (static files, CloudFront origin)
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

## CloudFront + S3 for Static UI Files

The frontend (`code-agent-ui`) is a static React build served via CloudFront from a private S3 bucket. CloudFront is the only authorized reader via an **Origin Access Control (OAC)** policy.

### 1. Create the S3 bucket

```bash
aws s3api create-bucket \
  --bucket code-agent-ui \
  --region eu-central-1 \
  --create-bucket-configuration LocationConstraint=eu-central-1

aws s3api put-public-access-block \
  --bucket code-agent-ui \
  --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```

### 2. Create an Origin Access Control

```bash
aws cloudfront create-origin-access-control \
  --origin-access-control-config \
    Name=code-agent-ui-oac,OriginAccessControlOriginType=s3,SigningBehavior=always,SigningProtocol=sigv4
```

Note the returned `Id` — you will use it when creating the distribution.

### 3. Attach the bucket policy

Replace `<DISTRIBUTION_ARN>` with the ARN of your CloudFront distribution after creation:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowCloudFrontOAC",
      "Effect": "Allow",
      "Principal": {
        "Service": "cloudfront.amazonaws.com"
      },
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::code-agent-ui/*",
      "Condition": {
        "StringEquals": {
          "AWS:SourceArn": "<DISTRIBUTION_ARN>"
        }
      }
    }
  ]
}
```

### 4. Create the CloudFront distribution

Key settings:

| Setting | Value |
|---------|-------|
| Origin domain | `code-agent-ui.s3.eu-central-1.amazonaws.com` |
| Origin access | OAC (from step 2) |
| Viewer protocol policy | Redirect HTTP to HTTPS |
| Default root object | `index.html` |
| Custom error response | 403 → `/index.html` (status 200) for SPA routing |
| Price class | `PriceClass_100` (EU + NA) or adjust to your audience |
| ACM certificate | Your domain certificate (us-east-1 region, required for CloudFront) |

**Cache behaviors:**

| Path pattern | Cache TTL | Notes |
|---|---|---|
| `/assets/*` | 1 year (31536000 s) | Vite hashed filenames — safe to cache forever |
| `*` (default) | 0 / no-cache | `index.html` and other entry points must always be fresh |

### 5. Attach the security headers CloudFront Function

The function at `infra/cloudfront-security-headers.js` applies security response headers (CSP, HSTS, X-Frame-Options, etc.) on the viewer-response event.

```bash
aws cloudfront create-function \
  --name code-agent-ui-security-headers \
  --function-config Comment="Security headers",Runtime=cloudfront-js-2.0 \
  --function-code fileb://infra/cloudfront-security-headers.js

aws cloudfront publish-function --name code-agent-ui-security-headers --if-match <ETAG>
```

Associate it with the **default cache behavior** on the **Viewer response** event via the Console or in your distribution config JSON.

### 6. Deploy with cache-control headers

Use a two-pass sync that sets the correct `Cache-Control` headers per file type. This eliminates the need for CloudFront invalidations entirely:

```bash
# Pass 1 — hashed assets: cache forever (filenames change on every build)
aws s3 sync dist/assets/ s3://code-agent-ui/assets/ \
  --delete \
  --cache-control "public,max-age=31536000,immutable"

# Pass 2 — entry points (index.html, etc.): never cache
aws s3 sync dist/ s3://code-agent-ui/ \
  --delete \
  --exclude "assets/*" \
  --cache-control "no-cache,no-store,must-revalidate"
```

- **`/assets/*`** — Vite embeds a content hash in every filename (e.g. `index-Dh3kL9aB.js`), so a cached copy can never be stale. CloudFront serves them at full edge speed indefinitely.
- **`index.html` and other root files** — `no-cache` tells CloudFront (and browsers) to always revalidate with the origin before serving, so users always receive the latest entry point without any manual invalidation step.

> `--delete` removes files from S3 that no longer exist in `dist/`. Run pass 1 before pass 2 so the `--exclude` pattern in pass 2 does not accidentally delete newly uploaded assets.

---

## EFS Volume for External Storage

Fargate tasks are ephemeral — any files written inside the container are lost when the task stops. For the code-agent, which clones repositories and runs builds under `/workspace`, an EFS (Elastic File System) mount provides durable, shared storage across task restarts and multiple running tasks.

### 1. Create the EFS file system

```bash
aws efs create-file-system \
  --region eu-central-1 \
  --performance-mode generalPurpose \
  --throughput-mode elastic \
  --encrypted \
  --tags Key=Name,Value=code-agent-workspace
```

Note the returned `FileSystemId` (e.g. `fs-0abc1234`).

### 2. Create an EFS access point

The access point enforces ownership by `appuser` (UID/GID 1001) so the container does not need elevated privileges:

```bash
aws efs create-access-point \
  --file-system-id fs-0abc1234 \
  --posix-user Uid=1001,Gid=1001 \
  --root-directory "Path=/workspace,CreationInfo={OwnerUid=1001,OwnerGid=1001,Permissions=750}"
```

Note the returned `AccessPointId` (e.g. `fsap-0def5678`).

### 3. Create mount targets

Create one mount target per private subnet where Fargate tasks run:

```bash
aws efs create-mount-target \
  --file-system-id fs-0abc1234 \
  --subnet-id subnet-xxxxxxxx \
  --security-groups sg-efs-nfs
```

Repeat for each private subnet (typically two AZs).

### 4. Security group for EFS

Create a dedicated security group `sg-efs-nfs` and allow inbound NFS only from the ECS task security group:

| Type | Protocol | Port | Source |
|------|----------|------|--------|
| NFS | TCP | 2049 | ECS task security group |

### 5. Add the EFS volume to the task definition

Add the `volumes` block at the task-definition level and a `mountPoints` entry inside the container definition:

```json
"volumes": [
  {
    "name": "workspace",
    "efsVolumeConfiguration": {
      "fileSystemId": "fs-0abc1234",
      "transitEncryption": "ENABLED",
      "authorizationConfig": {
        "accessPointId": "fsap-0def5678",
        "iam": "ENABLED"
      }
    }
  }
]
```

Inside `containerDefinitions`:

```json
"mountPoints": [
  {
    "sourceVolume": "workspace",
    "containerPath": "/workspace",
    "readOnly": false
  }
]
```

### 6. IAM permissions for EFS

Add the following statement to the **task role** (`code-agent-task-role`) to allow the container to mount and write via the access point:

```json
{
  "Effect": "Allow",
  "Action": [
    "elasticfilesystem:ClientMount",
    "elasticfilesystem:ClientWrite",
    "elasticfilesystem:DescribeMountTargets"
  ],
  "Resource": "arn:aws:elasticfilesystem:eu-central-1:123456789:file-system/fs-0abc1234",
  "Condition": {
    "StringEquals": {
      "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:eu-central-1:123456789:access-point/fsap-0def5678"
    }
  }
}
```

> `transitEncryption: ENABLED` combined with `iam: ENABLED` ensures traffic between the Fargate task and EFS is both encrypted and authenticated.

---

## Speech Dictation — Amazon Transcribe Streaming

The chat input supports voice dictation via **Amazon Transcribe Streaming** (AWS SDK v2). No sidecar container, no Docker image to harden, and no supply-chain CVEs to manage — transcription runs entirely inside the JVM using the existing IAM task role.

### How it works

1. The browser records audio with `MediaRecorder` and uses Voice Activity Detection (VAD) to split speech into chunks on silence.
2. Each chunk is POSTed to `POST /api/speech/transcribe` on the main container.
3. The main container streams the audio bytes to Amazon Transcribe Streaming via `TranscribeStreamingAsyncClient`, collects the final transcript, and returns it to the browser as `{ "transcript": "…" }`.
4. The AWS region is read at request time from the `transcribe.region` setting (DB → `application.properties` fallback), so it can be changed in **Settings → Integrations → Speech Dictation** without a redeploy.

### IAM permissions

The ECS task role needs one additional permission:

```json
{
  "Effect": "Allow",
  "Action": "transcribe:StartStreamTranscription",
  "Resource": "*"
}
```

Add this statement to the task role's inline or managed policy alongside the existing Bedrock and S3 permissions.

### Configuration

| Property | Environment variable | Default | Description |
|----------|---------------------|---------|-------------|
| `transcribe.region` | `TRANSCRIBE_REGION` | `eu-west-1` | AWS region for Transcribe Streaming. Must be a region where the service is available. |
| `transcribe.sample-rate` | `TRANSCRIBE_SAMPLE_RATE` | `16000` | PCM sample rate in Hz. OGG/Opus chunks from `MediaRecorder` are passed through as-is; this value is still required by the Transcribe API. |

Both settings can also be overridden at runtime via **Settings → Integrations → Speech Dictation (Amazon Transcribe)** in the UI.

### Supported audio formats

| Browser output | Transcribe encoding used |
|----------------|--------------------------|
| `audio/webm;codecs=opus` (Chrome, Edge) | `OGG_OPUS` |
| `audio/ogg;codecs=opus` (Firefox) | `OGG_OPUS` |
| `audio/wav` / `audio/wave` | `PCM` |

### Supported regions

Amazon Transcribe Streaming is available in: `us-east-1`, `us-west-2`, `eu-west-1`, `eu-central-1`, `ap-southeast-2`, `ap-northeast-1`, and others. See the [AWS regional services list](https://aws.amazon.com/about-aws/global-infrastructure/regional-product-services/) for the current full list.

### No task size change required

Removing the Whisper sidecar saves approximately **600 MB RAM** per task. The Transcribe Streaming client adds negligible memory overhead (it uses the existing Netty NIO HTTP/2 client already present in the AWS SDK). You can reduce the task memory allocation if it was previously sized for the sidecar.

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
9. Verify voice dictation works in the chat input. Confirm the Transcribe region in **Settings → Integrations → Speech Dictation (Amazon Transcribe)** matches the region where your task role has `transcribe:StartStreamTranscription` permission.
