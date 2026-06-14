# Phase-0 Spike Findings — netismaker-interview-service

This file mirrors `docs/superpowers/plans/00-spikes-findings.md` and records the result of
re-running `scripts/spike.ts` against the **real** `@anthropic-ai/claude-agent-sdk` using
**subscription auth via the local `claude` CLI** (no `ANTHROPIC_API_KEY`).

The corrections from the original spikes are already baked into the production modules
(Tasks 5, 10, 11, 12). This harness exists so the SDK option names and the cwd-pinned resume
behavior can be re-verified against a new SDK version. Each FAIL must name the production module
to adjust:

- option names → `src/sdk/sessionOptions.ts`
- binary resolution → `src/sdk/claudeCli.ts`
- splice / handoff path → `src/runner/skillDispatch.ts`
- turn model → `src/runner/interviewRunner.ts`

## How to run (operator-only, manual)

```bash
cd netismaker-interview-service
mkdir -p /tmp/spike-repo && git -C /tmp/spike-repo init -q
SUPERPOWERS_PLUGIN_PATH=/Users/micthebick/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0 \
  node --import tsx scripts/spike.ts /tmp/spike-repo
```

Expected: a non-null `init session_id`, a non-zero `shadow cost`, and `resume reached plan?: true`.
The resume call MUST use the IDENTICAL `workDir` (cwd) as the first call — the on-disk session
store is cwd-hashed (spike 02). If `resume` errors with `No conversation found with session ID`,
the cwd differed; fix the caller, not `buildOptions`.

> NOTE: This spike invokes the real Claude subscription and is therefore an operator-only,
> manual step. It is intentionally NOT run by CI or during automated implementation.

---

## Spike 00b — Subscription auth via `pathToClaudeCodeExecutable` (`apiKeySource:"none"`)

- **Status:** PENDING MANUAL RUN — see plan Task 17 Step 2.
- **What to verify:** the SDK starts with `pathToClaudeCodeExecutable` pointing at the resolved
  `claude` binary, `ANTHROPIC_API_KEY` is unset, and `init.apiKeySource` is `"none"`. The
  `result.usage.total_cost_usd` is a SHADOW value (what PAYG would have cost), not real billing.
- **Module on FAIL:** `src/sdk/sessionOptions.ts` (option names), `src/sdk/claudeCli.ts` (binary).

## Spike 02 — cwd-pinned cross-process `options.resume`

- **Status:** PENDING MANUAL RUN — see plan Task 17 Step 2.
- **What to verify:** the `session_id` captured from the first query's `system/init` can be resumed
  by a SEPARATE `query()` call as long as `options.cwd` is the IDENTICAL `workDir`. The on-disk
  session store lives at `~/.claude/projects/<cwd-hash>/` (single host / shared FS only).
- **Module on FAIL:** `src/runner/interviewRunner.ts` (turn model / cwd reuse), `src/runner/repoPrepare.ts`.

## Spike 04 — superpowers plugin load + `Skill` tool dispatch + brainstorming→writing-plans handoff

- **Status:** PENDING MANUAL RUN — see plan Task 17 Step 2.
- **What to verify:** `init.plugins` shows `superpowers@inline`; both `superpowers:brainstorming` and
  `superpowers:writing-plans` appear in `init.skills`; the `Skill` tool is in `init.tools`; invoking
  `{skill:"superpowers:brainstorming"}` runs the skill (`is_error:false`); and the resume turn reaches
  the `# <Feature> Implementation Plan` header. If the `Skill` tool does NOT auto-fire writing-plans
  on the announced handoff, the `buildWritingPlansSplice` fallback splices the SKILL.md.
- **Module on FAIL:** `src/sdk/sessionOptions.ts` (plugins/allowedTools), `src/runner/skillDispatch.ts`
  (handoff regex / splice path).

## Spike 01 — long-lived idle session

- **Status:** N/A by design. The runner runs ONE SDK turn per claim and returns (resume-per-answer),
  so there is no long-lived idle session.

## Spike 03 — concurrency

- **Status:** Deferred to load test. Concurrency = (interview workers) × (active-turn SDK
  subprocesses), naturally serialized by the shared subscription's rate limit — a capacity/quota
  tuning concern, not a blocker.
