#!/usr/bin/env python3
"""
AIW Code Agent — GitHub App installation token refresher.

Mints a short-lived installation token using the GitHub App's private
key + app ID + installation ID, writes it to /opt/aiw-code-agent/.env
as GITHUB_TOKEN, and rolls the app service to pick it up.

Designed to be run by a systemd timer every 50 minutes. Installation
tokens are valid for 1 hour, so a 50-minute cadence gives a 10-minute
safety window.

Config (env or /etc/aiw-code-agent/github-app.conf):
    AIW_GITHUB_APP_ID         — numeric App ID from GitHub Developer Settings
    AIW_GITHUB_INSTALLATION_ID — Installation ID from the org settings → installations page
    AIW_GITHUB_APP_KEY        — path to the private key .pem (default /opt/aiw-code-agent/.github-app-key.pem)
    AIW_ENV_FILE              — agent env file (default /opt/aiw-code-agent/.env)
    AIW_SERVICE_NAME          — Swarm service to roll (default aiw-code-agent_app). Empty = skip rollout.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error

# -----------------------------------------------------------------------------
# Config
# -----------------------------------------------------------------------------

def load_config() -> dict[str, str]:
    """Read config from /etc/aiw-code-agent/github-app.conf (KEY=VALUE lines)
    and overlay environment variables on top."""
    cfg = {}
    conf_path = "/etc/aiw-code-agent/github-app.conf"
    if os.path.exists(conf_path):
        with open(conf_path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, _, v = line.partition("=")
                cfg[k.strip()] = v.strip().strip('"').strip("'")
    for k in ("AIW_GITHUB_APP_ID", "AIW_GITHUB_INSTALLATION_ID",
              "AIW_GITHUB_APP_KEY", "AIW_ENV_FILE", "AIW_SERVICE_NAME"):
        if os.environ.get(k):
            cfg[k] = os.environ[k]
    cfg.setdefault("AIW_GITHUB_APP_KEY", "/opt/aiw-code-agent/.github-app-key.pem")
    cfg.setdefault("AIW_ENV_FILE", "/opt/aiw-code-agent/.env")
    cfg.setdefault("AIW_SERVICE_NAME", "aiw-code-agent_app")
    return cfg


# -----------------------------------------------------------------------------
# JWT minting (RS256) using only stdlib + openssl CLI
# -----------------------------------------------------------------------------

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def sign_rs256(signing_input: bytes, key_path: str) -> bytes:
    """Use openssl CLI to sign with RS256. Avoids requiring PyJWT/cryptography."""
    proc = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key_path],
        input=signing_input, capture_output=True, check=True,
    )
    return proc.stdout


def mint_jwt(app_id: str, key_path: str) -> str:
    """Mint a GitHub App JWT. iat=now-60 (clock skew), exp=now+540 (9 min)."""
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    payload = {
        "iat": now - 60,
        "exp": now + 540,
        "iss": app_id,
    }
    header_b64 = b64url(json.dumps(header, separators=(",", ":")).encode())
    payload_b64 = b64url(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{header_b64}.{payload_b64}".encode()
    sig = sign_rs256(signing_input, key_path)
    return f"{header_b64}.{payload_b64}.{b64url(sig)}"


# -----------------------------------------------------------------------------
# GitHub API calls
# -----------------------------------------------------------------------------

def get_installation_token(app_jwt: str, installation_id: str) -> dict:
    """POST /app/installations/{id}/access_tokens → returns {token, expires_at, ...}"""
    url = f"https://api.github.com/app/installations/{installation_id}/access_tokens"
    req = urllib.request.Request(url, method="POST")
    req.add_header("Authorization", f"Bearer {app_jwt}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "aiw-code-agent/1.0")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub API {e.code}: {body}") from None


# -----------------------------------------------------------------------------
# .env manipulation
# -----------------------------------------------------------------------------

def update_env_file(env_file: str, key: str, value: str) -> None:
    """Atomically update a KEY=VALUE line in a .env file."""
    lines = []
    found = False
    if os.path.exists(env_file):
        with open(env_file) as f:
            for line in f:
                if line.startswith(f"{key}="):
                    lines.append(f"{key}={value}\n")
                    found = True
                else:
                    lines.append(line)
    if not found:
        if lines and not lines[-1].endswith("\n"):
            lines[-1] += "\n"
        lines.append(f"{key}={value}\n")
    tmp = env_file + ".tmp"
    with open(tmp, "w") as f:
        f.writelines(lines)
    os.chmod(tmp, 0o600)
    os.replace(tmp, env_file)


# -----------------------------------------------------------------------------
# Service roll
# -----------------------------------------------------------------------------

def roll_service(service_name: str) -> None:
    """Roll the Swarm service via `docker stack deploy`.

    IMPORTANT: we deliberately use `docker stack deploy` here, NOT
    `docker service update --force`. Rationale: Swarm's env_file: directive
    is evaluated CLIENT-SIDE at `docker stack deploy` time — the file contents
    are inlined into the service spec. `docker service update` does NOT
    re-read the file; it just restarts the container with the existing spec.

    If this script used `service update`, any changes to /opt/aiw-code-agent/.env
    (model selection, webhook secrets, feature flags) would be silently
    invisible to the running container. Only the GITHUB_TOKEN line, which THIS
    script writes just before calling roll, would ever propagate.

    `docker stack deploy` re-reads the stack file + the env_file every time,
    so ALL .env changes (plus the fresh token) land on the next boot.
    """
    if not service_name:
        return
    print(f"[{time.strftime('%Y-%m-%dT%H:%M:%S')}] redeploying stack to pick up .env")
    # The stack name and compose file path are conventions — if they change,
    # update these two constants.
    stack_name = "aiw-code-agent"
    compose_file = "/opt/aiw-code-agent/docker-stack.aiw.yml"
    subprocess.run(
        ["docker", "stack", "deploy",
         "-c", compose_file,
         "--with-registry-auth",
         stack_name],
        check=True, capture_output=True,
    )


# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

def main() -> int:
    cfg = load_config()
    required = ("AIW_GITHUB_APP_ID", "AIW_GITHUB_INSTALLATION_ID")
    missing = [k for k in required if not cfg.get(k)]
    if missing:
        print(f"ERROR: missing config: {', '.join(missing)}", file=sys.stderr)
        return 2
    if not os.path.exists(cfg["AIW_GITHUB_APP_KEY"]):
        print(f"ERROR: private key not found at {cfg['AIW_GITHUB_APP_KEY']}", file=sys.stderr)
        return 2

    ts = time.strftime("%Y-%m-%dT%H:%M:%S")
    print(f"[{ts}] minting JWT for app {cfg['AIW_GITHUB_APP_ID']}")
    app_jwt = mint_jwt(cfg["AIW_GITHUB_APP_ID"], cfg["AIW_GITHUB_APP_KEY"])

    print(f"[{ts}] requesting installation token for {cfg['AIW_GITHUB_INSTALLATION_ID']}")
    result = get_installation_token(app_jwt, cfg["AIW_GITHUB_INSTALLATION_ID"])
    token = result["token"]
    expires_at = result.get("expires_at", "unknown")
    print(f"[{ts}] got installation token, expires_at={expires_at}, "
          f"permissions={list(result.get('permissions', {}).keys())}, "
          f"repo_selection={result.get('repository_selection', '?')}")

    update_env_file(cfg["AIW_ENV_FILE"], "GITHUB_TOKEN", token)
    print(f"[{ts}] wrote GITHUB_TOKEN to {cfg['AIW_ENV_FILE']}")

    roll_service(cfg["AIW_SERVICE_NAME"])
    print(f"[{ts}] done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
