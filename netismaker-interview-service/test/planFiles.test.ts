import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { harvestSessionPlanFiles } from '../src/runner/planFiles.js';

// 실제 git 저장소로 검증한다 — "이번 세션에 생긴/바뀐 파일만" 판정이 git status에 달려 있어서
// mock으로는 tracked/untracked 구분이 맞는지 증명할 수 없다.
const PLAN = '# 배포 테스트 구현 계획\n\n> 메모\n\n### 작업 1: README 수정\n- [ ] a\n';
const SPEC = '# 배포 테스트 설계\n\n목적: 파이프라인 종단 검증.\n';

let repo: string;
function git(...args: string[]): void {
  execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { cwd: repo, stdio: 'ignore' });
}
function write(rel: string, content: string): string {
  const p = join(repo, rel);
  mkdirSync(join(p, '..'), { recursive: true });
  writeFileSync(p, content);
  return p;
}

beforeEach(() => {
  repo = mkdtempSync(join(tmpdir(), 'planfiles-'));
  git('init', '-q');
  write('README.md', '# r\n');
  git('add', '-A');
  git('commit', '-q', '-m', 'init');
});
afterEach(() => rmSync(repo, { recursive: true, force: true }));

describe('harvestSessionPlanFiles', () => {
  it('이번 세션에 새로 쓴 plan 파일로 완료를 판정하고, 같은 세션의 spec 파일을 설계로 싣는다 (2026-10-08 세션 21 회귀)', async () => {
    write('docs/superpowers/plans/2026-10-08-deploy-test.md', PLAN);
    write('docs/superpowers/specs/2026-10-08-deploy-test-design.md', SPEC);

    const r = await harvestSessionPlanFiles(repo);

    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.harvest.planMarkdown).toContain('# 배포 테스트 구현 계획');
    expect(r.harvest.planJson).toEqual([{ task: 1, title: 'README 수정' }]);
    expect(r.harvest.designMarkdown).toContain('목적: 파이프라인 종단 검증.');
  });

  it('spec 파일이 없으면 설계는 plan 헤딩 앞 내용(보통 빈 문자열)이다', async () => {
    write('docs/superpowers/plans/p.md', PLAN);

    const r = await harvestSessionPlanFiles(repo);

    expect(r.ok).toBe(true);
    if (r.ok) expect(r.harvest.designMarkdown).toBe('');
  });

  it('저장소에 원래 커밋돼 있던(이번 세션에 안 바뀐) plan 파일은 쓰지 않는다', async () => {
    write('docs/superpowers/plans/old.md', PLAN);
    git('add', '-A');
    git('commit', '-q', '-m', 'old plan');

    expect(await harvestSessionPlanFiles(repo)).toEqual({ ok: false });
  });

  it('이번 세션에 고친 tracked plan 파일은 쓴다', async () => {
    write('docs/superpowers/plans/old.md', '# 옛 구현 계획\n\n### 작업 1: 옛 작업\n');
    git('add', '-A');
    git('commit', '-q', '-m', 'old plan');
    write('docs/superpowers/plans/old.md', PLAN);

    const r = await harvestSessionPlanFiles(repo);

    expect(r.ok).toBe(true);
    if (r.ok) expect(r.harvest.planJson).toEqual([{ task: 1, title: 'README 수정' }]);
  });

  it('세션 plan 파일이 여럿이면 가장 최근에 수정된 파일을 쓴다', async () => {
    const older = write('docs/superpowers/plans/a.md', '# 첫 구현 계획\n\n### 작업 1: 첫안\n');
    write('docs/superpowers/plans/b.md', '# 둘째 구현 계획\n\n### 작업 1: 최종안\n');
    const past = new Date(Date.now() - 60_000);
    utimesSync(older, past, past);

    const r = await harvestSessionPlanFiles(repo);

    expect(r.ok).toBe(true);
    if (r.ok) expect(r.harvest.planJson).toEqual([{ task: 1, title: '최종안' }]);
  });

  it('plan 폴더 밖의 새 md 파일은 plan으로 보지 않는다', async () => {
    write('docs/notes/p.md', PLAN);

    expect(await harvestSessionPlanFiles(repo)).toEqual({ ok: false });
  });

  it('plan 파일이 정규 형식(작업 줄)이 아니면 ok:false', async () => {
    write('docs/superpowers/plans/p.md', '# 구현 계획\n\n작업 목록 없음\n');

    expect(await harvestSessionPlanFiles(repo)).toEqual({ ok: false });
  });

  it('git 저장소가 아니거나 경로가 없으면 throw 없이 ok:false', async () => {
    expect(await harvestSessionPlanFiles(join(repo, 'no-such-dir'))).toEqual({ ok: false });
  });
});
