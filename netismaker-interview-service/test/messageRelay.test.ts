import { describe, expect, it } from 'vitest';
import { contextTokensOf, contextWindowOf, relay, summarizeToolUse } from '../src/runner/messageRelay.js';
import { questionStream, streamingQuestionStream, usageAwareQuestionStream } from './fixtures/sdkMessages.js';
import type { ActivityInput, RateLimitInfo } from '../src/types.js';
import type { SdkMessage } from '../src/sdk/sdkAdapter.js';

describe('relay', () => {
  it('captures session_id from system/init and collects assistant text + result', async () => {
    const out = await relay(questionStream());
    expect(out.sessionId).toBe('sess-new-1');
    expect(out.assistantText).toContain('Which columns');
    expect(out.costUsd).toBeCloseTo(0.12);
    expect(out.durationMs).toBe(800);
    expect(out.completed).toBe(true);
    expect(out.inputTokens).toBe(1000);
    expect(out.outputTokens).toBe(250);
    expect(out.cacheCreationTokens).toBe(30);
    expect(out.cacheReadTokens).toBe(8000);
  });
});

describe('relay — activity callback (includePartialMessages)', () => {
  it('emits thinking/text deltas + tool_use in stream order; skips signature + subagent deltas', async () => {
    const events: ActivityInput[] = [];
    const out = await relay(streamingQuestionStream(), {
      onActivity: (e) => events.push(e),
      workDir: '/work/repo',
    });
    expect(events).toEqual([
      { type: 'thinking', content: 'repo부터 봐야' },
      { type: 'text', content: '레포를 먼저 ' },
      { type: 'text', content: '읽겠습니다' },
      { type: 'tool', label: 'Read', detail: 'src/app.ts' },
      { type: 'text', content: 'Which columns?' },
    ]);
    // 최종 assistantText는 기존 계약 그대로 — postQuestion/planHarvest의 원천은 델타가 아니라 assistant 메시지
    expect(out.assistantText).toBe('레포를 먼저 읽겠습니다\n\nWhich columns?');
    expect(out.sessionId).toBe('sess-stream-1');
    expect(out.costUsd).toBeCloseTo(0.2);
  });

  it('without onActivity, behaves exactly as before (기존 계약 무변경)', async () => {
    const out = await relay(streamingQuestionStream());
    expect(out.assistantText).toBe('레포를 먼저 읽겠습니다\n\nWhich columns?');
    expect(out.completed).toBe(true);
  });
});

describe('summarizeToolUse', () => {
  it('Read→file_path(workDir 상대화), Grep/Glob→pattern, Skill→skill', () => {
    expect(summarizeToolUse('Read', { file_path: '/w/repo/src/a.ts' }, '/w/repo')).toBe('src/a.ts');
    expect(summarizeToolUse('Grep', { pattern: 'TODO' })).toBe('TODO');
    expect(summarizeToolUse('Glob', { pattern: '**/*.vue' })).toBe('**/*.vue');
    expect(summarizeToolUse('Skill', { skill: 'brainstorming' })).toBe('brainstorming');
  });

  it('120자 초과는 …로 절단, 미지 도구/입력 없음은 undefined', () => {
    const long = 'x'.repeat(200);
    expect(summarizeToolUse('Grep', { pattern: long })!.length).toBe(121); // 120 + '…'
    expect(summarizeToolUse('WebFetch', { url: 'http://x' })).toBeUndefined();
    expect(summarizeToolUse('Read', undefined)).toBeUndefined();
  });
});

describe('relay — 컨텍스트 스냅샷 + rate limit (스펙 2026-09-05 §4)', () => {
  it('마지막 최상위 assistant usage로 contextTokens, 주 모델의 contextWindow를 잡는다 (서브에이전트 usage 제외)', async () => {
    const out = await relay(usageAwareQuestionStream());
    expect(out.contextTokens).toBe(4 + 30000 + 46000);
    expect(out.contextWindow).toBe(200000); // haiku(100000)가 아니라 입력 토큰 합이 큰 opus
    expect(out.assistantText).toContain('AuthController');
    expect(out.costUsd).toBeCloseTo(0.05);
  });

  it('assistant usage가 없으면 최상위 message_start usage로 폴백한다', async () => {
    async function* stream(): AsyncIterable<SdkMessage> {
      yield { type: 'system', subtype: 'init', session_id: 's' };
      yield {
        type: 'stream_event',
        parent_tool_use_id: null,
        event: { type: 'message_start', message: { usage: { input_tokens: 2, cache_creation_input_tokens: 30000 } } },
      };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: '답' }] } };
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0 }, duration_ms: 1 };
    }
    const out = await relay(stream());
    expect(out.contextTokens).toBe(30002);
    expect(out.contextWindow).toBeNull(); // modelUsage 없음
  });

  it('usage가 전혀 없는 구형 스트림은 둘 다 null (기존 픽스처 무변경)', async () => {
    const out = await relay(questionStream());
    expect(out.contextTokens).toBeNull();
    expect(out.contextWindow).toBeNull();
  });

  it('rate_limit_event의 rate_limit_info를 가공 없이 onRateLimit에 넘기고(unifiedWindows 포함), 콜백이 없으면 무시한다', async () => {
    const seen: RateLimitInfo[] = [];
    await relay(usageAwareQuestionStream(), { onRateLimit: (i) => seen.push(i) });
    expect(seen).toHaveLength(1);
    expect(seen[0]!.rateLimitType).toBe('five_hour');
    expect(seen[0]!.utilization).toBeUndefined(); // 실측: 최상위 utilization 없음 — 정규화(Task 7)가 unifiedWindows를 본다
    expect(seen[0]!.unifiedWindows?.seven_day?.utilization).toBe(0.63);
    await expect(relay(usageAwareQuestionStream())).resolves.toMatchObject({ completed: true });
  });
});

describe('contextTokensOf / contextWindowOf', () => {
  it('세 필드 합, 비숫자는 0으로, 세 필드가 전부 없으면 null', () => {
    expect(contextTokensOf({ input_tokens: 1, cache_creation_input_tokens: 2, cache_read_input_tokens: 3 })).toBe(6);
    expect(contextTokensOf({ input_tokens: 1, cache_read_input_tokens: 'x' })).toBe(1);
    expect(contextTokensOf({ output_tokens: 5 })).toBeNull();
    expect(contextTokensOf(undefined)).toBeNull();
  });

  it('입력 토큰 합이 최대인 모델의 contextWindow; contextWindow 없는 항목은 후보에서 제외', () => {
    expect(
      contextWindowOf({
        a: { inputTokens: 10, contextWindow: 100000 },
        b: { inputTokens: 5, cacheReadInputTokens: 50, contextWindow: 200000 },
        c: { inputTokens: 999 },
      }),
    ).toBe(200000);
    expect(contextWindowOf({})).toBeNull();
    expect(contextWindowOf('nope')).toBeNull();
  });
});
