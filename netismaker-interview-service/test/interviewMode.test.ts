import { describe, expect, it } from 'vitest';
import { SIMPLE_RESUME_PREFIX, planningOnlyParagraph, processLine, superpowersOn } from '../src/runner/interviewMode.js';
import { PLAN_FORMAT_RULE } from '../src/runner/skillDispatch.js';
import { SIMPLE_PROCESS_LITERAL } from './fixtures/simpleInterview.js';

const BRAINSTORMING_LINE = 'Use the brainstorming skill: read the project context, then ask me one clarifying question at a time.';

describe('superpowersOn — 끔 판정 (스펙 2026-11-03 §5, Global Constraints "끔 판정")', () => {
  it('superpowersOn: false만 끔', () => {
    for (const v of [undefined, null, true]) expect(superpowersOn({ superpowersEnabled: v as any })).toBe(true);
    expect(superpowersOn({ superpowersEnabled: false })).toBe(false);
    // 칸 자체가 없는 claim(구버전 API)
    expect(superpowersOn({})).toBe(true);
  });

  it('false가 아닌 모르는 값(0·빈 문자열·"false")도 끔이 아니다 — refsCleared === true 선례와 같은 엄격 비교', () => {
    for (const v of [0, '', 'false', 'FALSE', [], {}]) expect(superpowersOn({ superpowersEnabled: v as any })).toBe(true);
  });
});

describe('planningOnlyParagraph — PLANNING-ONLY 문단', () => {
  it('켬은 기존 문단 그대로(진행 방식 괄호 포함, 끝에 빈 줄)', () => {
    const on = planningOnlyParagraph(true);
    expect(on.startsWith('IMPORTANT — this is a PLANNING-ONLY interview. Your only deliverable is a written implementation PLAN (via brainstorming → writing-plans), NOT code.')).toBe(true);
    expect(on.endsWith('implementation happens later in a separate step.\n\n')).toBe(true);
    expect(on.endsWith('\n\n\n')).toBe(false);
    expect(on.split('(via brainstorming → writing-plans)').length).toBe(2);
  });

  it('끔 PLANNING-ONLY는 진행 방식 괄호만 뺀 것', () => {
    expect(planningOnlyParagraph(false)).toBe(planningOnlyParagraph(true).replace(' (via brainstorming → writing-plans)', ''));
    expect(planningOnlyParagraph(false)).not.toContain('brainstorming');
    expect(planningOnlyParagraph(false)).not.toContain('writing-plans');
    expect(planningOnlyParagraph(false)).toContain('implementation PLAN, NOT code.');
  });
});

describe('processLine — kickoff 마지막 진행 방식 줄', () => {
  it('켬은 brainstorming 줄 그대로(끝 줄바꿈 없음)', () => {
    expect(processLine(true)).toBe(BRAINSTORMING_LINE);
  });

  it('끔 진행 방식 줄', () => {
    const off = processLine(false);
    // 계획이 정한 정확한 문구(시험 쪽 리터럴)와 바이트까지 같다
    expect(off).toBe(SIMPLE_PROCESS_LITERAL);
    for (const s of ['번호를 붙여', "'가정'", '섞지 마세요', '파일로 저장하지 마세요']) expect(off).toContain(s);
    expect(off).not.toContain('brainstorming');
    expect(off).not.toContain('writing-plans');
    expect(off.endsWith('\n')).toBe(false);
    expect(off.split('\n').length).toBe(4);
  });
});

describe('SIMPLE_RESUME_PREFIX — 끔 세션의 resume 접두', () => {
  it('resume 접두', () => {
    expect(SIMPLE_RESUME_PREFIX.startsWith('이 답변(또는 계획 수정 요청)으로 질문은 끝입니다. 더 묻지 말고')).toBe(true);
    expect(SIMPLE_RESUME_PREFIX).toContain(PLAN_FORMAT_RULE);
    expect(SIMPLE_RESUME_PREFIX.endsWith('\n\n')).toBe(true);
    expect(SIMPLE_RESUME_PREFIX.endsWith('\n\n\n')).toBe(false);
    expect(SIMPLE_RESUME_PREFIX).toBe(
      '이 답변(또는 계획 수정 요청)으로 질문은 끝입니다. 더 묻지 말고 이 내용을 반영해 정규 형식의 최종 계획을 내세요. ' + PLAN_FORMAT_RULE + '\n\n',
    );
  });
});
