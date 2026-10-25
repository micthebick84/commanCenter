import { describe, expect, it, vi } from 'vitest';
import {
  InterviewRunner,
  REFS_CLEARED_LINE,
  dbSection,
  interviewRefPrefix,
  interviewRefSection,
  refSection,
} from '../src/runner/interviewRunner.js';
import { REF_PREPARE_FALLBACK_REASON, type RefPrepared } from '../src/runner/repoPrepare.js';
import type { InterviewClaimResponse, RefRepoClaim } from '../src/types.js';
import {
  freshClaim,
  resumeClaim,
  freshClaimWithAttachments,
  interviewClaimWithRefs,
  questionClaim,
  questionClaimWithAttachments,
  questionClaimWithRefs,
} from './fixtures/claims.js';
import { planCompleteStream, questionStream, streamingQuestionStream, usageAwareQuestionStream } from './fixtures/sdkMessages.js';
import { buildPlanReformatSplice, detectHandoff } from '../src/runner/skillDispatch.js';

function makeClient() {
  return {
    postQuestion: vi.fn().mockResolvedValue(undefined),
    postPlan: vi.fn().mockResolvedValue(undefined),
    fail: vi.fn().mockResolvedValue(undefined),
    postActivity: vi.fn().mockResolvedValue(undefined),
    postRateLimit: vi.fn().mockResolvedValue(undefined),
  };
}
// ensureRepo / harvestPlanFiles are injected so unit tests never touch git.
const ensureRepo = vi.fn().mockResolvedValue(undefined);
const deps = {
  superpowersPluginPath: '/sp/5.1.0',
  claudeCliPath: '/home/me/.local/bin/claude',
  maxTurns: 20,
  forceFinishTurns: 19,
  ensureRepo,
  harvestPlanFiles: vi.fn().mockResolvedValue({ ok: false }),
};

describe('InterviewRunner', () => {
  it('fresh claim: prepares the repo at workDir, starts brainstorming, relays the question, posts session_id + cost', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn((_args: { prompt: AsyncIterable<unknown>; options: { resume?: string; cwd?: string } }) => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run(freshClaim);

    // repo prepared at workDir before the turn
    expect(ensureRepo).toHaveBeenCalledWith(
      expect.objectContaining({ githubRepo: 'acme/widgets', githubBranch: 'main', workDir: freshClaim.workDir }),
    );
    // cwd = workDir; fresh => no resume option
    expect(fakeQuery.mock.calls[0]![0].options.cwd).toBe(freshClaim.workDir);
    expect(fakeQuery.mock.calls[0]![0].options.resume).toBeUndefined();
    expect(client.postQuestion).toHaveBeenCalledWith(
      42,
      expect.objectContaining({
        content: expect.stringContaining('Which columns'),
        claudeSessionId: 'sess-new-1',
        kind: 'question',
        costUsd: 0.12,
        inputTokens: 1000,
        outputTokens: 250,
        cacheCreationTokens: 30,
        cacheReadTokens: 8000,
      }),
    );
    expect(client.postPlan).not.toHaveBeenCalled();
  });

  it('resume claim: passes options.resume + identical cwd, and injects lastAnswer into the prompt', async () => {
    const client = makeClient();
    let seenPrompt = '';
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }>; options: { resume?: string; cwd?: string } }) => {
      // eagerly drain prompt to assert the injected answer.
      // Shape is the SDK stream-json user message {type,message:{role,content}} (claude CLI >=2.1).
      (async () => {
        for await (const p of args.prompt) seenPrompt += (p as { message?: { content?: string } }).message?.content ?? '';
      })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run(resumeClaim);

    // repo re-prepared before resume too (resume is cwd-pinned)
    expect(ensureRepo).toHaveBeenCalledWith(
      expect.objectContaining({ workDir: resumeClaim.workDir }),
    );
    expect(fakeQuery.mock.calls[0]![0].options.resume).toBe('sess-abc-123');
    expect(fakeQuery.mock.calls[0]![0].options.cwd).toBe(resumeClaim.workDir);
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
        inputTokens: 2000,
        outputTokens: 500,
        cacheCreationTokens: 60,
        cacheReadTokens: 5400,
        durationMs: 5400,
      }),
    );
    expect(client.postQuestion).not.toHaveBeenCalled();
  });

  // 2026-10-15 작업 13: resume한 세션의 result.total_cost_usd는 세션 누적값이다 — 그대로 더하면 턴마다 이중 집계된다.
  it('resumed claim: posts only this turn cost (SDK total_cost_usd minus the prior session total)', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => questionStream()); // SDK reports session cumulative 0.12
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run({ ...resumeClaim, totalCostUsd: 0.1 });

    expect(client.fail).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({ costUsd: expect.closeTo(0.02, 6) }));
  });

  it('no per-session cost limit: an expensive session total never fails the turn', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run({ ...resumeClaim, totalCostUsd: 1000 });

    expect(client.fail).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalled();
  });

  // 2026-10-15 작업 13 세션 25: 첫 result 뒤 백그라운드 서브에이전트 알림을 처리하며 부른 mcp__db 도구의 권한 요청이
  // "Stream closed"로 실패했다 — 러너의 모든 질의는 CLI가 idle을 알릴 때까지 프롬프트 입력을 열어 둬야 한다.
  it('keeps the prompt input open past the first result until the CLI reports idle', async () => {
    const client = makeClient();
    let openAfterResult: boolean | undefined;
    const fakeQuery = vi.fn(({ prompt }: { prompt: AsyncIterable<unknown> }) =>
      (async function* () {
        const it = prompt[Symbol.asyncIterator]();
        await it.next();
        let ended = false;
        const endedP = it.next().then(() => {
          ended = true;
        });
        yield { type: 'system', subtype: 'init', session_id: 'sess-gate' };
        yield { type: 'system', subtype: 'session_state_changed', state: 'running' };
        yield { type: 'assistant', message: { content: [{ type: 'text', text: '어느 화면부터 볼까요?' }] } };
        yield { type: 'result', subtype: 'success', duration_ms: 10, total_cost_usd: 0.1, usage: {} };
        await new Promise((r) => setTimeout(r, 5));
        openAfterResult = !ended;
        yield { type: 'system', subtype: 'session_state_changed', state: 'idle' };
        await endedP;
      })(),
    );
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run(freshClaim);

    expect(openAfterResult).toBe(true);
    expect(client.fail).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({ claudeSessionId: 'sess-gate' }));
  });

  // 2026-09-22 라이브 검증: SDK 인증 만료로 턴이 죽었는데 interview.log는 기동 한 줄에 머물렀다.
  // 서버 보고만으로는 운영자가 로그에서 원인을 볼 수 없다 — 실패 경로는 stdout에도 한 줄 남겨야 한다.
  it('logs every session failure to stdout, not just to the server', async () => {
    const client = makeClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fakeQuery = vi.fn(() => {
      throw new Error('Claude Code returned an error result: OAuth session expired');
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run(freshClaim);

    expect(client.fail).toHaveBeenCalledWith(42, expect.stringContaining('OAuth session expired'));
    const logged = warn.mock.calls.map((c) => c.join(' ')).join('\n');
    expect(logged).toContain('42');
    expect(logged).toContain('OAuth session expired');
    warn.mockRestore();
  });

  it('masks credentials in the failure it logs', async () => {
    const client = makeClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fakeQuery = vi.fn(() => {
      throw new Error('fatal: https://oauth2:glpat-SECRET@gitlab.example.com/a/b.git not found');
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run(freshClaim);

    const logged = warn.mock.calls.map((c) => c.join(' ')).join('\n');
    expect(logged).not.toContain('glpat-SECRET');
    expect(logged).toContain('***@');
    warn.mockRestore();
  });

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

  it('fresh claim with attachments: kickoff prompt lists absolute paths and Read instruction', async () => {
    const client = makeClient();
    let seenPrompt = '';
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
      (async () => { for await (const p of args.prompt) seenPrompt += p.message?.content ?? ''; })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaimWithAttachments);
    expect(seenPrompt).toContain('첨부 자료');
    expect(seenPrompt).toContain('/Users/micthebick/netis-maker/attachments/task-7/1-요구사항.pdf');
    expect(seenPrompt).toContain('Read');
    // contentType null은 리터럴 'null'로 렌더링되지 않는다 (스펙 §7)
    expect(seenPrompt).not.toContain('(null');
  });

  it('fresh claim with zero attachments: no attachment section', async () => {
    const client = makeClient();
    let seenPrompt = '';
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
      (async () => { for await (const p of args.prompt) seenPrompt += p.message?.content ?? ''; })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(seenPrompt).not.toContain('첨부 자료');
  });

  it('legacy claim WITHOUT the attachments field does not crash (unconditional guard)', async () => {
    const legacy = { ...freshClaim } as Record<string, unknown>;
    delete legacy.attachments;
    const client = makeClient();
    let seenPrompt = '';
    // MUST drain the prompt like the sibling tests: vi.fn(() => questionStream()) never
    // consumes it, so attachmentSection() is never invoked and this test cannot fail.
    // The drain promise is captured and awaited so a throw inside the generator surfaces
    // as a test failure instead of an (ignorable) unhandled rejection.
    let drained: Promise<void> = Promise.resolve();
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
      drained = (async () => { for await (const p of args.prompt) seenPrompt += p.message?.content ?? ''; })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(legacy as never);
    await drained;
    // kickoff 프롬프트가 실제로 만들어졌고(=가드를 통과했고) 첨부 섹션만 비어 있어야 한다.
    expect(seenPrompt).toContain('Title:');
    expect(seenPrompt).not.toContain('첨부 자료');
    expect(client.fail).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalled();
  });

  it('resume claim: attachment section is NOT injected (session already has context)', async () => {
    const resumeWithAtts = { ...resumeClaim, attachments: freshClaimWithAttachments.attachments };
    const client = makeClient();
    let seenPrompt = '';
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
      (async () => { for await (const p of args.prompt) seenPrompt += p.message?.content ?? ''; })();
      return questionStream();
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(resumeWithAtts);
    expect(seenPrompt).not.toContain('첨부 자료');
  });

  it('harvests a KOREAN plan and POSTs /plan instead of /question', async () => {
    const client = makeClient();
    async function* koreanPlanStream() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-k' };
      yield { type: 'assistant', message: { content: [{ type: 'text',
        text: '# 설계\n\n개요.\n\n# 서버관리 구현 계획\n\n### 작업 1: DTO\n- [ ] a' }] } };
      yield { type: 'result', subtype: 'success', total_cost_usd: 0.2, usage: {}, duration_ms: 1000 };
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
});

describe('InterviewRunner plan 파일 대체 판정 (2026-10-08 세션 21)', () => {
  // writing-plans가 plan을 파일에만 쓰고 채팅엔 요약+질문만 남긴 턴 — 헤딩이 줄 머리가 아니라 백틱 속에 있다.
  async function* summaryOnly() {
    yield { type: 'system', subtype: 'init', session_id: 'sess-f' };
    yield { type: 'assistant', message: { content: [{ type: 'text',
      text: '계획을 `docs/superpowers/plans/p.md`에 저장했습니다.\n\n**계획 요약 (`# 배포 테스트 구현 계획`)**\n\n원하시는 내용이 맞나요?' }] } };
    yield {
      type: 'result', subtype: 'success', duration_ms: 100,
      total_cost_usd: 0.1, usage: { input_tokens: 100, output_tokens: 20, cache_creation_input_tokens: 5, cache_read_input_tokens: 200 },
    };
  }
  const fileHarvest = {
    ok: true as const,
    harvest: {
      designMarkdown: '# 설계\n\n목적',
      planMarkdown: '# 배포 테스트 구현 계획\n\n### 작업 1: README 수정',
      planJson: [{ task: 1, title: 'README 수정' }],
    },
  };

  it('채팅 본문 추출이 실패해도 세션 plan 파일이 있으면 그 내용으로 postPlan한다 (재질의 없음)', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => summaryOnly());
    const harvestPlanFiles = vi.fn().mockResolvedValue(fileHarvest);
    const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, harvestPlanFiles } as never);

    await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });

    expect(harvestPlanFiles).toHaveBeenCalledWith(resumeClaim.workDir);
    expect(fakeQuery).toHaveBeenCalledTimes(1);
    expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({
      designMarkdown: '# 설계\n\n목적',
      planMarkdown: '# 배포 테스트 구현 계획\n\n### 작업 1: README 수정',
      planJson: JSON.stringify([{ task: 1, title: 'README 수정' }]),
      costUsd: 0.1,
    }));
    expect(client.postQuestion).not.toHaveBeenCalled();
  });

  it('채팅 본문에 plan이 있으면 파일은 보지 않는다 (기존 경로 우선)', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => planCompleteStream());
    const harvestPlanFiles = vi.fn().mockResolvedValue(fileHarvest);
    const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, harvestPlanFiles } as never);

    await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });

    expect(harvestPlanFiles).not.toHaveBeenCalled();
    expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({
      planMarkdown: expect.stringContaining('## File Structure'),
    }));
  });

  it('plan 파일도 없으면 기존대로 질문으로 보낸다', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => summaryOnly());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });

    expect(client.postPlan).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({
      content: expect.stringContaining('원하시는 내용이 맞나요?'),
    }));
  });
});

