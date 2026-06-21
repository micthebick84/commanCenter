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
