import { describe, expect, it, vi } from 'vitest';
import { detectHandoff, buildWritingPlansSplice, detectPlanIntent, buildPlanReformatSplice } from '../src/runner/skillDispatch.js';

describe('detectHandoff', () => {
  it('detects when the agent announces the writing-plans transition', () => {
    const text =
      'The spec is approved. Transition to implementation — invoke writing-plans skill to create the plan.';
    expect(detectHandoff(text)).toBe(true);
  });

  it('detects progressive / first-person phrasings too', () => {
    expect(detectHandoff('I am invoking writing-plans now.')).toBe(true);
    expect(detectHandoff('Transitioning to implementation.')).toBe(true);
  });

  it('does not fire on ordinary brainstorming questions', () => {
    expect(detectHandoff('Which columns should the CSV include?')).toBe(false);
  });

  it('does not fire when a design-approval question merely previews the handoff', () => {
    // brainstorming 체크리스트 문구("invoke writing-plans")를 승인 요청 턴에서 미리 말하는 경우 —
    // handoff splice가 쏘이면 사용자 승인(하드 게이트) 없이 writing-plans로 넘어간다.
    expect(detectHandoff(
      '## 설계 요약\n\n- `# test` 아래에 `## 사용 방법` 추가\n\n' +
        'Once you approve this design, I will invoke writing-plans to write the plan. Does this look right?',
    )).toBe(false);
    expect(detectHandoff('설계가 괜찮으면 transition to implementation 하겠습니다. 이대로 진행할까요？')).toBe(false);
  });

  it("still fires when the only '?' is inside code (announcement, not a question)", () => {
    expect(detectHandoff('Spec approved. Invoking writing-plans now — see `cfg?.plan` for the path.')).toBe(true);
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

  it('requires the final plan document to use the English "Implementation Plan" header (completion marker)', () => {
    const out = buildWritingPlansSplice('/sp/5.1.0', () => '# Writing Plans\n\nbody');
    expect(out).toContain('Implementation Plan');
  });
});

describe('detectPlanIntent', () => {
  it('true on handoff phrasing', () => {
    expect(detectPlanIntent('Invoke writing-plans now.')).toBe(true);
  });
  it('true when a markdown heading names the plan but harvest-shape is off (near-miss)', () => {
    // PLAN_HEADER는 줄 끝이 "구현 계획"이어야 하므로 괄호 꼬리가 붙은 헤딩은 harvest 실패 → 보정 대상.
    expect(detectPlanIntent('# README 구현 계획 (초안)\n\n1. 섹션 추가')).toBe(true);
    expect(detectPlanIntent('## Widget Implementation Plan — draft\n\n- step')).toBe(true);
  });
  it('false when "구현 계획" is only mentioned in prose (announcement, no heading)', () => {
    expect(detectPlanIntent('이제 구현 계획을 제시합니다')).toBe(false);
    expect(detectPlanIntent('I will write the Implementation Plan at the end.')).toBe(false);
  });
  it('false on a clarifying-question turn that mentions the plan (2026-09-29 세션 #11 회귀)', () => {
    // 실제 첫 턴 발췌: 앞부분의 "구현 계획" 언급 + 굵은 질문 줄 + 선택지로 끝남(마지막 줄에 '?' 없음).
    const text =
      '**분류:** README 파일 하나만 고치는 작은 작업입니다. 질문은 한 번에 하나씩 드리고, ' +
      '마지막에는 정해진 형식의 구현 계획 문서를 작성하겠습니다.\n\n---\n\n' +
      "**질문 1. '사용 방법' 섹션을 README의 어디에 넣을까요?**\n\n" +
      '- **A) 맨 위 `# test` 제목 바로 아래 (추천)**\n' +
      '- **D) 템플릿을 전부 지우고 새로 작성**';
    expect(detectPlanIntent(text)).toBe(false);
  });
  it('false when a plan-titled heading comes with a question to the user (approval pending)', () => {
    expect(detectPlanIntent('## 구현 계획\n\n1. 가\n\n이 방향으로 진행할까요？')).toBe(false);
  });
  it("ignores '?' inside code (not a question to the user)", () => {
    expect(detectPlanIntent('# 구현 계획 (초안)\n\n```ts\nconst v = a?.b\n```\n\n`x ? y : z` 사용')).toBe(true);
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
