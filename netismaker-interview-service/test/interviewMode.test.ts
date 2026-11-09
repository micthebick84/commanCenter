import { describe, expect, it } from 'vitest';
import {
  MOCKUP_GUIDE,
  MOCKUP_RESTART_NOTE,
  SIMPLE_RESUME_PREFIX,
  planningOnlyParagraph,
  processLine,
  resumedAfterRestart,
  superpowersOn,
} from '../src/runner/interviewMode.js';
import { PLAN_FORMAT_RULE } from '../src/runner/skillDispatch.js';
import { SIMPLE_PROCESS_LITERAL } from './fixtures/simpleInterview.js';

const BRAINSTORMING_LINE = 'Use the brainstorming skill: read the project context, then ask me one clarifying question at a time.';

/**
 * 켬 kickoff 끝의 화면 목업 지시 — 계획이 정한 정확한 문구(앞 `\n\n` 포함, 끝 줄바꿈 없음)를 시험 쪽 리터럴로 둔다.
 * interviewRunner.test.ts의 골든은 소스 상수(`KICKOFF_BASE + MOCKUP_GUIDE`)로 만들므로 문구 바이트는 여기서만 지킨다.
 */
const MOCKUP_GUIDE_LITERAL =
  '\n\n' +
  '화면 목업: 이 환경에서는 visual companion 서버(start-server.sh)를 띄우지 마세요 — 사용자가 열 수 없습니다. ' +
  '대신 `show_mockup` 도구로 목업을 올리세요. 목업 HTML을 파일로 저장하지 마세요.\n' +
  "- 화면·배치·UI 요소를 고르거나 확인해야 하는 질문이면 '보여 드릴까요?'라고 묻지 말고 바로 목업을 올린 뒤, 질문 메시지에서 '목업 N'으로 가리키세요. 한 질문에 2~4안까지.\n" +
  '- 목업은 외부 리소스(CDN 스크립트·웹 글꼴·외부 이미지) 없는 완전한 HTML 문서(`<!doctype html>`)로 쓰고, 대상 레포의 실제 화면 구성·라벨·용어를 따르세요. ' +
  '고친 목업은 같은 name으로 다시 올리면 새 버전이 됩니다.\n' +
  "- 최종 계획에서 화면을 만드는 작업에는 따라야 할 목업을 '목업 N'으로 적으세요.";

/** InterviewService.releaseForRestart가 남기는 system 노트(Java 문구 그대로). */
const RESTART_NOTE_TURN = {
  seq: 3,
  role: 'system',
  kind: 'note',
  content: '인터뷰 서비스가 다시 시작돼 이 단계를 다시 진행합니다.',
  replyToSeq: null,
};
const ANSWER_TURN = { seq: 2, role: 'user', kind: 'answer', content: '보이는 열만요.', replyToSeq: 1 };

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

describe('MOCKUP_GUIDE — 켬 kickoff 끝의 화면 목업 지시 (스펙 2026-11-03 인터뷰 목업 §4)', () => {
  it('MOCKUP_GUIDE 핵심 구절', () => {
    expect(MOCKUP_GUIDE.startsWith('\n\n화면 목업:')).toBe(true);
    expect(MOCKUP_GUIDE.endsWith('\n')).toBe(false);
    for (const s of ['start-server.sh', 'show_mockup', "'보여 드릴까요?'", '2~4안', '<!doctype html>', '같은 name', "'목업 N'으로 적으세요"]) {
      expect(MOCKUP_GUIDE).toContain(s);
    }
  });

  it('계획이 정한 문구와 바이트까지 같다(지시 줄 4개)', () => {
    expect(MOCKUP_GUIDE).toBe(MOCKUP_GUIDE_LITERAL);
    expect(MOCKUP_GUIDE.slice(2).split('\n').length).toBe(4);
  });
});

describe('재기동 뒤 켬 resume — 버린 목업 안내 (스펙 2026-11-03 인터뷰 목업 §5)', () => {
  it('MOCKUP_RESTART_NOTE 문구(끝에 빈 줄)', () => {
    expect(MOCKUP_RESTART_NOTE).toBe('앞 시도에서 올린 목업은 버려졌습니다 — 필요하면 다시 올리세요.\n\n');
  });

  it('재기동 노트 판정', () => {
    // 마지막 턴이 재기동 노트 → true
    expect(resumedAfterRestart([ANSWER_TURN, RESTART_NOTE_TURN])).toBe(true);
    expect(resumedAfterRestart([RESTART_NOTE_TURN])).toBe(true);
    // 다른 system 노트로 끝남 → false
    expect(resumedAfterRestart([ANSWER_TURN, { ...RESTART_NOTE_TURN, content: '참조 repo 변경: 옛앱(release/6.5) 추가' }])).toBe(false);
    // user 답변으로 끝남(재기동 노트가 앞에 있어도) → false
    expect(resumedAfterRestart([RESTART_NOTE_TURN, ANSWER_TURN])).toBe(false);
    // 재기동 문구라도 system 턴이 아니면 → false
    expect(resumedAfterRestart([{ ...RESTART_NOTE_TURN, role: 'user' }])).toBe(false);
    // 턴이 없거나 모양이 다르면 → false
    for (const v of [undefined, null, [], 'x', {}, [null], [{ role: 'system' }], [{ role: 'system', content: 42 }]]) {
      expect(resumedAfterRestart(v)).toBe(false);
    }
  });
});