describe('InterviewRunner near-miss correction', () => {
  it('near-miss: harvest fails but plan-intent present → reformat splice once → re-harvest → postPlan', async () => {
    const client = makeClient();
    async function* intentNoStructure() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
      yield { type: 'assistant', message: { content: [{ type: 'text',
        text: '# 구현 계획 (초안)\n\n1. 가' }] } }; // plan 헤딩 O(꼬리 때문에 PLAN_HEADER 불일치), 추출 X
      yield {
        type: 'result', subtype: 'success', duration_ms: 100,
        total_cost_usd: 0.1, usage: { input_tokens: 100, output_tokens: 20, cache_creation_input_tokens: 5, cache_read_input_tokens: 200 },
      };
    }
    async function* reformattedPlan() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
      yield { type: 'assistant', message: { content: [{ type: 'text',
        text: '# 구현 계획\n\n### 작업 1: 가' }] } };
      yield {
        type: 'result', subtype: 'success', duration_ms: 200,
        total_cost_usd: 0.15, usage: { input_tokens: 300, output_tokens: 60, cache_creation_input_tokens: 15, cache_read_input_tokens: 400 },
      };
    }
    const fakeQuery = vi.fn()
      .mockImplementationOnce(() => intentNoStructure())
      .mockImplementationOnce(() => reformattedPlan());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });
    expect(fakeQuery).toHaveBeenCalledTimes(2); // 보정 splice 1회
    expect(client.postPlan).toHaveBeenCalledTimes(1);
    // 누적 검증: reformat retry는 별도 relay다 — 두 relay의 usage 합이 postPlan에 실려야 한다
    // (대입이면 retry분만 남아 첫 relay의 usage가 유실된다). 비용은 같은 세션 resume이라 SDK 보고값이 세션
    // 누적값이다(2026-10-15) — 0.1 + (0.15 − 0.1).
    expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({
      costUsd: 0.15,
      inputTokens: 400, // 100 + 300
      outputTokens: 80, // 20 + 60
      cacheCreationTokens: 20, // 5 + 15
      cacheReadTokens: 600, // 200 + 400
    }));
    expect(client.fail).not.toHaveBeenCalled();
  });

  it('near-miss reformat also fails → postQuestion (no fail)', async () => {
    const client = makeClient();
    async function* intentNoStructure() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n2' };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: '## 구현 계획 초안\n\n- 가' }] } };
      yield { type: 'result', subtype: 'success', total_cost_usd: 0.1, usage: {}, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => intentNoStructure()); // 매번 추출 불가
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });
    expect(fakeQuery).toHaveBeenCalledTimes(2);
    expect(client.postQuestion).toHaveBeenCalledTimes(1);
    expect(client.fail).not.toHaveBeenCalled();
  });

  it('질문 턴이 "구현 계획"을 언급만 하면 보정하지 않고 질문을 그대로 보낸다 (2026-09-29 세션 #11 회귀)', async () => {
    const client = makeClient();
    const questionText =
      '마지막에는 정해진 형식의 구현 계획 문서를 작성하겠습니다.\n\n---\n\n' +
      "**질문 1. '사용 방법' 섹션을 README의 어디에 넣을까요?**\n\n" +
      '- **A) 맨 위 (추천)**\n- **B) 끝부분**';
    async function* clarifyingQuestion() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-q1' };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: questionText }] } };
      yield { type: 'result', subtype: 'success', total_cost_usd: 0.1, usage: {}, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => clarifyingQuestion());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(fakeQuery).toHaveBeenCalledTimes(1); // 재작성 splice 없음
    expect(client.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({
      content: questionText,
      claudeSessionId: 'sess-q1',
      kind: 'question',
    }));
    expect(client.postPlan).not.toHaveBeenCalled();
  });
});

function turns(assistantCount: number) {
  const out: { seq: number; role: string; kind: string; content: string; replyToSeq: number | null }[] = [];
  let seq = 0;
  for (let i = 0; i < assistantCount; i++) {
    out.push({ seq: seq++, role: 'assistant', kind: 'question', content: `q${i}`, replyToSeq: null });
    out.push({ seq: seq++, role: 'user', kind: 'answer', content: 'a', replyToSeq: seq - 2 });
  }
  return out;
}

// ---- 참조 repo(스펙 2026-10-22) 테스트 공용 — describe 본문이 수집 시점에 읽으므로 사용처보다 앞에 둔다 ----
const REF_SHA = 'a'.repeat(40);
const REF_CLAIM: RefRepoClaim = questionClaimWithRefs.refRepos![0]!;
/** 준비에 성공한 참조 (error === null, sha = HEAD). */
const refAvailable = (over: Partial<RefPrepared> = {}): RefPrepared => ({ ref: REF_CLAIM, sha: REF_SHA, error: null, ...over });
/** 준비에 실패한 참조 — repoPrepare 계약처럼 sha는 null, error는 비어 있지 않은 문구. */
const refFailed = (error: string, over: Partial<RefRepoClaim> = {}): RefPrepared => ({ ref: { ...REF_CLAIM, ...over }, sha: null, error });

