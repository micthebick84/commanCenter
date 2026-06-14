# 대화형 분석 스파이크 (Conversational Analysis SDK Spikes) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. NOTE: These are *exploratory* spikes — the per-step rhythm is "write script → run → observe → record finding" rather than strict red/green TDD. There is no production code to keep; every script under `spikes/` is throwaway. The only durable artifact is `docs/superpowers/plans/00-spikes-findings.md`.

**Goal:** Before building `netismaker-interview-service` (the TS/Node Claude Agent SDK sidecar that drives superpowers `brainstorming → writing-plans` headlessly), empirically validate the four load-bearing SDK assumptions called out in the design spec §13 and §15. Each spike is a tiny standalone TS script that exercises exactly one risky assumption and emits a clear **PASS/FAIL** observation. The findings then either confirm the §9 design ("resume-per-answer + DB self-persistence + single-host pinning") or trigger a documented design adjustment *before* any Java entity, migration, or controller is written.

**Architecture:** One throwaway scratch project at `/Users/micthebick/IdeaProjects/netisMaker/spikes/` (its own `package.json`, NOT wired into the Gradle build, NOT committed to the Spring source tree). It depends only on `@anthropic-ai/claude-agent-sdk` and `tsx` for direct TS execution. Four spike scripts map 1:1 to spec §13 items (1) idle timeout, (2) cross-process crash-and-resume re-drive, (3) concurrent-session footprint, (4) superpowers skill auto-trigger equivalence. Each writes a machine-readable `*.result.json` next to itself so the final "Record findings & decide" task can collate raw evidence into the findings doc. All spikes pin the SDK to the verified-installed version **0.2.117** and use the **v1 stable streaming-input form** `query({ prompt: AsyncIterable<SDKUserMessage>, options })` (NOT `unstable_v2_*`, per spec §9). The superpowers plugin is loaded from the on-disk cache path `~/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0` (verified present, contains `skills/brainstorming` and `skills/writing-plans`).

**Tech Stack:** Node.js 24 (verified `v24.14.1`) / TypeScript via `tsx` / `@anthropic-ai/claude-agent-sdk@0.2.117` (verified installed) / superpowers plugin `5.1.0` (verified at `~/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0`) / `ANTHROPIC_API_KEY` auth (API key only — subscription/OAuth NOT supported per spec §15). macOS (darwin), single host.

**Spec:** `docs/superpowers/specs/2026-06-13-conversational-analysis-design.md` (esp. §9 SDK service detail, §13 spikes, §15 validation basis)

---

## File Structure

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `spikes/package.json` | 스파이크 전용 의존성(`@anthropic-ai/claude-agent-sdk@0.2.117`, `tsx`, `typescript`) — Gradle 빌드와 무관 | 신규 |
| `spikes/tsconfig.json` | ESM + `tsx` 실행용 최소 TS 설정 | 신규 |
| `spikes/.gitignore` | `node_modules/`, `*.result.json`, `*.session.json`, scratch 디렉터리 무시 | 신규 |
| `spikes/lib/sdk-common.ts` | 공통 헬퍼: 플러그인 경로 상수, `system/init`에서 `session_id` 캡처, `result` 메시지 비용/시간 수집, deferred 프롬프트(수동 yield) 큐 | 신규 |
| `spikes/01-idle-timeout.ts` | 스파이크 1: 스트리밍 입력 세션을 30분+ idle 후 재개 — idle 타임아웃 관찰 | 신규 |
| `spikes/02-cross-process-resume.ts` | 스파이크 2: 프로세스 A가 `session_id`를 JSON으로 영속 후 kill, 프로세스 B(다른 cwd)가 자체 상태로 resume·re-drive | 신규 |
| `spikes/02-driver.sh` | 스파이크 2 오케스트레이션: A 실행 → kill → B(다른 cwd) 실행 | 신규 |
| `spikes/03-concurrency.ts` | 스파이크 3: idle 세션 N개 동시 기동, 서브프로세스/메모리/레이트리밋 풋프린트 측정 | 신규 |
| `spikes/04-skill-handoff.ts` | 스파이크 4: superpowers 플러그인 로드 + `brainstorming → writing-plans` 핸드오프 실발화 검증 | 신규 |
| `docs/superpowers/plans/00-spikes-findings.md` | 4개 스파이크 PASS/FAIL 결과 + 설계 조정 결정 | 신규 |

---

## Task 1: 스파이크 스캐폴드 (scratch 프로젝트 + 공통 헬퍼)

목표: 4개 스파이크가 공유하는 최소 TS/Node 환경과 헬퍼를 만든다. 이 태스크가 끝나면 `tsx`로 빈 query 한 번이 실제로 돈다(= API 키/SDK/플러그인 경로가 살아있음).

**Files:**
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/package.json`
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/tsconfig.json`
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/.gitignore`
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/lib/sdk-common.ts`
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/00-smoke.ts`

- [ ] **Step 1: `package.json` 작성 (SDK 버전 핀 + tsx)**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/package.json`:

```json
{
  "name": "netismaker-interview-spikes",
  "private": true,
  "type": "module",
  "description": "Throwaway spikes validating Claude Agent SDK assumptions for netismaker-interview-service. NOT part of the Gradle build.",
  "scripts": {
    "smoke": "tsx 00-smoke.ts",
    "spike1": "tsx 01-idle-timeout.ts",
    "spike2:a": "tsx 02-cross-process-resume.ts a",
    "spike2:b": "tsx 02-cross-process-resume.ts b",
    "spike3": "tsx 03-concurrency.ts",
    "spike4": "tsx 04-skill-handoff.ts"
  },
  "dependencies": {
    "@anthropic-ai/claude-agent-sdk": "0.2.117"
  },
  "devDependencies": {
    "tsx": "4.19.2",
    "typescript": "5.7.2"
  }
}
```

- [ ] **Step 2: `tsconfig.json` 작성 (ESM, NodeNext)**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "resolveJsonModule": true,
    "noEmit": true,
    "lib": ["ES2022"]
  },
  "include": ["*.ts", "lib/*.ts"]
}
```

- [ ] **Step 3: `.gitignore` 작성 (스파이크 산출물은 커밋 금지)**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/.gitignore`:

```gitignore
node_modules/
*.result.json
*.session.json
scratch/
*.log
```

- [ ] **Step 4: 공통 헬퍼 `lib/sdk-common.ts` 작성**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/lib/sdk-common.ts`:

```ts
import type { SDKMessage, SDKUserMessage } from '@anthropic-ai/claude-agent-sdk';

/** Verified on-disk superpowers plugin (5.1.0) — contains skills/brainstorming + skills/writing-plans. */
export const SUPERPOWERS_PLUGIN_PATH =
  `${process.env.HOME}/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0`;

