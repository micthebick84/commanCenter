import { describe, expect, it } from 'vitest';
import { kickoffLead, taskTypeGuide, writableDbLine } from '../src/runner/kickoffByType.js';

const repo = { githubRepo: 'acme/widgets', githubBranch: 'main' };

describe('kickoffLead — 타입별 kickoff 첫 문장 (스펙 2026-11-03 §5.1)', () => {
  it('타입 없음·NEW_FEATURE·모르는 값은 지금 문장 그대로', () => {
    const now = 'I want to add a feature to the repo at acme/widgets (branch main).\n';
    for (const taskType of [undefined, null, 'NEW_FEATURE', 'QA_WORK', '', 'bug_fix'] as const) {
      expect(kickoffLead({ ...repo, taskType: taskType as never })).toBe(now);
    }
    // taskType 칸 자체가 없는 claim(구버전 API)
    expect(kickoffLead(repo as never)).toBe(now);
  });

  it('기능 수정', () => {
    expect(kickoffLead({ ...repo, taskType: 'FEATURE_CHANGE' })).toBe(
      'I want to change an existing feature in the repo at acme/widgets (branch main).\n',
    );
  });

  it('버그 픽스', () => {
    expect(kickoffLead({ ...repo, taskType: 'BUG_FIX' })).toBe('I want to fix a bug in the repo at acme/widgets (branch main).\n');
  });

  it('DB 작업', () => {
    expect(kickoffLead({ ...repo, taskType: 'DB_WORK' })).toBe(
      'I want to change database tables or data used by the repo at acme/widgets (branch main).\n',
    );
  });
});