describe('InterviewRunner kind=QUESTION (스펙 §6 — plan 경로 미진입, Q&A 전용)', () => {
  type Captured = { prompt: string; options: Record<string, unknown> };
  /** 프롬프트 텍스트 + options를 캡처하고 주어진 스트림을 돌려주는 fakeQuery. */
  function capturing(streamFactory: () => AsyncIterable<unknown>) {
    const captured: Captured = { prompt: '', options: {} };
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }>; options: Record<string, unknown> }) => {
      captured.options = args.options;
      (async () => { for await (const p of args.prompt) captured.prompt += p.message?.content ?? ''; })();
      return streamFactory();
    });
    return { fakeQuery, captured };
  }

  it('fresh: Q&A 계약 킥오프(질문 본문 포함, 스킬/plan 문구 없음) + QUESTION 옵션 + 답변은 postQuestion', async () => {
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(questionClaim);

    expect(captured.prompt).toContain('로그인은 어디서 처리되나요?');
    expect(captured.prompt).toContain('Q&A');
    expect(captured.prompt).toContain('읽기 전용');
    expect(captured.prompt).not.toContain('brainstorming');
    expect(captured.prompt).not.toContain('### 작업 N:');
    expect(captured.options.plugins).toEqual([]);
    expect(captured.options.allowedTools).toEqual([]);
    expect(captured.options.cwd).toBe(questionClaim.workDir);
    expect(client.postQuestion).toHaveBeenCalledWith(77, expect.objectContaining({
      content: 'Which columns should the CSV include?',
      claudeSessionId: 'sess-new-1',
      kind: 'question',
      costUsd: 0.12,
      inputTokens: 1000,
      outputTokens: 250,
      cacheCreationTokens: 30,
      cacheReadTokens: 8000,
    }));
    expect(client.postPlan).not.toHaveBeenCalled();
  });

  it('resume: 후속 질문(lastAnswer)을 그대로 주입하고 options.resume을 쓴다', async () => {
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...questionClaim, claudeSessionId: 'sess-q-1', lastAnswer: '토큰 검증은요?', turns: turns(1) });
    expect(captured.prompt).toBe('토큰 검증은요?');
    expect(captured.options.resume).toBe('sess-q-1');
  });

  it('resume: 대화 중 바뀐 claim.model/effort가 그 턴의 SDK 옵션에 그대로 실린다 (claim당 조립, 첫 턴 값 캐시 없음)', async () => {
    // 계약 고정 테스트(스펙 2026-09-05 §2 개정 "대화 중 모델·effort 변경"): Java가 ask 바디의 model/effort로 세션을
    // 갱신하면 다음 claim이 새 값을 싣고, 러너는 그 값을 resume 턴 옵션에 넣어야 다음 답변부터 모델이 바뀐다.
    // CLI는 --resume 세션에서도 --model/--effort를 적용한다(2026-09-06 실측: sonnet 세션 + haiku → modelUsage=haiku).
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({
      ...questionClaim, claudeSessionId: 'sess-q-1', lastAnswer: '이번 건 싸게 답해줘', turns: turns(1),
      model: 'claude-haiku-4-5', effort: 'low',
    });
    expect(captured.options.resume).toBe('sess-q-1');
    expect(captured.options.model).toBe('claude-haiku-4-5');
    expect(captured.options.effort).toBe('low');
  });

  it('가드①: forceFinish 턴수(19)에서도 reformat 프롬프트가 아니라 후속 질문을 보낸다', async () => {
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never); // forceFinishTurns 19
    await runner.run({ ...questionClaim, claudeSessionId: 'sess-q-1', lastAnswer: '마지막 질문', turns: turns(19) });
    expect(fakeQuery).toHaveBeenCalledTimes(1);
    expect(captured.prompt).toBe('마지막 질문');
    expect(captured.prompt).not.toContain('### 작업 N:');
    expect(client.postQuestion).toHaveBeenCalledTimes(1);
  });

  it('가드②: handoff 문구가 와도 splice 재질의 없이 그 텍스트가 답변으로 저장된다', async () => {
    const client = makeClient();
    async function* handoffText() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-h' };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: 'Spec approved. Invoke writing-plans skill now.' }] } };
      yield { type: 'result', subtype: 'success', total_cost_usd: 0.05, usage: {}, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => handoffText());
    const spliceRead = vi.fn();
    const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, spliceRead } as never);
    await runner.run(questionClaim);
    expect(fakeQuery).toHaveBeenCalledTimes(1);
    expect(spliceRead).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalledWith(77, expect.objectContaining({ content: expect.stringContaining('Invoke writing-plans') }));
    expect(client.postPlan).not.toHaveBeenCalled();
  });

  it('가드③: plan 의도 문구(구조 없음)도 reformat 재질의 없이 답변', async () => {
    const client = makeClient();
    async function* intentNoStructure() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: '이제 구현 계획을 정리하겠습니다.' }] } };
      yield { type: 'result', subtype: 'success', total_cost_usd: 0.1, usage: {}, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => intentNoStructure());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(questionClaim);
    expect(fakeQuery).toHaveBeenCalledTimes(1);
    expect(client.postQuestion).toHaveBeenCalledTimes(1);
    expect(client.postPlan).not.toHaveBeenCalled();
  });

  it('가드④: plan 정규 형식 답변이 와도 postPlan이 아니라 postQuestion', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => planCompleteStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...questionClaim, claudeSessionId: 'sess-q-1', lastAnswer: '계획 써줘', turns: turns(1) });
    expect(client.postPlan).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalledWith(77, expect.objectContaining({
      content: expect.stringContaining('Implementation Plan'),
      costUsd: 0.31,
      inputTokens: 2000,
      outputTokens: 500,
      cacheCreationTokens: 60,
      cacheReadTokens: 5400,
    }));
  });

  it('turn cap: 중립 문구로 fail (plan 언급 없음), 턴 미실행', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never); // maxTurns 20
    await runner.run({ ...questionClaim, claudeSessionId: 'sess-q-1', turns: turns(20) });
    expect(fakeQuery).not.toHaveBeenCalled();
    expect(client.fail).toHaveBeenCalledWith(77, expect.stringContaining('최대 문답 수'));
    expect(client.fail).not.toHaveBeenCalledWith(77, expect.stringContaining('plan'));
  });

  it('환경 준비(ensureRepo)는 QUESTION에서도 매 턴 실행된다 (방어 계층 ⑤)', async () => {
    const client = makeClient();
    ensureRepo.mockClear();
    const runner = new InterviewRunner(client as never, vi.fn(() => questionStream()) as never, deps as never);
    await runner.run(questionClaim);
    expect(ensureRepo).toHaveBeenCalledWith(expect.objectContaining({ githubRepo: 'acme/widgets', workDir: questionClaim.workDir }));
  });

  // 서버 doFail이 세션 종류에 맞는 라벨("답변 실패: "/"인터뷰 실패: ")을 붙이므로 러너는 원인만 보낸다.
  // 러너가 자기 접두사를 얹으면 배너가 "답변 실패: 답변 생성 실패: …"처럼 겹친다(라이브 검증 2026-09-22).
  it('SDK 쿼리가 throw하면 fail 사유는 원인 메시지 그대로다 — 러너 접두사 없음 (QUESTION)', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => {
      throw new Error('boom');
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(questionClaim);
    expect(client.fail).toHaveBeenCalledTimes(1);
    expect(client.fail.mock.calls[0]![1]).toBe('boom');
  });

  it('SDK 쿼리가 throw하면 fail 사유는 원인 메시지 그대로다 — 러너 접두사 없음 (INTERVIEW)', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => {
      throw new Error('boom');
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(client.fail).toHaveBeenCalledTimes(1);
    expect(client.fail.mock.calls[0]![1]).toBe('boom');
  });

  it('kind 미존재(구버전 백엔드) → 인터뷰 킥오프 그대로', async () => {
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(captured.prompt).toContain('brainstorming');
    expect(captured.options.plugins).toEqual([{ type: 'local', path: '/sp/5.1.0' }]);
  });

  describe('첨부파일 (스펙 2026-09-13 §6)', () => {
    const ATT_ROOT = '/Users/micthebick/netis-maker/attachments/question-77';
    const DOCX = `${ATT_ROOT}/create/1-요구사항.docx`;
    const DOCX_TXT = `${ATT_ROOT}/create/1-요구사항.docx.txt`;
    const PDF = `${ATT_ROOT}/create/2-화면.pdf`;

    it('fresh: "질문에 첨부된 파일:" 섹션이 질문 뒤·규칙 앞에 오고, 오피스는 sidecar Read 안내를 단다', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
      await runner.run(questionClaimWithAttachments);

      const p = captured.prompt;
      expect(p).toContain('질문에 첨부된 파일:');
      expect(p).toContain(`- 요구사항.docx: ${DOCX} (텍스트 추출본: ${DOCX_TXT} — 이 경로를 Read하세요)`);
      expect(p).toContain(`- 화면.pdf: ${PDF}`);
      expect(p).not.toContain(`${PDF} (텍스트 추출본`);
      expect(p).toContain('Read 도구로 위 파일을 읽고 답변에 반영');
      // 순서: 질문 → 첨부 섹션 → 규칙
      expect(p.indexOf('로그인은 어디서 처리되나요?')).toBeLessThan(p.indexOf('질문에 첨부된 파일:'));
      expect(p.indexOf('질문에 첨부된 파일:')).toBeLessThan(p.indexOf('규칙:'));
      // 규칙 한 줄: 첨부는 체크아웃 밖(세션 첨부 디렉토리)에 있을 수 있고 Read로만 읽는다
      expect(p).toContain('첨부');
      expect(p).toMatch(/체크아웃 밖.*Read/);
      // 인터뷰 킥오프의 첨부 문구는 섞이지 않는다
      expect(p).not.toContain('첨부 자료 (요청자가 등록 시 업로드한 파일)');
    });

    it('fresh: attachmentRoot가 buildOptions → canUseTool에 전달돼 첨부 디렉토리 Read가 허용된다', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
      await runner.run(questionClaimWithAttachments);
      const gate = captured.options.canUseTool as (t: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
      expect((await gate('Read', { file_path: DOCX_TXT })).behavior).toBe('allow');
      expect((await gate('Read', { file_path: '/Users/micthebick/netis-maker/attachments/question-78/x' })).behavior).toBe('deny');
      expect((await gate('Grep', { pattern: 'x', path: ATT_ROOT })).behavior).toBe('deny');
    });

    it('fresh: 첨부 0건 / 필드 없음(구버전 Java) → 섹션 없음, 첨부 디렉토리 Read는 deny', async () => {
      for (const claim of [
        { ...questionClaim, attachments: [] },
        (() => { const c = { ...questionClaim } as Partial<typeof questionClaim>; delete c.attachments; delete c.attachmentRoot; return c as typeof questionClaim; })(),
      ]) {
        const client = makeClient();
        const { fakeQuery, captured } = capturing(() => questionStream());
        const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
        await runner.run(claim);
        expect(captured.prompt).toContain('로그인은 어디서 처리되나요?');
        expect(captured.prompt).not.toContain('첨부된 파일:');
        expect(captured.prompt).not.toContain('Read 도구로 위 파일을 읽고');
        const gate = captured.options.canUseTool as (t: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
        expect((await gate('Read', { file_path: DOCX_TXT })).behavior).toBe('deny');
      }
    });

    it('resume: 마지막 user/answer 턴의 첨부를 "이 메시지에 첨부된 파일:"로 lastAnswer 뒤에 붙인다 (이전 턴 첨부는 제외)', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
      const older = { id: 21, fileName: '옛날.txt', absolutePath: `${ATT_ROOT}/2/1-옛날.txt`, contentType: 'text/plain', sizeBytes: 10, extractedTextPath: null };
      const latest = { id: 22, fileName: '표.xlsx', absolutePath: `${ATT_ROOT}/4/1-표.xlsx`, contentType: null, sizeBytes: 20, extractedTextPath: `${ATT_ROOT}/4/1-표.xlsx.txt` };
      await runner.run({
        ...questionClaimWithAttachments,
        claudeSessionId: 'sess-q-1',
        lastAnswer: '이 표 기준으로 다시 설명해줘',
        replyToSeq: 4,
        turns: [
          { seq: 1, role: 'assistant', kind: 'question', content: 'a1', replyToSeq: null },
          { seq: 2, role: 'user', kind: 'answer', content: 'q2', replyToSeq: 1, attachments: [older] },
          { seq: 3, role: 'assistant', kind: 'question', content: 'a3', replyToSeq: null },
          { seq: 4, role: 'user', kind: 'answer', content: '이 표 기준으로 다시 설명해줘', replyToSeq: 3, attachments: [latest] },
          { seq: 5, role: 'system', kind: 'note', content: 'MCP 도구 변경: ctx7', replyToSeq: null },
        ],
      });
      const p = captured.prompt;
      expect(p.startsWith('이 표 기준으로 다시 설명해줘')).toBe(true);
      expect(p).toContain('이 메시지에 첨부된 파일:');
      expect(p).toContain(`- 표.xlsx: ${ATT_ROOT}/4/1-표.xlsx (텍스트 추출본: ${ATT_ROOT}/4/1-표.xlsx.txt — 이 경로를 Read하세요)`);
      expect(p).toContain('Read 도구로 위 파일을 읽고 답변에 반영');
      expect(p).not.toContain('옛날.txt');
      // 등록 시 첨부(claim.attachments)는 resume에서 다시 나열하지 않는다
      expect(p).not.toContain('요구사항.docx');
      expect(p).not.toContain('질문에 첨부된 파일:');
      expect(captured.options.resume).toBe('sess-q-1');
      const gate = captured.options.canUseTool as (t: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
      expect((await gate('Read', { file_path: `${ATT_ROOT}/4/1-표.xlsx.txt` })).behavior).toBe('allow');
    });

    it('resume: 마지막 answer 턴에 첨부가 없거나(빈 배열/필드 없음) 턴이 없으면 lastAnswer만 그대로', async () => {
      for (const t of [
        turns(1),
        [{ seq: 1, role: 'assistant', kind: 'question', content: 'a1', replyToSeq: null }, { seq: 2, role: 'user', kind: 'answer', content: 'x', replyToSeq: 1, attachments: [] }],
        [],
      ]) {
        const client = makeClient();
        const { fakeQuery, captured } = capturing(() => questionStream());
        const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
        await runner.run({ ...questionClaimWithAttachments, claudeSessionId: 'sess-q-1', lastAnswer: '토큰 검증은요?', turns: t });
        expect(captured.prompt).toBe('토큰 검증은요?');
      }
    });
  });

  describe('DB MCP (스펙 2026-10-02 §6.4)', () => {
    const dbClaim = {
      ...questionClaim,
      dbConnections: [{ serverName: 'db-7', label: '운영 DB (MySQL)', dbType: 'MYSQL', host: 'h', port: 3306, database: 'app', username: 'u', password: 'Pw-secret' }],
      dbNotices: ['DB 연결 #9를 사용할 수 없습니다 — 삭제되었거나 비활성화됨'],
    } as never;
    const preparedFor = () => ({
      servers: { 'db-7': { type: 'sdk' as const, name: 'db-7', instance: {} } },
      tools: {},
      dialects: { 'db-7': 'mysql' as const },
      labels: [{ serverName: 'db-7', label: '운영 DB (MySQL)' }],
      notices: ['DB 연결 #9를 사용할 수 없습니다 — 삭제되었거나 비활성화됨'],
      close: vi.fn().mockResolvedValue(undefined),
    });

    it('DB 서버를 붙이고 프롬프트 앞에 서버↔DB 매핑·안내를 넣고, 끝나면 close한다', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const prepared = preparedFor();
      const createDbMcp = vi.fn().mockReturnValue(prepared);
      const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, createDbMcp } as never);
      await runner.run(dbClaim);

      expect(createDbMcp).toHaveBeenCalledWith(dbClaim);
      expect((captured.options.mcpServers as Record<string, unknown>)['db-7']).toBe(prepared.servers['db-7']);
      expect(captured.prompt.startsWith('사용 가능한 DB 도구')).toBe(true);
      expect(captured.prompt).toContain('mcp__db-7__query / list_tables / describe_table : 운영 DB (MySQL)');
      expect(captured.prompt).toContain('참고: DB 연결 #9를 사용할 수 없습니다');
      expect(captured.prompt).toContain('로그인은 어디서 처리되나요?');
      expect(captured.prompt).not.toContain('Pw-secret');
      expect(prepared.close).toHaveBeenCalledTimes(1);
    });

    it('closes_db_sessions_when_query_throws', async () => {
      // Review Focus 5
      const client = makeClient();
      const fakeQuery = vi.fn(() => { throw new Error('boom'); });
      const prepared = preparedFor();
      const runner = new InterviewRunner(client as never, fakeQuery as never,
        { ...deps, createDbMcp: vi.fn().mockReturnValue(prepared) } as never);
      await runner.run(dbClaim);
      expect(prepared.close).toHaveBeenCalledTimes(1);
      expect(client.fail).toHaveBeenCalled();
    });

    it('DB 연결이 없으면 프롬프트·옵션이 기존과 같다', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
      await runner.run(questionClaim);
      expect(captured.prompt.startsWith('당신은')).toBe(true);
      expect(Object.keys((captured.options.mcpServers ?? {}) as object)).not.toContain('db-7');
    });

    it('INTERVIEW에도 DB 서버를 붙이고 첫 프롬프트에 안내를 넣고, 끝나면 close한다 (스펙 2026-10-14 §5)', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => planCompleteStream());
      const prepared = preparedFor();
      const createDbMcp = vi.fn().mockReturnValue(prepared);
      const interviewDbClaim = { ...freshClaim, dbConnections: (dbClaim as { dbConnections: unknown }).dbConnections } as never;
      const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, createDbMcp } as never);
      await runner.run(interviewDbClaim);

      expect(createDbMcp).toHaveBeenCalledWith(interviewDbClaim);
      expect((captured.options.mcpServers as Record<string, unknown>)['db-7']).toBe(prepared.servers['db-7']);
      expect(captured.options.allowedTools as string[]).not.toContain('mcp__db-7');
      expect(captured.prompt.startsWith('사용 가능한 DB 도구')).toBe(true);
      expect(captured.prompt).toContain('PLANNING-ONLY');
      expect(prepared.close).toHaveBeenCalledTimes(1);
    });
  });

  describe('참조 repo (스펙 2026-10-22 §5.2·§5.4)', () => {
    type Gate = (t: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
    type Client = ReturnType<typeof makeClient>;
    /** 주입한 ensureRefRepos가 항상 results를 돌려준다 — 실제 git은 돌지 않는다. */
    const preparer = (results: RefPrepared[]) => vi.fn().mockResolvedValue(results);
    const runnerWith = (client: Client, fakeQuery: unknown, ensureRefRepos: unknown, extra: Record<string, unknown> = {}) =>
      new InterviewRunner(client as never, fakeQuery as never, { ...deps, ...extra, ensureRefRepos } as never);
    const reportedBody = (client: Client): Record<string, unknown> => client.postQuestion.mock.calls[0]![1] as Record<string, unknown>;
    const logged = (warn: { mock: { calls: unknown[][] } }): string => warn.mock.calls.map((c) => c.join(' ')).join('\n');

    it('question claim without refRepos never prepares refs and keeps the kickoff prompt unchanged', async () => {
      const runs: { prompt: string; body: Record<string, unknown> }[] = [];
      // refRepos 필드가 아예 없는 claim(구버전 Java)과 빈 배열(참조 없는 질문)은 같은 동작이어야 한다
      for (const claim of [questionClaim, { ...questionClaim, refRepos: [] }]) {
        const client = makeClient();
        const { fakeQuery, captured } = capturing(() => questionStream());
        const ensureRefRepos = vi.fn();
        await runnerWith(client, fakeQuery, ensureRefRepos).run(claim);

        expect(ensureRefRepos).not.toHaveBeenCalled();
        expect(client.fail).not.toHaveBeenCalled();
        expect(captured.prompt.startsWith('당신은')).toBe(true);
        expect(captured.prompt).not.toContain('참조 repo');
        expect(reportedBody(client)).not.toHaveProperty('refStates'); // 키 자체가 없다 — undefined 값도 아니다
        // 참조 폴더는 게이트 루트가 아니다
        const gate = captured.options.canUseTool as Gate;
        expect((await gate('Read', { file_path: '/r/_refs/5/12/A.java' })).behavior).toBe('deny');
        runs.push({ prompt: captured.prompt, body: reportedBody(client) });
      }
      expect(runs[1]!.prompt).toBe(runs[0]!.prompt);
      expect(JSON.stringify(runs[1]!.body)).toBe(JSON.stringify(runs[0]!.body));
    });

    it('question claim with refs: prepares them, prefixes the ref section, lets the gate read them, and reports refStates', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const ensureRefRepos = preparer([refAvailable()]);
      const gitTokens = { gitlabToken: 'glpat-X' };
      await runnerWith(client, fakeQuery, ensureRefRepos, { gitTokens }).run(questionClaimWithRefs);

      // claim의 참조 목록 그대로 + 서비스의 git 토큰 + 턴의 AbortSignal(SDK에 넘기는 abortController와 같은 것)
      // + 참조 하나당 받기 상한(기본 5분 — 스펙 2026-10-24 §5.1, 질문 세션에도 적용)
      expect(ensureRefRepos).toHaveBeenCalledTimes(1);
      expect(ensureRefRepos).toHaveBeenCalledWith(questionClaimWithRefs.refRepos, {
        tokens: gitTokens,
        signal: expect.any(AbortSignal),
        timeoutMs: 300_000,
      });
      expect((ensureRefRepos.mock.calls[0]![1] as { signal: AbortSignal }).signal)
        .toBe((captured.options.abortController as AbortController).signal);

      // 프롬프트: 참조 절이 맨 앞, 그 뒤에 지금과 같은 질문 킥오프
      const p = captured.prompt;
      expect(p.startsWith('참조 repo (읽기 전용 — 비교·참고용, 수정 금지):\n')).toBe(true);
      expect(p).toContain('- 옛앱 · release/6.5@aaaaaaa : /r/_refs/5/12\n');
      expect(p).not.toContain('사용할 수 없는 참조:'); // 규칙 줄에도 같은 말이 들어 있어 콜론까지 본다 — 못 받은 참조 줄이 없다
      expect(p.indexOf('규칙:\n- 대상 repo는 현재 작업 디렉토리다.')).toBeLessThan(p.indexOf('당신은'));
      expect(p).toContain('로그인은 어디서 처리되나요?');

      // 게이트: 참조 폴더 안은 Read/Grep/Glob 허용, Bash는 그대로(대상 안만)
      const gate = captured.options.canUseTool as Gate;
      expect((await gate('Read', { file_path: '/r/_refs/5/12/A.java' })).behavior).toBe('allow');
      expect((await gate('Grep', { pattern: 'login', path: '/r/_refs/5/12' })).behavior).toBe('allow');
      expect((await gate('Glob', { pattern: '**/*.java', path: '/r/_refs/5/12' })).behavior).toBe('allow');
      expect((await gate('Bash', { command: 'cat /r/_refs/5/12/A.java' })).behavior).toBe('deny');
      expect((await gate('Read', { file_path: '/r/_refs/5/120/A.java' })).behavior).toBe('deny'); // 이름 앞부분만 같은 형제

      // 보고: 참조 준비 결과가 답변과 함께 간다
      expect(client.postQuestion).toHaveBeenCalledWith(77, expect.objectContaining({
        content: 'Which columns should the CSV include?',
        refStates: [{ catalogId: 12, branch: 'release/6.5', sha: REF_SHA, error: null }],
      }));
      expect(client.fail).not.toHaveBeenCalled();
    });

    it('ref prepare failure still answers and reports the masked error', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      await runnerWith(client, fakeQuery, preparer([refFailed('권한 없음')])).run(questionClaimWithRefs);

      // 세션 실패가 아니다 — 그 참조 없이 답한다
      expect(client.fail).not.toHaveBeenCalled();
      expect(fakeQuery).toHaveBeenCalledTimes(1);
      // 프롬프트: 사용 가능한 참조가 없으면 머리말과 `- …` 줄은 빠지고 "사용할 수 없는 참조" 줄만 남는다
      expect(captured.prompt.startsWith('사용할 수 없는 참조: 옛앱(release/6.5) — 권한 없음\n규칙:\n')).toBe(true);
      expect(captured.prompt).not.toContain('참조 repo (읽기 전용');
      // 게이트: 못 받은 참조 폴더는 허용 루트가 아니다
      const gate = captured.options.canUseTool as Gate;
      expect((await gate('Read', { file_path: '/r/_refs/5/12/A.java' })).behavior).toBe('deny');
      expect((await gate('Grep', { pattern: 'x', path: '/r/_refs/5/12' })).behavior).toBe('deny');
      // 보고: 답변은 정상 보고되고 refStates에 사유가 그대로 실린다
      expect(client.postQuestion).toHaveBeenCalledWith(77, expect.objectContaining({
        content: 'Which columns should the CSV include?',
        refStates: [{ catalogId: 12, branch: 'release/6.5', sha: null, error: '권한 없음' }],
      }));
      warn.mockRestore();
    });

    it('resume turn re-sends the ref section', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const ensureRefRepos = preparer([refAvailable({ ref: { ...REF_CLAIM, sha: REF_SHA } })]);
      await runnerWith(client, fakeQuery, ensureRefRepos).run({
        ...questionClaimWithRefs, claudeSessionId: 'sess-q-1', lastAnswer: '토큰 검증은요?', turns: turns(1),
      });

      expect(ensureRefRepos).toHaveBeenCalledTimes(1); // 추가 질문 claim마다 다시 준비한다(스펙 §5.1)
      expect(captured.options.resume).toBe('sess-q-1');
      expect(captured.prompt.startsWith('참조 repo (읽기 전용 — 비교·참고용, 수정 금지):\n')).toBe(true);
      expect(captured.prompt).toContain('- 옛앱 · release/6.5@aaaaaaa : /r/_refs/5/12\n');
      expect(captured.prompt.endsWith('못 읽었다고 답한다.\n\n토큰 검증은요?')).toBe(true); // 접두 뒤에 후속 질문이 그대로 온다
      const gate = captured.options.canUseTool as Gate;
      expect((await gate('Read', { file_path: '/r/_refs/5/12/A.java' })).behavior).toBe('allow');
      expect(client.postQuestion).toHaveBeenCalledWith(77, expect.objectContaining({
        refStates: [{ catalogId: 12, branch: 'release/6.5', sha: REF_SHA, error: null }],
      }));
    });

    it('interview (non-question) claims prepare refRepos too and report refStates (스펙 2026-10-24 §5.1)', async () => {
      // kind 없음(구버전 백엔드)과 명시적 INTERVIEW 모두 — 참조를 보낼지는 API(refsFor)가 정하고 러너는 kind로 거르지 않는다
      for (const claim of [{ ...freshClaim, refRepos: interviewClaimWithRefs.refRepos }, interviewClaimWithRefs]) {
        const client = makeClient();
        const { fakeQuery, captured } = capturing(() => questionStream());
        const ref = interviewClaimWithRefs.refRepos![0]!;
        const ensureRefRepos = preparer([{ ref, sha: REF_SHA, error: null }]);
        await runnerWith(client, fakeQuery, ensureRefRepos).run(claim);

        expect(ensureRefRepos).toHaveBeenCalledTimes(1);
        expect(ensureRefRepos.mock.calls[0]![0]).toEqual(interviewClaimWithRefs.refRepos);
        expect(captured.prompt.startsWith('참조 repo (읽기 전용 — 비교·참고용, 수정 금지):\n')).toBe(true);
        expect(captured.prompt).toContain('PLANNING-ONLY'); // 인터뷰 킥오프가 뒤따른다
        expect(client.postQuestion).toHaveBeenCalledTimes(1);
        expect(reportedBody(client).refStates).toEqual([{ catalogId: 12, branch: 'release/6.5', sha: REF_SHA, error: null }]);
        expect(client.fail).not.toHaveBeenCalled();
      }
    });

    it('prepares references after the target checkout and before the SDK turn', async () => {
      ensureRepo.mockClear();
      const client = makeClient();
      const { fakeQuery } = capturing(() => questionStream());
      const ensureRefRepos = preparer([refAvailable()]);
      await runnerWith(client, fakeQuery, ensureRefRepos).run(questionClaimWithRefs);

      const firstCall = (fn: { mock: { invocationCallOrder: number[] } }) => fn.mock.invocationCallOrder[0]!;
      expect(firstCall(ensureRepo)).toBeLessThan(firstCall(ensureRefRepos));
      expect(firstCall(ensureRefRepos)).toBeLessThan(firstCall(fakeQuery));
    });

    it('only successfully prepared references become gate roots, and refStates keeps the input order', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const second = { catalogId: 13, alias: '공통 UI', branch: 'main', dir: '/r/_refs/5/13' };
      const claim = { ...questionClaimWithRefs, refRepos: [REF_CLAIM, { ...REF_CLAIM, ...second }] };
      await runnerWith(client, fakeQuery, preparer([refAvailable(), refFailed('브랜치를 찾을 수 없음', second)])).run(claim);

      const gate = captured.options.canUseTool as Gate;
      expect((await gate('Read', { file_path: '/r/_refs/5/12/A.java' })).behavior).toBe('allow');
      expect((await gate('Read', { file_path: '/r/_refs/5/13/B.java' })).behavior).toBe('deny');
      expect(captured.prompt).toContain(
        '- 옛앱 · release/6.5@aaaaaaa : /r/_refs/5/12\n사용할 수 없는 참조: 공통 UI(main) — 브랜치를 찾을 수 없음\n규칙:\n',
      );
      expect(reportedBody(client).refStates).toEqual([
        { catalogId: 12, branch: 'release/6.5', sha: REF_SHA, error: null },
        { catalogId: 13, branch: 'main', sha: null, error: '브랜치를 찾을 수 없음' },
      ]);
      warn.mockRestore();
    });

    it("prompt and report carry git's fatal line only; the command line and the rest go to the log (masked)", async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      // 실제 Node execFile 오류 모양(ensureRefRepos가 돌려주는 전문): `Command failed: <명령줄>` + stderr.
      // 첫 줄은 명령줄(로컬 경로 포함)이라 사유가 아니다 — 사유는 git의 fatal 줄이다 (Ruling 8b).
      const full =
        'Command failed: git clone --depth 1 --branch release/6.5 https://***@gitlab.example/g/old.git C:\\netis\\_refs\\5\\12.part\n' +
        "Cloning into 'C:\\netis\\_refs\\5\\12.part'...\n" +
        'fatal: Remote branch release/6.5 not found in upstream origin\n';
      const reason = 'fatal: Remote branch release/6.5 not found in upstream origin';
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      await runnerWith(client, fakeQuery, preparer([refFailed(full)])).run(questionClaimWithRefs);

      expect(captured.prompt).toContain(`사용할 수 없는 참조: 옛앱(release/6.5) — ${reason}\n규칙:\n`);
      expect(reportedBody(client).refStates).toEqual([{ catalogId: 12, branch: 'release/6.5', sha: null, error: reason }]);
      // 명령줄·로컬 경로·그 밖의 줄은 프롬프트와 보고에 나가지 않는다
      for (const text of [captured.prompt, JSON.stringify(reportedBody(client))]) {
        expect(text).not.toContain('Command failed');
        expect(text).not.toContain('C:\\netis');
        expect(text).not.toContain('Cloning into');
      }
      // 전체 문구는 운영자 로그에만 남는다
      const log = logged(warn);
      expect(log).toContain('Command failed: git clone');
      expect(log).toContain('C:\\netis\\_refs\\5\\12.part');
      expect(log).toContain('Cloning into');
      expect(log).toContain(reason);
      expect(log).toContain('77');
      expect(log).toContain('옛앱(release/6.5)');
      warn.mockRestore();
    });

    it('masks credentials even when the preparer left them in the message', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const full =
        'Command failed: git clone --depth 1 --branch release/6.5 https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git /r/_refs/5/12.part\n' +
        "fatal: unable to access 'https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git/': Could not resolve host: gitlab.hamon.vip\n";
      const reason = "fatal: unable to access 'https://***@gitlab.hamon.vip/g/p.git/': Could not resolve host: gitlab.hamon.vip";
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      await runnerWith(client, fakeQuery, preparer([refFailed(full)])).run(questionClaimWithRefs);

      expect(captured.prompt).toContain(`— ${reason}\n`);
      expect(reportedBody(client).refStates).toEqual([{ catalogId: 12, branch: 'release/6.5', sha: null, error: reason }]);
      const log = logged(warn);
      expect(log).toContain('***@'); // 로그의 전체 문구도 가려서 남는다
      expect(log).toContain('Could not resolve host');
      for (const text of [log, captured.prompt, JSON.stringify(reportedBody(client))]) expect(text).not.toContain('glpat-SECRET');
      warn.mockRestore();
    });

    it('"환경 준비" 활동의 detail에 참조 수(+ 참조 n)를 덧붙인다 — 참조 없는 질문·인터뷰는 대상만', async () => {
      // 참조가 있는 작업 인터뷰도 질문처럼 참조 수를 붙인다(스펙 2026-10-24 §5.1)
      const detailOf = async (claim: InterviewClaimResponse): Promise<unknown> => {
        const client = makeClient();
        await runnerWith(client, vi.fn(() => questionStream()), preparer([refAvailable()])).run(claim);
        const events = client.postActivity.mock.calls.flatMap(
          (c: unknown[]) => (c[1] as { events: Array<Record<string, unknown>> }).events,
        );
        expect(events[0]).toMatchObject({ type: 'tool', label: '환경 준비' });
        return events[0]!.detail;
      };
      const three = { ...questionClaimWithRefs, refRepos: [REF_CLAIM, { ...REF_CLAIM, catalogId: 13 }, { ...REF_CLAIM, catalogId: 14 }] };

      expect(await detailOf(questionClaimWithRefs)).toBe('acme/widgets + 참조 1');
      expect(await detailOf(three)).toBe('acme/widgets + 참조 3');
      expect(await detailOf(questionClaim)).toBe('acme/widgets');
      expect(await detailOf({ ...questionClaim, refRepos: [] })).toBe('acme/widgets');
      expect(await detailOf({ ...freshClaim })).toBe('acme/widgets');
      expect(await detailOf({ ...freshClaim, refRepos: questionClaimWithRefs.refRepos })).toBe('acme/widgets + 참조 1');
    });

    it('puts the ref section before the DB section', async () => {
      const db = {
        servers: {}, tools: {}, dialects: {},
        labels: [{ serverName: 'db-7', label: '운영 DB (MySQL)' }], notices: [],
        close: vi.fn().mockResolvedValue(undefined),
      };
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      await runnerWith(client, fakeQuery, preparer([refAvailable()]), { createDbMcp: vi.fn().mockReturnValue(db) }).run(questionClaimWithRefs);

      const p = captured.prompt;
      expect(p.startsWith('참조 repo (')).toBe(true);
      expect(p.indexOf('참조 repo (')).toBeLessThan(p.indexOf('사용 가능한 DB 도구'));
      expect(p.indexOf('사용 가능한 DB 도구')).toBeLessThan(p.indexOf('당신은'));
      expect(db.close).toHaveBeenCalledTimes(1);
    });

    it('treats an empty preparation result as no references (no section, no refStates)', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      await runnerWith(client, fakeQuery, preparer([])).run(questionClaimWithRefs);

      expect(captured.prompt.startsWith('당신은')).toBe(true);
      expect(reportedBody(client)).not.toHaveProperty('refStates');
      expect(client.fail).not.toHaveBeenCalled();
    });

    it('does not report refStates when the turn fails (the session ends, nothing to pin)', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const client = makeClient();
      const fakeQuery = vi.fn(() => {
        throw new Error('boom');
      });
      await runnerWith(client, fakeQuery, preparer([refAvailable()])).run(questionClaimWithRefs);

      expect(client.postQuestion).not.toHaveBeenCalled();
      expect(client.fail).toHaveBeenCalledTimes(1);
      expect(client.fail).toHaveBeenCalledWith(77, 'boom');
      warn.mockRestore();
    });

    it('a turn aborted while references are being prepared stops there: no SDK turn and no refStates report', async () => {
      vi.useFakeTimers();
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      try {
        const client = { ...makeClient(), heartbeat: vi.fn().mockResolvedValue(undefined) };
        const fakeQuery = vi.fn(() => questionStream());
        // 실제 ensureRefRepos처럼 abort를 예외가 아니라 "실패한 참조" 결과로 돌려준다 — 그래서 러너가 직접 abort를 확인해야 한다
        const ensureRefRepos = vi.fn((_refs: RefRepoClaim[], opts: { signal?: AbortSignal }) =>
          new Promise<RefPrepared[]>((resolve) => {
            opts.signal!.addEventListener('abort', () => resolve([refFailed('This operation was aborted')]));
          }),
        );
        const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, ensureRefRepos, turnTimeoutMs: 60_000 } as never);

        const done = runner.run(questionClaimWithRefs);
        await vi.advanceTimersByTimeAsync(60_000);
        await done;

        expect(fakeQuery).not.toHaveBeenCalled();
        expect(client.postQuestion).not.toHaveBeenCalled();
        expect(client.fail).toHaveBeenCalledTimes(1);
        expect(client.fail).toHaveBeenCalledWith(77, expect.stringContaining('wall-clock 타임아웃'));
      } finally {
        warn.mockRestore();
        vi.useRealTimers();
      }
    });

    it('a question preparer that throws (contract breach) does not fail the turn either (스펙 2026-10-24 §5.1)', async () => {
      // 준비기는 던지지 않는 계약이지만 어겨도 참조 문제로 턴이 실패하지 않는다 — 모든 참조를 못 받은 것으로 돌려 답한다
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const ensureRefRepos = vi.fn().mockRejectedValue(new Error('boom https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git'));
      await runnerWith(client, fakeQuery, ensureRefRepos).run(questionClaimWithRefs);

      const masked = 'boom https://***@gitlab.hamon.vip/g/p.git';
      expect(client.fail).not.toHaveBeenCalled();
      expect(fakeQuery).toHaveBeenCalledTimes(1);
      expect(captured.prompt.startsWith(`사용할 수 없는 참조: 옛앱(release/6.5) — ${masked}\n규칙:\n`)).toBe(true);
      const gate = captured.options.canUseTool as Gate;
      expect((await gate('Read', { file_path: '/r/_refs/5/12/A.java' })).behavior).toBe('deny');
      expect(reportedBody(client).refStates).toEqual([{ catalogId: 12, branch: 'release/6.5', sha: null, error: masked }]);
      for (const text of [captured.prompt, JSON.stringify(reportedBody(client)), logged(warn)]) {
        expect(text).not.toContain('glpat-SECRET');
      }
      warn.mockRestore();
    });

    // ── 작업 인터뷰(INTERVIEW)의 참조 repo ───────────────────────────────────────────────────
    describe('작업 인터뷰 (스펙 2026-10-24 §5)', () => {
      const IREF: RefRepoClaim = interviewClaimWithRefs.refRepos![0]!;
      const ISTATE = { catalogId: 12, branch: 'release/6.5', sha: REF_SHA, error: null };
      const iAvailable = (over: Partial<RefPrepared> = {}): RefPrepared => ({ ref: IREF, sha: REF_SHA, error: null, ...over });
      const iFailed = (error: string, over: Partial<RefRepoClaim> = {}): RefPrepared => ({
        ref: { ...IREF, ...over },
        sha: null,
        error,
      });
      /** 답변 턴(resume) — resumeClaim에 이 작업 인터뷰의 참조를 붙인 것. */
      const interviewResume: InterviewClaimResponse = {
        ...resumeClaim,
        kind: 'INTERVIEW',
        refRepos: interviewClaimWithRefs.refRepos,
      };
      const dbOf = (labels: { serverName: string; label: string }[]) => ({
        servers: {},
        tools: {},
        dialects: {},
        labels,
        notices: [] as string[],
        close: vi.fn().mockResolvedValue(undefined),
      });
      const noDb = () => dbOf([]);
      const withDb = () => dbOf([{ serverName: 'db-7', label: '운영 DB (MySQL)' }]);
      /**
       * freshClaim의 kickoff 프롬프트 — promptFor를 고치기 전(a5a36d1) 코드의 출력을 그대로 옮겨 고정했다.
       * 참조 없는 작업 인터뷰의 kickoff는 이 바이트를 지켜야 한다(스펙 §1.2).
       */
      const KICKOFF_GOLDEN =
        'I want to add a feature to the repo at acme/widgets (branch main).\n' +
        'Title: Add CSV export\nRequest: Users want to export the dashboard table as CSV.\n\n' +
        'IMPORTANT — this is a PLANNING-ONLY interview. Your only deliverable is a written implementation PLAN ' +
        '(via brainstorming → writing-plans), NOT code. Do NOT implement the feature: do not create or modify source files, ' +
        'do not run builds/installs/tests, do not git commit or push. Reading the repo for context is fine. Stop once the plan ' +
        'is written — a human reviews and approves it, and implementation happens later in a separate step.\n\n' +
        '최종 plan은 반드시 다음 정규 형식으로 작성하세요(설명은 한국어): 최상위 헤딩 `# <기능> 구현 계획`, ' +
        '그 아래 각 작업을 `### 작업 N: <제목>` 형식으로. 영문 `# <Feature> Implementation Plan` / `### Task N:` 도 허용됩니다. ' +
        '이 정확한 구조라야 시스템이 완료를 인식하며, 없으면 인터뷰가 끝나지 않습니다.\n\n' +
        'Use the brainstorming skill: read the project context, then ask me one clarifying question at a time.';

      /** 한 턴의 SDK 질의마다 프롬프트를 따로 모은다 — handoff·near-miss처럼 한 턴에 질의가 여러 번일 때. */
      function capturingEach(...streams: Array<() => AsyncIterable<unknown>>) {
        const prompts: string[] = [];
        const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }> }) => {
          const i = prompts.push('') - 1;
          (async () => {
            for await (const p of args.prompt) prompts[i] += p.message?.content ?? '';
          })();
          return streams[i]!();
        });
        return { fakeQuery, prompts };
      }

      it('INTERVIEW claim with refs: ensureRepo → ensureRefRepos → createDbMcp → SDK, with tokens, the turn signal and timeoutMs', async () => {
        ensureRepo.mockClear();
        const client = makeClient();
        const { fakeQuery, captured } = capturing(() => questionStream());
        const ensureRefRepos = preparer([iAvailable()]);
        const createDbMcp = vi.fn().mockReturnValue(noDb());
        const gitTokens = { gitlabToken: 'glpat-X' };
        await runnerWith(client, fakeQuery, ensureRefRepos, { gitTokens, createDbMcp }).run(interviewClaimWithRefs);

        expect(ensureRefRepos).toHaveBeenCalledTimes(1);
        expect(ensureRefRepos).toHaveBeenCalledWith(interviewClaimWithRefs.refRepos, {
          tokens: gitTokens,
          signal: expect.any(AbortSignal),
          timeoutMs: 300_000,
        });
        expect((ensureRefRepos.mock.calls[0]![1] as { signal: AbortSignal }).signal)
          .toBe((captured.options.abortController as AbortController).signal);
        const firstCall = (fn: { mock: { invocationCallOrder: number[] } }) => fn.mock.invocationCallOrder[0]!;
        expect(firstCall(ensureRepo)).toBeLessThan(firstCall(ensureRefRepos));
        expect(firstCall(ensureRefRepos)).toBeLessThan(firstCall(createDbMcp));
        expect(firstCall(createDbMcp)).toBeLessThan(firstCall(fakeQuery));
        // 인터뷰 옵션·게이트는 그대로 — Read/Grep/Glob은 사전 승인, 참조 폴더 쓰기는 지금처럼 막힌다(docs/superpowers 밖)
        expect(captured.options.allowedTools as string[]).toEqual(expect.arrayContaining(['Read', 'Grep', 'Glob']));
        const gate = captured.options.canUseTool as Gate;
        expect((await gate('Write', { file_path: '/r/_refs/42/12/A.java' })).behavior).toBe('deny');
        expect(client.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({ refStates: [ISTATE] }));
        expect(client.fail).not.toHaveBeenCalled();
      });

      it('kickoff: INTERVIEW ref section → DB section → existing kickoff', async () => {
        const db = withDb();
        const promptOf = async (claim: InterviewClaimResponse): Promise<string> => {
          const { fakeQuery, captured } = capturing(() => questionStream());
          await runnerWith(makeClient(), fakeQuery, preparer([iAvailable()]), { createDbMcp: vi.fn().mockReturnValue(db) }).run(claim);
          return captured.prompt;
        };
        const { refRepos: _refs, ...withoutRefs } = interviewClaimWithRefs;

        const baseline = await promptOf(withoutRefs);
        const withRefs = await promptOf(interviewClaimWithRefs);

        expect(baseline).toBe(dbSection(db) + KICKOFF_GOLDEN);
        expect(withRefs).toBe(interviewRefSection([iAvailable()]) + baseline);
        expect(withRefs).not.toContain('Read/Grep/Glob의 경로로 주어'); // 질문용 규칙이 아니다
      });

      it('resume: ref section + answer only — no DB section even with a DB attached', async () => {
        const { fakeQuery, captured } = capturing(() => questionStream());
        const prepared = [iAvailable()];
        await runnerWith(makeClient(), fakeQuery, preparer(prepared), { createDbMcp: vi.fn().mockReturnValue(withDb()) })
          .run(interviewResume);

        expect(captured.options.resume).toBe('sess-abc-123');
        expect(captured.prompt).toBe(interviewRefSection(prepared) + resumeClaim.lastAnswer);
      });

      it('no refs: kickoff/resume prompts and question/plan bodies are byte-identical', async () => {
        // 필드 없음(구버전 API) · 빈 목록 · refsCleared=false — 셋 다 참조 없는 인터뷰이고 지금과 같아야 한다
        const variantsOf = (claim: InterviewClaimResponse): InterviewClaimResponse[] => [
          claim,
          { ...claim, refRepos: [] },
          { ...claim, refRepos: [], refsCleared: false },
        ];
        const turnOf = async (claim: InterviewClaimResponse, db: ReturnType<typeof dbOf>, stream: () => AsyncIterable<unknown>) => {
          const client = makeClient();
          const { fakeQuery, captured } = capturing(stream);
          const ensureRefRepos = vi.fn();
          await runnerWith(client, fakeQuery, ensureRefRepos, { createDbMcp: vi.fn().mockReturnValue(db) }).run(claim);
          expect(ensureRefRepos).not.toHaveBeenCalled();
          expect(client.fail).not.toHaveBeenCalled();
          return { prompt: captured.prompt, client };
        };
        const bodies = { kickoff: new Set<string>(), resume: new Set<string>(), plan: new Set<string>() };

        for (const fresh of variantsOf(freshClaim)) {
          const plain = await turnOf(fresh, noDb(), () => questionStream());
          expect(plain.prompt).toBe(KICKOFF_GOLDEN);
          const db = withDb();
          expect((await turnOf(fresh, db, () => questionStream())).prompt).toBe(dbSection(db) + KICKOFF_GOLDEN);
          const body = plain.client.postQuestion.mock.calls[0]![1] as object;
          expect(body).not.toHaveProperty('refStates');
          bodies.kickoff.add(JSON.stringify(body));
        }
        for (const resume of variantsOf(resumeClaim)) {
          for (const db of [noDb(), withDb()]) {
            const { prompt, client } = await turnOf(resume, db, () => questionStream());
            expect(prompt).toBe(resumeClaim.lastAnswer);
            const body = client.postQuestion.mock.calls[0]![1] as object;
            expect(body).not.toHaveProperty('refStates');
            bodies.resume.add(JSON.stringify(body));
          }
          const { client } = await turnOf(resume, noDb(), () => planCompleteStream());
          expect(client.postQuestion).not.toHaveBeenCalled();
          const plan = client.postPlan.mock.calls[0]![1] as object;
          expect(plan).not.toHaveProperty('refStates');
          bodies.plan.add(JSON.stringify(plan));
        }
        // 세 가지 claim의 보고 바디가 바이트까지 같다
        expect([bodies.kickoff.size, bodies.resume.size, bodies.plan.size]).toEqual([1, 1, 1]);
      });

      it('refsCleared: one line instead of the section on every turn, nothing prepared or reported', async () => {
        const cleared = { kind: 'INTERVIEW' as const, refRepos: [], refsCleared: true };
        const turnOf = async (claim: InterviewClaimResponse, db: ReturnType<typeof dbOf>, stream: () => AsyncIterable<unknown>) => {
          const client = makeClient();
          const { fakeQuery, captured } = capturing(stream);
          const ensureRefRepos = vi.fn();
          await runnerWith(client, fakeQuery, ensureRefRepos, { createDbMcp: vi.fn().mockReturnValue(db) }).run(claim);
          expect(ensureRefRepos).not.toHaveBeenCalled();
          expect(client.fail).not.toHaveBeenCalled();
          return { prompt: captured.prompt, client };
        };

        // 답변 턴 — DB가 붙어 있어도 DB 절은 kickoff 전용이다
        const resume = await turnOf({ ...resumeClaim, ...cleared }, withDb(), () => questionStream());
        expect(resume.prompt).toBe(REFS_CLEARED_LINE + resumeClaim.lastAnswer);
        expect(resume.client.postQuestion.mock.calls[0]![1]).not.toHaveProperty('refStates');

        // 새 세션 + DB
        const db = withDb();
        const fresh = await turnOf({ ...freshClaim, ...cleared }, db, () => questionStream());
        expect(fresh.prompt).toBe(REFS_CLEARED_LINE + dbSection(db) + KICKOFF_GOLDEN);

        // 계획 턴도 보고에 refStates가 없다
        const plan = await turnOf({ ...resumeClaim, ...cleared }, noDb(), () => planCompleteStream());
        expect(plan.client.postPlan.mock.calls[0]![1]).not.toHaveProperty('refStates');
      });

      it('failed or timed-out reference: the turn goes on, unavailable line in the prompt, error in refStates, no fail', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        const second = { catalogId: 13, alias: '공통 UI', branch: 'main', dir: '/r/_refs/42/13' };
        const claim = { ...interviewClaimWithRefs, refRepos: [IREF, { ...IREF, ...second }] };
        const client = makeClient();
        const { fakeQuery, captured } = capturing(() => questionStream());
        await runnerWith(
          client,
          fakeQuery,
          preparer([iFailed('참조 repo 받기 시간 초과(5분)'), iFailed('fatal: Authentication failed for repo', second)]),
        ).run(claim);

        expect(client.fail).not.toHaveBeenCalled();
        expect(fakeQuery).toHaveBeenCalledTimes(1);
        expect(captured.prompt.startsWith(
          '사용할 수 없는 참조: 옛앱(release/6.5) — 참조 repo 받기 시간 초과(5분)\n' +
            '사용할 수 없는 참조: 공통 UI(main) — fatal: Authentication failed for repo\n' +
            '규칙:\n',
        )).toBe(true);
        expect(captured.prompt).toContain('PLANNING-ONLY');
        expect(reportedBody(client).refStates).toEqual([
          { catalogId: 12, branch: 'release/6.5', sha: null, error: '참조 repo 받기 시간 초과(5분)' },
          { catalogId: 13, branch: 'main', sha: null, error: 'fatal: Authentication failed for repo' },
        ]);
        expect(logged(warn)).toContain('session=42 옛앱(release/6.5)'); // 운영자 로그에 남는다
        warn.mockRestore();
      });

      it('postPlan carries refStates when references were prepared', async () => {
        const client = makeClient();
        await runnerWith(client, vi.fn(() => planCompleteStream()), preparer([iAvailable()])).run(interviewResume);

        expect(client.postQuestion).not.toHaveBeenCalled();
        expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({
          planMarkdown: expect.stringContaining('## File Structure'),
          refStates: [ISTATE],
        }));
      });

      it('force-finish reformat and handoff splice prompts carry no ref section, but the report still has refStates', async () => {
        const prepared = [iAvailable()];

        // ① 강제 마무리 턴: 재형식 splice만 보낸다
        {
          const client = makeClient();
          const { fakeQuery, prompts } = capturingEach(() => questionStream());
          await runnerWith(client, fakeQuery, preparer(prepared)).run({ ...interviewResume, turns: turns(19) });
          expect(prompts).toEqual([buildPlanReformatSplice()]);
          expect(client.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({ refStates: [ISTATE] }));
        }

        // ② handoff shim: 첫 질의에만 참조 절, 두 번째(writing-plans splice)에는 없다
        {
          async function* handoff() {
            yield { type: 'system', subtype: 'init', session_id: 'sess-h' };
            yield { type: 'assistant', message: { content: [{ type: 'text', text: 'Spec approved. Invoke writing-plans skill now.' }] } };
            yield { type: 'result', subtype: 'success', total_cost_usd: 0.05, usage: {}, duration_ms: 100 };
          }
          const client = makeClient();
          const { fakeQuery, prompts } = capturingEach(() => handoff(), () => planCompleteStream());
          const spliceRead = vi.fn().mockReturnValue('# Writing Plans\n\nbreak into tasks');
          await runnerWith(client, fakeQuery, preparer(prepared), { spliceRead }).run(interviewResume);
          expect(prompts).toHaveLength(2);
          expect(prompts[0]).toBe(interviewRefSection(prepared) + resumeClaim.lastAnswer);
          expect(prompts[1]).toContain('break into tasks');
          expect(prompts[1]).not.toContain('참조 repo');
          expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({ refStates: [ISTATE] }));
        }

        // ③ near-miss 보정: 재형식 splice에도 참조 절이 없다
        {
          async function* intentNoStructure() {
            yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
            yield { type: 'assistant', message: { content: [{ type: 'text', text: '# 구현 계획 (초안)\n\n1. 가' }] } };
            yield { type: 'result', subtype: 'success', total_cost_usd: 0.1, usage: {}, duration_ms: 100 };
          }
          async function* reformattedPlan() {
            yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
            yield { type: 'assistant', message: { content: [{ type: 'text', text: '# 구현 계획\n\n### 작업 1: 가' }] } };
            yield { type: 'result', subtype: 'success', total_cost_usd: 0.15, usage: {}, duration_ms: 200 };
          }
          const client = makeClient();
          const { fakeQuery, prompts } = capturingEach(() => intentNoStructure(), () => reformattedPlan());
          await runnerWith(client, fakeQuery, preparer(prepared)).run({ ...interviewResume, currentPhase: 'writing-plans' });
          expect(prompts).toEqual([interviewRefSection(prepared) + resumeClaim.lastAnswer, buildPlanReformatSplice()]);
          expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({ refStates: [ISTATE] }));
        }
      });

      it('a preparer that throws (contract breach) does not fail the turn', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        const second = { catalogId: 13, alias: '공통 UI', branch: 'main', dir: '/r/_refs/42/13' };
        const claim = { ...interviewClaimWithRefs, refRepos: [IREF, { ...IREF, ...second }] };
        const masked = 'boom https://***@gitlab.hamon.vip/g/p.git';
        const client = makeClient();
        const { fakeQuery, captured } = capturing(() => questionStream());
        const ensureRefRepos = vi.fn().mockRejectedValue(new Error('boom https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git'));
        await runnerWith(client, fakeQuery, ensureRefRepos).run(claim);

        // 모든 참조를 못 받은 것으로 — 같은 사유로 — 돌리고 턴은 계속된다
        expect(client.fail).not.toHaveBeenCalled();
        expect(fakeQuery).toHaveBeenCalledTimes(1);
        expect(captured.prompt.startsWith(
          `사용할 수 없는 참조: 옛앱(release/6.5) — ${masked}\n사용할 수 없는 참조: 공통 UI(main) — ${masked}\n규칙:\n`,
        )).toBe(true);
        expect(reportedBody(client).refStates).toEqual([
          { catalogId: 12, branch: 'release/6.5', sha: null, error: masked },
          { catalogId: 13, branch: 'main', sha: null, error: masked },
        ]);
        for (const text of [captured.prompt, JSON.stringify(reportedBody(client)), logged(warn)]) {
          expect(text).not.toContain('glpat-SECRET');
        }

        // 메시지 없이 던져도 사유는 비어 있지 않다(API는 빈 문자열도 그대로 저장한다)
        const silent = makeClient();
        await runnerWith(silent, vi.fn(() => questionStream()), vi.fn().mockRejectedValue(undefined)).run(interviewClaimWithRefs);
        expect(silent.fail).not.toHaveBeenCalled();
        expect(reportedBody(silent).refStates).toEqual([
          { catalogId: 12, branch: 'release/6.5', sha: null, error: REF_PREPARE_FALLBACK_REASON },
        ]);
        warn.mockRestore();
      });

      it('a turn aborted while preparing references stops there for INTERVIEW too', async () => {
        vi.useFakeTimers();
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        try {
          // 실제 준비기처럼 abort를 "못 받은 참조"로 돌려주는 경우와, 계약을 어기고 AbortError로 던지는 경우
          const onAbort: Array<(resolve: (r: RefPrepared[]) => void, reject: (e: unknown) => void) => void> = [
            (resolve) => resolve([iFailed('This operation was aborted')]),
            (_resolve, reject) => reject(Object.assign(new Error('This operation was aborted'), { name: 'AbortError' })),
          ];
          for (const settle of onAbort) {
            const client = { ...makeClient(), heartbeat: vi.fn().mockResolvedValue(undefined) };
            const fakeQuery = vi.fn(() => questionStream());
            const ensureRefRepos = vi.fn((_refs: RefRepoClaim[], opts: { signal?: AbortSignal }) =>
              new Promise<RefPrepared[]>((resolve, reject) => {
                opts.signal!.addEventListener('abort', () => settle(resolve, reject));
              }),
            );
            const runner = new InterviewRunner(client as never, fakeQuery as never, {
              ...deps,
              ensureRefRepos,
              turnTimeoutMs: 60_000,
            } as never);

            const done = runner.run(interviewClaimWithRefs);
            await vi.advanceTimersByTimeAsync(60_000);
            await done;

            expect(fakeQuery).not.toHaveBeenCalled();
            expect(client.postQuestion).not.toHaveBeenCalled();
            expect(client.postPlan).not.toHaveBeenCalled();
            expect(client.fail).toHaveBeenCalledTimes(1);
            expect(client.fail).toHaveBeenCalledWith(42, expect.stringContaining('wall-clock 타임아웃'));
          }
        } finally {
          warn.mockRestore();
          vi.useRealTimers();
        }
      });

      it('per-reference limit comes from deps.refTimeoutMs; NaN/0 fall back to 300_000 (QUESTION too)', async () => {
        const cases: Array<[Record<string, unknown>, number]> = [
          [{ refTimeoutMs: 120_000 }, 120_000],
          [{}, 300_000],
          [{ refTimeoutMs: Number.NaN }, 300_000],
          [{ refTimeoutMs: 0 }, 300_000],
          [{ refTimeoutMs: -1 }, 300_000],
          [{ refTimeoutMs: Number.POSITIVE_INFINITY }, 300_000],
        ];
        for (const claim of [interviewClaimWithRefs, questionClaimWithRefs]) {
          for (const [extra, expected] of cases) {
            const ensureRefRepos = preparer([iAvailable()]);
            await runnerWith(makeClient(), vi.fn(() => questionStream()), ensureRefRepos, extra).run(claim);
            expect((ensureRefRepos.mock.calls[0]![1] as { timeoutMs?: number }).timeoutMs).toBe(expected);
          }
        }
      });
    });
  });
});