/** Capture session_id from the system/init message (per SDK 0.2.117 SDKSystemMessage). */
export function captureSessionId(msg: SDKMessage): string | undefined {
  if (msg.type === 'system' && msg.subtype === 'init') return msg.session_id;
  return undefined;
}

/** Pull plain assistant text out of an SDKAssistantMessage's content blocks. */
export function assistantText(msg: SDKMessage): string | undefined {
  if (msg.type !== 'assistant') return undefined;
  const blocks = msg.message.content;
  if (typeof blocks === 'string') return blocks;
  return blocks
    .filter((b: any) => b.type === 'text')
    .map((b: any) => b.text)
    .join('\n')
    .trim() || undefined;
}

/** Summarize the result message: cost, duration, turns, skills, plugins. */
export function summarizeResult(msg: SDKMessage): Record<string, unknown> | undefined {
  if (msg.type !== 'result') return undefined;
  return {
    subtype: msg.subtype,
    is_error: msg.is_error,
    total_cost_usd: (msg as any).total_cost_usd,
    duration_ms: (msg as any).duration_ms,
    num_turns: (msg as any).num_turns,
    session_id: msg.session_id,
  };
}

/**
 * A manually-driven prompt queue for the v1 stable streaming-input form.
 * `query({ prompt: pump(), options })`. The generator AWAITS the next pushed
 * message — between pushes it blocks (no yield), which is exactly how we hold
 * a session idle. Call `push(text)` to feed the next user turn, `end()` to close.
 */
export class PromptPump {
  private resolvers: ((m: IteratorResult<SDKUserMessage>) => void)[] = [];
  private queue: IteratorResult<SDKUserMessage>[] = [];

  push(text: string) {
    const m: SDKUserMessage = {
      type: 'user',
      message: { role: 'user', content: text },
      parent_tool_use_id: null,
      session_id: '',
    } as unknown as SDKUserMessage;
    this.deliver({ value: m, done: false });
  }
  end() {
    this.deliver({ value: undefined as any, done: true });
  }
  private deliver(r: IteratorResult<SDKUserMessage>) {
    const resolve = this.resolvers.shift();
    if (resolve) resolve(r);
    else this.queue.push(r);
  }
  async *stream(): AsyncGenerator<SDKUserMessage> {
    while (true) {
      const r: IteratorResult<SDKUserMessage> =
        this.queue.shift() ??
        (await new Promise<IteratorResult<SDKUserMessage>>((res) => this.resolvers.push(res)));
      if (r.done) return;
      yield r.value;
    }
  }
}

/** Write a machine-readable result file next to the spike for the findings task. */
export async function writeResult(name: string, data: Record<string, unknown>) {
  const fs = await import('node:fs/promises');
  const path = new URL(`../${name}`, import.meta.url).pathname;
  await fs.writeFile(path, JSON.stringify(data, null, 2));
  console.log(`[result] wrote ${name}:`, JSON.stringify(data));
}
```

- [ ] **Step 5: 스모크 스크립트 `00-smoke.ts` 작성 (단일 string 프롬프트, 플러그인 미로드)**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/00-smoke.ts`:

```ts
import { query } from '@anthropic-ai/claude-agent-sdk';
import { captureSessionId, summarizeResult, writeResult } from './lib/sdk-common.js';

async function main() {
  if (!process.env.ANTHROPIC_API_KEY) {
    throw new Error('ANTHROPIC_API_KEY is required (subscription/OAuth not supported per spec §15)');
  }
  let sessionId: string | undefined;
  let result: Record<string, unknown> | undefined;
  const q = query({
    prompt: 'Reply with exactly the word: PONG',
    options: { maxTurns: 1, allowedTools: [] },
  });
  for await (const msg of q) {
    sessionId ??= captureSessionId(msg);
    result ??= summarizeResult(msg);
  }
  const pass = !!sessionId && !!result && (result as any).is_error === false;
  await writeResult('00-smoke.result.json', { pass, sessionId, result });
  console.log(pass ? 'SMOKE PASS' : 'SMOKE FAIL');
  process.exit(pass ? 0 : 1);
}
main().catch((e) => {
  console.error('SMOKE FAIL', e);
  process.exit(1);
});
```

- [ ] **Step 6: 의존성 설치**

Run:
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/spikes && npm install
```
Expected: `node_modules/@anthropic-ai/claude-agent-sdk` resolves to `0.2.117`. Confirm:
```bash
cat /Users/micthebick/IdeaProjects/netisMaker/spikes/node_modules/@anthropic-ai/claude-agent-sdk/package.json | grep '"version"'
```
Expected output contains: `"version": "0.2.117"`

- [ ] **Step 7: 스모크 실행 (환경/SDK/키 생존 확인)**

Run:
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/spikes && ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" npm run smoke
```
Expected: stdout ends with `SMOKE PASS`, and `00-smoke.result.json` exists with `"pass": true`, a non-empty `sessionId`, and `result.is_error: false`. If you see `SMOKE FAIL` with an auth error, stop and confirm `ANTHROPIC_API_KEY` is exported in your shell — every later spike depends on this. Record the observed `total_cost_usd` from the smoke as the baseline single-turn cost.

- [ ] **Step 8: Commit (scaffold only — node_modules/results git-ignored)**

```bash
cd /Users/micthebick/IdeaProjects/netisMaker && \
git add spikes/package.json spikes/tsconfig.json spikes/.gitignore spikes/lib/sdk-common.ts spikes/00-smoke.ts && \
git commit -m "spike: scratch SDK 환경 스캐폴드 + 스모크 (claude-agent-sdk 0.2.117)"
```

---

## Task 2: 스파이크 1 — 30분+ idle 후 스트리밍 세션 재개 (idle 타임아웃 관찰)

목표(spec §13.1, §15 "무기한 idle 타임아웃"): v1 스트리밍 입력 세션에서, 프롬프트 제너레이터가 다음 yield를 `await`로 막아 무기한 대기할 수 있다는 §15 주장을 30분+ 실측한다. 관찰 대상: idle 동안 SDK 서브프로세스가 살아있는가 / 깨운 후 같은 세션에서 추가 턴이 정상 진행되는가 / 어떤 타임아웃(소켓/스트림/API)이 먼저 터지는가.

> PASS = 30분 idle 후 두 번째 턴이 같은 `session_id`로 정상 응답. FAIL = idle 중 또는 깨운 직후 에러/프로세스 종료. 어느 쪽이든 정확한 경과시간·에러 메시지를 기록한다. (이 결과가 §9 "in-memory streaming hold(선택 최적화)" 채택 여부를 가른다.)

