#!/bin/bash
# Lobster wrapper for review-pr.sh — wraps the deterministic review pipeline
# in a Lobster `exec --shell --json` invocation so callers get a proper
# Lobster tool-mode envelope on stdout:
#
#   {
#     "protocolVersion": 1,
#     "ok": true,
#     "status": "ok",
#     "output": [ { workspace, language, archetype, diffStat, lintResult, ... } ],
#     "requiresApproval": null,
#     "requiresInput": null
#   }
#
# Usage:
#   lobster-review-pr.sh <repo-url> <branch> <base-branch> <token> [pr-number]
#
# Design notes:
# - `exec --shell "<cmdline>"` runs the review script via the system shell
#   so we can pass the full arg list as one string.
# - `--json` makes Lobster parse the script's stdout as a single JSON value
#   and wrap it in the `output` array of the envelope.
# - `--mode tool` forces the envelope output format suitable for parsing
#   from a foreign runtime (Java, in our case).
# - The review script writes progress lines to stderr so they don't
#   pollute the JSON stdout.
#
# This wrapper exists so Java's LobsterClient has a single command to
# invoke without nested shell quoting. When we want to add the `approve`
# primitive for the fix.issue workflow later, this is where we append
# `| approve --prompt "..."` before `--mode tool` returns.

set -euo pipefail

if [ $# -lt 4 ]; then
    echo "usage: $0 <repo-url> <branch> <base-branch> <token> [pr-number]" >&2
    exit 2
fi

REPO=$1
BRANCH=$2
BASE=$3
TOKEN=$4
PR=${5:-null}

# The review-pr.sh path is hardcoded because it's a sibling in the same
# /opt/aiw-lobster/workflows/ directory. Change both paths together if you
# relocate the scripts.
REVIEW_SCRIPT=/opt/aiw-lobster/workflows/review-pr.sh

cd /opt/aiw-lobster

exec node node_modules/.bin/lobster --mode tool \
    "exec --shell \"$REVIEW_SCRIPT $REPO $BRANCH $BASE $TOKEN $PR\" --json"
