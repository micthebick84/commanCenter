import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { REF_PREPARE_FALLBACK_REASON, ensureRefRepos, ensureRepo } from '../src/runner/repoPrepare.js';
import type { InterviewClaimResponse, RefRepoClaim, RefState, WorkerQuestionRequest } from '../src/types.js';

describe('ensureRepo', () => {
  it('clones --depth 1 --branch into workDir when the checkout does not exist', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const exists = vi.fn().mockReturnValue(false); // no .git yet
    await ensureRepo(
      { githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/session-42' },
      { run, exists },
    );
    expect(run).toHaveBeenCalledWith(
      'git',
      ['clone', '--depth', '1', '--branch', 'main', 'https://github.com/acme/widgets.git', '/wd/session-42'],
      expect.any(Object),
    );
  });

  it('fetch + reset --hard when the checkout already exists', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const exists = vi.fn().mockReturnValue(true); // .git present
    await ensureRepo(
      { githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/session-42' },
      { run, exists },
    );
    const cmds = run.mock.calls.map((c) => [c[0], ...(c[1] as string[])].join(' '));
    expect(cmds).toContain(
      'git fetch --depth 1 https://github.com/acme/widgets.git +refs/heads/main:refs/remotes/origin/main',
    );
    expect(cmds).toContain('git reset --hard origin/main');
  });

  it('clones with an authenticated gitlab url built from the claim, then scrubs origin back to plain', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    await ensureRepo(
      {
        githubRepo: 'g/sub/p',
        githubBranch: 'develop',
        workDir: '/wd/s1',
        gitUrl: 'https://gitlab.hamon.vip/g/sub/p.git',
        repoHost: 'gitlab',
        tokens: { gitlabToken: 'glpat-B' },
      },
      { run, exists: vi.fn().mockReturnValue(false) },
    );
    expect(run).toHaveBeenCalledWith(
      'git',
      ['clone', '--depth', '1', '--branch', 'develop', 'https://oauth2:glpat-B@gitlab.hamon.vip/g/sub/p.git', '/wd/s1'],
      expect.any(Object),
    );
    expect(run).toHaveBeenCalledWith(
      'git',
      ['remote', 'set-url', 'origin', 'https://gitlab.hamon.vip/g/sub/p.git'],
      expect.objectContaining({ cwd: '/wd/s1' }),
    );
  });

  it('never persists the authenticated url in origin for an existing checkout (set-url plain, fetch authenticated)', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    await ensureRepo(
      { githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/s2', tokens: { githubPat: 'ghp_A' } },
      { run, exists: vi.fn().mockReturnValue(true) },
    );
    const cmds = run.mock.calls.map((c) => [c[0], ...(c[1] as string[])].join(' '));
    expect(cmds[0]).toBe('git remote set-url origin https://github.com/acme/widgets.git');
    expect(cmds[1]).toBe(
      'git fetch --depth 1 https://oauth2:ghp_A@github.com/acme/widgets.git +refs/heads/main:refs/remotes/origin/main',
    );
    expect(cmds[2]).toBe('git reset --hard origin/main');
  });

  it('never leaves an oauth2/token credential in any "remote set-url" invocation, fresh or existing checkout', async () => {
    const freshRun = vi.fn().mockResolvedValue(undefined);
    await ensureRepo(
      {
        githubRepo: 'acme/widgets',
        githubBranch: 'main',
        workDir: '/wd/fresh',
        tokens: { githubPat: 'ghp_SECRET' },
      },
      { run: freshRun, exists: vi.fn().mockReturnValue(false) },
    );

    const existingRun = vi.fn().mockResolvedValue(undefined);
    await ensureRepo(
      {
        githubRepo: 'g/sub/p',
        githubBranch: 'develop',
        workDir: '/wd/existing',
        gitUrl: 'https://gitlab.hamon.vip/g/sub/p.git',
        repoHost: 'gitlab',
        tokens: { gitlabToken: 'glpat-SECRET' },
      },
      { run: existingRun, exists: vi.fn().mockReturnValue(true) },
    );

    for (const run of [freshRun, existingRun]) {
      const setUrlCalls = run.mock.calls.filter(
        (c) => (c[1] as string[])[0] === 'remote' && (c[1] as string[])[1] === 'set-url',
      );
      expect(setUrlCalls.length).toBeGreaterThan(0);
      for (const call of setUrlCalls) {
        const joined = (call[1] as string[]).join(' ');
        expect(joined).not.toContain('oauth2:');
        expect(joined).not.toContain('ghp_SECRET');
        expect(joined).not.toContain('glpat-SECRET');
      }
    }
  });

  it('never leaks the token through a git failure', async () => {
    const run = vi
      .fn()
      .mockRejectedValue(new Error('Command failed: git clone https://oauth2:glpat-B@gitlab.hamon.vip/g/p.git /wd'));
    await expect(
      ensureRepo(
        {
          githubRepo: 'g/p',
          githubBranch: 'main',
          workDir: '/wd/s3',
          gitUrl: 'https://gitlab.hamon.vip/g/p.git',
          repoHost: 'gitlab',
          tokens: { gitlabToken: 'glpat-B' },
        },
        { run, exists: vi.fn().mockReturnValue(false) },
      ),
    ).rejects.toThrow(/https:\/\/\*\*\*@gitlab\.hamon\.vip/);
    await expect(
      ensureRepo(
        { githubRepo: 'g/p', githubBranch: 'main', workDir: '/wd/s3', gitUrl: 'https://gitlab.hamon.vip/g/p.git', tokens: { gitlabToken: 'glpat-B' } },
        { run, exists: vi.fn().mockReturnValue(false) },
      ),
    ).rejects.not.toThrow(/glpat-B/);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// ensureRefRepos — 질문 세션의 읽기 전용 참조 repo 준비 (스펙 2026-10-22 §5.1)
// ─────────────────────────────────────────────────────────────────────────────

const SHA_A = 'a'.repeat(40);
const SHA_B = 'b'.repeat(40);
const FALLBACK_REASON = '참조 repo를 받지 못했습니다';

function refClaim(over: Partial<RefRepoClaim> = {}): RefRepoClaim {
  return {
    catalogId: 12,
    alias: 'Netis6.5(구)',
    githubRepo: 'acme/legacy',
    branch: 'release/6.5',
    dir: '/r/_refs/5/12',
    ...over,
  };
}

/** 가짜 ops: 기본은 "폴더 없음 + HEAD = SHA_A". git은 호출 기록만 남기고, 출력은 실제 git처럼 줄바꿈이 붙는다. */
function fakeRefOps() {
  return {
    run: vi.fn().mockResolvedValue(undefined),
    output: vi.fn().mockResolvedValue(`${SHA_A}\n`),
    exists: vi.fn().mockReturnValue(false),
    rm: vi.fn(),
    rename: vi.fn(),
  };
}
type FakeRefOps = ReturnType<typeof fakeRefOps>;

/**
 * 폴더가 이미 있고 HEAD가 branch인 상태 — `rev-parse --symbolic-full-name HEAD`는 `refs/heads/<branch>`(분리된 HEAD면
 * branch를 null로 주고 git처럼 `HEAD`), `rev-parse HEAD`는 sha. 실제 git처럼 줄바꿈이 붙는다.
 */
function withCheckoutOn(ops: FakeRefOps, branch: string | null, sha: string = SHA_A): FakeRefOps {
  ops.exists.mockReturnValue(true);
  ops.output.mockImplementation(async (_cmd: string, args: string[]) =>
    args.includes('--symbolic-full-name') ? `${branch === null ? 'HEAD' : `refs/heads/${branch}`}\n` : `${sha}\n`,
  );
  return ops;
}

/** 호출 순서 비교용 — vitest가 모든 목에 걸쳐 매기는 단조증가 번호. */
const callOrder = (fn: { mock: { invocationCallOrder: number[] } }, index: number): number =>
  fn.mock.invocationCallOrder[index]!;

/** run 호출들의 git 하위 명령(clone·remote·fetch…). */
const gitSubcommands = (run: FakeRefOps['run']): string[] => run.mock.calls.map((c) => (c[1] as string[])[0]!);

describe('ensureRefRepos', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('clones a missing ref into <dir>.part, scrubs origin, then renames into place', async () => {
    const ops = fakeRefOps();
    const ref = refClaim();

    const result = await ensureRefRepos([ref], { tokens: { githubPat: 'ghp_SECRET' } }, ops);

    expect(ops.exists).toHaveBeenCalledWith(join('/r/_refs/5/12', '.git'));
    // 인증 URL은 clone 전송에만 쓰고, 받는 곳은 <dir>.part, origin은 평문 URL로 되돌린다
    expect(ops.run.mock.calls).toEqual([
      [
        'git',
        [
          'clone',
          '--depth',
          '1',
          '--branch',
          'release/6.5',
          'https://oauth2:ghp_SECRET@github.com/acme/legacy.git',
          '/r/_refs/5/12.part',
        ],
        {},
      ],
      ['git', ['remote', 'set-url', 'origin', 'https://github.com/acme/legacy.git'], { cwd: '/r/_refs/5/12.part' }],
    ]);
    expect(ops.rename).toHaveBeenCalledTimes(1);
    expect(ops.rename).toHaveBeenCalledWith('/r/_refs/5/12.part', '/r/_refs/5/12');
    // 순서: 예전 폴더·찌꺼기 정리 → clone → origin 정리 → 제자리로 → HEAD 커밋
    expect(ops.rm.mock.calls).toEqual([['/r/_refs/5/12'], ['/r/_refs/5/12.part']]);
    expect(callOrder(ops.rm, 1)).toBeLessThan(callOrder(ops.run, 0));
    expect(callOrder(ops.run, 1)).toBeLessThan(callOrder(ops.rename, 0));
    expect(callOrder(ops.rename, 0)).toBeLessThan(callOrder(ops.output, 0));
    expect(ops.output).toHaveBeenCalledWith('git', ['rev-parse', 'HEAD'], { cwd: '/r/_refs/5/12' });
    expect(result).toEqual([{ ref, sha: SHA_A, error: null }]);
  });

  it('uses the ref host token for a gitlab ref and never writes it into origin', async () => {
    const ops = fakeRefOps();
    const ref = refClaim({
      githubRepo: 'g/sub/p',
      gitUrl: 'https://gitlab.hamon.vip/g/sub/p.git',
      repoHost: 'gitlab',
      branch: 'develop',
    });

    await ensureRefRepos([ref], { tokens: { githubPat: 'ghp_OTHER', gitlabToken: 'glpat-B' } }, ops);

    expect(ops.run).toHaveBeenNthCalledWith(
      1,
      'git',
      ['clone', '--depth', '1', '--branch', 'develop', 'https://oauth2:glpat-B@gitlab.hamon.vip/g/sub/p.git', '/r/_refs/5/12.part'],
      {},
    );
    expect(ops.run).toHaveBeenNthCalledWith(
      2,
      'git',
      ['remote', 'set-url', 'origin', 'https://gitlab.hamon.vip/g/sub/p.git'],
      { cwd: '/r/_refs/5/12.part' },
    );
  });

  it('reuses an existing checkout on the same branch without fetching', async () => {
    const ops = withCheckoutOn(fakeRefOps(), 'release/6.5', SHA_B);
    const ref = refClaim();

    const result = await ensureRefRepos([ref], {}, ops);

    // fetch하지 않는 것이 곧 커밋 고정 — git은 읽기만 하고 폴더는 건드리지 않는다
    expect(ops.run).not.toHaveBeenCalled();
    expect(ops.rm).not.toHaveBeenCalled();
    expect(ops.rename).not.toHaveBeenCalled();
    expect(ops.output.mock.calls).toEqual([
      ['git', ['rev-parse', '--symbolic-full-name', 'HEAD'], { cwd: '/r/_refs/5/12' }],
      ['git', ['rev-parse', 'HEAD'], { cwd: '/r/_refs/5/12' }],
    ]);
    expect(result).toEqual([{ ref, sha: SHA_B, error: null }]);
  });

  it('re-clones when the existing checkout is on a different branch', async () => {
    const ops = withCheckoutOn(fakeRefOps(), 'main', SHA_B);
    const ref = refClaim();

    const result = await ensureRefRepos([ref], {}, ops);

    // 낡은 폴더를 지우고(rm dir → rm part) → 같은 규칙으로 .part에 받아(토큰 없으면 평문 URL) → 제자리로
    expect(ops.rm.mock.calls).toEqual([['/r/_refs/5/12'], ['/r/_refs/5/12.part']]);
    expect(ops.run).toHaveBeenNthCalledWith(
      1,
      'git',
      ['clone', '--depth', '1', '--branch', 'release/6.5', 'https://github.com/acme/legacy.git', '/r/_refs/5/12.part'],
      {},
    );
    expect(ops.rename).toHaveBeenCalledWith('/r/_refs/5/12.part', '/r/_refs/5/12');
    expect(callOrder(ops.rm, 0)).toBeLessThan(callOrder(ops.run, 0));
    expect(result).toEqual([{ ref, sha: SHA_B, error: null }]);
  });

  it('treats a detached HEAD as a different branch', async () => {
    // 분리된 HEAD면 git이 (종료 코드 0으로) "HEAD"를 돌려준다 — 요청 브랜치(refs/heads/…)와 같다고 볼 수 없다
    const ops = withCheckoutOn(fakeRefOps(), null);

    await ensureRefRepos([refClaim()], {}, ops);

    expect(gitSubcommands(ops.run)).toEqual(['clone', 'remote']);
    expect(ops.rename).toHaveBeenCalledTimes(1);
  });

  it('keeps the existing folder untouched when its HEAD cannot be read', async () => {
    // 읽지 못했다는 이유만으로 폴더를 지우지 않는다 — 고정한 체크아웃을 일시 오류(타임아웃 등)로 잃지 않게
    const ops = fakeRefOps();
    ops.exists.mockReturnValue(true);
    ops.output.mockRejectedValue(new Error('fatal: not a git repository'));
    const ref = refClaim();

    const result = await ensureRefRepos([ref], {}, ops);

    expect(result).toEqual([{ ref, sha: null, error: expect.stringContaining('not a git repository') }]);
    expect(ops.run).not.toHaveBeenCalled();
    expect(ops.rename).not.toHaveBeenCalled();
    expect(ops.rm.mock.calls.map((c) => c[0])).not.toContain('/r/_refs/5/12');
  });

  it('returns a masked error and leaves no folder when the clone fails', async () => {
    const ops = fakeRefOps();
    ops.run.mockRejectedValue(
      new Error(
        'Command failed: git clone --depth 1 --branch master https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git /r/_refs/5/12.part\n' +
          'fatal: Remote branch master not found in upstream origin\n',
      ),
    );
    const ref = refClaim({
      githubRepo: 'g/p',
      gitUrl: 'https://gitlab.hamon.vip/g/p.git',
      repoHost: 'gitlab',
      branch: 'master',
    });

    const result = await ensureRefRepos([ref], { tokens: { gitlabToken: 'glpat-SECRET' } }, ops);

    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ ref, sha: null });
    const error = result[0]!.error!;
    expect(error).not.toContain('glpat-SECRET');
    expect(error).toContain('***@');
    expect(error).toContain('Remote branch master not found'); // 사유는 읽을 수 있게 남는다
    expect(JSON.stringify(result)).not.toContain('glpat-SECRET');
    // 반쪽 폴더는 남기지 않는다 — clone이 실패한 뒤에 .part를 지운다(앞의 두 번은 준비 단계)
    expect(ops.rm).toHaveBeenCalledTimes(3);
    expect(ops.rm).toHaveBeenLastCalledWith('/r/_refs/5/12.part');
    expect(callOrder(ops.rm, 2)).toBeGreaterThan(callOrder(ops.run, 0));
    expect(ops.rename).not.toHaveBeenCalled();
  });

  it('removes the .part folder and reports the reason when the final rename fails', async () => {
    const ops = fakeRefOps();
    ops.rename.mockImplementation(() => {
      throw new Error("EPERM: operation not permitted, rename '/r/_refs/5/12.part' -> '/r/_refs/5/12'");
    });

    const result = await ensureRefRepos([refClaim()], {}, ops);

    expect(result[0]).toMatchObject({ sha: null, error: expect.stringContaining('EPERM') });
    expect(ops.rm).toHaveBeenLastCalledWith('/r/_refs/5/12.part');
    expect(callOrder(ops.rm, 2)).toBeGreaterThan(callOrder(ops.rename, 0));
  });

  it('still reports the original failure when removing the partial folder fails too', async () => {
    const ops = fakeRefOps();
    ops.run.mockRejectedValue(new Error('fatal: unable to access the remote'));
    let removals = 0;
    ops.rm.mockImplementation(() => {
      removals += 1;
      if (removals > 2) throw new Error('EBUSY: resource busy or locked'); // 실패 뒤 정리 단계
    });

    const result = await ensureRefRepos([refClaim()], {}, ops);

    expect(result[0]!.sha).toBeNull();
    expect(result[0]!.error).toContain('unable to access the remote');
    expect(result[0]!.error).not.toContain('EBUSY');
  });

  it.each([
    ['an Error with an empty message', new Error('')],
    ['an Error with a blank message', new Error('  \n')],
    ['an empty string', ''],
    ['undefined', undefined],
  ])('falls back to a fixed reason when git fails with %s', async (_label, thrown) => {
    // API는 error를 있는 그대로(빈 문자열도) 저장하므로 실패는 항상 비어 있지 않은 사유여야 한다
    const ops = fakeRefOps();
    ops.run.mockRejectedValue(thrown);

    const result = await ensureRefRepos([refClaim()], {}, ops);

    expect(result[0]!.sha).toBeNull();
    expect(result[0]!.error).toBe(FALLBACK_REASON);
  });

  it('exports the fallback reason so the runner reuses it instead of keeping its own copy', () => {
    expect(REF_PREPARE_FALLBACK_REASON).toBe(FALLBACK_REASON);
  });

  it('passes the abort signal to every git call', async () => {
    const { signal } = new AbortController();

    // 새로 받는 경로: clone · set-url · rev-parse
    const fresh = fakeRefOps();
    await ensureRefRepos([refClaim()], { signal }, fresh);
    // 재사용 경로: rev-parse --symbolic-full-name · rev-parse
    const reuse = withCheckoutOn(fakeRefOps(), 'release/6.5');
    await ensureRefRepos([refClaim()], { signal }, reuse);

    const calls = [...fresh.run.mock.calls, ...fresh.output.mock.calls, ...reuse.output.mock.calls];
    expect(calls).toHaveLength(2 + 1 + 2);
    for (const call of calls) {
      expect((call[2] as { signal?: AbortSignal }).signal).toBe(signal);
    }
  });

  it('prepares refs one after another in the given order', async () => {
    const ops = fakeRefOps();
    let finishFirstClone!: () => void;
    const firstClone = new Promise<void>((resolve) => {
      finishFirstClone = resolve;
    });
    ops.run.mockImplementation((_cmd: string, args: string[]) =>
      args[0] === 'clone' && args.includes('/r/_refs/5/12.part') ? firstClone : Promise.resolve(undefined),
    );
    const first = refClaim();
    const second = refClaim({ catalogId: 13, alias: '공통 UI', githubRepo: 'acme/ui', branch: 'main', dir: '/r/_refs/5/13' });

    const pending = ensureRefRepos([first, second], {}, ops);
    await new Promise((resolve) => setImmediate(resolve));

    // 첫 clone이 끝나기 전에는 두 번째 참조에 손대지 않는다
    expect(ops.run.mock.calls.map((c) => (c[1] as string[]).at(-1))).toEqual(['/r/_refs/5/12.part']);
    expect(ops.rm.mock.calls).toEqual([['/r/_refs/5/12'], ['/r/_refs/5/12.part']]);

    finishFirstClone();
    const result = await pending;

    // run: [clone 12, set-url 12, clone 13, set-url 13] — 두 번째 clone은 첫 번째 rename 뒤
    expect(gitSubcommands(ops.run)).toEqual(['clone', 'remote', 'clone', 'remote']);
    expect(callOrder(ops.rename, 0)).toBeLessThan(callOrder(ops.run, 2));
    expect(ops.rename.mock.calls).toEqual([
      ['/r/_refs/5/12.part', '/r/_refs/5/12'],
      ['/r/_refs/5/13.part', '/r/_refs/5/13'],
    ]);
    expect(result.map((r) => r.ref)).toEqual([first, second]);
  });

  it('keeps going with the remaining refs when one of them fails', async () => {
    const ops = fakeRefOps();
    ops.run.mockImplementation(async (_cmd: string, args: string[]) => {
      if (args[0] === 'clone' && args.includes('release/6.5')) {
        throw new Error('fatal: Remote branch release/6.5 not found in upstream origin');
      }
    });
    const broken = refClaim();
    const healthy = refClaim({ catalogId: 13, alias: '공통 UI', githubRepo: 'acme/ui', branch: 'main', dir: '/r/_refs/5/13' });

    const result = await ensureRefRepos([broken, healthy], {}, ops);

    expect(result).toEqual([
      { ref: broken, sha: null, error: expect.stringContaining('release/6.5 not found') },
      { ref: healthy, sha: SHA_A, error: null },
    ]);
    expect(ops.rename).toHaveBeenCalledTimes(1);
    expect(ops.rename).toHaveBeenCalledWith('/r/_refs/5/13.part', '/r/_refs/5/13');
  });

  it('does nothing for an empty list', async () => {
    const ops = fakeRefOps();

    await expect(ensureRefRepos([], { tokens: { githubPat: 'ghp_A' } }, ops)).resolves.toEqual([]);

    for (const fn of Object.values(ops)) expect(fn).not.toHaveBeenCalled();
  });

  describe('pinned commit (claim.sha)', () => {
    it('does not warn when there is no pin or the commit matches it', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

      const unpinned = await ensureRefRepos([refClaim({ sha: null })], {}, fakeRefOps());
      const matching = await ensureRefRepos([refClaim({ sha: SHA_A })], {}, withCheckoutOn(fakeRefOps(), 'release/6.5', SHA_A));

      expect(unpinned[0]).toMatchObject({ sha: SHA_A, error: null });
      expect(matching[0]).toMatchObject({ sha: SHA_A, error: null });
      expect(warn).not.toHaveBeenCalled();
    });

    it('only warns when a fresh clone lands on a different commit than the pinned one', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const ops = fakeRefOps(); // 폴더가 사라진 경우 — 새로 받은 HEAD는 SHA_A
      const ref = refClaim({ sha: SHA_B });

      const result = await ensureRefRepos([ref], { tokens: { githubPat: 'ghp_SECRET' } }, ops);

      // 고정 커밋 재현은 하지 않는다: 브랜치 최신을 받고, 새로 받은 커밋을 그대로 알린다(API는 이미 정해진 sha를 바꾸지 않는다)
      expect(gitSubcommands(ops.run)).toEqual(['clone', 'remote']);
      expect(result).toEqual([{ ref, sha: SHA_A, error: null }]);
      expect(warn).toHaveBeenCalledTimes(1);
      const message = String(warn.mock.calls[0]![0]);
      expect(message).toContain('Netis6.5(구)');
      expect(message).toContain(SHA_B.slice(0, 7));
      expect(message).toContain(SHA_A.slice(0, 7));
      expect(message).not.toContain('ghp_SECRET');
    });

    it('only warns when the reused checkout is not at the pinned commit', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const ops = withCheckoutOn(fakeRefOps(), 'release/6.5', SHA_A);
      const ref = refClaim({ sha: SHA_B });

      const result = await ensureRefRepos([ref], {}, ops);

      expect(ops.run).not.toHaveBeenCalled();
      expect(result).toEqual([{ ref, sha: SHA_A, error: null }]);
      expect(warn).toHaveBeenCalledTimes(1);
    });
  });

  it('carries the claim and report fields of the wire contract (compile-time check)', () => {
    // 필드 이름은 Java InterviewClaimResponse.ClaimRefRepo / WorkerQuestionRequest.RefState와 같다 — 어긋나면 tsc가 막는다
    const refRepos: InterviewClaimResponse['refRepos'] = [
      {
        catalogId: 12,
        alias: 'Netis6.5(구)',
        githubRepo: 'acme/legacy',
        gitUrl: null,
        repoHost: 'github',
        branch: 'release/6.5',
        dir: '/r/_refs/5/12',
        sha: null,
      },
    ];
    const state: RefState = { catalogId: 12, branch: 'release/6.5', sha: SHA_A, error: null };
    const refStates: WorkerQuestionRequest['refStates'] = [state];

    expect(refRepos?.[0]?.dir).toBe('/r/_refs/5/12');
    expect(refStates).toEqual([{ catalogId: 12, branch: 'release/6.5', sha: SHA_A, error: null }]);
  });
});