**Files:**
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/01-idle-timeout.ts`

- [ ] **Step 1: 스파이크 1 스크립트 작성 (`PromptPump`로 idle hold)**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/01-idle-timeout.ts`:

```ts
import { query } from '@anthropic-ai/claude-agent-sdk';
import { PromptPump, captureSessionId, assistantText, summarizeResult, writeResult } from './lib/sdk-common.js';

// Override idle minutes via env for a fast dry-run, default the real 31-minute soak.
const IDLE_MIN = Number(process.env.SPIKE_IDLE_MIN ?? '31');

async function main() {
  const startedAt = Date.now();
  const pump = new PromptPump();
  let sessionId: string | undefined;
  const transcript: string[] = [];
  let firstAnswered = false;
  let secondAnswered = false;
  let idleError: string | undefined;

  const q = query({
    prompt: pump.stream(),
    options: {
      maxTurns: 4,
      allowedTools: [],
      stderr: (d) => process.stderr.write(`[stderr] ${d}`),
    },
  });

  // First turn now.
  pump.push('Turn 1: reply with the single word READY.');

  // Schedule the second turn AFTER the idle window. The for-await loop keeps the
  // subprocess alive; the pump simply doesn't yield until we push again.
  const wakeTimer = setTimeout(() => {
    console.log(`[idle] ${IDLE_MIN} min elapsed, pushing turn 2`);
    pump.push('Turn 2: reply with the single word ALIVE.');
  }, IDLE_MIN * 60_000);
  // Heartbeat so a 31-min run shows liveness in logs.
  const beat = setInterval(
    () => console.log(`[alive] +${Math.round((Date.now() - startedAt) / 1000)}s`),
    60_000,
  );

  try {
    for await (const msg of q) {
      sessionId ??= captureSessionId(msg);
      const text = assistantText(msg);
      if (text) {
        transcript.push(text);
        if (!firstAnswered && /READY/i.test(text)) firstAnswered = true;
        else if (firstAnswered && /ALIVE/i.test(text)) {
          secondAnswered = true;
          pump.end(); // got what we needed — close the stream
        }
      }
      const r = summarizeResult(msg);
      if (r) console.log('[result]', JSON.stringify(r));
    }
  } catch (e: any) {
    idleError = `${e?.name ?? 'Error'}: ${e?.message ?? String(e)}`;
    console.error('[idle-error]', idleError);
  } finally {
    clearTimeout(wakeTimer);
    clearInterval(beat);
  }

  const elapsedMs = Date.now() - startedAt;
  const pass = firstAnswered && secondAnswered && !idleError;
  await writeResult('01-idle-timeout.result.json', {
    pass,
    idleMinutesConfigured: IDLE_MIN,
    elapsedMs,
    sessionId,
    firstAnswered,
    secondAnswered,
    idleError: idleError ?? null,
    transcript,
  });
  console.log(pass ? 'SPIKE1 PASS' : 'SPIKE1 FAIL');
  process.exit(pass ? 0 : 1);
}
main().catch((e) => { console.error('SPIKE1 FAIL', e); process.exit(1); });
```

- [ ] **Step 2: 빠른 dry-run (1분 idle) — 로직/타이머 정상 확인**

Run:
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/spikes && \
SPIKE_IDLE_MIN=1 ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" npm run spike1
```
Expected: 약 1분 후 `[idle] 1 min elapsed, pushing turn 2` 로그, 그 뒤 두 번째 답변, 마지막 줄 `SPIKE1 PASS`. `01-idle-timeout.result.json`에 `firstAnswered:true, secondAnswered:true, idleError:null`. 이 단계는 스크립트가 idle-then-continue를 실제로 수행함을 증명할 뿐(타임아웃 관찰 아님).

- [ ] **Step 3: 본 측정 (31분 idle 소크) — 백그라운드 실행**

Run (백그라운드, 35분 timeout):
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/spikes && \
SPIKE_IDLE_MIN=31 ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
npm run spike1 > 01-idle-timeout.run.log 2>&1
```
Observe (관찰): `[alive] +60s`, `[alive] +120s` ... 매분 하트비트가 31분간 끊기지 않는지 / 31분 시점 `[idle]` 로그 후 두 번째 턴이 성공하는지 / 또는 중간에 `[idle-error]`가 어떤 메시지(예: 소켓 타임아웃, `terminated`, API timeout)로, 몇 초에 터지는지.

- [ ] **Step 4: 결과 관찰 & 기록**

Run:
```bash
cat /Users/micthebick/IdeaProjects/netisMaker/spikes/01-idle-timeout.result.json
```
관찰·기록할 값: `pass`, `elapsedMs`(≈ 31분이면 idle이 유지된 것), `secondAnswered`, `idleError`. **PASS면** §9의 "answer마다 resume" 기본 설계는 그대로 두되 "in-memory streaming hold(짧은 적극 응답 구간 최적화)"가 실제로 안전하다는 근거가 된다. **FAIL이면** idle hold를 버리고 *모든* 답변을 resume-per-answer로만 처리해야 한다 — 관찰한 타임아웃 한계(분)를 findings에 적는다. (DB 저장 → process.exit 직후 stdout/`.run.log`의 마지막 `[idle-error]`/`SPIKE1 ...` 줄을 같이 보관.)

- [ ] **Step 5: Commit**

```bash
cd /Users/micthebick/IdeaProjects/netisMaker && \
git add spikes/01-idle-timeout.ts && \
git commit -m "spike1: 30분+ idle 후 스트리밍 세션 재개 — idle 타임아웃 관찰 스크립트"
```

---

## Task 3: 스파이크 2 — 교차 프로세스 crash-and-resume + 자체 영속 상태로 re-drive

목표(spec §13.2, §15 "교차-프로세스 resume + 크래시 시 펜딩 재구동"): 프로세스 A가 brainstorming 세션을 한 턴 돌려 첫 질문을 받고 `session_id` + 펜딩 질문/우리 상태를 **자체 JSON**에 저장한 뒤 자신을 kill한다. 그 다음 **다른 cwd**의 프로세스 B가 그 JSON만 읽어 `options.resume`으로 재진입하고, A가 받은 펜딩 질문에 대한 답을 주입해 다음 턴을 받아낸다. SDK의 펜딩 콜백에 의존하지 않고 우리 DB(여기선 JSON)만으로 re-drive가 되는지가 핵심.

> PASS = B가 A의 `session_id`로 resume 성공 + A의 펜딩 질문에 대한 답이 반영된 후속 응답 수신(같은/포크된 세션 컨텍스트 유지). FAIL = resume 거부, 세션 미발견, 또는 컨텍스트 유실. cwd가 달라도 되는지(`resume`이 로컬 세션 스토어 경로 의존인지)를 명시 관찰 — §9 "단일 호스트/공유 볼륨 고정" 근거.

