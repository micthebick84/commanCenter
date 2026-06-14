# SDK Interview Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:test-driven-development` — every task below is RED → GREEN → COMMIT. Write the failing test, run it, see the exact failure, write minimal code, see it pass, commit. Do not skip the RED step. Also load `superpowers:verification-before-completion` before claiming any task done.

**Goal:** Build `netismaker-interview-service` — a greenfield TypeScript/Node sidecar that runs superpowers `brainstorming → writing-plans` headlessly via the Claude Agent SDK, pulling interview jobs from the netisMaker Java API (`POST /worker/interviews/claim?workerId=...`), relaying one plain-text question per turn, resuming the SDK session per answer with all pending state read from the claim payload (`InterviewClaimResponse`, never held in memory), and harvesting the final design + plan back to Java.

**Architecture:** Stateless pull/claim worker. A `ClaimLoop` polls Java on an interval (`POST /worker/interviews/claim?workerId=...`, `X-Worker-API-Key` header). For each claimed session an `InterviewRunner` first ensures the repo is checked out at `claim.workDir` (clone-or-fetch), then either (a) starts a fresh `brainstorming` SDK session or (b) resumes via `options.resume = claudeSessionId` with the **identical** `options.cwd = claim.workDir`, injecting the last user answer. The runner captures `session_id` from the SDK `system`/`init` message, relays assistant question text via `POST /worker/interviews/{id}/question`, then exits — the session is parked in Java DB as `AWAITING_INPUT`. The next answer arrives as a *new claim* (resume-per-answer is the DEFAULT). On the `brainstorming → writing-plans` handoff a skill-dispatch shim verifies the transition fired and splices the next `SKILL.md` if it did not. On `writing-plans` completion the runner harvests design + plan markdown and POSTs `POST /worker/interviews/{id}/plan`. A SHADOW `total_cost_usd` is accumulated from `result.usage.total_cost_usd` (subscription/CLI auth — not real billing) and treated as a per-session **turn/quota guard**, not a dollar cap; heartbeats and failures are reported to Java.

**Tech Stack:** Node 20+ / TypeScript 5.6 (ESM, `"type":"module"` — matches `frontend/package.json`), `@anthropic-ai/claude-agent-sdk@0.2.117`, `vitest` for unit tests, `undici`-style native `fetch` (Node built-in) for the Java HTTP client, `eslint` + `prettier` (workspace convention: single quotes, semicolons, 2-space indent, trailing commas). Superpowers plugin loaded from `/Users/micthebick/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0`.