describe('refSection (스펙 2026-10-22 §5.2)', () => {
  const HEADER = '참조 repo (읽기 전용 — 비교·참고용, 수정 금지):\n';
  const RULES =
    '규칙:\n' +
    '- 대상 repo는 현재 작업 디렉토리다. 참조 repo는 위 절대 경로를 Read/Grep/Glob의 경로로 주어 읽는다(셸 명령은 대상 안에서만 동작한다).\n' +
    '- 참조 파일 근거는 `별칭:경로:줄`(예: `옛앱:src/a/B.java:12`), 대상 파일은 `경로:줄`로 적는다.\n' +
    '- 사용할 수 없는 참조의 내용은 추측하지 말고 못 읽었다고 답한다.\n' +
    '\n'; // 끝에 빈 줄 하나
  const FALLBACK = '참조 repo를 받지 못했습니다';
  const OLD_APP = '- 옛앱 · release/6.5@aaaaaaa : /r/_refs/5/12\n';
  const unavailable = (reason: string): string => `사용할 수 없는 참조: 옛앱(release/6.5) — ${reason}\n`;

  it('refSection returns an empty string for no refs', () => {
    expect(refSection([])).toBe('');
  });

  it('available references only: header and one line each, no unavailable line, then the rules and one blank line', () => {
    expect(refSection([refAvailable()])).toBe(HEADER + OLD_APP + RULES);
  });

  it('available and unavailable: the unavailable line follows the list', () => {
    const batch = refFailed('브랜치를 찾을 수 없음', { catalogId: 13, alias: '배치 서버', branch: 'master', dir: '/r/_refs/5/13' });
    expect(refSection([refAvailable(), batch])).toBe(
      HEADER + OLD_APP + '사용할 수 없는 참조: 배치 서버(master) — 브랜치를 찾을 수 없음\n' + RULES,
    );
  });

  it('unavailable only: the header and "- …" lines are dropped, the rules stay', () => {
    expect(refSection([refFailed('권한 없음')])).toBe(unavailable('권한 없음') + RULES);
  });

  it('keeps the input order, one line per reference, and shows only the first 7 characters of the commit', () => {
    const second = { catalogId: 13, alias: '공통 UI', branch: 'main', dir: '/r/_refs/5/13' };
    const third = { catalogId: 14, alias: '배치', branch: 'master', dir: '/r/_refs/5/14' };
    const fourth = { catalogId: 15, alias: '문서', branch: 'docs', dir: '/r/_refs/5/15' };
    const out = refSection([
      refAvailable({ sha: 'abcdef0123456789abcdef0123456789abcdef01' }),
      refFailed('권한 없음', third),
      refAvailable({ ref: { ...REF_CLAIM, ...second }, sha: '0123456789abcdef0123456789abcdef01234567' }),
      refFailed('시간 초과', fourth),
    ]);
    expect(out).toBe(
      HEADER +
        '- 옛앱 · release/6.5@abcdef0 : /r/_refs/5/12\n' +
        '- 공통 UI · main@0123456 : /r/_refs/5/13\n' +
        '사용할 수 없는 참조: 배치(master) — 권한 없음\n' +
        '사용할 수 없는 참조: 문서(docs) — 시간 초과\n' +
        RULES,
    );
  });

  // Ruling 8b: 사유 요약 — 마스킹한 문구를 줄로 나눠 `Command failed:` 줄은 버리고, git의 마지막 `fatal:`/`error:` 줄을 먼저 쓰며,
  // 없으면 남은 줄 중 마지막 비어 있지 않은 줄을 쓴다. 다듬어 200자 이내로 자르고, 아무것도 안 남으면 고정 문구.
  // Node execFile 오류는 `Command failed: <명령줄>\n<stderr>`라 첫 줄은 명령줄이고 원인은 stderr의 fatal 줄에 있다.
  const EXEC_FAILED =
    'Command failed: git clone --depth 1 --branch nope https://***@gitlab.example/g/old.git C:\\netis\\_refs\\5\\12.part';
  it.each<[string, string, string]>([
    ['an unprefixed single line as is', 'boom', 'boom'],
    [
      'a Node execFile message: the git fatal line, not the command line',
      `${EXEC_FAILED}\nCloning into 'C:\\netis\\_refs\\5\\12.part'...\nfatal: Remote branch nope not found in upstream origin\n`,
      'fatal: Remote branch nope not found in upstream origin',
    ],
    ['only a Command failed line: nothing useful is left', EXEC_FAILED, FALLBACK],
    ['a Command failed line plus blank lines only', `${EXEC_FAILED}\n\n  \n`, FALLBACK],
    [
      'the LAST fatal/error line wins',
      'Command failed: x\nerror: RPC failed; curl 18 transfer closed\nfatal: early EOF\nfatal: fetch-pack: invalid index-pack output\n',
      'fatal: fetch-pack: invalid index-pack output',
    ],
    ['error: counts like fatal: (the last of both kinds wins)', 'fatal: first\nerror: second', 'error: second'],
    ['a git diagnostic beats later plain lines', 'fatal: Authentication failed for repo\nhint: check the token', 'fatal: Authentication failed for repo'],
    ['without a diagnostic: the last non-empty line', '첫 줄\n둘째 줄\n셋째 줄\n\n', '셋째 줄'],
    ['without a diagnostic: Command failed is dropped, the last line stays', `${EXEC_FAILED}\nCloning into 'x'...\n`, "Cloning into 'x'..."],
    ['trims the chosen line and ignores blank lines around it', '\n  \r\n   fatal: x  \n \n', 'fatal: x'],
    ['CRLF line breaks', 'Command failed: x\r\nfatal: y\r\n', 'fatal: y'],
    ['a bare CR (git progress output)', 'Receiving objects: 10%\rReceiving objects: 20%', 'Receiving objects: 20%'],
    [
      'masks credentials before choosing',
      "Command failed: x\nfatal: unable to access 'https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git/': Could not resolve host",
      "fatal: unable to access 'https://***@gitlab.hamon.vip/g/p.git/': Could not resolve host",
    ],
    ['at most 200 characters', 'a'.repeat(300), 'a'.repeat(200)],
    ['the 200-character cut applies to the chosen fatal line', `Command failed: x\nfatal: ${'f'.repeat(300)}`, `fatal: ${'f'.repeat(193)}`],
    ['exactly 200 characters stays whole', 'b'.repeat(200), 'b'.repeat(200)],
    ['no trailing space after the cut', `${'c'.repeat(199)} dddd`, 'c'.repeat(199)],
    ['never splits a surrogate pair at the cut', `${'e'.repeat(199)}😀tail`, 'e'.repeat(199)],
    ['an empty message', '', FALLBACK],
    ['only whitespace and line breaks', ' \n\t\r\n  ', FALLBACK],
  ])('condenses an unavailable reason: %s', (_name, error, expected) => {
    expect(refSection([refFailed(error)])).toBe(unavailable(expected) + RULES);
    expect(expected.length).toBeLessThanOrEqual(200);
    expect(expected).not.toBe('');
  });

  it('reuses the fallback text exported by repoPrepare instead of its own copy', () => {
    expect(REF_PREPARE_FALLBACK_REASON).toBe(FALLBACK); // 사용자에게 보이는 문구는 여기서 한 번 고정한다
    expect(refSection([refFailed('Command failed: git clone only the command line')])).toBe(
      unavailable(REF_PREPARE_FALLBACK_REASON) + RULES,
    );
  });
});

