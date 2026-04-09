#!/bin/bash
# AIW code-agent review workflow — deterministic replacement for the
# ClaudeToolUseLoop review iteration path.
#
# Usage:
#   review-pr.sh <repo-url> <branch> <base-branch> <github-token> [pr-number]
#
# Emits a single JSON document on stdout (for Lobster's exec --json mode).
# Progress messages go to stderr so stdout stays pure JSON.
#
# No jq dependency — uses python3 (already in the agent container) for JSON
# emission. Bash-portable, no associative arrays, no new process spawns per
# field.

set -u -o pipefail

REPO_URL=$1
BRANCH=$2
BASE=$3
TOKEN=$4
PR_NUMBER=${5:-null}

WORKSPACE=/tmp/aiw-review-$$-$RANDOM
START_NS=$(date +%s%N)

step() { echo "[$1] $2" >&2; }

step clone "cloning $REPO_URL branch=$BRANCH into $WORKSPACE"
# Inject bearer token into clone URL
AUTH_URL=$(echo "$REPO_URL" | sed "s|https://|https://x-access-token:$TOKEN@|")
if ! git clone --depth=20 --branch "$BRANCH" "$AUTH_URL" "$WORKSPACE" 2>&2; then
    python3 -c "import json; print(json.dumps({'error': 'clone_failed', 'repo': '$REPO_URL', 'branch': '$BRANCH'}))"
    exit 1
fi
CLONE_MS=$(( ($(date +%s%N) - START_NS) / 1000000 ))

cd "$WORKSPACE"

step diff "computing diff vs origin/$BASE"
T=$(date +%s%N)
git fetch origin "$BASE" --depth=20 2>&2 || true
DIFF_STAT=$(git diff "origin/$BASE...HEAD" --stat 2>/dev/null | head -50 || true)
DIFF_FULL=$(git diff "origin/$BASE...HEAD" 2>/dev/null || true)
DIFF_HEAD=$(echo "$DIFF_FULL" | head -c 8000)
DIFF_LINES=$(printf '%s' "$DIFF_FULL" | wc -l)
# File list: one per line, handled by python3 below
FILES_CHANGED_RAW=$(git diff "origin/$BASE...HEAD" --name-only 2>/dev/null | head -20 || true)
DIFF_MS=$(( ($(date +%s%N) - T) / 1000000 ))

step detect "detecting language + archetype"
T=$(date +%s%N)
LANGUAGE=unknown
ARCHETYPE=unknown
PROJECT_ROOT=.
# Check root and common monorepo subdirs (matches PR #16 Java detector)
for dir in . web app frontend packages/web apps/web; do
    if [ -f "$dir/package.json" ]; then
        LANGUAGE=nodejs
        PROJECT_ROOT=$dir
        if grep -q '"next"' "$dir/package.json" 2>/dev/null; then
            ARCHETYPE=nextjs
        elif grep -q '"@angular/core"' "$dir/package.json" 2>/dev/null; then
            ARCHETYPE=angular
        elif grep -q '"react"' "$dir/package.json" 2>/dev/null; then
            ARCHETYPE=react
        else
            ARCHETYPE=nodejs-generic
        fi
        break
    fi
done
if [ "$LANGUAGE" = "unknown" ] && [ -f "pyproject.toml" ]; then
    LANGUAGE=python
    if grep -q fastapi pyproject.toml; then ARCHETYPE=python-fastapi
    elif grep -q -i django pyproject.toml; then ARCHETYPE=python-django
    elif grep -q -i flask pyproject.toml; then ARCHETYPE=python-flask
    else ARCHETYPE=python; fi
fi
if [ "$LANGUAGE" = "unknown" ] && [ -f "pom.xml" ]; then
    LANGUAGE=java
    ARCHETYPE=maven
fi
DETECT_MS=$(( ($(date +%s%N) - T) / 1000000 ))

step lint "running lint if available"
T=$(date +%s%N)
LINT_RAN=false
LINT_PASSED=false
LINT_OUTPUT=""
if [ "$LANGUAGE" = "nodejs" ] && [ -f "$PROJECT_ROOT/package.json" ]; then
    # Check if 'lint' script exists (no jq — grep the JSON)
    if grep -q '"lint"' "$PROJECT_ROOT/package.json" 2>/dev/null; then
        LINT_RAN=true
        if [ ! -d "$PROJECT_ROOT/node_modules" ]; then
            step lint "installing npm deps (timeout 120s)"
            (cd "$PROJECT_ROOT" && timeout 120 npm ci --prefer-offline --no-audit --silent 2>&1) >/dev/null 2>&1 || true
        fi
        LINT_OUTPUT=$( (cd "$PROJECT_ROOT" && timeout 60 npm run lint 2>&1) | tail -60 || true)
        if [ ${PIPESTATUS[0]:-1} -eq 0 ]; then
            LINT_PASSED=true
        fi
    fi
