import { describe, expect, it, vi } from 'vitest';
import { InterviewRunner } from '../src/runner/interviewRunner.js';
import { freshClaim, resumeClaim, freshClaimWithAttachments } from './fixtures/claims.js';
import { planCompleteStream, questionStream } from './fixtures/sdkMessages.js';
import { detectHandoff } from '../src/runner/skillDispatch.js';

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
    const fakeQuery = vi.fn(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(legacy as never);
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
    expect(fakeQuery.mock.calls[1]![0].options.resume).toBe('sess-h');
    expect(client.postPlan).toHaveBeenCalledTimes(1);
  });
});