describe('interviewRefSection (스펙 2026-10-24 §5.2)', () => {
  // 헤더·참조 줄·사용 불가 줄은 질문용 refSection과 같고 규칙만 다르다 — 스펙 §5.2 코드 블록 그대로, 끝에 빈 줄 하나
  const HEADER = '참조 repo (읽기 전용 — 비교·참고용, 수정 금지):\n';
  const RULES =
    '규칙:\n' +
    '- 대상 repo는 현재 작업 디렉토리다. 참조 repo는 위 절대 경로에서 읽기만 한다.\n' +
    '- 참조 폴더의 파일은 만들거나 고치거나 지우지 않는다(셸 명령 포함).\n' +
    '- 위 목록에 없는 참조(앞 턴에 있던 것 포함)는 더 쓰지 않는다. 구현에는 이 목록의 참조만 넘어간다.\n' +
    '- 설계·계획 문서에는 참조 파일을 절대 경로가 아니라 `별칭:경로:줄`(예: `옛앱:src/a/B.java:12`)로 적는다. 구현 단계에서는 참조 폴더 위치가 다르다.\n' +
    '- 사용할 수 없는 참조의 내용은 추측하지 않는다.\n' +
    '\n';
  const OLD_APP = '- 옛앱 · release/6.5@aaaaaaa : /r/_refs/5/12\n';
  const batch = refFailed('브랜치를 찾을 수 없음', { catalogId: 13, alias: '배치 서버', branch: 'master', dir: '/r/_refs/5/13' });

  it('returns an empty string for no refs', () => {
    expect(interviewRefSection([])).toBe('');
  });

  it('available references only: header and one line each, then the INTERVIEW rules and one blank line', () => {
    expect(interviewRefSection([refAvailable()])).toBe(HEADER + OLD_APP + RULES);
  });

  it('unavailable only: the header and "- …" lines are dropped, the rules stay', () => {
    expect(interviewRefSection([refFailed('참조 repo 받기 시간 초과(5분)')])).toBe(
      '사용할 수 없는 참조: 옛앱(release/6.5) — 참조 repo 받기 시간 초과(5분)\n' + RULES,
    );
  });

  it('mixed: the unavailable lines follow the list, with the same condensed reason as the question section', () => {
    expect(interviewRefSection([refAvailable(), batch])).toBe(
      HEADER + OLD_APP + '사용할 수 없는 참조: 배치 서버(master) — 브랜치를 찾을 수 없음\n' + RULES,
    );
    // 사유 줄이기·가리기는 질문용과 같은 규칙
    expect(interviewRefSection([refFailed('Command failed: x\nfatal: y')])).toBe('사용할 수 없는 참조: 옛앱(release/6.5) — fatal: y\n' + RULES);
  });

  it('shares everything but the rules with the question section', () => {
    const prepared = [refAvailable(), batch];
    const rulesStart = (s: string): number => s.indexOf('규칙:\n');
    expect(interviewRefSection(prepared).slice(0, rulesStart(interviewRefSection(prepared)))).toBe(
      refSection(prepared).slice(0, rulesStart(refSection(prepared))),
    );
    expect(interviewRefSection(prepared)).not.toContain('Read/Grep/Glob의 경로로 주어');
  });

  it('REFS_CLEARED_LINE is the one line for a session whose references were all removed', () => {
    expect(REFS_CLEARED_LINE).toBe(
      '참조 repo: 없음(대화 중 모두 뺐다) — 앞서 본 참조는 더 쓰지 않는다. 계획은 대상 repo만으로 세운다.\n\n',
    );
  });

  it('interviewRefPrefix: the section when references were prepared, the cleared line when all were removed, else nothing', () => {
    expect(interviewRefPrefix([refAvailable()], false)).toBe(interviewRefSection([refAvailable()]));
    expect(interviewRefPrefix([refAvailable()], true)).toBe(interviewRefSection([refAvailable()]));
    expect(interviewRefPrefix([], true)).toBe(REFS_CLEARED_LINE);
    expect(interviewRefPrefix([], false)).toBe('');
  });
});

