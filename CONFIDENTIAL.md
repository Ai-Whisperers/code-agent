# CONFIDENTIAL — Study Material

This repository contains source code originally developed by **Ecedo Software BV** (a subsidiary of **Eneve**, formed in June 2025 from the merger of Energy21, Ecedo, Jules, and Gridhub — backed by Vortex Capital Partners, Netherlands).

## Provenance

- **Upstream origin:** `github.com/Ecedo-Software-BV/code-agent` (and `code-agent-ui`)
- **Acquired via:** Engagement context — see `Ai-Whisperers/energy21` repo for the client engagement file
- **Acquisition date:** April 2026
- **Branches preserved as-is:** `upstream-main`, `upstream-develop` — DO NOT push commits to these. They exist as a pristine baseline for diffing against AIW customizations.

## Usage Rules

1. **Internal study only.** This code is not licensed to AI Whisperers for redistribution, resale, or production deployment under the AIW brand without explicit re-implementation.
2. **Do NOT make this repo public.** Always remains private inside the `Ai-Whisperers` org.
3. **Do NOT copy code verbatim into client deliverables.** Extract patterns and ideas, then re-implement in our own style and stack.
4. **Do NOT push to upstream branches.** The `upstream-main` and `upstream-develop` branches are immutable baselines.
5. **Treat as competitive intelligence.** Eneve is being analyzed as a competitor in the Solstein market research (`Ai-Whisperers/solstein`) — anything learned here should be considered while respecting professional ethics.

## What We're Doing With It

- **Study the architecture** of an enterprise-grade self-hosted coding agent (112K LOC Java + 57K LOC React).
- **Extract reusable patterns:** parallel read-only tool execution, context compaction, checkpoint/resume, self-review loop, tool authorization gates, secret scanning UI, clarification flow, editable prompt UI.
- **Build our own version** for the AIW stack on a separate branch (`aiw/main`), keeping `upstream-*` untouched.

## Authorized Access

- Ivan Weiss van der Pol (Founder)
- Kyrian Weiss van der Pol (Tech)
- Jonathan Verdún (Operations)

If anyone else needs access, ask Ivan first.
