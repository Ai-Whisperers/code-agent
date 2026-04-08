# Client Profile: Solstein

> AI-powered competitive intelligence platform for Private Equity / VC. Second pilot client for the AIW Code Agent. Heavier refactor candidate than Vete — currently in "CRITICAL state" per its own Hermes context file.

## Repository

| Field | Value |
|---|---|
| **GitHub** | `Ai-Whisperers/solstein` (private) |
| **Default branch** | `master` (⚠ not `main` — agent config must use this) |
| **Target branch for PRs** | `develop` (per `.hermes.md`) |
| **Size** | 18 MB |
| **LOC** | 110K (636 source files) |
| **Test files** | 333 (coverage ~28%) |
| **Language** | Python 3.10+ (target 3.12), src-layout |
| **Stack** | FastAPI · SQLAlchemy 2.0 · asyncpg + psycopg · pgvector · Alembic · LangGraph · Celery + Redis · Anthropic + OpenAI + Instructor · Langfuse · OpenTelemetry · Prometheus |
| **Auth** | Currently a demo shim (accepts any credentials) — P0 vulnerability on the roadmap |
| **Deploy** | Docker → k8s via Helm · Terraform for infra · mkdocs for docs |
| **Observability** | Langfuse (LLM) · OpenTelemetry · Prometheus |
| **Open issues** | 72 |

## Critical project state