describe('InterviewRunner turn cap + force-finish', () => {
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

  it('force-finish: near-miss correction is skipped (no second query)', async () => {
    // Verifies §4.4: when forceFinish has already sent the reformat splice, the near-miss
    // block must NOT fire a second query even when the assistant output contains plan intent
    // but no parseable structure (the !forceFinish gate must hold).
    const client = makeClient();
    async function* intentNoStructureStream() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-ff-nm' };
      yield {
        type: 'assistant',
        message: { content: [{ type: 'text', text: '구현 계획을 작성합니다. 주요 작업은 다음과 같습니다.' }] },
      }; // plan 의도(detectPlanIntent=true) O, 추출 가능 구조(### 작업 N:) X
      yield { type: 'result', subtype: 'success', total_cost_usd: 0.1, usage: {}, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => intentNoStructureStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never); // forceFinishTurns 19
    // 19 assistant turns → forceFinish=true; resumeClaim provides claudeSessionId='sess-abc-123'
    await runner.run({ ...resumeClaim, currentPhase: 'brainstorming', turns: turns(19) });
    // near-miss block must be skipped: only 1 query (the force-finish reformat splice)
    expect(fakeQuery).toHaveBeenCalledTimes(1);
    expect(client.postQuestion).toHaveBeenCalledTimes(1);
    expect(client.fail).not.toHaveBeenCalled();
  });
});