**Files:**
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/02-cross-process-resume.ts`
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/02-driver.sh`

- [ ] **Step 1: 스파이크 2 스크립트 작성 (모드 a=시작·영속·kill, b=resume·re-drive)**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/02-cross-process-resume.ts`:

```ts
import { query } from '@anthropic-ai/claude-agent-sdk';
import { PromptPump, captureSessionId, assistantText, summarizeResult, writeResult } from './lib/sdk-common.js';
import { readFile, writeFile } from 'node:fs/promises';

// We deliberately persist OUR OWN state file (stands in for com.interview_session
// row: claude_session_id + pending question + the answer we will inject).
const STATE_FILE = `${process.cwd()}/02.session.json`; // mode 'a' writes here using a FIXED abs path passed via env
const ABS_STATE = process.env.SPIKE_STATE_FILE ?? STATE_FILE;

type State = { sessionId: string; pendingQuestion: string; answer: string };

/** MODE A: start brainstorming-like session, capture first question + session_id, persist, exit hard. */
async function modeA() {
  const pump = new PromptPump();
  let sessionId: string | undefined;
  let firstQuestion: string | undefined;
  const q = query({
    prompt: pump.stream(),
    options: { maxTurns: 2, allowedTools: [], cwd: process.cwd() },
  });
  pump.push(
    'You are interviewing me to design a feature. Ask me ONE concise clarifying question and then STOP. Do not answer it yourself.',
  );
  for await (const msg of q) {
    sessionId ??= captureSessionId(msg);
    const text = assistantText(msg);
    if (text && !firstQuestion) {
      firstQuestion = text;
      break; // got the pending question; stop reading
    }
  }
  if (!sessionId || !firstQuestion) throw new Error('modeA: failed to capture session_id/question');
  const state: State = {
    sessionId,
    pendingQuestion: firstQuestion,
    answer: 'Add a CSV export button to the report toolbar; admins only.',
  };
  await writeFile(ABS_STATE, JSON.stringify(state, null, 2));
  console.log(`[A] persisted state -> ${ABS_STATE}`);
  console.log(`[A] session_id=${sessionId} cwd=${process.cwd()}`);
  // Simulate a crash: hard-kill this process WITHOUT graceful query teardown.
  process.kill(process.pid, 'SIGKILL');
}

/** MODE B: from a DIFFERENT cwd, read state, resume, inject the persisted answer, get next turn. */
async function modeB() {
  const raw = await readFile(ABS_STATE, 'utf8');
  const state: State = JSON.parse(raw);
  console.log(`[B] resuming session_id=${state.sessionId} from cwd=${process.cwd()}`);

  const pump = new PromptPump();
  let resumedSessionId: string | undefined;
  let nextAnswer: string | undefined;
  let resumeError: string | undefined;

  try {
    const q = query({
      prompt: pump.stream(),
      options: {
        resume: state.sessionId,
        maxTurns: 2,
        allowedTools: [],
        cwd: process.cwd(), // intentionally DIFFERENT from mode A
        stderr: (d) => process.stderr.write(`[B-stderr] ${d}`),
      },
    });
    // Inject the human answer to the question A captured.
    pump.push(
      `Here is my answer to your question "${state.pendingQuestion.slice(0, 120)}...": ${state.answer}. ` +
        `Confirm you understood by restating my feature in one sentence starting with "FEATURE:".`,
    );
    for await (const msg of q) {
      resumedSessionId ??= captureSessionId(msg);
      const text = assistantText(msg);
      if (text && /FEATURE:/i.test(text)) { nextAnswer = text; pump.end(); }
      const r = summarizeResult(msg);
      if (r) console.log('[B-result]', JSON.stringify(r));
    }
  } catch (e: any) {
    resumeError = `${e?.name ?? 'Error'}: ${e?.message ?? String(e)}`;
    console.error('[B-resume-error]', resumeError);
  }

  const contextCarried = !!nextAnswer && /csv|export/i.test(nextAnswer ?? '');
  const pass = !resumeError && !!nextAnswer && contextCarried;
  await writeResult('02-cross-process-resume.result.json', {
    pass,
    persistedSessionId: state.sessionId,
    resumedSessionId: resumedSessionId ?? null,
    crossCwd: true,
    contextCarried,
    resumeError: resumeError ?? null,
    nextAnswer: nextAnswer ?? null,
  });
  console.log(pass ? 'SPIKE2 PASS' : 'SPIKE2 FAIL');
  process.exit(pass ? 0 : 1);
}

const mode = process.argv[2];
(mode === 'a' ? modeA() : modeB()).catch((e) => {
  console.error('SPIKE2 FAIL', e);
  process.exit(1);
});
```

- [ ] **Step 2: 드라이버 스크립트 작성 (A 실행 → kill → B를 다른 cwd에서 실행)**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/02-driver.sh`:

```bash
#!/usr/bin/env bash
set -uo pipefail
SPIKES="/Users/micthebick/IdeaProjects/netisMaker/spikes"
STATE="$SPIKES/02.session.json"
B_CWD="$(mktemp -d /tmp/spike2-b.XXXXXX)"   # a DIFFERENT cwd for process B
export SPIKE_STATE_FILE="$STATE"
export ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:?ANTHROPIC_API_KEY must be set}"

rm -f "$STATE"
echo "=== Process A (cwd=$SPIKES) ==="
( cd "$SPIKES" && npx tsx 02-cross-process-resume.ts a )
echo "A exit code: $? (137 = SIGKILL, expected)"

if [[ ! -f "$STATE" ]]; then echo "FAIL: A did not persist state"; exit 1; fi
echo "=== state persisted ==="; cat "$STATE"

echo "=== Process B (DIFFERENT cwd=$B_CWD) ==="
( cd "$B_CWD" && npx tsx "$SPIKES/02-cross-process-resume.ts" b )
B_EXIT=$?
echo "B exit code: $B_EXIT"
exit "$B_EXIT"
```

- [ ] **Step 3: 드라이버 실행 권한 부여 + 실행**

Run:
```bash
chmod +x /Users/micthebick/IdeaProjects/netisMaker/spikes/02-driver.sh && \
/Users/micthebick/IdeaProjects/netisMaker/spikes/02-driver.sh
```
Expected (PASS 경로): `A exit code: 137`(SIGKILL로 crash 시뮬레이션됨), `=== state persisted ===` 아래 `sessionId`/`pendingQuestion`/`answer` JSON, 그리고 B가 다른 cwd(`/tmp/spike2-b.*`)에서 `[B] resuming session_id=...` → `FEATURE: ...` 응답 → `SPIKE2 PASS`.