**Auth (Phase-0 spike 00b):** Subscription via the local `claude` CLI — `options.pathToClaudeCodeExecutable` points at the resolved binary (resolution order: `PATH` → `~/.local/bin` → `~/.claude/local` → `/opt/homebrew/bin` → `/usr/local/bin` → `/usr/bin`, matching netisMaker's `ClaudeExecAdapter`). `ANTHROPIC_API_KEY` is **not** required (`apiKeySource:"none"`). No per-token billing; `total_cost_usd` is a shadow value used only as a per-session quota guard.

---

## File Structure

```
netisMaker/netismaker-interview-service/
  package.json                          # deps (@anthropic-ai/claude-agent-sdk@0.2.117), scripts (build/dev/test/lint); type:module
  tsconfig.json                         # strict ESM, NodeNext resolution, outDir dist
  vitest.config.ts                      # node env, globals, coverage
  .eslintrc.cjs                         # workspace eslint+prettier conventions
  .env.example                          # API_BASE_URL, WORKER_API_KEY, WORKER_ID, quota guard, CLAUDE_CLI (no ANTHROPIC_API_KEY)
  src/
    config.ts                           # env parsing + typed Config (api url, worker key/id, quota cap, poll interval, superpowers path, claude cli path)
    types.ts                            # InterviewClaimResponse, WorkerQuestionRequest, WorkerPlanRequest DTOs (mirror Java records)
    api/javaClient.ts                   # JavaApiClient: claim(?workerId=)/question/plan/heartbeat/fail over fetch + X-Worker-API-Key
    sdk/claudeCli.ts                     # resolveClaudeCli(): PATH -> ~/.local/bin -> ~/.claude/local -> /opt/homebrew/bin -> /usr/local/bin -> /usr/bin
    sdk/sdkAdapter.ts                    # thin wrapper over @anthropic-ai/claude-agent-sdk query() (the seam mocked in tests)
    sdk/permissions.ts                  # buildCanUseTool(): confine Write to docs/superpowers/**, Bash whitelist, block repo mutation/push
    sdk/sessionOptions.ts               # buildOptions(): pathToClaudeCodeExecutable, plugins:[{type:'local'}], cwd=workDir, allowedTools(+Skill), resume, canUseTool
    runner/repoPrepare.ts               # ensureRepo(): clone --depth 1 --branch at workDir, or fetch+reset if present (before first turn AND resume)
    runner/interviewRunner.ts           # InterviewRunner.run(claim): repo prep, fresh-start vs resume, relay loop, quota guard, harvest dispatch
    runner/messageRelay.ts              # consume SDK stream: capture session_id from init, collect assistant text, detect result
    runner/skillDispatch.ts             # brainstorming->writing-plans handoff detection + SKILL.md splice fallback
    runner/planHarvest.ts               # parse final transcript/files into {designMarkdown, planMarkdown, planJson}
    runner/costGuard.ts                 # accumulate shadow total_cost_usd, throw QuotaGuardExceeded over per-session quota cap
    claimLoop.ts                        # poll claim endpoint on interval, hand each claim to InterviewRunner, heartbeat, fail-report
    index.ts                            # entrypoint: load config, start ClaimLoop
  test/
    fixtures/sdkMessages.ts             # canned SDK message streams (init, assistant question, result, handoff)
    fixtures/claims.ts                  # canned InterviewClaimResponse payloads (fresh + resume)
    javaClient.test.ts                  # HTTP client request shape (header, ?workerId= query param, body, paths)
    claudeCli.test.ts                   # claude CLI resolution order
    repoPrepare.test.ts                 # clone-or-fetch at workDir (fresh clone vs existing fetch+reset)
    messageRelay.test.ts                # session_id capture, assistant-text collection, result detection
    interviewRunner.test.ts            # relay loop, resume re-entry, repo-prep call, harvest dispatch (mocked SDK + mocked client)
    costGuard.test.ts                   # shadow cost accumulation + quota cap enforcement
    planHarvest.test.ts                 # harvest parser (design/plan/json extraction + malformed input)
    permissions.test.ts                 # canUseTool gating (Write path confinement, Bash whitelist)
    skillDispatch.test.ts               # handoff detection + splice fallback
```

---

### Task 1: Project scaffold (package.json, tsconfig, vitest, lint)

**Files:**
- Create `netisMaker/netismaker-interview-service/package.json`
- Create `netisMaker/netismaker-interview-service/tsconfig.json`
- Create `netisMaker/netismaker-interview-service/vitest.config.ts`
- Create `netisMaker/netismaker-interview-service/.eslintrc.cjs`
- Create `netisMaker/netismaker-interview-service/.env.example`

- [ ] **Step 1: Write package.json with SDK + vitest deps.**
  Create `netisMaker/netismaker-interview-service/package.json`:
  ```json
  {
    "name": "netismaker-interview-service",
    "version": "0.1.0",
    "private": true,
    "type": "module",
    "engines": { "node": ">=20" },
    "scripts": {
      "build": "tsc -p tsconfig.json",
      "dev": "node --import tsx --watch src/index.ts",
      "start": "node dist/index.js",
      "test": "vitest run",
      "test:watch": "vitest",
      "lint": "eslint \"src/**/*.ts\" \"test/**/*.ts\" --fix"
    },
    "dependencies": {
      "@anthropic-ai/claude-agent-sdk": "0.2.117"
    },
    "devDependencies": {
      "@types/node": "^22.0.0",
      "eslint": "^9.0.0",
      "prettier": "^3.5.2",
      "tsx": "^4.19.0",
      "typescript": "^5.6.0",
      "vitest": "^2.1.0"
    }
  }
  ```

- [ ] **Step 2: Write tsconfig.json (strict ESM, NodeNext).**
  Create `netisMaker/netismaker-interview-service/tsconfig.json`:
  ```json
  {
    "compilerOptions": {
      "target": "ES2022",
      "module": "NodeNext",
      "moduleResolution": "NodeNext",
      "lib": ["ES2022"],
      "outDir": "dist",
      "rootDir": ".",
      "strict": true,
      "noUnusedLocals": true,
      "noUnusedParameters": true,
      "noUncheckedIndexedAccess": true,
      "esModuleInterop": true,
      "skipLibCheck": true,
      "declaration": false,
      "sourceMap": true
    },
    "include": ["src/**/*.ts", "test/**/*.ts", "vitest.config.ts"]
  }
  ```

- [ ] **Step 3: Write vitest.config.ts.**
  Create `netisMaker/netismaker-interview-service/vitest.config.ts`:
  ```ts
  import { defineConfig } from 'vitest/config';

  export default defineConfig({
    test: {
      environment: 'node',
      globals: true,
      include: ['test/**/*.test.ts'],
      coverage: { provider: 'v8', reporter: ['text', 'lcov'] },
    },
  });
  ```

- [ ] **Step 4: Write .eslintrc.cjs and .env.example.**
  Create `netisMaker/netismaker-interview-service/.eslintrc.cjs`:
  ```cjs
  module.exports = {
    root: true,
    parser: '@typescript-eslint/parser',
    parserOptions: { ecmaVersion: 2022, sourceType: 'module' },
    env: { node: true, es2022: true },
    rules: {
      quotes: ['error', 'single', { avoidEscape: true }],
      semi: ['error', 'always'],
      indent: ['error', 2],
      'comma-dangle': ['error', 'always-multiline'],
    },
  };
  ```
  Create `netisMaker/netismaker-interview-service/.env.example`:
  ```bash
  # Auth = subscription via the local claude CLI (Phase-0 spike 00b).
  # ANTHROPIC_API_KEY is NOT required (apiKeySource:"none"); the SDK uses
  # options.pathToClaudeCodeExecutable. Leave CLAUDE_CLI unset to auto-resolve.
  # CLAUDE_CLI=/Users/micthebick/.local/bin/claude
  # netisMaker Java API gateway
  API_BASE_URL=http://localhost:8090
  WORKER_API_KEY=dev-only-change-me
  WORKER_ID=interview-worker-1
  # Per-session SHADOW quota guard (turns/quota, NOT dollars — subscription has no per-token billing).
  # Session FAILS when accumulated shadow total_cost_usd crosses this guard.
  INTERVIEW_QUOTA_GUARD=5.00
  # Claim poll interval (ms) and heartbeat interval (ms)
  CLAIM_POLL_INTERVAL_MS=3000
  HEARTBEAT_INTERVAL_MS=15000
  # Absolute path to the superpowers plugin (contains skills/brainstorming, skills/writing-plans)
  SUPERPOWERS_PLUGIN_PATH=/Users/micthebick/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0
  ```

- [ ] **Step 5: Install deps and confirm vitest runs with zero tests.**
  Run:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm install && npm test
  ```
  Expected: install completes; vitest exits with `No test files found` (exit 1 is fine here) — this confirms vitest is wired. Then add a trivial sentinel to confirm green:
  Create `netisMaker/netismaker-interview-service/test/scaffold.test.ts`:
  ```ts
  import { describe, expect, it } from 'vitest';

  describe('scaffold', () => {
    it('runs vitest', () => {
      expect(1 + 1).toBe(2);
    });
  });
  ```
  Run `npm test` — expected: `1 passed`.

- [ ] **Step 6: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "scaffold: netismaker-interview-service (TS/Node, SDK, vitest)"
  ```

---

### Task 2: Config + DTO types

**Files:**
- Create `netisMaker/netismaker-interview-service/src/config.ts`
- Create `netisMaker/netismaker-interview-service/src/types.ts`
- Create `netisMaker/netismaker-interview-service/test/fixtures/claims.ts`

- [ ] **Step 1: Write failing test for config parsing.**
  Create `netisMaker/netismaker-interview-service/test/config.test.ts`:
  ```ts
  import { describe, expect, it } from 'vitest';
  import { loadConfig } from '../src/config.js';

  const base = {
    API_BASE_URL: 'http://localhost:8090',
    WORKER_API_KEY: 'k',
    WORKER_ID: 'iw-1',
    SUPERPOWERS_PLUGIN_PATH: '/sp',
  };

  describe('loadConfig', () => {
    it('parses required env and applies defaults', () => {
      const c = loadConfig(base);
      expect(c.apiBaseUrl).toBe('http://localhost:8090');
      expect(c.quotaGuard).toBe(5);
      expect(c.claimPollIntervalMs).toBe(3000);
      // No ANTHROPIC_API_KEY: auth is subscription via the claude CLI.
      expect(c.claudeCliPath).toBeUndefined();
    });

    it('passes through CLAUDE_CLI override when set', () => {
      const c = loadConfig({ ...base, CLAUDE_CLI: '/custom/claude' });
      expect(c.claudeCliPath).toBe('/custom/claude');
    });

    it('throws when API_BASE_URL missing', () => {
      const { API_BASE_URL: _omit, ...rest } = base;
      expect(() => loadConfig(rest)).toThrow(/API_BASE_URL/);
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- config
  ```
  Expected: fails with `Cannot find module '../src/config.js'`.

- [ ] **Step 3: Implement config.ts.**
  Create `netisMaker/netismaker-interview-service/src/config.ts`:
  ```ts
  export interface Config {
    apiBaseUrl: string;
    workerApiKey: string;
    workerId: string;
    /** SHADOW per-session quota guard (turns/quota, NOT dollars — subscription auth). */
    quotaGuard: number;
    claimPollIntervalMs: number;
    heartbeatIntervalMs: number;
    superpowersPluginPath: string;
    /** Optional override for the claude CLI binary; auto-resolved by resolveClaudeCli() when unset. */
    claudeCliPath?: string;
  }

  function req(env: Record<string, string | undefined>, key: string): string {
    const v = env[key];
    if (!v) throw new Error(`Missing required env: ${key}`);
    return v;
  }

  function num(env: Record<string, string | undefined>, key: string, dflt: number): number {
    const v = env[key];
    return v === undefined ? dflt : Number(v);
  }

  export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
    return {
      apiBaseUrl: req(env, 'API_BASE_URL'),
      workerApiKey: req(env, 'WORKER_API_KEY'),
      workerId: req(env, 'WORKER_ID'),
      quotaGuard: num(env, 'INTERVIEW_QUOTA_GUARD', 5),
      claimPollIntervalMs: num(env, 'CLAIM_POLL_INTERVAL_MS', 3000),
      heartbeatIntervalMs: num(env, 'HEARTBEAT_INTERVAL_MS', 15000),
      superpowersPluginPath: req(env, 'SUPERPOWERS_PLUGIN_PATH'),
      // Auth = subscription via the local claude CLI (Phase-0 spike 00b). No ANTHROPIC_API_KEY.
      claudeCliPath: env.CLAUDE_CLI,
    };
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- config
  ```
  Expected: `3 passed`.

- [ ] **Step 5: Write DTO types mirroring the Java worker contract.**
  Create `netisMaker/netismaker-interview-service/src/types.ts` (field names + JSON keys match the LOCKED CONTRACT records `InterviewClaimResponse`, `WorkerQuestionRequest`, `WorkerPlanRequest` from plan 01 EXACTLY):
  ```ts
  /** One persisted turn echoed back on the claim (plan 01 InterviewTurn). */
  export interface InterviewTurn {
    seq: number;
    role: string;
    kind: string;
    content: string;
    replyToSeq: number | null;
  }

  /**
   * Returned by POST /worker/interviews/claim?workerId=... — the Java `InterviewClaimResponse`
   * record. All pending state lives here — never in memory.
   */
  export interface InterviewClaimResponse {
    sessionId: number;
    githubRepo: string;
    githubBranch: string;
    title: string;
    description: string;
    /** null => fresh brainstorming start; present => options.resume target */
    claudeSessionId: string | null;
    /** brainstorming | writing-plans — drives skill-dispatch shim */
    currentPhase: 'brainstorming' | 'writing-plans';
    /** Repo checkout dir; cwd for the SDK turn. Java assigns + persists this on first claim
     *  and returns the SAME value on every resume claim (resume is cwd-pinned, spike 02). */
    workDir: string;
    /** The newest user answer to inject this turn (null on the very first claim) */
    lastAnswer: string | null;
    /** seq of the answer above, for idempotency/logging */
    replyToSeq: number | null;
    /** Extra MCP servers to merge for this session (JSONB snapshot) */
    mcpsExtra: unknown;
    /** Prior turns (questions + answers) for context/logging */
    turns: InterviewTurn[];
  }

  /** Body for POST /worker/interviews/{id}/question */
  export interface WorkerQuestionRequest {
    content: string;
    claudeSessionId: string;
    kind: 'question' | 'design' | 'gate' | 'note';
    /** SHADOW cost for this turn (quota accounting, not dollars). */
    costUsd: number;
  }

  /**
   * Body for POST /worker/interviews/{id}/plan.
   * planJson is a **serialized JSON string** — Java stores it as TEXT/JSONB and the frontend
   * parses it on use. The TS service must call JSON.stringify(harvest.planJson) before sending.
   */
  export interface WorkerPlanRequest {
    designMarkdown: string;
    planMarkdown: string;
    /** Serialized JSON string (Array<{task,title}>). Java stores as text; frontend parses on use. */
    planJson: string;
    /** SHADOW cost for this turn (quota accounting, not dollars). */
    costUsd: number;
    durationMs: number;
  }
  ```
  Create `netisMaker/netismaker-interview-service/test/fixtures/claims.ts`:
  ```ts
  import type { InterviewClaimResponse } from '../../src/types.js';

  export const freshClaim: InterviewClaimResponse = {
    sessionId: 42,
    githubRepo: 'acme/widgets',
    githubBranch: 'main',
    title: 'Add CSV export',
    description: 'Users want to export the dashboard table as CSV.',
    claudeSessionId: null,
    currentPhase: 'brainstorming',
    workDir: '/Users/micthebick/netis-maker/interviews/acme/widgets/session-42',
    lastAnswer: null,
    replyToSeq: null,
    mcpsExtra: null,
    turns: [],
  };

  export const resumeClaim: InterviewClaimResponse = {
    ...freshClaim,
    claudeSessionId: 'sess-abc-123',
    lastAnswer: 'Yes, scope it to the visible columns only.',
    replyToSeq: 3,
    turns: [
      { seq: 1, role: 'assistant', kind: 'question', content: 'Which columns?', replyToSeq: null },
      { seq: 2, role: 'user', kind: 'answer', content: 'Yes, scope it to the visible columns only.', replyToSeq: 1 },
    ],
  };
  ```

- [ ] **Step 6: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "config + worker DTO types (mirror Java InterviewClaimResponse/WorkerQuestion/WorkerPlanRequest)"
  ```

---

### Task 3: Java API client (claim / question / plan / heartbeat / fail)

**Files:**
- Create `netisMaker/netismaker-interview-service/src/api/javaClient.ts`
- Create `netisMaker/netismaker-interview-service/test/javaClient.test.ts`

- [ ] **Step 1: Write failing test for claim + question request shape.**
  Create `netisMaker/netismaker-interview-service/test/javaClient.test.ts`:
  ```ts
  import { afterEach, describe, expect, it, vi } from 'vitest';
  import { JavaApiClient } from '../src/api/javaClient.js';
  import { freshClaim } from './fixtures/claims.js';

  const cfg = { apiBaseUrl: 'http://api:8090', workerApiKey: 'KEY', workerId: 'iw-1' };

  afterEach(() => vi.restoreAllMocks());

  describe('JavaApiClient', () => {
    it('claim POSTs to /worker/interviews/claim?workerId=... with worker key header, returns body', async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(freshClaim), { status: 200 }),
      );
      const client = new JavaApiClient(cfg, fetchMock);
      const res = await client.claim();
      expect(fetchMock).toHaveBeenCalledWith(
        'http://api:8090/worker/interviews/claim?workerId=iw-1',
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({ 'X-Worker-API-Key': 'KEY' }),
        }),
      );
      expect(res?.sessionId).toBe(42);
    });

    it('claim returns null on 204 (no work)', async () => {
      const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
      const client = new JavaApiClient(cfg, fetchMock);
      expect(await client.claim()).toBeNull();
    });

    it('postQuestion PUTs body to /worker/interviews/{id}/question', async () => {
      const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
      const client = new JavaApiClient(cfg, fetchMock);
      await client.postQuestion(42, {
        content: 'What columns?',
        claudeSessionId: 'sess-1',
        kind: 'question',
        costUsd: 0.1,
      });
      const [url, init] = fetchMock.mock.calls[0];
      expect(url).toBe('http://api:8090/worker/interviews/42/question');
      expect(JSON.parse(init.body).content).toBe('What columns?');
    });

    it('postPlan sends planJson as a STRING (Java stores as text; frontend parses on use)', async () => {
      const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
      const client = new JavaApiClient(cfg, fetchMock);
      const planJsonStr = JSON.stringify([{ task: 1, title: 'Build exporter' }]);
      await client.postPlan(42, {
        designMarkdown: '# Design',
        planMarkdown: '# Plan',
        planJson: planJsonStr,
        costUsd: 0.3,
        durationMs: 5000,
      });
      const [url, init] = fetchMock.mock.calls[0];
      expect(url).toBe('http://api:8090/worker/interviews/42/plan');
      // planJson must be a string on the wire — Java stores it as text, frontend parses on use
      expect(typeof JSON.parse(init.body).planJson).toBe('string');
    });

    it('fail POSTs reason to /worker/interviews/{id}/fail', async () => {
      const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
      const client = new JavaApiClient(cfg, fetchMock);
      await client.fail(42, 'cost cap exceeded');
      expect(fetchMock.mock.calls[0][0]).toBe('http://api:8090/worker/interviews/42/fail');
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- javaClient
  ```
  Expected: fails with `Cannot find module '../src/api/javaClient.js'`.

- [ ] **Step 3: Implement javaClient.ts.**
  Create `netisMaker/netismaker-interview-service/src/api/javaClient.ts`:
  ```ts
  import type { InterviewClaimResponse, WorkerPlanRequest, WorkerQuestionRequest } from '../types.js';

  type FetchLike = typeof fetch;
  interface ClientConfig {
    apiBaseUrl: string;
    workerApiKey: string;
    workerId: string;
  }

  export class JavaApiClient {
    constructor(
      private readonly cfg: ClientConfig,
      private readonly fetchFn: FetchLike = fetch,
    ) {}

    private headers(): Record<string, string> {
      return {
        'Content-Type': 'application/json',
        'X-Worker-API-Key': this.cfg.workerApiKey,
      };
    }

    private url(path: string): string {
      return `${this.cfg.apiBaseUrl}${path}`;
    }

    async claim(): Promise<InterviewClaimResponse | null> {
      // workerId is a QUERY param (LOCKED CONTRACT); auth is the X-Worker-API-Key header.
      const res = await this.fetchFn(
        this.url(`/worker/interviews/claim?workerId=${encodeURIComponent(this.cfg.workerId)}`),
        {
          method: 'POST',
          headers: this.headers(),
        },
      );
      if (res.status === 204) return null;
      if (!res.ok) throw new Error(`claim failed: ${res.status}`);
      return (await res.json()) as InterviewClaimResponse;
    }

    async postQuestion(id: number, body: WorkerQuestionRequest): Promise<void> {
      const res = await this.fetchFn(this.url(`/worker/interviews/${id}/question`), {
        method: 'POST',
        headers: this.headers(),
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`question failed: ${res.status}`);
    }

    async postPlan(id: number, body: WorkerPlanRequest): Promise<void> {
      const res = await this.fetchFn(this.url(`/worker/interviews/${id}/plan`), {
        method: 'POST',
        headers: this.headers(),
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`plan failed: ${res.status}`);
    }

    async heartbeat(id: number): Promise<void> {
      await this.fetchFn(this.url(`/worker/interviews/${id}/heartbeat`), {
        method: 'POST',
        headers: this.headers(),
        body: JSON.stringify({ workerId: this.cfg.workerId }),
      });
    }

    async fail(id: number, reason: string): Promise<void> {
      await this.fetchFn(this.url(`/worker/interviews/${id}/fail`), {
        method: 'POST',
        headers: this.headers(),
        body: JSON.stringify({ reason }),
      });
    }
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- javaClient
  ```
  Expected: `4 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "JavaApiClient: claim(?workerId=)/question/plan/heartbeat/fail over X-Worker-API-Key"
  ```

---

### Task 4: Claude CLI resolver (subscription auth — no API key)

> **Phase-0 spike 00b:** Auth is subscription via the local `claude` CLI, NOT `ANTHROPIC_API_KEY`. `buildOptions` sets `options.pathToClaudeCodeExecutable` to the resolved binary so the SDK reuses the operator's logged-in subscription (`apiKeySource:"none"`). Resolution order mirrors netisMaker's `ClaudeExecAdapter`.

**Files:**
- Create `netisMaker/netismaker-interview-service/src/sdk/claudeCli.ts`
- Create `netisMaker/netismaker-interview-service/test/claudeCli.test.ts`

- [ ] **Step 1: Write failing test for CLI resolution order.**
  Create `netisMaker/netismaker-interview-service/test/claudeCli.test.ts`:
  ```ts
  import { describe, expect, it } from 'vitest';
  import { resolveClaudeCli } from '../src/sdk/claudeCli.js';

  describe('resolveClaudeCli', () => {
    it('returns the explicit override when provided', () => {
      const exists = () => true;
      expect(resolveClaudeCli('/custom/claude', '/home/me', exists)).toBe('/custom/claude');
    });

    it('walks the candidate list in order and returns the first that exists', () => {
      // only ~/.local/bin/claude exists
      const exists = (p: string) => p === '/home/me/.local/bin/claude';
      expect(resolveClaudeCli(undefined, '/home/me', exists)).toBe('/home/me/.local/bin/claude');
    });

    it('falls back to bare "claude" (PATH lookup) when no candidate file exists', () => {
      expect(resolveClaudeCli(undefined, '/home/me', () => false)).toBe('claude');
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- claudeCli
  ```
  Expected: fails with `Cannot find module '../src/sdk/claudeCli.js'`.

- [ ] **Step 3: Implement claudeCli.ts.**
  Create `netisMaker/netismaker-interview-service/src/sdk/claudeCli.ts`:
  ```ts
  import { existsSync } from 'node:fs';
  import { join } from 'node:path';
  import { homedir } from 'node:os';

  type ExistsFn = (path: string) => boolean;

  /**
   * Resolves the claude CLI binary for subscription auth (Phase-0 spike 00b),
   * mirroring netisMaker's ClaudeExecAdapter discovery order:
   *   explicit override -> ~/.local/bin -> ~/.claude/local -> /opt/homebrew/bin
   *   -> /usr/local/bin -> /usr/bin -> bare "claude" (PATH).
   */
  export function resolveClaudeCli(
    override: string | undefined,
    home: string = homedir(),
    exists: ExistsFn = existsSync,
  ): string {
    if (override) return override;
    const candidates = [
      join(home, '.local/bin/claude'),
      join(home, '.claude/local/claude'),
      '/opt/homebrew/bin/claude',
      '/usr/local/bin/claude',
      '/usr/bin/claude',
    ];
    for (const c of candidates) {
      if (exists(c)) return c;
    }
    return 'claude'; // last resort: rely on PATH
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- claudeCli
  ```
  Expected: `3 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "resolveClaudeCli: subscription auth via local claude CLI (no ANTHROPIC_API_KEY)"
  ```

---

### Task 5: SDK adapter seam + session options (pathToClaudeCodeExecutable, plugins, cwd, allowedTools+Skill, resume, canUseTool)

**Files:**
- Create `netisMaker/netismaker-interview-service/src/sdk/sdkAdapter.ts`
- Create `netisMaker/netismaker-interview-service/src/sdk/sessionOptions.ts`
- Create `netisMaker/netismaker-interview-service/test/sessionOptions.test.ts`

- [ ] **Step 1: Write failing test for buildOptions (fresh vs resume + plugin/setting/tool wiring).**
  Create `netisMaker/netismaker-interview-service/test/sessionOptions.test.ts`:
  ```ts
  import { describe, expect, it } from 'vitest';
  import { buildOptions } from '../src/sdk/sessionOptions.js';

  const base = {
    superpowersPluginPath: '/sp/5.1.0',
    workDir: '/tmp/repo',
    claudeCliPath: '/home/me/.local/bin/claude',
  };

  describe('buildOptions', () => {
    it('loads superpowers as a local plugin (isolation: plugins-only, no settingSources)', () => {
      const o = buildOptions({ ...base, claudeSessionId: null });
      expect(o.plugins).toEqual([{ type: 'local', path: '/sp/5.1.0' }]);
      // Phase-0 spike 04 caveat 2: settingSources:['user','project'] loads ALL user plugins;
      // for isolation we rely on plugins:[] only and do NOT set settingSources.
      expect(o.settingSources).toBeUndefined();
    });

    it('uses subscription auth: pathToClaudeCodeExecutable set, no env.ANTHROPIC_API_KEY', () => {
      const o = buildOptions({ ...base, claudeSessionId: null });
      expect(o.pathToClaudeCodeExecutable).toBe('/home/me/.local/bin/claude');
      expect(o.env).toBeUndefined();
    });

    it('allows Skill + read tools + Write + Bash (Skill must be present so the tool appears in init.tools)', () => {
      const o = buildOptions({ ...base, claudeSessionId: null });
      expect(o.allowedTools).toEqual(
        expect.arrayContaining(['Skill', 'Read', 'Grep', 'Glob', 'Write', 'Bash']),
      );
    });

    it('omits resume on a fresh start', () => {
      const o = buildOptions({ ...base, claudeSessionId: null });
      expect(o.resume).toBeUndefined();
    });

    it('sets resume when a claudeSessionId is present', () => {
      const o = buildOptions({ ...base, claudeSessionId: 'sess-abc' });
      expect(o.resume).toBe('sess-abc');
    });

    it('sets cwd to workDir (resume MUST reuse the identical cwd) and provides canUseTool', () => {
      const o = buildOptions({ ...base, claudeSessionId: null });
      expect(o.cwd).toBe('/tmp/repo');
      expect(typeof o.canUseTool).toBe('function');
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- sessionOptions
  ```
  Expected: fails with `Cannot find module '../src/sdk/sessionOptions.js'`.

- [ ] **Step 3: Implement sessionOptions.ts (the permissions import lands in Task 6; canUseTool is wired now).**
  Create `netisMaker/netismaker-interview-service/src/sdk/sessionOptions.ts`:
  ```ts
  import { buildCanUseTool } from './permissions.js';

  export interface SessionOptionsInput {
    superpowersPluginPath: string;
    /** Repo checkout dir = options.cwd. Resume MUST reuse the IDENTICAL cwd (spike 02, cwd-pinned). */
    workDir: string;
    /** Resolved claude CLI binary for subscription auth (Phase-0 spike 00b). */
    claudeCliPath: string;
    claudeSessionId: string | null;
  }

  /**
   * SDK options for one interview turn.
   * - Auth = subscription via the local claude CLI: pathToClaudeCodeExecutable points at
   *   the resolved binary; ANTHROPIC_API_KEY is NOT set (apiKeySource:"none", spike 00b).
   * - Isolation: load superpowers via plugins:[{type:'local'}] ONLY and do NOT set
   *   settingSources (settingSources:['user','project'] would load ALL user plugins, spike 04 caveat 2).
   *   'Skill' is whitelisted so the Skill tool appears in init.tools.
   * - cwd = workDir; resume reuses the identical cwd (the on-disk session store is cwd-hashed, spike 02).
   * - PERMISSIONS are enforced via canUseTool — allowedTools does NOT constrain skill-internal
   *   tool calls (spike 04 caveat 1: brainstorming ran Bash despite not being whitelisted).
   */
  export function buildOptions(input: SessionOptionsInput): Record<string, unknown> {
    return {
      pathToClaudeCodeExecutable: input.claudeCliPath,
      plugins: [{ type: 'local', path: input.superpowersPluginPath }],
      allowedTools: ['Skill', 'Read', 'Grep', 'Glob', 'Write', 'Bash'],
      cwd: input.workDir,
      permissionMode: 'default',
      ...(input.claudeSessionId ? { resume: input.claudeSessionId } : {}),
      canUseTool: buildCanUseTool(input.workDir),
    };
  }
  ```
  Create `netisMaker/netismaker-interview-service/src/sdk/sdkAdapter.ts` (the thin seam tests mock):
  ```ts
  import { query } from '@anthropic-ai/claude-agent-sdk';

  export type SdkMessage = {
    type: string;
    [key: string]: unknown;
  };

  export interface SdkQuery {
    (args: { prompt: AsyncIterable<unknown>; options: Record<string, unknown> }): AsyncIterable<SdkMessage>;
  }

  /** Default real query; injected as a seam so tests pass a fake. */
  export const realQuery: SdkQuery = (args) =>
    query(args as never) as unknown as AsyncIterable<SdkMessage>;
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- sessionOptions
  ```
  Expected: `5 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "SDK session options: pathToClaudeCodeExecutable, local superpowers plugin, cwd=workDir, allowedTools(+Skill), resume, canUseTool"
  ```

> **Phase-0 spike resolved (spikes 00b, 02, 04):** Auth is subscription via the CLI (`pathToClaudeCodeExecutable`, no `ANTHROPIC_API_KEY`). Cross-process `options.resume` works but is **cwd-pinned** — the on-disk session store lives at `~/.claude/projects/<cwd-hash>/`, so every resume MUST pass the identical `cwd = workDir` (single host / shared FS only). Permission enforcement is `canUseTool` (allowedTools alone does not constrain skill-internal tool calls). All SDK option names live in `buildOptions` here — no other module touches them.

---

### Task 6: canUseTool gating (Write confined to docs/superpowers/**, Bash whitelist, block repo mutation/push)

> **Phase-0 spike 04 caveat 1:** `allowedTools` does NOT constrain skill-internal tool calls (brainstorming ran `Bash` despite not being whitelisted). Permission enforcement therefore happens HERE, in `canUseTool`, which intercepts every tool call.

**Files:**
- Create `netisMaker/netismaker-interview-service/src/sdk/permissions.ts`
- Create `netisMaker/netismaker-interview-service/test/permissions.test.ts`

- [ ] **Step 1: Write failing test for tool gating.**
  Create `netisMaker/netismaker-interview-service/test/permissions.test.ts`:
  ```ts
  import { describe, expect, it } from 'vitest';
  import { buildCanUseTool } from '../src/sdk/permissions.js';

  const canUse = buildCanUseTool('/tmp/repo');

  describe('canUseTool', () => {
    it('allows Write inside docs/superpowers/**', async () => {
      const r = await canUse('Write', { file_path: '/tmp/repo/docs/superpowers/specs/x.md' });
      expect(r.behavior).toBe('allow');
    });

    it('denies Write outside docs/superpowers (no repo mutation, spec §9)', async () => {
      const r = await canUse('Write', { file_path: '/tmp/repo/src/main.ts' });
      expect(r.behavior).toBe('deny');
    });

    it('denies Write that escapes the repo via traversal', async () => {
      const r = await canUse('Write', { file_path: '/tmp/repo/docs/superpowers/../../etc/passwd' });
      expect(r.behavior).toBe('deny');
    });

    it('allows whitelisted Bash (git status, ls, grep, rg, cat)', async () => {
      for (const cmd of ['git status', 'ls -la', 'grep -r foo .', 'rg bar', 'cat README.md']) {
        expect((await canUse('Bash', { command: cmd })).behavior).toBe('allow');
      }
    });

    it('denies non-whitelisted Bash (git push, rm, curl)', async () => {
      for (const cmd of ['git push origin main', 'rm -rf /', 'curl http://evil']) {
        expect((await canUse('Bash', { command: cmd })).behavior).toBe('deny');
      }
    });

    it('allows read/skill tools', async () => {
      expect((await canUse('Read', { file_path: '/tmp/repo/src/main.ts' })).behavior).toBe('allow');
      expect((await canUse('Skill', { name: 'writing-plans' })).behavior).toBe('allow');
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- permissions
  ```
  Expected: fails with `Cannot find module '../src/sdk/permissions.js'`.

- [ ] **Step 3: Implement permissions.ts.**
  Create `netisMaker/netismaker-interview-service/src/sdk/permissions.ts`:
  ```ts
  import { resolve } from 'node:path';

  export interface PermissionResult {
    behavior: 'allow' | 'deny';
    message?: string;
  }
  export type CanUseTool = (
    toolName: string,
    input: Record<string, unknown>,
  ) => Promise<PermissionResult>;

  // Bash commands the interview may run. Read-only / inspection only. NO push, rm, curl, write.
  const BASH_WHITELIST = [/^git (status|log|diff|show|branch)\b/, /^ls\b/, /^cat\b/, /^grep\b/, /^rg\b/, /^find\b/, /^head\b/, /^tail\b/, /^wc\b/, /^pwd$/];

  export function buildCanUseTool(repoDir: string): CanUseTool {
    const allowedWriteRoot = resolve(repoDir, 'docs/superpowers');
    return async (toolName, input) => {
      if (toolName === 'Write' || toolName === 'Edit' || toolName === 'MultiEdit') {
        const fp = String(input.file_path ?? '');
        const abs = resolve(repoDir, fp);
        if (abs === allowedWriteRoot || abs.startsWith(allowedWriteRoot + '/')) {
          return { behavior: 'allow' };
        }
        return { behavior: 'deny', message: 'Write confined to docs/superpowers/**' };
      }
      if (toolName === 'Bash') {
        const cmd = String(input.command ?? '').trim();
        if (BASH_WHITELIST.some((re) => re.test(cmd))) return { behavior: 'allow' };
        return { behavior: 'deny', message: `Bash not whitelisted: ${cmd}` };
      }
      // Read/Grep/Glob/Skill and other inspection tools are allowed.
      return { behavior: 'allow' };
    };
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- permissions
  ```
  Expected: `6 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "canUseTool: confine Write to docs/superpowers/**, Bash read-only whitelist"
  ```

---

### Task 7: Quota guard (accumulate SHADOW total_cost_usd + per-session quota cap)

> **Phase-0 spike 00b:** Subscription auth has NO per-token billing — `result.usage.total_cost_usd` is a SHADOW value (what PAYG *would* have cost). This guard is a per-session **turn/quota** safety net, not a dollar cap. The LOCKED CONTRACT `InterviewClaimResponse` carries NO `totalCostUsd` field, so the runner seeds the guard from `0` each turn (it caps a single runaway turn); the cumulative per-session shadow total is owned server-side via the `costUsd` posted on `/question` and `/plan`.

**Files:**
- Create `netisMaker/netismaker-interview-service/src/runner/costGuard.ts`
- Create `netisMaker/netismaker-interview-service/test/costGuard.test.ts`

- [ ] **Step 1: Write failing test for shadow-cost accumulation + quota cap.**
  Create `netisMaker/netismaker-interview-service/test/costGuard.test.ts`:
  ```ts
  import { describe, expect, it } from 'vitest';
  import { QuotaGuardExceeded, CostGuard } from '../src/runner/costGuard.js';

  describe('CostGuard', () => {
    it('accumulates onto the prior shadow total', () => {
      const g = new CostGuard(5, 0.42); // quota guard 5, prior shadow 0.42
      g.add(0.1);
      expect(g.total).toBeCloseTo(0.52);
    });

    it('throws QuotaGuardExceeded when the running total crosses the guard', () => {
      const g = new CostGuard(0.5, 0.45);
      expect(() => g.add(0.1)).toThrow(QuotaGuardExceeded);
    });

    it('does not throw exactly at the guard', () => {
      const g = new CostGuard(0.5, 0.4);
      expect(() => g.add(0.1)).not.toThrow();
      expect(g.total).toBeCloseTo(0.5);
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- costGuard
  ```
  Expected: fails with `Cannot find module '../src/runner/costGuard.js'`.

- [ ] **Step 3: Implement costGuard.ts.**
  Create `netisMaker/netismaker-interview-service/src/runner/costGuard.ts`:
  ```ts
  export class QuotaGuardExceeded extends Error {
    constructor(public readonly total: number, public readonly guard: number) {
      super(`session shadow cost ${total.toFixed(4)} crossed quota guard ${guard.toFixed(4)}`);
      this.name = 'QuotaGuardExceeded';
    }
  }

  /**
   * Accumulates the SHADOW result.usage.total_cost_usd onto the prior session total and
   * enforces a per-session quota guard. NOT a dollar cap — subscription auth has no per-token
   * billing (Phase-0 spike 00b); this is a turn/quota safety net.
   */
  export class CostGuard {
    private _total: number;
    constructor(private readonly guardUsd: number, priorTotalUsd = 0) {
      this._total = priorTotalUsd;
    }
    get total(): number {
      return this._total;
    }
    /** Adds this turn's shadow cost; throws if the running total crosses the guard. */
    add(turnCostUsd: number): void {
      this._total += turnCostUsd;
      if (this._total > this.guardUsd) throw new QuotaGuardExceeded(this._total, this.guardUsd);
    }
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- costGuard
  ```
  Expected: `3 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "CostGuard: accumulate SHADOW total_cost_usd onto prior + per-session quota guard"
  ```

---

### Task 8: Message relay (capture session_id from init, collect assistant text, detect result)

**Files:**
- Create `netisMaker/netismaker-interview-service/src/runner/messageRelay.ts`
- Create `netisMaker/netismaker-interview-service/test/fixtures/sdkMessages.ts`
- Create `netisMaker/netismaker-interview-service/test/messageRelay.test.ts`

- [ ] **Step 1: Write SDK message fixtures.**
  Create `netisMaker/netismaker-interview-service/test/fixtures/sdkMessages.ts`:
  ```ts
  import type { SdkMessage } from '../../src/sdk/sdkAdapter.js';

  async function* gen(...msgs: SdkMessage[]): AsyncIterable<SdkMessage> {
    for (const m of msgs) yield m;
  }

  /** Init -> one assistant question -> result (the agent paused for human input). */
  export const questionStream = (): AsyncIterable<SdkMessage> =>
    gen(
      { type: 'system', subtype: 'init', session_id: 'sess-new-1' },
      {
        type: 'assistant',
        message: { content: [{ type: 'text', text: 'Which columns should the CSV include?' }] },
      },
      { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.12 }, duration_ms: 800 },
    );

  /** Init -> writing-plans completes: assistant emits design+plan, then result. */
  export const planCompleteStream = (): AsyncIterable<SdkMessage> =>
    gen(
      { type: 'system', subtype: 'init', session_id: 'sess-resumed-9' },
      {
        type: 'assistant',
        message: {
          content: [
            {
              type: 'text',
              text:
                '# Design\n\nExport visible columns as CSV.\n\n' +
                '# CSV Export Implementation Plan\n\n## File Structure\n\n- a.ts\n\n### Task 1: x\n- [ ] **Step 1: do**',
            },
          ],
        },
      },
      { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.31 }, duration_ms: 5400 },
    );
  ```

- [ ] **Step 2: Write failing relay test.**
  Create `netisMaker/netismaker-interview-service/test/messageRelay.test.ts`:
  ```ts
  import { describe, expect, it } from 'vitest';
  import { relay } from '../src/runner/messageRelay.js';
  import { questionStream } from './fixtures/sdkMessages.js';

  describe('relay', () => {
    it('captures session_id from system/init and collects assistant text + result', async () => {
      const out = await relay(questionStream());
      expect(out.sessionId).toBe('sess-new-1');
      expect(out.assistantText).toContain('Which columns');
      expect(out.costUsd).toBeCloseTo(0.12);
      expect(out.durationMs).toBe(800);
      expect(out.completed).toBe(true);
    });
  });
  ```

- [ ] **Step 3: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- messageRelay
  ```
  Expected: fails with `Cannot find module '../src/runner/messageRelay.js'`.

- [ ] **Step 4: Implement messageRelay.ts.**
  Create `netisMaker/netismaker-interview-service/src/runner/messageRelay.ts`:
  ```ts
  import type { SdkMessage } from '../sdk/sdkAdapter.js';

  export interface RelayResult {
    sessionId: string | null;
    assistantText: string;
    costUsd: number;
    durationMs: number;
    completed: boolean;
  }

  function extractText(msg: SdkMessage): string {
    const content = (msg.message as { content?: Array<{ type: string; text?: string }> } | undefined)
      ?.content;
    if (!Array.isArray(content)) return '';
    return content
      .filter((b) => b.type === 'text' && typeof b.text === 'string')
      .map((b) => b.text as string)
      .join('\n');
  }

  /** Drains one SDK turn: captures session_id from init, joins assistant text, reads result usage. */
  export async function relay(stream: AsyncIterable<SdkMessage>): Promise<RelayResult> {
    let sessionId: string | null = null;
    const parts: string[] = [];
    let costUsd = 0;
    let durationMs = 0;
    let completed = false;
    for await (const msg of stream) {
      if (msg.type === 'system' && msg.subtype === 'init') {
        sessionId = (msg.session_id as string) ?? null;
      } else if (msg.type === 'assistant') {
        const t = extractText(msg);
        if (t) parts.push(t);
      } else if (msg.type === 'result') {
        const usage = msg.usage as { total_cost_usd?: number } | undefined;
        costUsd = usage?.total_cost_usd ?? 0;
        durationMs = (msg.duration_ms as number) ?? 0;
        completed = true;
      }
    }
    return { sessionId, assistantText: parts.join('\n\n'), costUsd, durationMs, completed };
  }
  ```

- [ ] **Step 5: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- messageRelay
  ```
  Expected: `1 passed`.

- [ ] **Step 6: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "messageRelay: capture session_id from init, collect assistant text + result usage"
  ```

---

### Task 9: Plan harvest parser (design + plan markdown + planJson)

**Files:**
- Create `netisMaker/netismaker-interview-service/src/runner/planHarvest.ts`
- Create `netisMaker/netismaker-interview-service/test/planHarvest.test.ts`

- [ ] **Step 1: Write failing test for harvest parsing.**
  Create `netisMaker/netismaker-interview-service/test/planHarvest.test.ts`:
  ```ts
  import { describe, expect, it } from 'vitest';
  import { harvestPlan, HarvestError } from '../src/runner/planHarvest.js';

  const transcript =
    '# Design\n\nExport visible columns as CSV.\n\n' +
    '# CSV Export Implementation Plan\n\n## File Structure\n\n- a.ts\n\n' +
    '### Task 1: Build exporter\n- [ ] **Step 1: write test**\n' +
    '### Task 2: Wire button\n- [ ] **Step 1: add button**';

  describe('harvestPlan', () => {
    it('splits design vs plan at the "Implementation Plan" header', () => {
      const r = harvestPlan(transcript);
      expect(r.designMarkdown).toContain('Export visible columns');
      expect(r.designMarkdown).not.toContain('Implementation Plan');
      expect(r.planMarkdown).toContain('## File Structure');
    });

    it('extracts task titles into planJson', () => {
      const r = harvestPlan(transcript);
      expect(r.planJson).toEqual([
        { task: 1, title: 'Build exporter' },
        { task: 2, title: 'Wire button' },
      ]);
    });

    it('throws HarvestError when no Implementation Plan header is present', () => {
      expect(() => harvestPlan('# Design\n\nonly design, no plan')).toThrow(HarvestError);
    });

    it('throws HarvestError when plan has zero tasks', () => {
      expect(() => harvestPlan('# Design\nx\n# Foo Implementation Plan\nno tasks here')).toThrow(
        HarvestError,
      );
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- planHarvest
  ```
  Expected: fails with `Cannot find module '../src/runner/planHarvest.js'`.

- [ ] **Step 3: Implement planHarvest.ts.**
  Create `netisMaker/netismaker-interview-service/src/runner/planHarvest.ts`:
  ```ts
  export class HarvestError extends Error {
    constructor(message: string) {
      super(message);
      this.name = 'HarvestError';
    }
  }

  export interface Harvest {
    designMarkdown: string;
    planMarkdown: string;
    planJson: Array<{ task: number; title: string }>;
  }

  // writing-plans header convention: "# <Feature> Implementation Plan"
  const PLAN_HEADER = /^#\s+.*Implementation Plan\s*$/m;
  const TASK_LINE = /^###\s+Task\s+(\d+):\s*(.+?)\s*$/gm;

  /** Splits the final transcript into design (before the plan header) and the plan, and indexes tasks. */
  export function harvestPlan(transcript: string): Harvest {
    const match = PLAN_HEADER.exec(transcript);
    if (!match || match.index === undefined) {
      throw new HarvestError('no "Implementation Plan" header found in transcript');
    }
    const designMarkdown = transcript.slice(0, match.index).trim();
    const planMarkdown = transcript.slice(match.index).trim();

    const planJson: Array<{ task: number; title: string }> = [];
    let m: RegExpExecArray | null;
    TASK_LINE.lastIndex = 0;
    while ((m = TASK_LINE.exec(planMarkdown)) !== null) {
      planJson.push({ task: Number(m[1]), title: m[2] });
    }
    if (planJson.length === 0) {
      throw new HarvestError('plan contains zero "### Task N:" entries');
    }
    return { designMarkdown, planMarkdown, planJson };
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- planHarvest
  ```
  Expected: `4 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "planHarvest: split design/plan at Implementation Plan header, index tasks to planJson"
  ```

---

### Task 10: Skill-dispatch shim (detect brainstorming→writing-plans handoff, splice SKILL.md)

**Files:**
- Create `netisMaker/netismaker-interview-service/src/runner/skillDispatch.ts`
- Create `netisMaker/netismaker-interview-service/test/skillDispatch.test.ts`

- [ ] **Step 1: Write failing test for handoff detection + splice.**
  Create `netisMaker/netismaker-interview-service/test/skillDispatch.test.ts`:
  ```ts
  import { describe, expect, it, vi } from 'vitest';
  import { detectHandoff, buildWritingPlansSplice } from '../src/runner/skillDispatch.js';

  describe('detectHandoff', () => {
    it('detects when the agent announces the writing-plans transition', () => {
      const text =
        'The spec is approved. Transition to implementation — invoke writing-plans skill to create the plan.';
      expect(detectHandoff(text)).toBe(true);
    });

    it('does not fire on ordinary brainstorming questions', () => {
      expect(detectHandoff('Which columns should the CSV include?')).toBe(false);
    });
  });

  describe('buildWritingPlansSplice', () => {
    it('reads writing-plans SKILL.md and wraps it as a user prompt to splice into the same session', () => {
      const readFn = vi.fn().mockReturnValue('# Writing Plans\n\nBreak the spec into tasks.');
      const out = buildWritingPlansSplice('/sp/5.1.0', readFn);
      expect(readFn).toHaveBeenCalledWith('/sp/5.1.0/skills/writing-plans/SKILL.md', 'utf8');
      expect(out).toContain('Break the spec into tasks');
      expect(out.toLowerCase()).toContain('writing plans');
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- skillDispatch
  ```
  Expected: fails with `Cannot find module '../src/runner/skillDispatch.js'`.

- [ ] **Step 3: Implement skillDispatch.ts.**
  Create `netisMaker/netismaker-interview-service/src/runner/skillDispatch.ts`:
  ```ts
  import { readFileSync } from 'node:fs';

  type ReadFn = (path: string, enc: 'utf8') => string;

  // brainstorming checklist item 9 wording: "Transition to implementation — invoke writing-plans".
  const HANDOFF = /(invoke\s+writing-plans|transition\s+to\s+(implementation|writing-plans))/i;

  /** True when the assistant text announces the brainstorming -> writing-plans handoff. */
  export function detectHandoff(assistantText: string): boolean {
    return HANDOFF.test(assistantText);
  }

  /**
   * Fallback when the 'Skill' tool did NOT auto-fire writing-plans (spec §9 shim):
   * read writing-plans SKILL.md and return a user prompt that splices it into the SAME session.
   */
  export function buildWritingPlansSplice(
    superpowersPluginPath: string,
    readFn: ReadFn = readFileSync as ReadFn,
  ): string {
    const skillPath = `${superpowersPluginPath}/skills/writing-plans/SKILL.md`;
    const skill = readFn(skillPath, 'utf8');
    return (
      'Now follow the writing-plans skill to turn the approved spec into an implementation plan. ' +
      'Apply this skill exactly:\n\n' +
      skill
    );
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- skillDispatch
  ```
  Expected: `3 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "skillDispatch: detect brainstorming->writing-plans handoff + SKILL.md splice fallback"
  ```

> **Phase-0 spike 04 resolved:** superpowers loads via `plugins:[{type:'local'}]` (`init.plugins` shows `superpowers@inline`), both `superpowers:brainstorming` and `superpowers:writing-plans` appear in `init.skills`, the `Skill` tool is in `init.tools`, and invoking `{skill:"superpowers:brainstorming"}` ran the skill (`is_error:false`). The `Skill` dispatch mechanism works, so `buildWritingPlansSplice` is the safety-net fallback for the case where the agent announces the handoff but the `Skill` tool did not auto-fire writing-plans. The detection regex in `detectHandoff` is the only thing to retune in practice.

---

### Task 11: Repo prepare (clone/fetch at workDir before first turn AND before resume)

> **Phase-0 spike 02 / CLONE requirement:** `options.resume` is cwd-pinned (the session store is `~/.claude/projects/<cwd-hash>/`). The interview service is responsible for ensuring the repo is checked out at `claim.workDir` **before the first turn AND before every resume**. Fresh dir => `git clone --depth 1 --branch <branch>`; existing checkout => `git fetch` + `git reset --hard origin/<branch>`. Single host / shared FS only.

**Files:**
- Create `netisMaker/netismaker-interview-service/src/runner/repoPrepare.ts`
- Create `netisMaker/netismaker-interview-service/test/repoPrepare.test.ts`

- [ ] **Step 1: Write failing test — clone when workDir absent, fetch+reset when present.**
  Create `netisMaker/netismaker-interview-service/test/repoPrepare.test.ts`:
  ```ts
  import { describe, expect, it, vi } from 'vitest';
  import { ensureRepo } from '../src/runner/repoPrepare.js';

  describe('ensureRepo', () => {
    it('clones --depth 1 --branch into workDir when the checkout does not exist', async () => {
      const run = vi.fn().mockResolvedValue(undefined);
      const exists = vi.fn().mockReturnValue(false); // no .git yet
      await ensureRepo(
        { githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/session-42' },
        { run, exists },
      );
      expect(run).toHaveBeenCalledWith(
        'git',
        ['clone', '--depth', '1', '--branch', 'main', 'https://github.com/acme/widgets.git', '/wd/session-42'],
        expect.any(Object),
      );
    });

    it('fetch + reset --hard when the checkout already exists', async () => {
      const run = vi.fn().mockResolvedValue(undefined);
      const exists = vi.fn().mockReturnValue(true); // .git present
      await ensureRepo(
        { githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/session-42' },
        { run, exists },
      );
      const cmds = run.mock.calls.map((c) => [c[0], ...(c[1] as string[])].join(' '));
      expect(cmds).toContain('git fetch --depth 1 origin main');
      expect(cmds).toContain('git reset --hard origin/main');
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- repoPrepare
  ```
  Expected: fails with `Cannot find module '../src/runner/repoPrepare.js'`.

- [ ] **Step 3: Implement repoPrepare.ts.**
  Create `netisMaker/netismaker-interview-service/src/runner/repoPrepare.ts`:
  ```ts
  import { execFile } from 'node:child_process';
  import { existsSync, mkdirSync } from 'node:fs';
  import { join } from 'node:path';
  import { promisify } from 'node:util';

  const execFileAsync = promisify(execFile);

  export interface RepoInput {
    githubRepo: string; // "owner/repo"
    githubBranch: string;
    workDir: string; // = claim.workDir, also options.cwd
  }

  export interface RepoOps {
    run: (cmd: string, args: string[], opts: { cwd?: string }) => Promise<unknown>;
    exists: (path: string) => boolean;
  }

  const defaultOps: RepoOps = {
    run: (cmd, args, opts) => execFileAsync(cmd, args, opts),
    exists: existsSync,
  };

  /**
   * Ensures the repo is checked out at workDir BEFORE the first turn AND before every resume
   * (resume is cwd-pinned — spike 02). Fresh => shallow clone; existing => fetch + hard reset.
   * Uses an unauthenticated https URL for read-only interview checkouts; swap in a PAT URL if
   * the interview repos are private (mirrors netisMaker GitRepoCache).
   */
  export async function ensureRepo(input: RepoInput, ops: RepoOps = defaultOps): Promise<void> {
    const { githubRepo, githubBranch, workDir } = input;
    const gitDir = join(workDir, '.git');
    if (ops.exists(gitDir)) {
      await ops.run('git', ['fetch', '--depth', '1', 'origin', githubBranch], { cwd: workDir });
      await ops.run('git', ['reset', '--hard', `origin/${githubBranch}`], { cwd: workDir });
      return;
    }
    mkdirSync(workDir, { recursive: true });
    const url = `https://github.com/${githubRepo}.git`;
    await ops.run('git', ['clone', '--depth', '1', '--branch', githubBranch, url, workDir], {});
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- repoPrepare
  ```
  Expected: `2 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "ensureRepo: clone --depth 1 --branch / fetch+reset at workDir before first turn and resume"
  ```

---

### Task 12: InterviewRunner (repo prep, fresh-start vs resume, relay loop, quota guard, harvest dispatch)

**Files:**
- Create `netisMaker/netismaker-interview-service/src/runner/interviewRunner.ts`
- Create `netisMaker/netismaker-interview-service/test/interviewRunner.test.ts`

- [ ] **Step 1: Write failing test — fresh claim relays a question (resume-per-answer DEFAULT, no in-memory wait).**
  Create `netisMaker/netismaker-interview-service/test/interviewRunner.test.ts`:
  ```ts
  import { describe, expect, it, vi } from 'vitest';
  import { InterviewRunner } from '../src/runner/interviewRunner.js';
  import { freshClaim, resumeClaim } from './fixtures/claims.js';
  import { planCompleteStream, questionStream } from './fixtures/sdkMessages.js';

  function makeClient() {
    return {
      postQuestion: vi.fn().mockResolvedValue(undefined),
      postPlan: vi.fn().mockResolvedValue(undefined),
      fail: vi.fn().mockResolvedValue(undefined),
    };
  }
  // ensureRepo is injected so unit tests never touch git.
  const ensureRepo = vi.fn().mockResolvedValue(undefined);
  const deps = {
    superpowersPluginPath: '/sp/5.1.0',
    claudeCliPath: '/home/me/.local/bin/claude',
    quotaGuard: 5,
    ensureRepo,
  };

  describe('InterviewRunner', () => {
    it('fresh claim: prepares the repo at workDir, starts brainstorming, relays the question, posts session_id + cost', async () => {
      const client = makeClient();
      const fakeQuery = vi.fn(() => questionStream());
      const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

      await runner.run(freshClaim);

      // repo prepared at workDir before the turn
      expect(ensureRepo).toHaveBeenCalledWith(
        expect.objectContaining({ githubRepo: 'acme/widgets', githubBranch: 'main', workDir: freshClaim.workDir }),
      );
      // cwd = workDir; fresh => no resume option
      expect(fakeQuery.mock.calls[0][0].options.cwd).toBe(freshClaim.workDir);
      expect(fakeQuery.mock.calls[0][0].options.resume).toBeUndefined();
      expect(client.postQuestion).toHaveBeenCalledWith(
        42,
        expect.objectContaining({
          content: expect.stringContaining('Which columns'),
          claudeSessionId: 'sess-new-1',
          kind: 'question',
          costUsd: 0.12,
        }),
      );
      expect(client.postPlan).not.toHaveBeenCalled();
    });

    it('resume claim: passes options.resume + identical cwd, and injects lastAnswer into the prompt', async () => {
      const client = makeClient();
      let seenPrompt = '';
      const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ text: string }>; options: { resume?: string; cwd?: string } }) => {
        // eagerly drain prompt to assert the injected answer
        (async () => {
          for await (const p of args.prompt) seenPrompt += (p as { text?: string }).text ?? '';
        })();
        return questionStream();
      });
      const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

      await runner.run(resumeClaim);

      // repo re-prepared before resume too (resume is cwd-pinned)
      expect(ensureRepo).toHaveBeenCalledWith(
        expect.objectContaining({ workDir: resumeClaim.workDir }),
      );
      expect(fakeQuery.mock.calls[0][0].options.resume).toBe('sess-abc-123');
      expect(fakeQuery.mock.calls[0][0].options.cwd).toBe(resumeClaim.workDir);
      expect(seenPrompt).toContain('visible columns only');
    });

    it('plan completion: harvests design+plan and POSTs /plan instead of /question', async () => {
      const client = makeClient();
      const fakeQuery = vi.fn(() => planCompleteStream());
      const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

      await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });

      expect(client.postPlan).toHaveBeenCalledWith(
        42,
        expect.objectContaining({
          designMarkdown: expect.stringContaining('Export visible columns'),
          planMarkdown: expect.stringContaining('## File Structure'),
          costUsd: 0.31,
          durationMs: 5400,
        }),
      );
      expect(client.postQuestion).not.toHaveBeenCalled();
    });

    it('quota guard: when a turn shadow cost crosses the guard, reports fail and does not post a question', async () => {
      const client = makeClient();
      const fakeQuery = vi.fn(() => questionStream()); // turn shadow cost 0.12
      // guard 0.05 < this turn's 0.12 => trips on the single turn (seeded from 0; see note below)
      const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, quotaGuard: 0.05 } as never);

      await runner.run(freshClaim);

      expect(client.fail).toHaveBeenCalledWith(42, expect.stringContaining('quota'));
      expect(client.postQuestion).not.toHaveBeenCalled();
    });
  });
  ```

  > **Note on the quota seed:** the claim does NOT carry a `totalCostUsd` field (LOCKED CONTRACT `InterviewClaimResponse`). The runner seeds `CostGuard`'s prior total from `0`; the per-session shadow total is owned server-side via the `costUsd` posted on `/question` and `/plan`. The guard protects against a single runaway turn, so the test trips it with a tiny `quotaGuard` rather than relying on a prior-total field.

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- interviewRunner
  ```
  Expected: fails with `Cannot find module '../src/runner/interviewRunner.js'`.

- [ ] **Step 3: Implement interviewRunner.ts.**
  Create `netisMaker/netismaker-interview-service/src/runner/interviewRunner.ts`:
  ```ts
  import type { JavaApiClient } from '../api/javaClient.js';
  import type { SdkMessage, SdkQuery } from '../sdk/sdkAdapter.js';
  import type { InterviewClaimResponse } from '../types.js';
  import { buildOptions } from '../sdk/sessionOptions.js';
  import { QuotaGuardExceeded, CostGuard } from './costGuard.js';
  import { harvestPlan, HarvestError } from './planHarvest.js';
  import { relay } from './messageRelay.js';
  import { ensureRepo as defaultEnsureRepo, type RepoInput } from './repoPrepare.js';

  export interface RunnerDeps {
    superpowersPluginPath: string;
    /** Resolved claude CLI binary for subscription auth (Phase-0 spike 00b). */
    claudeCliPath: string;
    /** SHADOW per-session quota guard (turns/quota, NOT dollars). */
    quotaGuard: number;
    /** Injectable repo prepare (defaults to the real git clone/fetch). */
    ensureRepo?: (input: RepoInput) => Promise<void>;
  }

  /** One async-iterable user prompt for the turn. Fresh => kickoff text; resume => the injected answer. */
  async function* promptFor(claim: InterviewClaimResponse): AsyncIterable<{ type: 'user'; text: string }> {
    if (!claim.claudeSessionId) {
      yield {
        type: 'user',
        text:
          `I want to add a feature to the repo at ${claim.githubRepo} (branch ${claim.githubBranch}).\n` +
          `Title: ${claim.title}\nRequest: ${claim.description}\n\n` +
          'Use the brainstorming skill: read the project context, then ask me one clarifying question at a time.',
      };
    } else {
      yield { type: 'user', text: claim.lastAnswer ?? '' };
    }
  }

  /**
   * Runs exactly ONE turn for a claimed session, then returns. Resume-per-answer is the
   * DEFAULT loop: pending state comes from the claim payload (DB), never held in memory.
   * The repo is (re)prepared at claim.workDir before EVERY turn — fresh or resume — because
   * options.resume is cwd-pinned to that checkout (spike 02).
   */
  export class InterviewRunner {
    private readonly ensureRepo: (input: RepoInput) => Promise<void>;
    constructor(
      private readonly client: JavaApiClient,
      private readonly query: SdkQuery,
      private readonly deps: RunnerDeps,
    ) {
      this.ensureRepo = deps.ensureRepo ?? defaultEnsureRepo;
    }

    async run(claim: InterviewClaimResponse): Promise<void> {
      // CostGuard is seeded from 0: the claim carries no prior shadow total (LOCKED CONTRACT
      // InterviewClaimResponse has no totalCostUsd). The guard caps a single runaway turn;
      // server-side accumulates the per-session shadow total from /question + /plan costUsd.
      const guard = new CostGuard(this.deps.quotaGuard, 0);
      try {
        // CLONE: ensure the checkout exists at workDir before the (fresh OR resume) turn.
        await this.ensureRepo({
          githubRepo: claim.githubRepo,
          githubBranch: claim.githubBranch,
          workDir: claim.workDir,
        });

        const options = buildOptions({
          superpowersPluginPath: this.deps.superpowersPluginPath,
          workDir: claim.workDir,
          claudeCliPath: this.deps.claudeCliPath,
          claudeSessionId: claim.claudeSessionId,
        });
        const stream: AsyncIterable<SdkMessage> = this.query({
          prompt: promptFor(claim),
          options,
        });
        const result = await relay(stream);
        guard.add(result.costUsd);

        if (claim.currentPhase === 'writing-plans' && /Implementation Plan/m.test(result.assistantText)) {
          const harvest = harvestPlan(result.assistantText);
          // planJson is sent as a JSON STRING — Java stores it as text/JSONB; frontend parses on use.
          await this.client.postPlan(claim.sessionId, {
            designMarkdown: harvest.designMarkdown,
            planMarkdown: harvest.planMarkdown,
            planJson: JSON.stringify(harvest.planJson),
            costUsd: result.costUsd,
            durationMs: result.durationMs,
          });
          return;
        }

        await this.client.postQuestion(claim.sessionId, {
          content: result.assistantText,
          claudeSessionId: result.sessionId ?? claim.claudeSessionId ?? '',
          kind: 'question',
          costUsd: result.costUsd,
        });
      } catch (err) {
        if (err instanceof QuotaGuardExceeded) {
          await this.client.fail(claim.sessionId, err.message);
          return;
        }
        if (err instanceof HarvestError) {
          await this.client.fail(claim.sessionId, `plan harvest failed: ${err.message}`);
          return;
        }
        await this.client.fail(claim.sessionId, `interview turn failed: ${(err as Error).message}`);
      }
    }
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- interviewRunner
  ```
  Expected: `4 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "InterviewRunner: repo prep + one-turn resume-per-answer loop, quota guard, plan harvest dispatch"
  ```

> **Phase-0 spikes 01 & 03 deferred (by design):** The runner runs one SDK turn per claim and returns (resume-per-answer), so there is no long-lived idle session (spike 01 N/A) and concurrency = (interview workers) × (active-turn SDK subprocesses), naturally serialized by the shared subscription's rate limit — a capacity/quota tuning concern, not a blocker (spike 03 deferred to load test). The optional in-memory streaming hold remains a future optimization; the DB-backed resume path above is always safe and is the default.

---

### Task 13: Skill-dispatch wiring into the runner (splice when handoff not auto-fired)

**Files:**
- Modify `netisMaker/netismaker-interview-service/src/runner/interviewRunner.ts` (relay branch)
- Modify `netisMaker/netismaker-interview-service/test/interviewRunner.test.ts` (add handoff case)

- [ ] **Step 1: Write failing test — brainstorming turn that announces handoff but produced no plan triggers a follow-up splice turn.**
  Add to `netisMaker/netismaker-interview-service/test/interviewRunner.test.ts`:
  ```ts
  import { detectHandoff } from '../src/runner/skillDispatch.js';

  describe('InterviewRunner handoff shim', () => {
    it('on handoff announcement without a plan, re-queries the same session with the writing-plans splice', async () => {
      const client = makeClient();
      async function* handoffThenQuestion() {
        yield { type: 'system', subtype: 'init', session_id: 'sess-h' };
        yield {
          type: 'assistant',
          message: { content: [{ type: 'text', text: 'Spec approved. Invoke writing-plans skill now.' }] },
        };
        yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.05 }, duration_ms: 100 };
      }
      // first call returns handoff announcement; second (after splice) returns a plan
      const fakeQuery = vi
        .fn()
        .mockImplementationOnce(() => handoffThenQuestion())
        .mockImplementationOnce(() => planCompleteStream());
      const spliceRead = vi.fn().mockReturnValue('# Writing Plans\n\nbreak into tasks');
      const runner = new InterviewRunner(client as never, fakeQuery as never, {
        ...deps,
        spliceRead,
      } as never);

      await runner.run({ ...resumeClaim, currentPhase: 'brainstorming' });

      expect(detectHandoff('Invoke writing-plans skill now.')).toBe(true);
      expect(fakeQuery).toHaveBeenCalledTimes(2);
      // second query reuses the SAME session via resume and carries the spliced SKILL.md
      expect(fakeQuery.mock.calls[1][0].options.resume).toBe('sess-h');
      expect(client.postPlan).toHaveBeenCalledTimes(1);
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- interviewRunner
  ```
  Expected: fails — `expected fakeQuery to be called 2 times, but got 1` (the splice path does not exist yet).

- [ ] **Step 3: Wire the splice into the runner.**
  In `netisMaker/netismaker-interview-service/src/runner/interviewRunner.ts`, add imports at the top:
  ```ts
  import { buildWritingPlansSplice, detectHandoff } from './skillDispatch.js';
  ```
  Extend `RunnerDeps` with an optional injectable reader (defaults to real fs read inside skillDispatch):
  ```ts
  export interface RunnerDeps {
    superpowersPluginPath: string;
    claudeCliPath: string;
    quotaGuard: number;
    ensureRepo?: (input: RepoInput) => Promise<void>;
    spliceRead?: (path: string, enc: 'utf8') => string;
  }
  ```
  Replace the post-relay branch (the `if (claim.currentPhase === 'writing-plans' ...)` block plus the `postQuestion` call) with the handoff-aware version:
  ```ts
        let assistantText = result.assistantText;
        let sessionId = result.sessionId ?? claim.claudeSessionId ?? '';
        let costUsd = result.costUsd;
        let durationMs = result.durationMs;

        // Skill-dispatch shim: if brainstorming announced the writing-plans handoff
        // but no plan was produced, splice the writing-plans SKILL.md into the SAME
        // session and run one more turn (fallback when 'Skill' did not auto-fire).
        const handoff = detectHandoff(assistantText);
        const hasPlan = /Implementation Plan/m.test(assistantText);
        if (handoff && !hasPlan && sessionId) {
          const splice = buildWritingPlansSplice(this.deps.superpowersPluginPath, this.deps.spliceRead);
          const second = await relay(
            this.query({
              prompt: (async function* () {
                yield { type: 'user', text: splice };
              })(),
              options: buildOptions({
                superpowersPluginPath: this.deps.superpowersPluginPath,
                workDir: claim.workDir,
                claudeCliPath: this.deps.claudeCliPath,
                claudeSessionId: sessionId,
              }),
            }),
          );
          guard.add(second.costUsd);
          assistantText = second.assistantText;
          sessionId = second.sessionId ?? sessionId;
          costUsd = second.costUsd;
          durationMs = second.durationMs;
        }

        if (/Implementation Plan/m.test(assistantText)) {
          const harvest = harvestPlan(assistantText);
          // planJson is sent as a JSON STRING — Java stores it as text/JSONB; frontend parses on use.
          await this.client.postPlan(claim.sessionId, {
            designMarkdown: harvest.designMarkdown,
            planMarkdown: harvest.planMarkdown,
            planJson: JSON.stringify(harvest.planJson),
            costUsd,
            durationMs,
          });
          return;
        }

        await this.client.postQuestion(claim.sessionId, {
          content: assistantText,
          claudeSessionId: sessionId,
          kind: 'question',
          costUsd,
        });
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- interviewRunner
  ```
  Expected: all `interviewRunner` cases pass (5 total).

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "InterviewRunner: splice writing-plans SKILL.md when handoff not auto-fired"
  ```

---

### Task 14: ClaimLoop (poll, dispatch to runner, heartbeat, fail-report)

**Files:**
- Create `netisMaker/netismaker-interview-service/src/claimLoop.ts`
- Create `netisMaker/netismaker-interview-service/test/claimLoop.test.ts`

- [ ] **Step 1: Write failing test — loop claims, dispatches to runner, stops on signal.**
  Create `netisMaker/netismaker-interview-service/test/claimLoop.test.ts`:
  ```ts
  import { describe, expect, it, vi } from 'vitest';
  import { ClaimLoop } from '../src/claimLoop.js';
  import { freshClaim } from './fixtures/claims.js';

  describe('ClaimLoop', () => {
    it('claims work, hands it to the runner, then continues; stops when stop() called', async () => {
      const client = {
        claim: vi.fn().mockResolvedValueOnce(freshClaim).mockResolvedValue(null),
        heartbeat: vi.fn().mockResolvedValue(undefined),
        fail: vi.fn().mockResolvedValue(undefined),
      };
      const runner = { run: vi.fn().mockResolvedValue(undefined) };
      const loop = new ClaimLoop(client as never, runner as never, { pollIntervalMs: 1 });

      const p = loop.start();
      // let a few poll cycles happen
      await new Promise((r) => setTimeout(r, 10));
      loop.stop();
      await p;

      expect(runner.run).toHaveBeenCalledWith(freshClaim);
    });

    it('does not run the runner when claim returns null (no work)', async () => {
      const client = { claim: vi.fn().mockResolvedValue(null), heartbeat: vi.fn(), fail: vi.fn() };
      const runner = { run: vi.fn() };
      const loop = new ClaimLoop(client as never, runner as never, { pollIntervalMs: 1 });

      const p = loop.start();
      await new Promise((r) => setTimeout(r, 5));
      loop.stop();
      await p;

      expect(runner.run).not.toHaveBeenCalled();
    });

    it('reports fail to Java if the runner throws (does not crash the loop)', async () => {
      const client = {
        claim: vi.fn().mockResolvedValueOnce(freshClaim).mockResolvedValue(null),
        heartbeat: vi.fn(),
        fail: vi.fn().mockResolvedValue(undefined),
      };
      const runner = { run: vi.fn().mockRejectedValue(new Error('boom')) };
      const loop = new ClaimLoop(client as never, runner as never, { pollIntervalMs: 1 });

      const p = loop.start();
      await new Promise((r) => setTimeout(r, 10));
      loop.stop();
      await p;

      expect(client.fail).toHaveBeenCalledWith(freshClaim.sessionId, expect.stringContaining('boom'));
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- claimLoop
  ```
  Expected: fails with `Cannot find module '../src/claimLoop.js'`.

- [ ] **Step 3: Implement claimLoop.ts.**
  Create `netisMaker/netismaker-interview-service/src/claimLoop.ts`:
  ```ts
  import type { JavaApiClient } from './api/javaClient.js';
  import type { InterviewRunner } from './runner/interviewRunner.js';

  export interface LoopConfig {
    pollIntervalMs: number;
  }

  const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

  /**
   * Polls POST /worker/interviews/claim. For each claimed session, hands it to the
   * InterviewRunner (one turn). The runner itself reports /question, /plan, or /fail.
   * A throw escaping the runner is reported via /fail so the loop never dies.
   */
  export class ClaimLoop {
    private running = false;
    constructor(
      private readonly client: JavaApiClient,
      private readonly runner: InterviewRunner,
      private readonly cfg: LoopConfig,
    ) {}

    stop(): void {
      this.running = false;
    }

    async start(): Promise<void> {
      this.running = true;
      while (this.running) {
        let claim: Awaited<ReturnType<JavaApiClient['claim']>> = null;
        try {
          claim = await this.client.claim();
        } catch {
          claim = null; // transient API error: back off and retry next tick
        }
        if (claim) {
          try {
            await this.runner.run(claim);
          } catch (err) {
            await this.client.fail(claim.sessionId, `runner crashed: ${(err as Error).message}`);
          }
        }
        await sleep(this.cfg.pollIntervalMs);
      }
    }
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- claimLoop
  ```
  Expected: `3 passed`.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "ClaimLoop: poll claim endpoint, dispatch to runner, fail-report on crash"
  ```

---

### Task 15: Heartbeat ticker (background heartbeat during a turn)

**Files:**
- Create `netisMaker/netismaker-interview-service/src/runner/heartbeat.ts`
- Create `netisMaker/netismaker-interview-service/test/heartbeat.test.ts`

- [ ] **Step 1: Write failing test for heartbeat ticker start/stop.**
  Create `netisMaker/netismaker-interview-service/test/heartbeat.test.ts`:
  ```ts
  import { describe, expect, it, vi } from 'vitest';
  import { HeartbeatTicker } from '../src/runner/heartbeat.js';

  describe('HeartbeatTicker', () => {
    it('sends heartbeats on the interval until stopped', async () => {
      const client = { heartbeat: vi.fn().mockResolvedValue(undefined) };
      const ticker = new HeartbeatTicker(client as never, 42, 2);
      ticker.start();
      await new Promise((r) => setTimeout(r, 9));
      ticker.stop();
      const calls = client.heartbeat.mock.calls.length;
      expect(calls).toBeGreaterThanOrEqual(2);
      expect(client.heartbeat).toHaveBeenCalledWith(42);
      // no more after stop
      await new Promise((r) => setTimeout(r, 6));
      expect(client.heartbeat.mock.calls.length).toBe(calls);
    });
  });
  ```

- [ ] **Step 2: Run test, see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- heartbeat
  ```
  Expected: fails with `Cannot find module '../src/runner/heartbeat.js'`.

- [ ] **Step 3: Implement heartbeat.ts.**
  Create `netisMaker/netismaker-interview-service/src/runner/heartbeat.ts`:
  ```ts
  import type { JavaApiClient } from '../api/javaClient.js';

  /** Periodically POSTs /worker/interviews/{id}/heartbeat while a turn is in flight. */
  export class HeartbeatTicker {
    private timer: ReturnType<typeof setInterval> | null = null;
    constructor(
      private readonly client: Pick<JavaApiClient, 'heartbeat'>,
      private readonly sessionId: number,
      private readonly intervalMs: number,
    ) {}

    start(): void {
      if (this.timer) return;
      this.timer = setInterval(() => {
        void this.client.heartbeat(this.sessionId).catch(() => undefined);
      }, this.intervalMs);
    }

    stop(): void {
      if (this.timer) {
        clearInterval(this.timer);
        this.timer = null;
      }
    }
  }
  ```

- [ ] **Step 4: Run test, see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- heartbeat
  ```
  Expected: `1 passed`.

- [ ] **Step 5: Wire the ticker into the runner so it brackets the SDK turn.**
  In `netisMaker/netismaker-interview-service/src/runner/interviewRunner.ts`, add the import:
  ```ts
  import { HeartbeatTicker } from './heartbeat.js';
  ```
  Extend `RunnerDeps`:
  ```ts
    heartbeatIntervalMs?: number;
  ```
  At the very start of `run(claim)`, before the `try`, start the ticker, and stop it in a `finally`:
  ```ts
      const ticker = new HeartbeatTicker(this.client, claim.sessionId, this.deps.heartbeatIntervalMs ?? 15000);
      ticker.start();
      try {
        // ... existing body (CostGuard through postQuestion/postPlan) ...
      } catch (err) {
        // ... existing catch ...
      } finally {
        ticker.stop();
      }
  ```

- [ ] **Step 6: Run the runner tests, confirm still green.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test -- interviewRunner heartbeat
  ```
  Expected: all pass (heartbeat 1 + interviewRunner 5).

- [ ] **Step 7: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "HeartbeatTicker: background heartbeat bracketing each interview turn"
  ```

---

### Task 16: Entrypoint wiring + full suite green

**Files:**
- Create `netisMaker/netismaker-interview-service/src/index.ts`
- Modify `netisMaker/netismaker-interview-service/test/scaffold.test.ts` (remove sentinel)

- [ ] **Step 1: Write index.ts (composition root — no logic, just wiring).**
  Create `netisMaker/netismaker-interview-service/src/index.ts`:
  ```ts
  import { loadConfig } from './config.js';
  import { JavaApiClient } from './api/javaClient.js';
  import { realQuery } from './sdk/sdkAdapter.js';
  import { resolveClaudeCli } from './sdk/claudeCli.js';
  import { InterviewRunner } from './runner/interviewRunner.js';
  import { ClaimLoop } from './claimLoop.js';

  async function main(): Promise<void> {
    const cfg = loadConfig();
    // Subscription auth: resolve the local claude CLI (Phase-0 spike 00b). No ANTHROPIC_API_KEY.
    const claudeCliPath = resolveClaudeCli(cfg.claudeCliPath);
    const client = new JavaApiClient(cfg);
    const runner = new InterviewRunner(client, realQuery, {
      superpowersPluginPath: cfg.superpowersPluginPath,
      claudeCliPath,
      quotaGuard: cfg.quotaGuard,
      heartbeatIntervalMs: cfg.heartbeatIntervalMs,
    });
    const loop = new ClaimLoop(client, runner, { pollIntervalMs: cfg.claimPollIntervalMs });

    const shutdown = (): void => loop.stop();
    process.on('SIGINT', shutdown);
    process.on('SIGTERM', shutdown);

    // eslint-disable-next-line no-console
    console.log(`[interview-service] worker=${cfg.workerId} cli=${claudeCliPath} polling ${cfg.apiBaseUrl}`);
    await loop.start();
  }

  void main();
  ```

- [ ] **Step 2: Remove the scaffold sentinel test (now redundant).**
  Delete `netisMaker/netismaker-interview-service/test/scaffold.test.ts`:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && rm test/scaffold.test.ts
  ```

- [ ] **Step 3: Run the full suite + typecheck.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm test && npm run build
  ```
  Expected: all suites pass (config 3, claudeCli 3, javaClient 4, sessionOptions 6, permissions 6, costGuard 3, messageRelay 1, planHarvest 4, skillDispatch 3, repoPrepare 2, interviewRunner 5, claimLoop 3, heartbeat 1 = 44 tests); `tsc` exits 0 with no errors.

- [ ] **Step 4: Lint.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npm run lint
  ```
  Expected: zero errors (auto-fix applies formatting).

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "entrypoint: wire config->client->runner->ClaimLoop; full suite green"
  ```

---

### Task 17: Phase-0 spike harness (regression re-check — keep findings current)

> Phase-0 spikes (00b, 02, 04) already PASSED and their corrections are baked into Tasks 5, 10, 11, 12 (see `docs/superpowers/plans/00-spikes-findings.md`). This task keeps a runnable harness so the SDK option names and cwd-pinned resume behavior can be re-verified against a new `@anthropic-ai/claude-agent-sdk` version. Run it against the **real** SDK using **subscription auth via the local `claude` CLI** (no `ANTHROPIC_API_KEY`), not the mock.

**Files:**
- Create `netisMaker/netismaker-interview-service/scripts/spike.ts`
- Create `netisMaker/netismaker-interview-service/SPIKES.md`

- [ ] **Step 1: Write the spike script that exercises the four §13 unknowns.**
  Create `netisMaker/netismaker-interview-service/scripts/spike.ts`:
  ```ts
  /**
   * Phase-0 spike re-check. Subscription auth via the local claude CLI (no ANTHROPIC_API_KEY).
   * Run against a small temp repo (must already be a git checkout at the same path used for resume):
   *   SUPERPOWERS_PLUGIN_PATH=/Users/.../superpowers/5.1.0 \
   *   node --import tsx scripts/spike.ts /tmp/spike-repo
   * Records evidence for: (00b) subscription auth, (02) cwd-pinned resume, (04) skill auto-trigger.
   */
  import { realQuery } from '../src/sdk/sdkAdapter.js';
  import { buildOptions } from '../src/sdk/sessionOptions.js';
  import { resolveClaudeCli } from '../src/sdk/claudeCli.js';
  import { relay } from '../src/runner/messageRelay.js';

  const workDir = process.argv[2] ?? process.cwd();
  const superpowersPluginPath = process.env.SUPERPOWERS_PLUGIN_PATH ?? '';
  const claudeCliPath = resolveClaudeCli(process.env.CLAUDE_CLI);

  async function* prompt(text: string): AsyncIterable<{ type: 'user'; text: string }> {
    yield { type: 'user', text };
  }

  async function spike(): Promise<void> {
    // Spike 04: does the brainstorming skill auto-fire and reach the writing-plans handoff?
    const first = await relay(
      realQuery({
        prompt: prompt(
          'Use the brainstorming skill for a tiny feature: add a /health endpoint. Ask one question, then on my "go" produce the spec and transition to writing-plans.',
        ),
        options: buildOptions({ superpowersPluginPath, workDir, claudeCliPath, claudeSessionId: null }),
      }),
    );
    // eslint-disable-next-line no-console
    console.log('SPIKE init session_id:', first.sessionId, 'shadow cost:', first.costUsd);

    // Spike 02: resume the captured session_id from a SEPARATE query call with the IDENTICAL cwd.
    if (first.sessionId) {
      const second = await relay(
        realQuery({
          prompt: prompt('go'),
          options: buildOptions({
            superpowersPluginPath,
            workDir,
            claudeCliPath,
            claudeSessionId: first.sessionId,
          }),
        }),
      );
      // eslint-disable-next-line no-console
      console.log('SPIKE resume reached plan?:', /Implementation Plan/m.test(second.assistantText));
    }
  }

  void spike();
  ```

- [ ] **Step 2: Run the spike (manual, subscription auth — no API key) and capture output.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && mkdir -p /tmp/spike-repo && git -C /tmp/spike-repo init -q && SUPERPOWERS_PLUGIN_PATH=/Users/micthebick/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0 node --import tsx scripts/spike.ts /tmp/spike-repo
  ```
  Expected: prints a non-null `init session_id`, a non-zero `shadow cost`, and `resume reached plan?: true`. The resume call MUST use the IDENTICAL `workDir` (cwd) as the first call — the session store is cwd-hashed (spike 02). If `resume` errors with `No conversation found with session ID`, the cwd differed; fix the caller, not `buildOptions`.

- [ ] **Step 3: Record findings in SPIKES.md.**
  Create `netisMaker/netismaker-interview-service/SPIKES.md` and fill each section with the observed result (PASS/FAIL + notes), mirroring `docs/superpowers/plans/00-spikes-findings.md`: (00b) subscription auth via `pathToClaudeCodeExecutable` (`apiKeySource:"none"`, shadow cost only), (02) cwd-pinned cross-process resume, (04) superpowers skill load + `Skill` tool dispatch + brainstorming→writing-plans handoff. Spikes 01 (idle) and 03 (concurrency) are N/A / deferred by the resume-per-answer design. Each FAIL must name the production module to adjust (`sessionOptions.ts` for option names, `claudeCli.ts` for binary resolution, `skillDispatch.ts` for the splice path, `interviewRunner.ts` for the turn model).

- [ ] **Step 4: Commit the spike harness + findings.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && git add -A && git commit -m "Phase-0 spike harness + SPIKES.md findings (idle/resume/concurrency/skill auto-trigger)"
  ```