describe('InterviewRunner handoff shim', () => {
  it('on handoff announcement without a plan, re-queries the same session with the writing-plans splice', async () => {
    const client = makeClient();
    async function* handoffThenQuestion() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-h' };
      yield {
        type: 'assistant',
        message: { content: [{ type: 'text', text: 'Spec approved. Invoke writing-plans skill now.' }] },
      };
      yield {
        type: 'result', subtype: 'success', duration_ms: 100,
        total_cost_usd: 0.05, usage: { input_tokens: 100, output_tokens: 20, cache_creation_input_tokens: 5, cache_read_input_tokens: 200 },
      };
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
    expect(fakeQuery.mock.calls[1]![0].options.resume).toBe('sess-h');
    expect(client.postPlan).toHaveBeenCalledTimes(1);
    // 누적 검증: handoff shim(2차 relay)의 usage가 1차 relay 위에 합산돼야 한다
    // (planCompleteStream: costUsd 0.31, inputTokens 2000, outputTokens 500, cache 60/5400).
    // 비용만은 같은 세션 resume이라 2차 보고값 0.31이 세션 누적값이다(2026-10-15) — 0.05 + (0.31 − 0.05).
    expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({
      costUsd: 0.31,
      inputTokens: 2100, // 100 + 2000
      outputTokens: 520, // 20 + 500
      cacheCreationTokens: 65, // 5 + 60
      cacheReadTokens: 5600, // 200 + 5400
    }));
  });
});

