import { describe, expect, it, vi } from 'vitest';
import { InterviewRunner } from '../src/runner/interviewRunner.js';
import { freshClaim, resumeClaim, freshClaimWithAttachments, questionClaim } from './fixtures/claims.js';
import { planCompleteStream, questionStream, streamingQuestionStream, usageAwareQuestionStream } from './fixtures/sdkMessages.js';
import { detectHandoff } from '../src/runner/skillDispatch.js';

function makeClient() {
  return {
    postQuestion: vi.fn().mockResolvedValue(undefined),
    postPlan: vi.fn().mockResolvedValue(undefined),
    fail: vi.fn().mockResolvedValue(undefined),
    postActivity: vi.fn().mockResolvedValue(undefined),
    postRateLimit: vi.fn().mockResolvedValue(undefined),
  };
}
// ensureRepo is injected so unit tests never touch git.
const ensureRepo = vi.fn().mockResolvedValue(undefined);
const deps = {
  superpowersPluginPath: '/sp/5.1.0',
  claudeCliPath: '/home/me/.local/bin/claude',
  quotaGuard: 5,
  maxTurns: 20,
  forceFinishTurns: 19,
  ensureRepo,
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

  it('quota guard: when a turn shadow cost crosses the guard, reports fail and does not post a question', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => questionStream()); // turn shadow cost 0.12
    // guard 0.05 < this turn's 0.12 => trips on the single turn (seeded from 0; see note below)
    const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, quotaGuard: 0.05 } as never);

    await runner.run(freshClaim);

    expect(client.fail).toHaveBeenCalledWith(42, expect.stringContaining('quota'));
    expect(client.postQuestion).not.toHaveBeenCalled();
  });

  it('quota guard is CUMULATIVE across the session: seeded from claim.totalCostUsd so a cheap turn that crosses the prior session total trips fail', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => questionStream()); // this turn shadow cost 0.12
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never); // quotaGuard 5
    // prior accumulated 4.95 + this turn 0.12 = 5.07 > 5 => trips. (Would NOT trip if seeded from 0.)
    await runner.run({ ...freshClaim, totalCostUsd: 4.95 });

    expect(client.fail).toHaveBeenCalledWith(42, expect.stringContaining('quota'));
    expect(client.postQuestion).not.toHaveBeenCalled();
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
});

describe('InterviewRunner near-miss correction', () => {
  it('near-miss: harvest fails but plan-intent present → reformat splice once → re-harvest → postPlan', async () => {
    const client = makeClient();
    async function* intentNoStructure() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
      yield { type: 'assistant', message: { content: [{ type: 'text',
        text: '이제 구현 계획을 정리하겠습니다.' }] } }; // plan 의도 O, 추출 X
      yield {
        type: 'result', subtype: 'success', duration_ms: 100,
        usage: { total_cost_usd: 0.1, input_tokens: 100, output_tokens: 20, cache_creation_input_tokens: 5, cache_read_input_tokens: 200 },
      };
    }
    async function* reformattedPlan() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
      yield { type: 'assistant', message: { content: [{ type: 'text',
        text: '# 구현 계획\n\n### 작업 1: 가' }] } };
      yield {
        type: 'result', subtype: 'success', duration_ms: 200,
        usage: { total_cost_usd: 0.15, input_tokens: 300, output_tokens: 60, cache_creation_input_tokens: 15, cache_read_input_tokens: 400 },
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
    // (대입이면 retry분만 남아 첫 relay의 usage가 유실된다).
    expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({
      costUsd: 0.25, // 0.1 + 0.15
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
      yield { type: 'assistant', message: { content: [{ type: 'text', text: '구현 계획 초안입니다.' }] } };
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.1 }, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => intentNoStructure()); // 매번 추출 불가
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...resumeClaim, currentPhase: 'writing-plans' });
    expect(fakeQuery).toHaveBeenCalledTimes(2);
    expect(client.postQuestion).toHaveBeenCalledTimes(1);
    expect(client.fail).not.toHaveBeenCalled();
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
    expect(captured.options.allowedTools).toEqual(['Read', 'Grep', 'Glob']);
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
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.05 }, duration_ms: 100 };
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
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.1 }, duration_ms: 100 };
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

  it('SDK 쿼리가 throw하면 fail 사유는 "답변 생성 실패"이지 인터뷰 문구가 아니다 (final-review finding, minor)', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => {
      throw new Error('boom');
    });
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(questionClaim);
    expect(client.fail).toHaveBeenCalledTimes(1);
    const message = client.fail.mock.calls[0]![1] as string;
    expect(message).toContain('답변 생성 실패');
    expect(message).not.toContain('interview turn failed');
  });

  it('kind 미존재(구버전 백엔드) → 인터뷰 킥오프 그대로', async () => {
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(captured.prompt).toContain('brainstorming');
    expect(captured.options.plugins).toEqual([{ type: 'local', path: '/sp/5.1.0' }]);
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
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.1 }, duration_ms: 100 };
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
        usage: { total_cost_usd: 0.05, input_tokens: 100, output_tokens: 20, cache_creation_input_tokens: 5, cache_read_input_tokens: 200 },
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
    expect(client.postPlan).toHaveBeenCalledWith(42, expect.objectContaining({
      costUsd: 0.36, // 0.05 + 0.31
      inputTokens: 2100, // 100 + 2000
      outputTokens: 520, // 20 + 500
      cacheCreationTokens: 65, // 5 + 60
      cacheReadTokens: 5600, // 200 + 5400
    }));
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
