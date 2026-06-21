# 인터뷰 완료감지 재설계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 한국어 plan에서도 인터뷰가 PLAN_READY로 정상 완료되고, 추출 실패는 하드 FAIL 대신 보정 후 계속되며, 안 끝나는 대화는 턴 상한으로 종료되게 한다.

**Architecture:** `netismaker-interview-service`(TS/Node) 워커 국한 변경. `planHarvest`를 한/영 이중언어로 인식, `interviewRunner`의 완료 트리거를 "harvest 시도"로 바꿔 near-miss를 보정 후 계속시키고, `claim.turns` 기반 턴 상한 + force-finish를 추가한다. 백엔드(Java)·DB·SSE 변경 없음.

**Tech Stack:** TypeScript, Node(tsx), vitest. Claude Agent SDK 워커.

## Global Constraints

- 변경 범위는 `netismaker-interview-service/` 워커에 **국한**. 백엔드 Java·DB 스키마·SSE 계약·프론트 변경 금지.
- 인터뷰는 **planning-only**: plan만 산출, 구현/빌드/커밋 금지(프롬프트 문구 유지).
- planJson 스키마 `Array<{ task: number; title: string }>` **변경 금지**.
- 최종 plan 산출물 언어는 **한국어**(영문 구조도 허용). 분석 결과 한국어 관례 유지.
- TDD: 모든 프로덕션 코드는 실패하는 테스트 후 작성. 잦은 커밋.
- 테스트 실행: `npm test --prefix netismaker-interview-service` (vitest). 단일 파일: `npx vitest run <path>` (cwd=`netismaker-interview-service`).
- 기존 테스트(54개) 전부 GREEN 유지.

---

### Task 1: planHarvest 이중언어화 + tryHarvest 헬퍼

**Files:**
- Modify: `netismaker-interview-service/src/runner/planHarvest.ts`
- Test: `netismaker-interview-service/test/planHarvest.test.ts`

**Interfaces:**
- Consumes: 기존 `harvestPlan(transcript: string): Harvest`, `HarvestError`.
- Produces: `tryHarvest(transcript: string): { ok: true; harvest: Harvest } | { ok: false }` (HarvestError를 흡수, 그 외 에러는 재던짐). `Harvest`/`HarvestError` 시그니처 불변.

- [ ] **Step 1: 실패 테스트 추가 (한글/H2/전각콜론/tryHarvest)**

`test/planHarvest.test.ts` 에 추가:

```typescript
import { harvestPlan, tryHarvest, HarvestError } from '../src/runner/planHarvest.js';

describe('harvestPlan bilingual', () => {
  it('harvests a Korean plan: "# … 구현 계획" header + "### 작업 N:" tasks', () => {
    const t = '# 설계\n\n개요.\n\n# 서버관리 구현 계획\n\n### 작업 1: DTO 정의\n- [ ] a\n\n### 작업 2: API\n- [ ] b';
    const h = harvestPlan(t);
    expect(h.designMarkdown).toContain('# 설계');
    expect(h.planMarkdown).toContain('# 서버관리 구현 계획');
    expect(h.planJson).toEqual([{ task: 1, title: 'DTO 정의' }, { task: 2, title: 'API' }]);
  });

  it('accepts an H2 header and full-width colon', () => {
    const t = '## 구현 계획\n\n### 작업 1： 첫 작업';
    const h = harvestPlan(t);
    expect(h.planJson).toEqual([{ task: 1, title: '첫 작업' }]);
  });

  it('accepts "### 태스크 N:" and "### Task N:" task lines', () => {
    const t = '# 구현 계획\n\n### 태스크 1: 가\n\n### Task 2: na';
    expect(harvestPlan(t).planJson).toEqual([{ task: 1, title: '가' }, { task: 2, title: 'na' }]);
  });

  it('still harvests the English structure (regression)', () => {
    const t = '# Design\n\nx\n\n# CSV Export Implementation Plan\n\n### Task 1: do';
    expect(harvestPlan(t).planJson).toEqual([{ task: 1, title: 'do' }]);
  });

  it('throws HarvestError when no plan header present', () => {
    expect(() => harvestPlan('## 설계안 (1/4)\n\n질문입니다')).toThrow(HarvestError);
  });

  it('throws HarvestError when the plan has zero task lines', () => {
    expect(() => harvestPlan('# 구현 계획\n\n작업 항목이 없음')).toThrow(HarvestError);
  });
});

describe('tryHarvest', () => {
  it('returns ok:true with harvest on success', () => {
    const r = tryHarvest('# 구현 계획\n\n### 작업 1: 가');
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.harvest.planJson).toEqual([{ task: 1, title: '가' }]);
  });
  it('returns ok:false on HarvestError (no throw)', () => {
    expect(tryHarvest('## 설계안\n\n질문')).toEqual({ ok: false });
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run test/planHarvest.test.ts` (cwd=`netismaker-interview-service`)
Expected: FAIL — 한글 케이스 불일치 + `tryHarvest` is not exported.