describe('InterviewRunner handoff shim — question guard', () => {
  it('설계 승인 질문이 handoff 문구를 미리 말해도 writing-plans splice 없이 질문을 그대로 보낸다', async () => {
    const client = makeClient();
    const questionText =
      '## 설계 요약\n\n- `# test` 아래에 `## 사용 방법` 추가\n\n' +
      'Once you approve this design, I will invoke writing-plans to write the plan. Does this look right?';
    async function* approvalQuestion() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-hq' };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: questionText }] } };
      yield { type: 'result', subtype: 'success', total_cost_usd: 0.05, usage: {}, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => approvalQuestion());
    const spliceRead = vi.fn().mockReturnValue('# Writing Plans\n\nbreak into tasks');
    const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, spliceRead } as never);

    await runner.run({ ...resumeClaim, currentPhase: 'brainstorming' });

    expect(fakeQuery).toHaveBeenCalledTimes(1); // handoff splice 재질의 없음
    expect(spliceRead).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({
      content: questionText,
      claudeSessionId: 'sess-hq',
      kind: 'question',
    }));
    expect(client.postPlan).not.toHaveBeenCalled();
  });
});

describe('InterviewRunner activity wiring', () => {
  it('활동 배선: 합성 "환경 준비" 활동이 최초로, relay 활동이 이어서 poster로 전송된다', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => streamingQuestionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    // run()의 finally에서 poster.stop()이 잔여 큐를 flush하므로 최소 1회 전송된다.
    expect(client.postActivity).toHaveBeenCalled();
    const events = client.postActivity.mock.calls.flatMap(
      (c: unknown[]) => (c[1] as { events: Array<Record<string, unknown>> }).events,
    );
    expect(events[0]).toMatchObject({ type: 'tool', label: '환경 준비' });
    // relay 활동(델타/tool_use)도 흘러들어옴
    expect(events.some((e) => e.type === 'thinking')).toBe(true);
    expect(events.some((e) => e.type === 'tool' && e.label === 'Read')).toBe(true);
    // 활동과 무관하게 최종 question 전송은 기존 그대로
    expect(client.postQuestion).toHaveBeenCalled();
  });

  it('활동 전송이 전부 실패해도 인터뷰(question 전송)는 성공한다', async () => {
    const client = makeClient();
    client.postActivity.mockRejectedValue(new Error('boom'));
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fakeQuery = vi.fn(() => streamingQuestionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(client.postQuestion).toHaveBeenCalled();
    expect(client.fail).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('활동 배치는 전부 question POST보다 먼저 전송된다 (trailing 배치 유령 렌더 방지)', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => streamingQuestionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(client.postActivity).toHaveBeenCalled();
    const lastActivityOrder = Math.max(...client.postActivity.mock.invocationCallOrder);
    const questionOrder = client.postQuestion.mock.invocationCallOrder[0]!;
    expect(lastActivityOrder).toBeLessThan(questionOrder);
  });
});

describe('InterviewRunner wall-clock timeout', () => {
  // HeartbeatTicker가 fake timer 진행 중 발화하므로 heartbeat mock이 필요하다.
  function makeClientWithHeartbeat() {
    return {
      postQuestion: vi.fn().mockResolvedValue(undefined),
      postPlan: vi.fn().mockResolvedValue(undefined),
      fail: vi.fn().mockResolvedValue(undefined),
      postActivity: vi.fn().mockResolvedValue(undefined),
      heartbeat: vi.fn().mockResolvedValue(undefined),
    };
  }
  /** 영원히 yield하지 않는 스트림 = SDK 행업 재현. */
  function hungStream(): AsyncIterable<never> {
    return (async function* () {
      await new Promise<never>(() => {});
    })() as AsyncIterable<never>;
  }

  it('행업한 SDK 스트림은 타임아웃 시 abort되고 세션이 FAILED로 보고된다', async () => {
    vi.useFakeTimers();
    try {
      const client = makeClientWithHeartbeat();
      let abortSignal: AbortSignal | undefined;
      const fakeQuery = vi.fn((args: { options: { abortController?: AbortController } }) => {
        abortSignal = args.options.abortController?.signal;
        return hungStream();
      });
      const runner = new InterviewRunner(client as never, fakeQuery as never, {
        ...deps,
        turnTimeoutMs: 60_000,
      } as never);

      const done = runner.run(freshClaim);
      await vi.advanceTimersByTimeAsync(60_000);
      await done;

      expect(abortSignal?.aborted).toBe(true);
      expect(client.fail).toHaveBeenCalledWith(42, expect.stringContaining('wall-clock 타임아웃'));
      expect(client.postQuestion).not.toHaveBeenCalled();
      expect(client.postPlan).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('git clone(ensureRepo) 행업도 같은 데드라인으로 회수되고 signal이 abort된다', async () => {
    vi.useFakeTimers();
    try {
      const client = makeClientWithHeartbeat();
      let repoSignal: AbortSignal | undefined;
      const hungEnsureRepo = vi.fn((input: { signal?: AbortSignal }) => {
        repoSignal = input.signal;
        return new Promise<void>(() => {});
      });
      const fakeQuery = vi.fn(() => questionStream());
      const runner = new InterviewRunner(client as never, fakeQuery as never, {
        ...deps,
        ensureRepo: hungEnsureRepo,
        turnTimeoutMs: 60_000,
      } as never);

      const done = runner.run(freshClaim);
      await vi.advanceTimersByTimeAsync(60_000);
      await done;

      expect(client.fail).toHaveBeenCalledWith(42, expect.stringContaining('wall-clock 타임아웃'));
      expect(fakeQuery).not.toHaveBeenCalled(); // SDK 진입 전에 걸린 행업
      // abort가 git(ensureRepo)까지 전파돼 자식 프로세스 좀비를 남기지 않는다
      expect(repoSignal?.aborted).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('turnTimeoutMs가 NaN/0이어도 기본 30분으로 폴백해 정상 턴이 즉시 abort되지 않는다', async () => {
    const client = makeClientWithHeartbeat();
    const fakeQuery = vi.fn(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, {
      ...deps,
      turnTimeoutMs: Number.NaN,
    } as never);

    await runner.run(freshClaim);

    expect(client.postQuestion).toHaveBeenCalled();
    expect(client.fail).not.toHaveBeenCalled();
  });

  it('정상 완료 턴은 타임아웃과 무관하게 기존 계약 그대로 동작한다', async () => {
    const client = makeClientWithHeartbeat();
    const fakeQuery = vi.fn(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, {
      ...deps,
      turnTimeoutMs: 60_000,
    } as never);

    await runner.run(freshClaim);

    expect(client.postQuestion).toHaveBeenCalled();
    expect(client.fail).not.toHaveBeenCalled();
  });

  it('타임아웃 fail 보고 자체가 실패해도 run()은 throw 없이 종료한다 (ClaimLoop 보호)', async () => {
    vi.useFakeTimers();
    try {
      const client = makeClientWithHeartbeat();
      client.fail.mockRejectedValue(new Error('api down'));
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const fakeQuery = vi.fn(() => hungStream());
      const runner = new InterviewRunner(client as never, fakeQuery as never, {
        ...deps,
        turnTimeoutMs: 60_000,
      } as never);

      const done = runner.run(freshClaim);
      await vi.advanceTimersByTimeAsync(60_000);
      await expect(done).resolves.toBeUndefined();
      warnSpy.mockRestore();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('InterviewRunner rate limit reporting + context snapshot (스펙 2026-09-05 §4)', () => {
  it('usage-aware question turn: reports rate limits in order and posts the context snapshot with the answer', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => usageAwareQuestionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run(questionClaim);

    expect(client.postRateLimit).toHaveBeenCalledTimes(2);
    expect(client.postRateLimit).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ limitType: 'five_hour', utilization: 0.42, resetsAt: '2026-09-05T04:00:00.000Z' }),
    );
    expect(client.postRateLimit).toHaveBeenNthCalledWith(2, expect.objectContaining({ limitType: 'seven_day', utilization: 0.63 }));
    expect(client.postQuestion).toHaveBeenCalledWith(
      questionClaim.sessionId,
      expect.objectContaining({ contextTokens: 76004, contextWindow: 200000, claudeSessionId: 'sess-usage-1' }),
    );
    expect(client.fail).not.toHaveBeenCalled();
  });

  it('interview turn also carries the context snapshot; legacy streams send null', async () => {
    const client = makeClient();
    const runner = new InterviewRunner(client as never, vi.fn(() => usageAwareQuestionStream()) as never, deps as never);
    await runner.run(freshClaim);
    expect(client.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({ contextTokens: 76004, contextWindow: 200000 }));

    const legacy = makeClient();
    const runner2 = new InterviewRunner(legacy as never, vi.fn(() => questionStream()) as never, deps as never);
    await runner2.run(freshClaim);
    expect(legacy.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({ contextTokens: null, contextWindow: null }));
    expect(legacy.postRateLimit).not.toHaveBeenCalled();
  });
});