- [ ] **Step 4: 결과 관찰 & 기록**

Run:
```bash
cat /Users/micthebick/IdeaProjects/netisMaker/spikes/02-cross-process-resume.result.json
```
관찰·기록: `pass`, `crossCwd:true`에도 `resumeError:null`인지, `contextCarried`(B 응답에 "csv/export"가 남아 A의 펜딩 질문 컨텍스트가 이어졌는지). 추가로 `[B-resume-error]`가 났다면 그 메시지(예: `session not found`)를 그대로 기록. **PASS면** §9 "answer마다 resume + 펜딩은 우리 DB" 모델과 "단일 호스트(같은 디스크 세션 스토어)면 교차 프로세스 OK" 가정이 입증된다. **FAIL이면**(특히 cwd가 달라서 깨질 때) findings에 "resume은 같은 cwd 또는 명시적 세션 스토어 경로 필요" 조정을 적고, §9의 "공유 볼륨/세션 스토어 경로 고정"을 구체화한다.

- [ ] **Step 5: Commit**

```bash
cd /Users/micthebick/IdeaProjects/netisMaker && \
git add spikes/02-cross-process-resume.ts spikes/02-driver.sh && \
git commit -m "spike2: 교차 프로세스 crash-and-resume + 자체 영속 상태 re-drive"
```

---

## Task 4: 스파이크 3 — 동시 idle 세션 N개 리소스/레이트리밋 풋프린트

목표(spec §13.3, §15 "고동시성"): 인터뷰 워커 풀이 동시에 `AWAITING_INPUT`로 idle hold하는 세션을 여러 개 들고 있을 때, **세션당 서브프로세스 수 / RSS 메모리 / 레이트리밋 노출**이 어떤지 측정한다. 이 수치가 "단일 호스트에 몇 개 동시 인터뷰까지 안전한가"의 운영 상한 근거가 된다.

> PASS = N개 세션이 모두 첫 응답을 받고 idle hold 상태로 공존, 측정값(서브프로세스 수, 총 RSS, 에러/레이트리밋 카운트) 수집 완료. FAIL = 일부 세션이 spawn 실패 / rate-limit(429) / OOM. 어느 쪽이든 N별 수치를 표로 기록.

**Files:**
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/03-concurrency.ts`

- [ ] **Step 1: 스파이크 3 스크립트 작성 (N개 동시 query + 풋프린트 측정)**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/03-concurrency.ts`:

```ts
import { query } from '@anthropic-ai/claude-agent-sdk';
import { PromptPump, captureSessionId, assistantText, summarizeResult, writeResult } from './lib/sdk-common.js';
import { execSync } from 'node:child_process';

const N = Number(process.env.SPIKE_N ?? '5');

type Session = { idx: number; pump: PromptPump; sessionId?: string; answered: boolean; error?: string };

/** Count claude/node child subprocesses + total RSS (MB) of this process tree. */
function footprint(): { procCount: number; rssMb: number } {
  try {
    // All descendants of this PID; sum RSS (KB) and count rows.
    const out = execSync(
      `ps -axo pid=,ppid=,rss=,comm= | awk -v root=${process.pid} 'BEGIN{c=0;r=0} {pid[$1]=$2; rss[$1]=$3} END{}'`,
      { encoding: 'utf8' },
    );
    // Simpler: count claude-code / node children via pgrep tree.
    const tree = execSync(`pgrep -P ${process.pid} || true`, { encoding: 'utf8' })
      .trim().split('\n').filter(Boolean);
    let rssKb = 0;
    let procCount = tree.length;
    for (const pid of tree) {
      try {
        const rss = execSync(`ps -o rss= -p ${pid} || echo 0`, { encoding: 'utf8' }).trim();
        rssKb += Number(rss) || 0;
        // grandchildren (the actual claude-code subprocess often nests)
        const grand = execSync(`pgrep -P ${pid} || true`, { encoding: 'utf8' }).trim().split('\n').filter(Boolean);
        procCount += grand.length;
        for (const g of grand) {
          const grss = execSync(`ps -o rss= -p ${g} || echo 0`, { encoding: 'utf8' }).trim();
          rssKb += Number(grss) || 0;
        }
      } catch { /* process gone */ }
    }
    return { procCount, rssMb: Math.round(rssKb / 1024) };
  } catch (e) {
    return { procCount: -1, rssMb: -1 };
  }
}

async function main() {
  const sessions: Session[] = [];
  let rateLimited = 0;
  let spawnFailed = 0;

  // Launch N concurrent sessions; each gets ONE turn then holds idle (pump never ends).
  const runners = Array.from({ length: N }, (_, idx) => {
    const pump = new PromptPump();
    const s: Session = { idx, pump, answered: false };
    sessions.push(s);
    const q = query({
      prompt: pump.stream(),
      options: { maxTurns: 1, allowedTools: [], stderr: () => {} },
    });
    pump.push(`Session ${idx}: reply with exactly READY-${idx}.`);
    return (async () => {
      try {
        for await (const msg of q) {
          s.sessionId ??= captureSessionId(msg);
          const text = assistantText(msg);
          if (text && new RegExp(`READY-${idx}`).test(text)) {
            s.answered = true;
            break; // hold idle: do NOT pump.end(); keep generator suspended
          }
          const r = summarizeResult(msg) as any;
          if (r?.is_error) { s.error = String(r.subtype); if (/rate/i.test(r.subtype)) rateLimited++; }
        }
      } catch (e: any) {
        s.error = `${e?.name}: ${e?.message}`;
        if (/spawn|ENOENT|EMFILE/i.test(s.error)) spawnFailed++;
        if (/rate|429/i.test(s.error)) rateLimited++;
      }
    })();
  });

  // Give them up to 90s to all answer, then sample footprint while idle.
  await Promise.race([
    Promise.all(runners),
    new Promise((r) => setTimeout(r, 90_000)),
  ]);

  const fp = footprint();
  const answered = sessions.filter((s) => s.answered).length;
  const pass = answered === N && spawnFailed === 0;

  await writeResult('03-concurrency.result.json', {
    pass,
    N,
    answered,
    rateLimited,
    spawnFailed,
    subprocessCount: fp.procCount,
    totalRssMb: fp.rssMb,
    rssPerSessionMb: fp.rssMb > 0 ? Math.round(fp.rssMb / N) : -1,
    perSession: sessions.map((s) => ({ idx: s.idx, sessionId: s.sessionId ?? null, answered: s.answered, error: s.error ?? null })),
  });
  console.log(`SPIKE3 ${pass ? 'PASS' : 'FAIL'} — N=${N} answered=${answered} procs=${fp.procCount} rss=${fp.rssMb}MB rateLimited=${rateLimited} spawnFailed=${spawnFailed}`);
  // Clean up: end all pumps so subprocesses exit.
  for (const s of sessions) s.pump.end();
  process.exit(pass ? 0 : 1);
}
main().catch((e) => { console.error('SPIKE3 FAIL', e); process.exit(1); });
```