elif [ "$LANGUAGE" = "python" ]; then
    if command -v ruff >/dev/null 2>&1 && [ -f "pyproject.toml" ]; then
        LINT_RAN=true
        LINT_OUTPUT=$(timeout 30 ruff check . 2>&1 | tail -60 || true)
        if [ ${PIPESTATUS[0]:-1} -eq 0 ]; then
            LINT_PASSED=true
        fi
    fi
fi
LINT_MS=$(( ($(date +%s%N) - T) / 1000000 ))

TOTAL_MS=$(( ($(date +%s%N) - START_NS) / 1000000 ))

step done "workflow complete in ${TOTAL_MS}ms"

# Emit JSON via python3 (no jq dependency). Read variables from env so
# python3 doesn't have to deal with bash string escaping.
export AIW_WORKSPACE="$WORKSPACE"
export AIW_REPO_URL="$REPO_URL"
export AIW_BRANCH="$BRANCH"
export AIW_BASE="$BASE"
export AIW_PR_NUMBER="$PR_NUMBER"
export AIW_LANGUAGE="$LANGUAGE"
export AIW_ARCHETYPE="$ARCHETYPE"
export AIW_PROJECT_ROOT="$PROJECT_ROOT"
export AIW_DIFF_STAT="$DIFF_STAT"
export AIW_DIFF_HEAD="$DIFF_HEAD"
export AIW_DIFF_LINES="$DIFF_LINES"
export AIW_FILES_CHANGED_RAW="$FILES_CHANGED_RAW"
export AIW_LINT_RAN="$LINT_RAN"
export AIW_LINT_PASSED="$LINT_PASSED"
export AIW_LINT_OUTPUT="$LINT_OUTPUT"
export AIW_CLONE_MS="$CLONE_MS"
export AIW_DIFF_MS="$DIFF_MS"
export AIW_DETECT_MS="$DETECT_MS"
export AIW_LINT_MS="$LINT_MS"
export AIW_TOTAL_MS="$TOTAL_MS"

python3 - <<'PY'
import json, os

def _i(key, default=0):
    try:
        return int(os.environ.get(key, default) or default)
    except ValueError:
        return default

def _b(key):
    return os.environ.get(key, "false").strip().lower() == "true"

def _pr_number():
    raw = os.environ.get("AIW_PR_NUMBER", "null")
    if raw in ("null", "", "None"):
        return None
    try:
        return int(raw)
    except ValueError:
        return raw

files_raw = os.environ.get("AIW_FILES_CHANGED_RAW", "").strip()
files_changed = [f for f in files_raw.splitlines() if f.strip()]

result = {
    "workspace": os.environ.get("AIW_WORKSPACE", ""),
    "repoUrl": os.environ.get("AIW_REPO_URL", ""),
    "branch": os.environ.get("AIW_BRANCH", ""),
    "base": os.environ.get("AIW_BASE", ""),
    "prNumber": _pr_number(),
    "language": os.environ.get("AIW_LANGUAGE", "unknown"),
    "archetype": os.environ.get("AIW_ARCHETYPE", "unknown"),
    "projectRoot": os.environ.get("AIW_PROJECT_ROOT", "."),
    "diffStat": os.environ.get("AIW_DIFF_STAT", ""),
    "diffHead": os.environ.get("AIW_DIFF_HEAD", ""),
    "diffLines": _i("AIW_DIFF_LINES"),
    "filesChanged": files_changed,
    "lintResult": {
        "ran": _b("AIW_LINT_RAN"),
        "passed": _b("AIW_LINT_PASSED"),
        "output": os.environ.get("AIW_LINT_OUTPUT", ""),
    },
    "timingMs": {
        "clone": _i("AIW_CLONE_MS"),
        "diff": _i("AIW_DIFF_MS"),
        "detect": _i("AIW_DETECT_MS"),
        "lint": _i("AIW_LINT_MS"),
        "total": _i("AIW_TOTAL_MS"),
    },
}
print(json.dumps(result, indent=2))
PY
