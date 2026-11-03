import { describe, expect, it } from 'vitest';
import { kickoffLead, taskTypeGuide } from '../src/runner/kickoffByType.js';

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