- [ ] **Step 2: 소규모 실행 (N=3) — 측정 로직 정상 확인**

Run:
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/spikes && \
SPIKE_N=3 ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" npm run spike3
```
Expected: `SPIKE3 PASS — N=3 answered=3 procs=<n>` 형태의 한 줄. `03-concurrency.result.json`의 `subprocessCount`/`totalRssMb`/`rssPerSessionMb`가 음수가 아니어야(측정 성공). 음수면 `footprint()`의 `ps/pgrep`가 환경에서 막힌 것 — `ps -axo pid=,ppid=,rss=` 단독 실행으로 권한 확인 후 보정.

- [ ] **Step 3: 본 측정 (N=5, N=10) — 풋프린트 스케일 관찰**

Run:
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/spikes && \
for n in 5 10; do \
  echo "=== N=$n ==="; \
  SPIKE_N=$n ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" npm run spike3; \
  cp 03-concurrency.result.json 03-concurrency.N$n.result.json; \
done
```
관찰: N=5 vs N=10에서 `subprocessCount`(세션당 서브프로세스 수가 1:1로 늘어나는지), `totalRssMb`/`rssPerSessionMb`(선형인지), `rateLimited`(429가 N에서 나타나기 시작하는 임계), `spawnFailed`(EMFILE 등 fd 한계).

- [ ] **Step 4: 결과 관찰 & 기록 (운영 상한 도출)**

Run:
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/spikes && \
for n in 5 10; do echo "--- N=$n ---"; cat 03-concurrency.N$n.result.json; done
```
기록: N별 (subprocessCount, totalRssMb, rssPerSessionMb, rateLimited, spawnFailed). **세션당 약 X MB / Y 서브프로세스** 수치를 findings 표에 적고, "단일 호스트 안전 동시 인터뷰 상한 ≈ (가용 RAM / rssPerSessionMb)와 레이트리밋 중 작은 값"으로 결론. **rate-limited가 낮은 N에서 빈발하면** §9의 "프롬프트 캐싱 + 세션당 비용 상한"에 더해 *동시 인터뷰 수 캡*(워커 풀 크기)을 설계에 추가해야 함을 명시.

- [ ] **Step 5: Commit**

```bash
cd /Users/micthebick/IdeaProjects/netisMaker && \
git add spikes/03-concurrency.ts && \
git commit -m "spike3: 동시 idle 세션 N개 서브프로세스/메모리/레이트리밋 풋프린트 측정"
```

---

## Task 5: 스파이크 4 — superpowers 플러그인 로드 + brainstorming→writing-plans 핸드오프 실발화 (스킬 자동 트리거 동등성)

목표(spec §13.4, §9 "스킬 디스패치 shim", §15 "superpowers 스킬 자동 트리거 동등성"): SDK에 `plugins:[{type:'local',path}]` + `settingSources:['user','project']` + `allowedTools:['Skill', ...]`로 superpowers 플러그인을 로드하고, brainstorming을 시작시킨 뒤 그 **종결 상태(= writing-plans invoke)** 가 모델에 의해 실제로 발화되는지 end-to-end로 확인한다. brainstorming SKILL.md는 "The terminal state is invoking writing-plans"라고 못박혀 있으므로(검증됨, 5.1.0 SKILL.md L66), 헤드리스에서도 그 핸드오프가 fire되면 §9의 "shim fallback"이 *대부분 불필요*함을 의미한다.

> PASS = (a) `system/init` 메시지의 `plugins`에 superpowers가, `skills`에 `brainstorming`/`writing-plans`가 보이고(로드 검증), (b) 대화 진행 중 `Skill` 툴 호출 입력에 `writing-plans`가 등장(핸드오프 실발화). FAIL = 플러그인/스킬 미로드, 또는 brainstorming이 승인까지 갔는데 writing-plans로 자동 전이하지 않음(→ shim 필수). canUseTool로 Skill 호출을 가로채 관찰만 하고 deny하지 않는다.

**Files:**
- Create: `/Users/micthebick/IdeaProjects/netisMaker/spikes/04-skill-handoff.ts`

- [ ] **Step 1: 스파이크 4 스크립트 작성 (플러그인 로드 + Skill 호출 관찰)**

`/Users/micthebick/IdeaProjects/netisMaker/spikes/04-skill-handoff.ts`:

```ts
import { query } from '@anthropic-ai/claude-agent-sdk';
import type { CanUseTool } from '@anthropic-ai/claude-agent-sdk';
import {
  PromptPump, SUPERPOWERS_PLUGIN_PATH, captureSessionId, assistantText, summarizeResult, writeResult,
} from './lib/sdk-common.js';
import { existsSync } from 'node:fs';

