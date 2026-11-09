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