- [ ] **Step 3: 정규식 이중언어화 + tryHarvest 구현**

`src/runner/planHarvest.ts` 에서 두 정규식을 교체하고 헬퍼 추가:

```typescript
// writing-plans header convention: "# <Feature> Implementation Plan" 또는 한국어 "# <기능> 구현 계획" (H1/H2)
const PLAN_HEADER = /^#{1,2}\s+.*(Implementation Plan|구현\s*계획)\s*$/m;
const TASK_LINE = /^###\s+(?:Task|작업|태스크)\s+(\d+)\s*[:：]\s*(.+?)\s*$/gm;
```

파일 끝에 추가:

```typescript
export type HarvestResult = { ok: true; harvest: Harvest } | { ok: false };

/** harvestPlan을 try/catch로 감싼다. HarvestError는 ok:false로 흡수, 그 외 에러는 재던짐. */
export function tryHarvest(transcript: string): HarvestResult {
  try {
    return { ok: true, harvest: harvestPlan(transcript) };
  } catch (e) {
    if (e instanceof HarvestError) return { ok: false };
    throw e;
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `npx vitest run test/planHarvest.test.ts`
Expected: PASS (신규 + 기존 회귀 모두).

- [ ] **Step 5: 커밋**

```bash
git add netismaker-interview-service/src/runner/planHarvest.ts netismaker-interview-service/test/planHarvest.test.ts
git commit -m "feat(interview): planHarvest 한/영 이중언어 인식 + tryHarvest 헬퍼"
```

---

### Task 2: skillDispatch — buildPlanReformatSplice + detectPlanIntent + 한국어 구조 프롬프트

**Files:**
- Modify: `netismaker-interview-service/src/runner/skillDispatch.ts`
- Test: `netismaker-interview-service/test/skillDispatch.test.ts`

**Interfaces:**
- Consumes: 기존 `detectHandoff`, `buildWritingPlansSplice`.
- Produces:
  - `detectPlanIntent(text: string): boolean` — handoff 또는 "Implementation Plan"/"구현 계획" 문구 존재.
  - `buildPlanReformatSplice(): string` — 지정 정규 형식으로 plan을 다시(또는 지금) 제시하라는 self-contained 프롬프트(파일 읽기 없음).

- [ ] **Step 1: 실패 테스트 추가**

`test/skillDispatch.test.ts` 에 추가:

```typescript
import { detectPlanIntent, buildPlanReformatSplice } from '../src/runner/skillDispatch.js';

describe('detectPlanIntent', () => {
  it('true on handoff phrasing', () => {
    expect(detectPlanIntent('Invoke writing-plans now.')).toBe(true);
  });
  it('true when "구현 계획" or "Implementation Plan" appears', () => {
    expect(detectPlanIntent('이제 구현 계획을 제시합니다')).toBe(true);
    expect(detectPlanIntent('Here is the Implementation Plan')).toBe(true);
  });
  it('false on an ordinary question', () => {
    expect(detectPlanIntent('어떤 컬럼을 포함할까요?')).toBe(false);
  });
});

