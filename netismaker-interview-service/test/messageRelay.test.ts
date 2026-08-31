import { describe, expect, it } from 'vitest';
import { relay, summarizeToolUse } from '../src/runner/messageRelay.js';
import { questionStream, streamingQuestionStream } from './fixtures/sdkMessages.js';
import type { ActivityInput } from '../src/types.js';

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