async function main() {
  if (!existsSync(`${SUPERPOWERS_PLUGIN_PATH}/skills/brainstorming/SKILL.md`)) {
    throw new Error(`superpowers plugin not found at ${SUPERPOWERS_PLUGIN_PATH}`);
  }
  const pump = new PromptPump();
  let sessionId: string | undefined;
  let pluginLoaded = false;
  let skillsAdvertised: string[] = [];
  const skillCalls: { name: string; input: unknown }[] = [];
  let writingPlansFired = false;
  const transcript: string[] = [];

  // Observe (do not block) every Skill invocation so we can see the handoff fire.
  const canUseTool: CanUseTool = async (toolName, input) => {
    if (toolName === 'Skill') {
      skillCalls.push({ name: toolName, input });
      const skillName = (input as any)?.command ?? (input as any)?.skill ?? JSON.stringify(input);
      console.log('[Skill]', skillName);
      if (/writing-plans/i.test(JSON.stringify(input))) writingPlansFired = true;
    }
    return { behavior: 'allow', updatedInput: input };
  };

  const q = query({
    prompt: pump.stream(),
    options: {
      plugins: [{ type: 'local', path: SUPERPOWERS_PLUGIN_PATH }],
      settingSources: ['user', 'project'],
      allowedTools: ['Skill', 'Read', 'Grep', 'Glob', 'Write', 'TodoWrite'],
      canUseTool,
      maxTurns: 40,
      cwd: '/Users/micthebick/IdeaProjects/netisMaker/spikes',
      stderr: (d) => process.stderr.write(`[stderr] ${d}`),
    },
  });

  // Kick off a brainstorming-shaped request, then auto-approve each gate so the
  // session marches to its terminal state (invoke writing-plans).
  pump.push(
    'Use the superpowers brainstorming skill to design a tiny feature: a "copy session id" button. ' +
      'This is a trivial spike — keep every section to one sentence. Ask me ONE question at a time.',
  );

  let turns = 0;
  for await (const msg of q) {
    sessionId ??= captureSessionId(msg);
    if (msg.type === 'system' && msg.subtype === 'init') {
      const inits = msg as any;
      pluginLoaded = (inits.plugins ?? []).some((p: any) => /superpowers/i.test(p.name ?? p.path ?? ''));
      skillsAdvertised = (inits.skills ?? []) as string[];
      console.log('[init] plugins=', JSON.stringify(inits.plugins), 'skills=', JSON.stringify(inits.skills));
    }
    const text = assistantText(msg);
    if (text) {
      transcript.push(text);
      // Auto-approve to drive through brainstorming gates toward writing-plans.
      if (turns++ < 30 && !writingPlansFired) {
        pump.push(
          'Approved. I have no further changes — proceed to the next step, and when brainstorming is ' +
            'complete, hand off to writing-plans as the skill instructs.',
        );
      }
    }
    if (writingPlansFired) { pump.end(); break; }
    const r = summarizeResult(msg);
    if (r) { console.log('[result]', JSON.stringify(r)); }
  }

  const pass = pluginLoaded
    && skillsAdvertised.includes('brainstorming')
    && skillsAdvertised.includes('writing-plans')
    && writingPlansFired;

  await writeResult('04-skill-handoff.result.json', {
    pass,
    sessionId,
    pluginLoaded,
    skillsAdvertised,
    writingPlansFired,
    skillCalls,
    turns,
  });
  console.log(pass ? 'SPIKE4 PASS' : 'SPIKE4 FAIL');
  process.exit(pass ? 0 : 1);
}
main().catch((e) => { console.error('SPIKE4 FAIL', e); process.exit(1); });
```

- [ ] **Step 2: 플러그인 경로 존재 사전 확인**

Run:
```bash
ls -1 /Users/micthebick/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0/skills/ | grep -E 'brainstorming|writing-plans'
```
Expected: `brainstorming` 과 `writing-plans` 두 줄이 출력(둘 다 존재). 출력이 비면 버전 폴더가 바뀐 것 — `lib/sdk-common.ts`의 `SUPERPOWERS_PLUGIN_PATH`를 실제 버전 경로로 갱신 후 진행.

- [ ] **Step 3: 스파이크 4 실행 — 로드 검증 + 핸드오프 관찰**

Run (백그라운드, 8분 timeout 권장 — 멀티턴이라 느릴 수 있음):
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/spikes && \
ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" npm run spike4 > 04-skill-handoff.run.log 2>&1
```
Observe: `[init] plugins=...skills=...` 줄에서 superpowers/`brainstorming`/`writing-plans`가 보이는지(로드 검증) → 이후 `[Skill] brainstorming` → 게이트들을 지나 `[Skill] writing-plans`가 찍히는지(핸드오프 실발화).

- [ ] **Step 4: 결과 관찰 & 기록 (shim 필요 여부 결정)**

Run:
```bash
cat /Users/micthebick/IdeaProjects/netisMaker/spikes/04-skill-handoff.result.json
```
기록: `pluginLoaded`, `skillsAdvertised`(brainstorming/writing-plans 포함?), `writingPlansFired`, `skillCalls`(실제 호출 순서), `turns`. **PASS면**(writingPlansFired=true) §9의 "오케스트레이터 splice fallback shim"은 *안전망*으로 격하 가능 — Java/SDK 설계에서 핸드오프는 모델 자발 호출을 1차 경로로 신뢰. **FAIL이면**(로드는 됐으나 writing-plans로 자동 전이 안 함) §9의 "전이 감지 후 다음 SKILL.md를 같은 세션에 splice"가 **필수 경로**임이 확정 — findings에 "shim 1차, 자발 호출 보조"로 우선순위를 뒤집고, 어느 게이트에서 멈췄는지(transcript 마지막 줄)를 기록한다. 플러그인 자체가 안 보이면(`pluginLoaded=false`) 경로/`settingSources` 문제이므로 그것부터 해결.

- [ ] **Step 5: Commit**

```bash
cd /Users/micthebick/IdeaProjects/netisMaker && \
git add spikes/04-skill-handoff.ts && \
git commit -m "spike4: superpowers 로드 + brainstorming→writing-plans 핸드오프 실발화 검증"
```

---

## Task 6: Record findings & decide — 결과 수집 + 설계 조정 결정

목표: 4개 스파이크의 `*.result.json`/`.run.log`에서 나온 실측을 한 문서로 모으고, 각 결과가 spec §9/§15의 가정을 **확정/조정/반증** 중 무엇으로 만드는지 명시한다. 이 문서가 이후 Java/SDK 구현 계획들이 참조하는 단일 근거가 된다. (구현 코드가 아니므로 테스트는 없고, 문서 완결성만 검증.)

**Files:**
- Create: `/Users/micthebick/IdeaProjects/netisMaker/docs/superpowers/plans/00-spikes-findings.md`

- [ ] **Step 1: 모든 결과 JSON 수집 (한 화면에)**

Run:
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/spikes && \
for f in 00-smoke 01-idle-timeout 02-cross-process-resume 03-concurrency 04-skill-handoff; do \
  echo "=== $f ==="; cat "$f.result.json" 2>/dev/null || echo "(missing — run the spike first)"; echo; \
done
```
Expected: 5개 JSON이 모두 출력. 누락된 게 있으면 해당 스파이크 Task로 돌아가 실행한다(findings는 4개 스파이크 결과가 모두 있어야 작성).

- [ ] **Step 2: 실측값으로 findings 문서 작성 (아래 골격에 관찰값을 채움)**

`/Users/micthebick/IdeaProjects/netisMaker/docs/superpowers/plans/00-spikes-findings.md` — 각 `<...>` 자리에 Step 1에서 본 **실제 측정값**을 채워 넣는다(예시 수치로 두지 말 것):

```markdown
# 대화형 분석 스파이크 결과 & 설계 결정 (00-spikes-findings)

- **날짜**: 2026-06-13
- **대상 스펙**: `docs/superpowers/specs/2026-06-13-conversational-analysis-design.md` (§9, §13, §15)
- **환경**: macOS / Node v24.14.1 / `@anthropic-ai/claude-agent-sdk@0.2.117` / superpowers 5.1.0 / ANTHROPIC_API_KEY 인증
- **스파이크 코드**: `/Users/micthebick/IdeaProjects/netisMaker/spikes/` (throwaway, Gradle 빌드 외)