describe('taskTypeGuide — 타입별 인터뷰 지시 문단', () => {
  it('타입 없음·NEW_FEATURE·모르는 값은 빈 문자열', () => {
    for (const t of [undefined, null, 'NEW_FEATURE', 'QA_WORK', '', 42, {}]) {
      expect(taskTypeGuide(t)).toBe('');
    }
  });

  it('세 타입은 각자의 문단이고 끝에 빈 줄이 하나 있다', () => {
    const change = taskTypeGuide('FEATURE_CHANGE');
    const bug = taskTypeGuide('BUG_FIX');
    const db = taskTypeGuide('DB_WORK');
    expect(change.startsWith('작업 타입: 기능 수정.')).toBe(true);
    expect(bug.startsWith('작업 타입: 버그 픽스.')).toBe(true);
    expect(db.startsWith('작업 타입: DB 작업.')).toBe(true);
    for (const g of [change, bug, db]) {
      expect(g.endsWith('\n\n')).toBe(true);
      expect(g.endsWith('\n\n\n')).toBe(false);
    }
    expect(new Set([change, bug, db]).size).toBe(3);
  });

  it('DB 작업 문단은 바로 실행·승인 대기·거부를 모두 안내한다', () => {
    const db = taskTypeGuide('DB_WORK');
    for (const s of ['[바로 실행]', '[승인 대기]', 'DROP·TRUNCATE', '거부됩니다']) expect(db).toContain(s);
  });

  it('taskTypeGuide 켬은 둘째 인자 생략과 같다', () => {
    for (const t of ['FEATURE_CHANGE', 'BUG_FIX', 'DB_WORK', 'NEW_FEATURE', undefined, 'QA_WORK']) {
      expect(taskTypeGuide(t, true)).toBe(taskTypeGuide(t));
    }
  });

  it('taskTypeGuide 끔은 질문 순서 문장만 바뀐다', () => {
    const KEEP: Record<string, string[]> = {
      FEATURE_CHANGE: ['바꾸면 안 되는 것'],
      BUG_FIX: ['재현 → 원인 → 수정', '첨부(캡처·로그)'],
      DB_WORK: ['[바로 실행]', '[승인 대기]', 'DROP·TRUNCATE'],
    };
    for (const t of ['FEATURE_CHANGE', 'BUG_FIX', 'DB_WORK']) {
      const off = taskTypeGuide(t, false);
      const on = taskTypeGuide(t, true);
      expect(off).not.toBe(on);
      expect(off).not.toContain('부터 질문하세요');
      expect(off).toContain('한 번의 질문 묶음');
      expect(off).toContain('자료·코드·DB로');
      for (const s of KEEP[t]!) expect(off).toContain(s);
      // 앞머리(작업 타입 표지)와 끝 빈 줄은 켬과 같다
      expect(off.slice(0, 8)).toBe(on.slice(0, 8));
      expect(off.endsWith('\n\n')).toBe(true);
      expect(off.endsWith('\n\n\n')).toBe(false);
    }
    // 정확한 값(계획 Interfaces) — 첫 "…부터 질문하세요." 문장만 달라지고 계획 내용 요구는 켬과 같다
    expect(taskTypeGuide('FEATURE_CHANGE', false)).toBe(
      '작업 타입: 기능 수정. 먼저 코드를 읽어 지금 어떻게 동작하는지 확인하고, 무엇을 어떻게 바꿀지와 바꾸면 안 되는 것(유지할 동작)도 자료·코드·DB로 먼저 확인하세요. ' +
        '그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요.\n\n',
    );
    const bugOff = taskTypeGuide('BUG_FIX', false);
    const bugOn = taskTypeGuide('BUG_FIX', true);
    expect(bugOff.startsWith(
      '작업 타입: 버그 픽스. 어느 화면에서, 무엇을 했을 때, 어떤 오류가 뜨는지는 먼저 자료·코드·DB로 확인하고, 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요. 첨부(캡처·로그)가 있으면 ',
    )).toBe(true);
    expect(bugOff.endsWith(bugOn.slice(bugOn.indexOf('첨부(캡처·로그)')))).toBe(true);
    const dbOff = taskTypeGuide('DB_WORK', false);
    const dbOn = taskTypeGuide('DB_WORK', true);
    expect(dbOff.startsWith(
      '작업 타입: DB 작업. 어떤 테이블·데이터를 어떻게 바꿀지는 먼저 자료·코드·DB로 확인하고, 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요. 계획에는 ',
    )).toBe(true);
    expect(dbOff.endsWith(dbOn.slice(dbOn.indexOf('계획에는 실행할 SQL')))).toBe(true);
    // 타입 없음·NEW_FEATURE·모르는 값은 끔에서도 지시 문단이 없다
    for (const t of [undefined, null, 'NEW_FEATURE', 'QA_WORK', '']) expect(taskTypeGuide(t, false)).toBe('');
  });
});

describe('writableDbLine — 구현 때 쓰기가 열리는 연결 안내 (스펙 2026-11-03 §5.1)', () => {
  it('연결 이름이 없거나 배열이 아니면 빈 문자열(지금 문구 그대로)', () => {
    for (const v of [undefined, null, [], 'x', 42, {}, [''], [null, undefined, '  ']]) {
      expect(writableDbLine(v)).toBe('');
    }
  });

  it('이름을 쉼표로 이어 한 줄 문단으로 안내하고 끝에 빈 줄이 하나 있다', () => {
    expect(writableDbLine(['a', 'b'])).toContain('a, b.');
    const one = writableDbLine(['개발DB (PostgreSQL)']);
    expect(one).toBe(
      '구현 단계에서는 다음 DB 연결의 공용 개발 DB에 변경이 직접 적용됩니다: 개발DB (PostgreSQL). ' +
        '남의 테이블 컬럼 삭제·변경 등은 승인 대기로 남습니다. ' +
        'DB 변경이 필요하면 계획에 DB 변경 단계를 넣으세요. 이 인터뷰에서는 읽기 전용입니다.\n\n',
    );
    expect(one.endsWith('\n\n')).toBe(true);
    expect(one.endsWith('\n\n\n')).toBe(false);
  });

  it('문자열이 아닌 원소와 빈 이름은 건너뛴다', () => {
    expect(writableDbLine(['a', 7, '', null, ' b ', {}])).toContain(': a, b.');
  });
});
