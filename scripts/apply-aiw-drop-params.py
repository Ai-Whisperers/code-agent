#!/usr/bin/env python3
"""
AIW LiteLLM patch — add drop_params + additional_drop_params to every
model in LiteLLM's model_list.

Why: the Anthropic Java SDK v2.15.0 (used by the code-agent) always
attaches cache_control, web_search_options, anthropic_version, and
thinking fields to every request. Non-Anthropic providers (Groq,
Cerebras, SambaNova, Mistral) reject these fields with 400 errors.

LiteLLM supports stripping unsupported params at the gateway layer via
`drop_params: true` + a list of field names to drop. This script applies
that to every model entry idempotently.

Usage (on the VPS that hosts LiteLLM):
    python3 /opt/litellm/apply-aiw-drop-params.py
    docker restart litellm

Idempotent — safe to run repeatedly. If the fields are already present
with the same values, writing the file back is a no-op.

Backup of the pre-patch config:
    /opt/litellm/config.yaml.bak-aiw

If the upstream LiteLLM config is ever reinstalled (e.g. via an ansible
playbook or a package update), this script must be re-run. See
/opt/litellm/README-AIW.md for the full rationale.
"""

import yaml

CONFIG_PATH = "/opt/litellm/config.yaml"
DROPPED_FIELDS = [
    "cache_control",      # Anthropic prompt-cache blocks inside messages
    "web_search_options", # Anthropic hosted web-search tool config
    "anthropic_version",  # Top-level API version
    "thinking",           # Claude extended-thinking config
]

# AIW: DO NOT drop Anthropic-native fields when routing to Anthropic itself
# (directly or via OpenRouter). These providers NEED cache_control and thinking
# to deliver the features the Anthropic Java SDK expects — dropping them
# silently disables prompt caching and extended thinking on the one route that
# actually supports them. Identified by the underlying model prefix.
ANTHROPIC_NATIVE_PREFIXES = (
    "anthropic/",
    "openrouter/anthropic/",
    "bedrock/anthropic.",
)


def is_anthropic_native(backend: str) -> bool:
    return any(backend.startswith(p) for p in ANTHROPIC_NATIVE_PREFIXES)


def main() -> None:
    with open(CONFIG_PATH) as f:
        cfg = yaml.safe_load(f)

    stripped = 0
    preserved = 0
    for model in cfg["model_list"]:
        params = model.setdefault("litellm_params", {})
        backend = params.get("model", "")
        if is_anthropic_native(backend):
            # Clear any previously-applied drop_params so Anthropic sees
            # cache_control etc. natively.
            params.pop("drop_params", None)
            params.pop("additional_drop_params", None)
            preserved += 1
        else:
            params["drop_params"] = True
            params["additional_drop_params"] = DROPPED_FIELDS
            stripped += 1

    with open(CONFIG_PATH, "w") as f:
        yaml.dump(cfg, f, default_flow_style=False, sort_keys=False)

    print(f"applied drop_params to {stripped} non-Anthropic models")
    print(f"preserved Anthropic-native params on {preserved} models")
    print(f"total: {stripped + preserved} models in {CONFIG_PATH}")
    print("restart litellm to pick up: docker restart litellm")


if __name__ == "__main__":
    main()