## 요약 (PASS/FAIL 매트릭스)

| # | 스파이크 | 결과 | 핵심 수치 | 설계 영향 |
|---|---|---|---|---|
| 0 | 스모크 (env/SDK/키) | <PASS/FAIL> | 단일턴 cost=$<...> | 베이스라인 |
| 1 | 30분+ idle 후 재개 | <PASS/FAIL> | elapsed=<...>ms, idleError=<...> | <확정/조정> |
| 2 | 교차 프로세스 crash-and-resume re-drive | <PASS/FAIL> | crossCwd=true, contextCarried=<...> | <확정/조정> |
| 3 | 동시 N 세션 풋프린트 | <PASS/FAIL> | rss/session=<...>MB, proc/session=<...>, rateLimited@N=<...> | <상한 결정> |
| 4 | 스킬 자동 트리거 동등성 | <PASS/FAIL> | pluginLoaded=<...>, writingPlansFired=<...> | <shim 우선순위> |

## 스파이크 1 — idle 타임아웃
- **관찰**: <31분 idle 유지 여부 / 깨운 후 2번째 턴 성공 여부 / idleError 메시지·경과초>
- **결정**: PASS → §9 "in-memory streaming hold(짧은 적극 응답 최적화)" 유지 가능. FAIL → 모든 답변을 resume-per-answer로 강제하고, 안전 idle 한계 = <관찰값>분으로 AWAITING_INPUT TTL(`last_activity_at` 기반 EXPIRED) 설정.

## 스파이크 2 — 교차 프로세스 resume + re-drive
- **관찰**: <B가 A의 session_id로 다른 cwd에서 resume 성공? contextCarried? resumeError?>
- **결정**: PASS → §9 "answer마다 resume + 펜딩은 com.interview_session(claude_session_id) DB" + "단일 호스트면 교차 프로세스 OK" 확정. FAIL(cwd 의존) → §9 "단일 호스트/공유 볼륨 고정"을 "동일 cwd 또는 명시적 세션 스토어 경로(env <관찰한 경로>) 고정"으로 구체화. StaleTaskRecoveryJob 재큐 시 같은 워커/경로로 라우팅 필요.

## 스파이크 3 — 동시성 풋프린트
- **관찰**: N=5/10에서 subprocessCount=<...>, totalRssMb=<...>, rateLimited=<...>, spawnFailed=<...>
- **결정**: 단일 호스트 안전 동시 인터뷰 상한 = <계산값>. 인터뷰 워커 풀 크기 캡 = <값>. rate-limit 빈발 시 §9 "프롬프트 캐싱 + 세션당 비용 상한"에 더해 *동시 인터뷰 수 제한*(요청자별 active 한도와 별개로 전역 캡) 추가.

## 스파이크 4 — 스킬 자동 트리거 동등성
- **관찰**: pluginLoaded=<...>, skillsAdvertised=<...>, writingPlansFired=<...>, skillCalls 순서=<...>
- **결정**: writingPlansFired=true → §9 "splice shim"은 안전망으로 격하, 핸드오프 1차 경로 = 모델 자발 `Skill('writing-plans')`. false → splice shim이 **필수 1차 경로**(brainstorming 종료 감지 → writing-plans SKILL.md 텍스트를 같은 세션에 주입). 멈춘 게이트 = <관찰값>.

## 종합 결정 (구현 계획에 반영)
1. 세션 대기 모델: <resume-per-answer 단독 / idle-hold 최적화 병행> — 근거 스파이크 1.
2. 워커 풀 토폴로지: <단일 호스트 + 세션 스토어 경로 고정 방식> — 근거 스파이크 2.
3. 동시성 한도: 전역 인터뷰 동시 실행 캡 = <값>, AWAITING_INPUT idle TTL = <값>분 — 근거 스파이크 1·3.
4. 핸드오프: <자발 호출 1차 / shim 1차> — 근거 스파이크 4.
5. 변경 없는 가정: 인증=API 키, resume 매 턴 히스토리 재전송 → 프롬프트 캐싱·비용 누적(`total_cost_usd`)·세션당 상한(§15 하드 사실, 스파이크로 반증되지 않음).
```

- [ ] **Step 3: 문서 완결성 검증 (플레이스홀더 잔존 금지)**

Run:
```bash
grep -nE '<\.\.\.>|<PASS/FAIL>|<확정/조정>|<값>|<계산값>|<관찰값>' \
  /Users/micthebick/IdeaProjects/netisMaker/docs/superpowers/plans/00-spikes-findings.md || echo "NO PLACEHOLDERS LEFT"
```
Expected: `NO PLACEHOLDERS LEFT`. 매치가 남으면 그 라인의 자리표시자를 실측값으로 채울 때까지 반복(이 문서는 후속 구현 계획의 근거이므로 빈칸 금지).

- [ ] **Step 4: 4개 스파이크 결과가 모두 인용됐는지 확인**

Run:
```bash
grep -cE '스파이크 1|스파이크 2|스파이크 3|스파이크 4' \
  /Users/micthebick/IdeaProjects/netisMaker/docs/superpowers/plans/00-spikes-findings.md
```
Expected: 4 이상(각 스파이크 섹션이 존재). 4개 PASS/FAIL이 요약 매트릭스와 종합 결정 양쪽에 반영됐는지 눈으로 확인.

- [ ] **Step 5: Commit (findings 문서만 — 스파이크 결과 JSON은 git-ignored)**

```bash
cd /Users/micthebick/IdeaProjects/netisMaker && \
git add docs/superpowers/plans/00-spikes-findings.md && \
git commit -m "docs: 대화형 분석 SDK 스파이크 결과 + 설계 조정 결정 (00-spikes-findings)"
```

- [ ] **Step 6: 다음 단계 안내 (구현 계획 착수 게이트)**

이 문서의 "종합 결정"이 후속 구현 계획(Java API: `InterviewController`/`InterviewService`/엔티티/`V11__interview.sql`, SDK 서비스 `netismaker-interview-service`, 프론트 분할 뷰)의 입력이다. 특히 스파이크 1·3 결과는 `interview_session`의 idle TTL/동시성 캡 상수를, 스파이크 2는 워커 풀 호스트 고정 방식을, 스파이크 4는 SDK 서비스의 핸드오프 코드 경로(자발 호출 vs splice shim 우선순위)를 결정한다. 스파이크가 하나라도 FAIL이고 그 조정이 §9를 근본적으로 바꾸면(예: 교차 프로세스 resume 완전 불가) 구현 계획 작성 전에 spec §9를 갱신할 것.