// 기본 ops(execFile·rmSync·renameSync)는 가짜 ops로는 검증되지 않는다 — 로컬 저장소를 file:// URL로 받아 실제 git으로 확인한다
// (planFiles.test.ts와 같은 방식).
describe('ensureRefRepos with the default ops (real git, local file:// origin)', () => {
  let work: string;
  let origin: string;

  const git = (cwd: string, ...args: string[]): string =>
    execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', '-c', 'core.autocrlf=false', ...args], {
      cwd,
      encoding: 'utf8',
    }).trim();
  const commitToOrigin = (file: string): string => {
    writeFileSync(join(origin, file), `${file}\n`);
    git(origin, 'add', '-A');
    git(origin, 'commit', '-q', '-m', file);
    return git(origin, 'rev-parse', 'HEAD');
  };

  beforeEach(() => {
    work = mkdtempSync(join(tmpdir(), 'refrepos-'));
    origin = join(work, 'origin');
    mkdirSync(origin);
    git(origin, 'init', '-q', '-b', 'release/6.5');
  });
  afterEach(() => {
    rmSync(work, { recursive: true, force: true });
  });

  it('clones in place, keeps the first commit on reuse, re-clones on another branch, and leaves nothing behind on failure', async () => {
    const c1 = commitToOrigin('a.txt');
    git(origin, 'branch', 'main'); // main은 c1에서 멈춘다
    const originUrl = pathToFileURL(origin).href;
    const ref: RefRepoClaim = {
      catalogId: 12,
      alias: 'legacy',
      githubRepo: 'acme/legacy',
      gitUrl: originUrl,
      repoHost: 'github',
      branch: 'release/6.5',
      dir: join(work, 'root', '_refs', '5', '12'),
      sha: null,
    };

    // 새로 받는다 — 제자리에(.part는 남지 않는다), origin은 평문 URL, 결과 sha는 HEAD
    expect(await ensureRefRepos([ref], {})).toEqual([{ ref, sha: c1, error: null }]);
    expect(existsSync(join(ref.dir, '.git'))).toBe(true);
    expect(existsSync(`${ref.dir}.part`)).toBe(false);
    expect(git(ref.dir, 'remote', 'get-url', 'origin')).toBe(originUrl);

    // 원본이 앞서 가도 같은 브랜치면 그대로 쓴다 — 처음 받은 커밋을 지킨다(fetch 없음)
    commitToOrigin('b.txt');
    expect(await ensureRefRepos([{ ...ref, sha: c1 }], {})).toMatchObject([{ sha: c1, error: null }]);
    expect(existsSync(join(ref.dir, 'b.txt'))).toBe(false);

    // 다른 브랜치를 요청하면 폴더를 지우고 그 브랜치로 다시 받는다
    expect(await ensureRefRepos([{ ...ref, branch: 'main' }], {})).toMatchObject([{ sha: c1, error: null }]);
    expect(git(ref.dir, 'rev-parse', '--symbolic-full-name', 'HEAD')).toBe('refs/heads/main');

    // 없는 브랜치는 사유만 돌려주고 폴더도 .part도 남기지 않는다
    const missing: RefRepoClaim = { ...ref, catalogId: 13, branch: 'nope', dir: join(work, 'root', '_refs', '5', '13') };
    expect(await ensureRefRepos([missing], {})).toMatchObject([{ sha: null, error: expect.stringContaining('nope') }]);
    expect(existsSync(missing.dir)).toBe(false);
    expect(existsSync(`${missing.dir}.part`)).toBe(false);
  }, 30_000);

  it('keeps reusing the checkout (no re-clone, same pinned commit) when a tag has the same name as the branch', async () => {
    // 브랜치 끝 커밋에 브랜치와 같은 이름의 태그가 붙은 원본 — clone하면 태그도 함께 들어오고, 그러면 git은
    // `rev-parse --abbrev-ref HEAD`·`symbolic-ref --short HEAD`에서 모호성을 피하려고 `heads/<이름>`을 돌려준다.
    // 그 문자열을 브랜치 이름과 비교하면 거짓이 되어 매 턴 폴더를 지우고 다시 받는다 — 고정이 풀린다.
    const c1 = commitToOrigin('a.txt');
    git(origin, 'tag', 'release/6.5');
    const ref: RefRepoClaim = {
      catalogId: 12,
      alias: 'legacy',
      githubRepo: 'acme/legacy',
      gitUrl: pathToFileURL(origin).href,
      repoHost: 'github',
      branch: 'release/6.5',
      dir: join(work, 'root', '_refs', '5', '12'),
      sha: null,
    };

    expect(await ensureRefRepos([ref], {})).toEqual([{ ref, sha: c1, error: null }]);
    expect(git(ref.dir, 'tag', '--list', 'release/6.5')).toBe('release/6.5'); // 전제: 같은 이름의 태그가 체크아웃에 있다

    // 체크아웃 안에 표식을 두고 원본을 앞서 보낸다 — 다시 받았다면 표식이 사라지고 sha가 c2로 바뀐다
    writeFileSync(join(ref.dir, 'marker.txt'), 'kept\n');
    commitToOrigin('b.txt');

    expect(await ensureRefRepos([{ ...ref, sha: c1 }], {})).toMatchObject([{ sha: c1, error: null }]);
    expect(existsSync(join(ref.dir, 'marker.txt'))).toBe(true);
    expect(existsSync(join(ref.dir, 'b.txt'))).toBe(false);
  }, 30_000);
});