describe('buildPlanReformatSplice', () => {
  it('demands the canonical Korean plan structure', () => {
    const out = buildPlanReformatSplice();
    expect(out).toContain('구현 계획');
    expect(out).toContain('### 작업 N:');
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run test/skillDispatch.test.ts`
Expected: FAIL — `detectPlanIntent`/`buildPlanReformatSplice` is not exported.

- [ ] **Step 3: 구현 추가 + 기존 splice 프롬프트 보강**

`src/runner/skillDispatch.ts` 의 `HANDOFF` 정규식 아래에 추가:

```typescript
const PLAN_INTENT = /(Implementation Plan|구현\s*계획)/i;

/** 어시스턴트 텍스트가 plan을 마무리하려는 신호인가(보정 splice 시도 여부 판단용). */
export function detectPlanIntent(assistantText: string): boolean {
  return HANDOFF.test(assistantText) || PLAN_INTENT.test(assistantText);
}

/** 지정 정규 형식으로 plan을 한 번에 확정 제시하라는 프롬프트(near-miss 보정 / force-finish 공용). */
export function buildPlanReformatSplice(): string {
  return (
    '지금까지의 논의를 바탕으로 최종 구현 계획을 한 번에 확정해 제시하세요. ' +
    '반드시 다음 정규 형식을 지키세요(설명은 한국어): 최상위 헤딩 `# <기능> 구현 계획`, ' +
    '그 아래 각 작업을 `### 작업 N: <제목>` 형식으로 나열. ' +
    '영문 `# <Feature> Implementation Plan` / `### Task N:` 도 허용됩니다. ' +
    '이 구조라야 시스템이 완료를 인식합니다. 구현/빌드/커밋은 하지 마세요(plan 문서만).'
  );
}
```

기존 `buildWritingPlansSplice` 반환 문자열에 한국어 구조 한 줄을 추가(영문 "Implementation Plan" 언급은 유지 → 기존 테스트 보존):

```typescript
  return (
    'Now follow the writing-plans skill to turn the approved spec into an implementation plan. ' +
    'Produce the PLAN DOCUMENT ONLY — do NOT implement it: no source edits, no builds/installs/tests, ' +
    'no git commit or push. The plan is reviewed and approved by a human before any implementation. ' +
    '최종 plan은 `# <기능> 구현 계획` H1 + 각 작업을 `### 작업 N: <제목>` 형식으로(설명은 한국어). ' +
    '영문 `# <Feature> Implementation Plan` / `### Task N:` 도 허용. 이 구조라야 시스템이 완료를 인식한다. ' +
    'Apply this skill exactly:\n\n' +
    skill
  );
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `npx vitest run test/skillDispatch.test.ts`
Expected: PASS (신규 + 기존 detectHandoff/buildWritingPlansSplice 테스트 모두).

- [ ] **Step 5: 커밋**

```bash
git add netismaker-interview-service/src/runner/skillDispatch.ts netismaker-interview-service/test/skillDispatch.test.ts
git commit -m "feat(interview): detectPlanIntent + buildPlanReformatSplice + 한국어 구조 프롬프트"
```

---

### Task 3: config — maxTurns / forceFinishTurns

**Files:**
- Modify: `netismaker-interview-service/src/config.ts`
- Test: `netismaker-interview-service/test/config.test.ts`

**Interfaces:**
- Produces: `Config.maxTurns: number`(env `INTERVIEW_MAX_TURNS`, 기본 20), `Config.forceFinishTurns: number`(env `INTERVIEW_FORCE_FINISH_TURNS`, 기본 19).

- [ ] **Step 1: 실패 테스트 추가**

`test/config.test.ts` 에 추가(기존 `loadConfig` import/필수 env 패턴 재사용):

```typescript
  it('defaults maxTurns=20, forceFinishTurns=19 and honors env overrides', () => {
    const base = {
      API_BASE_URL: 'http://x', WORKER_API_KEY: 'k', WORKER_ID: 'w',
      SUPERPOWERS_PLUGIN_PATH: '/sp',
    };
    const d = loadConfig({ ...base });
    expect(d.maxTurns).toBe(20);
    expect(d.forceFinishTurns).toBe(19);

    const o = loadConfig({ ...base, INTERVIEW_MAX_TURNS: '30', INTERVIEW_FORCE_FINISH_TURNS: '28' });
    expect(o.maxTurns).toBe(30);
    expect(o.forceFinishTurns).toBe(28);
  });
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run test/config.test.ts`
Expected: FAIL — `maxTurns`/`forceFinishTurns` undefined.

- [ ] **Step 3: 구현**

`src/config.ts` 의 `Config` 인터페이스에 추가:

```typescript
  /** 인터뷰 1세션 최대 assistant 질문 턴(초과 시 FAILED). 무력한 비용 가드를 대체하는 실효 백스톱. */
  maxTurns: number;
  /** 이 턴 수 이상이면 force-finish(정규 형식 plan 강제 요청) 프롬프트 사용. 기본 maxTurns-1. */
  forceFinishTurns: number;
```

`loadConfig` 의 반환 객체에 추가(기존 `num()` 헬퍼 사용):

```typescript
    maxTurns: num(env, 'INTERVIEW_MAX_TURNS', 20),
    forceFinishTurns: num(env, 'INTERVIEW_FORCE_FINISH_TURNS', 19),
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `npx vitest run test/config.test.ts`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add netismaker-interview-service/src/config.ts netismaker-interview-service/test/config.test.ts
git commit -m "feat(interview): config maxTurns/forceFinishTurns (턴 상한)"
```

---

### Task 4: interviewRunner — 완료 트리거를 harvest 시도로 + 한국어 kickoff + deps 배선

**Files:**
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts`
- Modify: `netismaker-interview-service/src/index.ts` (RunnerDeps에 maxTurns/forceFinishTurns 전달)
- Test: `netismaker-interview-service/test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: `tryHarvest`(Task 1), `detectPlanIntent`(Task 2), `cfg.maxTurns/forceFinishTurns`(Task 3).
- Produces: `RunnerDeps`에 `maxTurns: number; forceFinishTurns: number` 추가. `run()`은 harvest 성공 시 `postPlan`, 실패 시(이 태스크 범위) `postQuestion` — **HarvestError로 인한 하드 FAIL 제거**.

- [ ] **Step 1: 기존 kickoff 프롬프트 테스트 갱신 + 한글 plan 완료 테스트 추가**

`test/interviewRunner.test.ts` 에서 **기존** "fresh kickoff prompt requires ... Implementation Plan" 테스트의 단언을 한국어 구조로 교체:

```typescript
  it('fresh kickoff prompt requires the canonical plan structure (Korean header + task lines)', async () => {
    const client = makeClient();
    let seenPrompt = '';
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
      (async () => { for await (const p of args.prompt) seenPrompt += p.message?.content ?? ''; })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(seenPrompt).toContain('구현 계획');
    expect(seenPrompt).toContain('### 작업 N:');
  });
```

그리고 한국어 plan 완료 테스트를 추가(인라인 스트림 픽스처):

```typescript
  it('harvests a KOREAN plan and POSTs /plan instead of /question', async () => {
    const client = makeClient();
    async function* koreanPlanStream() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-k' };
      yield { type: 'assistant', message: { content: [{ type: 'text',
        text: '# 설계\n\n개요.\n\n# 서버관리 구현 계획\n\n### 작업 1: DTO\n- [ ] a' }] } };
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.2 }, duration_ms: 1000 };
    }
    const fakeQuery = vi.fn(() => koreanPlanStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });
    expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({
      planMarkdown: expect.stringContaining('# 서버관리 구현 계획'),
    }));
    expect(client.postQuestion).not.toHaveBeenCalled();
    expect(client.fail).not.toHaveBeenCalled();
  });
```

또한 파일 상단 `deps` 객체에 `maxTurns: 20, forceFinishTurns: 19` 추가:

```typescript
const deps = {
  superpowersPluginPath: '/sp/5.1.0',
  claudeCliPath: '/home/me/.local/bin/claude',
  quotaGuard: 5,
  maxTurns: 20,
  forceFinishTurns: 19,
  ensureRepo,
};
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run test/interviewRunner.test.ts`
Expected: FAIL — kickoff에 '구현 계획' 미포함, 한글 plan이 postQuestion으로 빠짐(또는 loose-match로 fail).

- [ ] **Step 3: kickoff 프롬프트 한국어 구조화**

`src/runner/interviewRunner.ts` `promptFor` 의 kickoff 본문에서 PR#8의 영문-only 헤더 지시 문단을 다음으로 교체:

```typescript
        '최종 plan은 반드시 다음 정규 형식으로 작성하세요(설명은 한국어): ' +
        '최상위 헤딩 `# <기능> 구현 계획`, 그 아래 각 작업을 `### 작업 N: <제목>` 형식으로. ' +
        '영문 `# <Feature> Implementation Plan` / `### Task N:` 도 허용됩니다. ' +
        '이 정확한 구조라야 시스템이 완료를 인식하며, 없으면 인터뷰가 끝나지 않습니다.\n\n' +
```

- [ ] **Step 4: 완료 트리거를 harvest 시도로 교체**

import에 추가: `import { harvestPlan, HarvestError, tryHarvest } from './planHarvest.js';` (HarvestError는 더 이상 catch 불필요하므로 제거 가능하나, 남겨도 무방). `detectPlanIntent` import 추가: `import { buildWritingPlansSplice, detectHandoff, detectPlanIntent, buildPlanReformatSplice } from './skillDispatch.js';`

`run()` 의 handoff shim `hasPlan` 판정을 이중언어로 교체:

```typescript
      const handoff = detectHandoff(assistantText);
      const hasPlan = tryHarvest(assistantText).ok;
```

그리고 기존 완료 분기(아래 블록)를 교체:

```typescript
      // (기존) if (/Implementation Plan/m.test(assistantText)) { const harvest = harvestPlan(...) ... return; }
      //        await this.client.postQuestion(...);
      // (신규)
      const harvested = tryHarvest(assistantText);
      if (harvested.ok) {
        await this.client.postPlan(claim.sessionId, {
          designMarkdown: harvested.harvest.designMarkdown,
          planMarkdown: harvested.harvest.planMarkdown,
          planJson: JSON.stringify(harvested.harvest.planJson),
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

(catch 블록의 `HarvestError` 분기는 이제 도달 불가 — 제거하거나 그대로 둔다. 그대로 두면 무해.)

- [ ] **Step 5: RunnerDeps + index 배선**

`interviewRunner.ts` 의 `RunnerDeps` 인터페이스에 추가:

```typescript
  /** 세션 최대 assistant 질문 턴(초과 시 FAILED). */
  maxTurns: number;
  /** 이 턴 수 이상이면 force-finish 프롬프트 사용. */
  forceFinishTurns: number;
```

`src/index.ts` 의 `new InterviewRunner(client, realQuery, { ... })` deps 객체에 추가:

```typescript
    maxTurns: cfg.maxTurns,
    forceFinishTurns: cfg.forceFinishTurns,
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `npm test --prefix netismaker-interview-service`
Expected: PASS (전체). 특히 한글 plan→postPlan, 영문 plan 회귀, quota/cumulative guard 유지.

- [ ] **Step 7: 커밋**

```bash
git add netismaker-interview-service/src/runner/interviewRunner.ts netismaker-interview-service/src/index.ts netismaker-interview-service/test/interviewRunner.test.ts
git commit -m "feat(interview): 완료 트리거를 harvest 시도로 + 한국어 kickoff (하드 FAIL 제거)"
```

---

### Task 5: interviewRunner — near-miss 형식 보정(normal 모드 1회)

**Files:**
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts`
- Test: `netismaker-interview-service/test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: `buildPlanReformatSplice`, `detectPlanIntent`, `tryHarvest`.
- Produces: harvest 실패 + plan 의도 신호 시 같은 세션에 reformat splice 1회 재실행 후 재harvest. 성공이면 postPlan, 아니면 postQuestion. (force-finish 모드에선 생략 — Task 6에서 forceFinish 플래그 도입)

- [ ] **Step 1: 실패 테스트 추가**

`test/interviewRunner.test.ts` 에 추가:

```typescript
  it('near-miss: harvest fails but plan-intent present → reformat splice once → re-harvest → postPlan', async () => {
    const client = makeClient();
    async function* intentNoStructure() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
      yield { type: 'assistant', message: { content: [{ type: 'text',
        text: '이제 구현 계획을 정리하겠습니다.' }] } }; // plan 의도 O, 추출 X
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.1 }, duration_ms: 100 };
    }
    async function* reformattedPlan() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
      yield { type: 'assistant', message: { content: [{ type: 'text',
        text: '# 구현 계획\n\n### 작업 1: 가' }] } };
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.15 }, duration_ms: 200 };
    }
    const fakeQuery = vi.fn()
      .mockImplementationOnce(() => intentNoStructure())
      .mockImplementationOnce(() => reformattedPlan());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });
    expect(fakeQuery).toHaveBeenCalledTimes(2); // 보정 splice 1회
    expect(client.postPlan).toHaveBeenCalledTimes(1);
    expect(client.fail).not.toHaveBeenCalled();
  });

  it('near-miss reformat also fails → postQuestion (no fail)', async () => {
    const client = makeClient();
    async function* intentNoStructure() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n2' };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: '구현 계획 초안입니다.' }] } };
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.1 }, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => intentNoStructure()); // 매번 추출 불가
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });
    expect(client.postQuestion).toHaveBeenCalledTimes(1);
    expect(client.fail).not.toHaveBeenCalled();
  });
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run test/interviewRunner.test.ts`
Expected: FAIL — 보정 splice 미구현이라 첫 케이스가 postQuestion으로 빠지고 fakeQuery 1회만 호출.

- [ ] **Step 3: near-miss 보정 구현**

`run()` 에서 완료 분기를 다음으로 교체(Task 4의 분기 확장):

```typescript
      let harvested = tryHarvest(assistantText);
      // near-miss 보정: 추출 실패 + plan 의도 신호 시, 같은 세션에 정규 형식 재요청 1회.
      if (!harvested.ok && detectPlanIntent(assistantText) && sessionId) {
        const reformatSplice = buildPlanReformatSplice();
        const retry = await relay(
          this.query({
            prompt: (async function* () { yield userTurn(reformatSplice); })(),
            options: buildOptions({
              superpowersPluginPath: this.deps.superpowersPluginPath,
              workDir: claim.workDir,
              claudeCliPath: this.deps.claudeCliPath,
              claudeSessionId: sessionId,
              mcpsExtra: claim.mcpsExtra,
              model: claim.model,
              effort: claim.effort,
            }),
          }),
        );
        guard.add(retry.costUsd);
        assistantText = retry.assistantText;
        sessionId = retry.sessionId ?? sessionId;
        costUsd = retry.costUsd;
        durationMs = retry.durationMs;
        harvested = tryHarvest(assistantText);
      }
      if (harvested.ok) {
        await this.client.postPlan(claim.sessionId, {
          designMarkdown: harvested.harvest.designMarkdown,
          planMarkdown: harvested.harvest.planMarkdown,
          planJson: JSON.stringify(harvested.harvest.planJson),
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

- [ ] **Step 4: 테스트 통과 확인**

Run: `npm test --prefix netismaker-interview-service`
Expected: PASS (전체).

- [ ] **Step 5: 커밋**

```bash
git add netismaker-interview-service/src/runner/interviewRunner.ts netismaker-interview-service/test/interviewRunner.test.ts
git commit -m "feat(interview): near-miss 형식 보정 1회 후 대화 계속(하드 FAIL 없음)"
```

---

### Task 6: interviewRunner — 턴 상한 + force-finish

**Files:**
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts`
- Test: `netismaker-interview-service/test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: `claim.turns`, `this.deps.maxTurns/forceFinishTurns`, `buildPlanReformatSplice`.
- Produces: claim 직후 `assistantTurnCount(claim.turns) >= maxTurns` → 실행 없이 `client.fail`. `>= forceFinishTurns` → 이번 턴 프롬프트를 force-finish로 대체, near-miss 보정은 생략.

- [ ] **Step 1: 실패 테스트 추가**

`test/interviewRunner.test.ts` 에 추가(헬퍼: assistant 턴 N개짜리 claim):

```typescript
  function turns(assistantCount: number) {
    const out: { seq: number; role: string; kind: string; content: string; replyToSeq: number | null }[] = [];
    let seq = 0;
    for (let i = 0; i < assistantCount; i++) {
      out.push({ seq: seq++, role: 'assistant', kind: 'question', content: `q${i}`, replyToSeq: null });
      out.push({ seq: seq++, role: 'user', kind: 'answer', content: 'a', replyToSeq: seq - 2 });
    }
    return out;
  }

  it('turn cap: assistant turns >= maxTurns → fail WITHOUT running the turn', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never); // maxTurns 20
    await runner.run({ ...resumeClaim, currentPhase: 'brainstorming', turns: turns(20) });
    expect(fakeQuery).not.toHaveBeenCalled();
    expect(client.fail).toHaveBeenCalledWith(42, expect.stringContaining('최대'));
    expect(client.postQuestion).not.toHaveBeenCalled();
  });

  it('force-finish: assistant turns >= forceFinishTurns → prompt demands the canonical plan structure', async () => {
    const client = makeClient();
    let seenPrompt = '';
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
      (async () => { for await (const p of args.prompt) seenPrompt += p.message?.content ?? ''; })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never); // forceFinishTurns 19
    await runner.run({ ...resumeClaim, currentPhase: 'brainstorming', turns: turns(19) });
    expect(fakeQuery).toHaveBeenCalledTimes(1);
    expect(seenPrompt).toContain('### 작업 N:'); // force-finish = reformat splice
  });
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run test/interviewRunner.test.ts`
Expected: FAIL — 상한 미구현이라 maxTurns 케이스가 turn을 실행하고, force-finish 프롬프트가 일반 resume 텍스트.

- [ ] **Step 3: 턴 상한 + force-finish 구현**

`run()` 의 `ticker.start()` + `guard` 생성 직후, `ensureRepo` 전에 추가:

```typescript
      // 턴 상한: claim.turns의 assistant 턴 수로 진행도 판정(백엔드 변경 불필요).
      const assistantTurns = claim.turns.filter((t) => t.role === 'assistant').length;
      if (assistantTurns >= this.deps.maxTurns) {
        await this.client.fail(
          claim.sessionId,
          `최대 질문 턴(${this.deps.maxTurns}) 초과 — plan 미완성`,
        );
        return;
      }
      const forceFinish = assistantTurns >= this.deps.forceFinishTurns;
```

프롬프트 선택을 force-finish로 분기(기존 `prompt: promptFor(claim)` 를 교체):

```typescript
      const stream: AsyncIterable<SdkMessage> = this.query({
        prompt:
          forceFinish && claim.claudeSessionId
            ? (async function* () { yield userTurn(buildPlanReformatSplice()); })()
            : promptFor(claim),
        options,
      });
```

near-miss 보정 가드에 `!forceFinish` 추가(force-finish는 이미 reformat이므로 이중 splice 방지):

```typescript
      if (!harvested.ok && !forceFinish && detectPlanIntent(assistantText) && sessionId) {
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `npm test --prefix netismaker-interview-service`
Expected: PASS (전체). `ensureRepo`가 maxTurns 케이스에서 호출되지 않는지도 fakeQuery로 간접 확인됨.

- [ ] **Step 5: 커밋**

```bash
git add netismaker-interview-service/src/runner/interviewRunner.ts netismaker-interview-service/test/interviewRunner.test.ts
git commit -m "feat(interview): claim.turns 기반 턴 상한 + force-finish (무력 비용가드 대체)"
```

---

### Task 7: 전체 검증 + 배포 메모

**Files:** 없음(검증).

- [ ] **Step 1: 전체 테스트**

Run: `npm test --prefix netismaker-interview-service`
Expected: 전체 PASS, 출력 깨끗.

- [ ] **Step 2: 타입 체크(있으면)**

Run: `npx tsc --noEmit -p netismaker-interview-service/tsconfig.json` (존재 시)
Expected: 에러 없음.

- [ ] **Step 3: 배포 메모 확인**

라이브 반영은 interview-service 재기동만 필요(tsx, 빌드 없음). 백엔드 변경 없음. 실제 인터뷰 1건으로 한글 plan→PLAN_READY 발동 라이브 검증은 quota 소비하므로 사람 판단(선택).

---

## Self-Review

**1. Spec coverage:**
- 4.1 harvest 이중언어 → Task 1 ✓
- 4.2 트리거=harvest 시도 → Task 4 ✓
- 4.3 near-miss 보정 → Task 5 ✓
- 4.4 턴 상한+force-finish → Task 3(config)+Task 6 ✓
- 4.5 프롬프트 보강(kickoff+splice) → Task 4(kickoff)+Task 2(splice) ✓
- 4.6 CostGuard 유지·명시 → Task 6 커밋 메시지 + 코드 주석(유지), 제거 안 함 ✓

**2. Placeholder scan:** 모든 step에 실제 코드/명령/기대 출력 포함. TODO/TBD 없음.

**3. Type consistency:** `tryHarvest` 반환 `{ok:true;harvest}|{ok:false}` (Task1) → Task4/5에서 `harvested.ok`/`harvested.harvest`로 사용 일치. `detectPlanIntent`/`buildPlanReformatSplice`(Task2) 시그니처 → Task5/6 사용 일치. `maxTurns`/`forceFinishTurns`(Task3 config → Task4 RunnerDeps → Task6 사용) 명칭 일치. `assistantTurns = claim.turns.filter(role==='assistant')` 일관.