From `.hermes.md` (the repo's own agent context):

```
Current State: CRITICAL
- 132 READY stories, 98 BLOCKED, 70 EPICs total
- Auth is a demo shim (accepts any credentials)
- 70% field loss in data pipeline
- Fake health checks (asyncio.sleep + return True)
- Classification thresholds conflict between 3 files
```

**Implication:** the agent should NOT mass-refactor. Work one story at a time. The project is in active triage; the team uses an **Autoresearch Protocol** (Karpathy pattern):

```
MEASURE → CHANGE → TEST → MEASURE → KEEP or DISCARD
```

1. Baseline metrics BEFORE any work
2. One small change at a time
3. Test immediately after each change
4. Revert if tests break — try a different approach
5. Commit ONLY with green tests
6. **Include metrics delta in every commit message**

The agent's commit message template for Solstein must include a `Metrics:` footer with before/after numbers for anything testable (coverage, lint count, test count, benchmark).

## Branch model

- `master` — default branch in GitHub, but…
- `develop` — **target branch for all agent PRs** (per `.hermes.md`)
- Feature branches: `feature/STORY-NNN-short-description`
- The agent must pull the story ID from the Linear/GitHub issue and use it in the branch name

## Canonical vs frozen code regions

From `.hermes.md` architecture rules:

```
Data Sources → Adapters → Research Pipeline → Scoring → Export
    (11)        (enrichment)   (pipeline.py)    (analytics/)  (exporters/)
                               CANONICAL
```

| Path | Status | Agent rule |
|---|---|---|
| `src/solstein/research/pipeline.py` | **CANONICAL** | All new pipeline work goes here |
| `src/solstein/research/graph/` | **FROZEN** | Security patches only — no refactors, no new features |
| `src/solstein/analytics/constants.py` | **SOURCE OF TRUTH** for classification thresholds | Never edit without cross-checking the 3 conflicting files noted in `.hermes.md` |

## Build, test, and lint commands

From `pyproject.toml` + `.hermes.md`:

```bash
# Install (the project uses uv)
uv sync --all-extras

# Test (the exact commands from .hermes.md)
PYTHONPATH=src python3 -m pytest tests/unit/ -x -q          # fast smoke
PYTHONPATH=src python3 -m pytest tests/unit/ -v --cov=solstein --cov-report=term-missing
PYTHONPATH=src python3 -m pytest tests/integration/ -v      # slower, may need DB
PYTHONPATH=src python3 -m pytest -m "unit and not slow"     # fastest subset

# Lint
PYTHONPATH=src python3 -m ruff check src/                   # read-only
PYTHONPATH=src python3 -m ruff check src/ --fix             # autofix
PYTHONPATH=src python3 -m ruff check src/ --statistics      # delta reporting

# Format
PYTHONPATH=src python3 -m black src/ tests/

# Type check (they use BOTH mypy and basedpyright)
PYTHONPATH=src python3 -m mypy src/solstein
basedpyright src/solstein

# Pre-commit (runs the above)
pre-commit run --all-files
```

### Pytest markers — agent should respect these

| Marker | Meaning |
|---|---|
| `unit` | Fast isolated tests |
| `integration` | Needs real DB/APIs |
| `data_quality` | Regression against curated datasets |
| `agents` | Agent orchestration behaviour |
| `slow` | Long-running |
| `e2e` | End-to-end |

**Default agent run**: `pytest -m "unit and not slow" --cov=solstein` (fast feedback).
**Pre-merge**: `pytest -m "not slow" --cov=solstein`.
**Nightly / release**: full suite.

### Ruff ignore list is load-bearing

The `pyproject.toml` has an extensive `ignore` list documented with STORY IDs and "pre-existing, gradual cleanup" annotations. The agent must treat these as **intentional tolerances**:

- Don't enable more rules.
- Don't fix old issues as a side effect of an unrelated fix.
- If ruff statistics show **new** warnings of an enabled class, fail the PR.

The correct pattern for a Solstein lint fix: run `ruff check src/ --statistics` before and after, and the **delta must be ≤ 0** for every rule code.

## Protected paths

```
src/solstein/research/graph/**      # FROZEN region
alembic/versions/*.py               # migration files are immutable once merged
.env                                # secrets
config/secrets/**                   # config secrets
test_integration.db                 # test fixture (⚠ check: should probably NOT be in repo)
test_perf.sqlite3                   # test fixture (⚠ same)
k8s/secrets/**                      # k8s secrets
terraform/*.tfstate*                # Terraform state — never commit
helm/values.*.yaml                  # environment-specific values
.github/workflows/**                # CI only humans touch
```

⚠ **Flag**: `test_integration.db` and `test_perf.sqlite3` are committed to the repo. The agent should open an issue about this (SQLite binary fixtures in git is an anti-pattern — should be generated by a fixture script). File this as a `chore` issue during the first run.

## Allowed shell commands

```
python3, python, pip, uv,
pytest, ruff, black, mypy, basedpyright, pre-commit,
git (diff|status|log|add|commit|push|pull|fetch|branch|stash|restore|reset|checkout),
ls, find, cat, grep,
alembic (upgrade|downgrade|current|history|revision),
docker, docker-compose
```

**Forbidden**: `alembic downgrade base`, `rm -rf`, `psql` direct, `terraform apply`, `helm upgrade`, any `kubectl` mutation.

## Caps for the agent

| Setting | Value | Rationale |
|---|---|---|
| `run-fix.max-files-changed` | **8** | Solstein is in careful-refactor mode — smaller, more reviewable PRs |
| `run-fix.max-lines-changed` | **400** | Same |
| `run-fix.max-loop-iterations` | **150** | |
| `run-fix.self-review.enabled` | `true` | |
| `run-fix.self-review.max-iterations` | **15** | |
| `run-fix.job-timeout-minutes` | **30** | Python tests are fast |
| `metrics.delta-required-in-commit-message` | `true` (custom setting) | Autoresearch Protocol |

## Reviewer preferences (seed the learning extractor)

- "Follow the Autoresearch Protocol: every PR commit message includes a Metrics: footer with before/after numbers."
- "One small change at a time. Don't bundle unrelated fixes."
- "Never touch `src/solstein/research/graph/**` except for security patches."
- "Never edit `analytics/constants.py` classification thresholds without cross-checking the 2 other places they're defined (see .hermes.md)."
- "Use Pydantic v2 models for all I/O. Never return dicts from FastAPI routes."
- "Use `async def` endpoints and `asyncpg`/`psycopg` — not blocking calls inside async context."
- "LangGraph nodes go in `research/pipeline.py` (canonical), never in `research/graph/` (frozen)."
- "Log with `loguru`, not `print` or `logging`."
- "Tests must carry a marker (`unit`, `integration`, `agents`, etc.). Untagged tests are rejected."
- "Use `TRY400` style (`logger.error` vs `logger.exception`) per the existing codebase — don't fight ruff on this."
- "Never use `raise Exception(...)` in non-test code — define a specific exception class in `solstein/exceptions.py`."
- "STORY-NNN references in commit messages link to Linear/GitHub issues."
- "Target branch is `develop`, NOT `master`."

## Issue assignment

- **Work queue**: `planning/QUEUE.md` — agent picks first READY story top-to-bottom.
- **Canonical backlog**: `backlog/EPICS/` — 70 EPICs with child stories.
- **Priority roadmap**: `NEXT_ACTIONS.md` (P0→P4).
- **Linear team**: `SOL` (once Linear integration ships).

Auto-fix triggers on:
- Linear/GitHub issues labeled `aiw-agent` AND `P0` or `P1`
- Dependency upgrades (Dependabot, Renovate) — auto-merge if tests pass
- Langfuse-flagged regressions (via webhook)

## Quality-report schedule

| Report | Frequency | Branches |
|---|---|---|
| Quality snapshot | Daily 06:00 UTC | `master`, `develop` |
| Coverage trend | Daily | `develop` |
| Ruff statistics delta | Per-PR | `develop` |
| Tech-debt heatmap | Weekly | `master` |
| Dependency upgrade plan | Weekly (Monday) | `develop` |

## Notifications

Default target: `telegram:@solstein-alerts`
P0 (auth, data loss, security): `telegram:ivan` + `telegram:@solstein-alerts`
LLM cost spikes (via Langfuse): `telegram:@solstein-alerts`

## Docs generation

Solstein uses **mkdocs** (`mkdocs.yml` + `mkdocs.strict.yml`). Generated docs go into `docs/` and are deployed by the `docs.yml` workflow. The agent's `generate-docs` tool should respect the strict config.

## First high-value tasks for the agent

Once the agent is running against Solstein, these are the low-risk, high-value tasks to start with:

1. **Remove committed SQLite fixtures** (`test_integration.db`, `test_perf.sqlite3`) and replace with a `conftest.py` fixture that creates them on demand. Issue type: `chore`.
2. **Reconcile the 3 conflicting classification-threshold files** noted in `.hermes.md`. Promote `analytics/constants.py` as canonical, have others import from it. Issue type: `refactor`.
3. **Add type stubs for untyped third-party deps** (`edgartools`, `yfinance` don't ship stubs). Issue type: `chore`.
4. **Fix the fake health checks** (`asyncio.sleep + return True`). Issue type: `fix`, priority P1.
5. **Add ruff `--statistics` delta check to CI** so no PR can add new warnings of enabled rule classes. Issue type: `feature`.

These do NOT touch the auth shim or the data pipeline — both of which need human architectural decisions before the agent is let loose.

## Known pain points the agent will hit

1. **Test DB fixtures as binary files** — any test run that mutates them makes the repo dirty. The agent must snapshot+restore or work on a copy.
2. **PYTHONPATH=src is required everywhere** — the agent's Python detector must set this env var.
3. **Alembic migrations are in `alembic/versions/`** not the default `migrations/`. Detection needs to look for `alembic.ini` + `[alembic] script_location`.
4. **uv is preferred over pip/poetry** — faster, but not every CI path uses it. Agent should detect `uv.lock` and prefer `uv sync`.
5. **Coverage is only ~28%** — do NOT gate the agent on coverage thresholds; it'll reject almost every PR. Use delta coverage instead ("new lines added must be covered").
6. **Pre-commit has `sgconfig.yml` (semgrep)** — agent should run this locally to avoid CI surprises.
