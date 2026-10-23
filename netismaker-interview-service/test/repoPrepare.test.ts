import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  REF_PREPARE_FALLBACK_REASON,
  ensureRefRepos,
  ensureRepo,
  noPromptGitEnv,
  type RepoInput,
} from '../src/runner/repoPrepare.js';
import type { InterviewClaimResponse, RefRepoClaim, RefState, WorkerQuestionRequest } from '../src/types.js';

/** 대상·참조의 모든 git 호출이 하위 명령 앞에 달고 가는 인자 — 운영자 PC의 자격증명 도우미(GCM)와 askpass를 끈다. */
const NO_PROMPT_GIT_PREFIX = ['-c', 'credential.helper=', '-c', 'core.askPass='];

type GitCallOptionsLike = { cwd?: string; signal?: AbortSignal; env?: NodeJS.ProcessEnv };
/** 목 git 호출의 세 번째 인자(옵션). */
const optsOf = (call: unknown[]): GitCallOptionsLike => call[2] as GitCallOptionsLike;

/** 프롬프트를 끈 환경인지 — askpass 프로그램 변수가 없고(대소문자 무관), 터미널 프롬프트와 GCM 대화상자가 꺼져 있다. */
function expectPromptsOff(env: NodeJS.ProcessEnv | undefined): void {
  expect(env).toBeDefined();
  expect(Object.keys(env!).filter((name) => /^(git|ssh)_askpass$/i.test(name))).toEqual([]);
  expect(env!.GIT_TERMINAL_PROMPT).toBe('0');
  expect(env!.GCM_INTERACTIVE).toBe('never');
}

/** run 호출들을 `git <하위 명령…>` 한 줄씩으로 — 앞의 공통 인자(-c …)가 붙었는지 확인하고 뗀 뒤 본다. */
function gitCommandLines(run: ReturnType<typeof vi.fn>): string[] {
  return run.mock.calls.map((c) => {
    const args = c[1] as string[];
    expect(args.slice(0, NO_PROMPT_GIT_PREFIX.length)).toEqual(NO_PROMPT_GIT_PREFIX);
    return [c[0], ...args.slice(NO_PROMPT_GIT_PREFIX.length)].join(' ');
  });
}

describe('ensureRepo', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('clones --depth 1 --branch into workDir when the checkout does not exist', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    const exists = vi.fn().mockReturnValue(false); // no .git yet
    await ensureRepo(
      { githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/session-42' },
      { run, exists },
    );
    expect(run).toHaveBeenCalledWith(
      'git',
      [...NO_PROMPT_GIT_PREFIX, 'clone', '--depth', '1', '--branch', 'main', 'https://github.com/acme/widgets.git', '/wd/session-42'],
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
    const cmds = gitCommandLines(run);
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
      [...NO_PROMPT_GIT_PREFIX, 'clone', '--depth', '1', '--branch', 'develop', 'https://oauth2:glpat-B@gitlab.hamon.vip/g/sub/p.git', '/wd/s1'],
      expect.any(Object),
    );
    expect(run).toHaveBeenCalledWith(
      'git',
      [...NO_PROMPT_GIT_PREFIX, 'remote', 'set-url', 'origin', 'https://gitlab.hamon.vip/g/sub/p.git'],
      expect.objectContaining({ cwd: '/wd/s1' }),
    );
  });

  it('never persists the authenticated url in origin for an existing checkout (set-url plain, fetch authenticated)', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    await ensureRepo(
      { githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/s2', tokens: { githubPat: 'ghp_A' } },
      { run, exists: vi.fn().mockReturnValue(true) },
    );
    const cmds = gitCommandLines(run);
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
      const setUrlLines = gitCommandLines(run).filter((line) => line.startsWith('git remote set-url '));
      expect(setUrlLines.length).toBeGreaterThan(0);
      for (const line of setUrlLines) {
        expect(line).not.toContain('oauth2:');
        expect(line).not.toContain('ghp_SECRET');
        expect(line).not.toContain('glpat-SECRET');
      }
    }
  });

  // 대상도 참조와 같은 git 호출을 쓴다 — 운영자 PC의 자격증명 도우미·askpass·터미널 프롬프트 없이. 이 PC처럼 시스템 git 설정에
  // `credential.helper=manager`(GCM)가 있고 로그온 세션에서 돌면, 토큰이 없는 호스트의 401(비공개·이름이 바뀐·지워진 repo — GitHub는
  // 없는 repo에도 401)에서 GCM 대화상자가 뜬 채 턴 상한(30분)까지 멈췄다가 세션이 늦게 FAILED로 끝났다.
  it('runs every git call without the credential helper, askpass or prompts, on a fresh clone and an existing checkout alike', async () => {
    vi.stubEnv('GIT_ASKPASS', '/operator/askpass');
    vi.stubEnv('GIT_TERMINAL_PROMPT', '1');
    vi.stubEnv('REPOTEST_KEEP', 'kept');
    const { signal } = new AbortController();
    const run = vi.fn().mockResolvedValue(undefined);

    await ensureRepo({ githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/a', signal }, { run, exists: vi.fn().mockReturnValue(false) });
    await ensureRepo({ githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/b', signal }, { run, exists: vi.fn().mockReturnValue(true) });

    // 다섯 호출 모두 하위 명령 앞에 도우미를 끄는 인자가 붙고(gitCommandLines가 확인), 프롬프트를 끈 환경 사본과 턴 신호를 받는다
    expect(gitCommandLines(run).map((line) => line.split(' ')[1])).toEqual(['clone', 'remote', 'remote', 'fetch', 'reset']);
    for (const call of run.mock.calls) {
      const { env, signal: passed } = optsOf(call);
      expectPromptsOff(env);
      expect(env!.REPOTEST_KEEP).toBe('kept'); // 나머지 변수(PATH 등)는 그대로 물려준다
      expect(passed).toBe(signal);
    }
    // 부모 환경은 건드리지 않는다 — 자식에게 주는 것은 사본이다
    expect(process.env.GIT_ASKPASS).toBe('/operator/askpass');
    expect(process.env.GIT_TERMINAL_PROMPT).toBe('1');
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
const INVALID_DIR_REASON = '참조 폴더 경로가 올바르지 않습니다';

/**
 * 참조 git 호출의 옵션 모양 — cwd(있을 때만)와 프롬프트를 끈 환경. 환경의 세부(askpass 제거·process.env 복사)는 전용 테스트가 본다.
 * `signal`은 넘기지 않았을 때 키 자체가 없어야 하므로 toEqual이 함께 확인한다.
 */
const refOpts = (cwd?: string) => ({
  ...(cwd === undefined ? {} : { cwd }),
  env: expect.objectContaining({ GIT_TERMINAL_PROMPT: '0', GCM_INTERACTIVE: 'never' }),
});

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

/**
 * 가짜 ops: 기본은 "폴더 없음 + HEAD = SHA_A". git은 호출 기록만 남기고, 출력은 실제 git처럼 줄바꿈이 붙는다.
 * sleep도 가짜 — rename 재시도가 실제로 기다리지 않게 하고(테스트가 느려지지 않는다) 기다린 시간을 기록한다.
 */
function fakeRefOps() {
  return {
    run: vi.fn().mockResolvedValue(undefined),
    output: vi.fn().mockResolvedValue(`${SHA_A}\n`),
    exists: vi.fn().mockReturnValue(false),
    rm: vi.fn(),
    rename: vi.fn(),
    sleep: vi.fn().mockResolvedValue(undefined),
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

/** run 호출들의 git 하위 명령(clone·remote·fetch…) — 앞의 공통 인자(-c …)는 건너뛴다. */
const gitSubcommands = (run: FakeRefOps['run']): string[] =>
  run.mock.calls.map((c) => (c[1] as string[])[NO_PROMPT_GIT_PREFIX.length]!);

describe('ensureRefRepos', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
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
          ...NO_PROMPT_GIT_PREFIX,
          'clone',
          '--depth',
          '1',
          '--branch',
          'release/6.5',
          'https://oauth2:ghp_SECRET@github.com/acme/legacy.git',
          '/r/_refs/5/12.part',
        ],
        refOpts(),
      ],
      [
        'git',
        [...NO_PROMPT_GIT_PREFIX, 'remote', 'set-url', 'origin', 'https://github.com/acme/legacy.git'],
        refOpts('/r/_refs/5/12.part'),
      ],
    ]);
    expect(ops.rename).toHaveBeenCalledTimes(1);
    expect(ops.rename).toHaveBeenCalledWith('/r/_refs/5/12.part', '/r/_refs/5/12');
    // 순서: 예전 폴더·찌꺼기 정리 → clone → origin 정리 → 제자리로 → HEAD 커밋
    expect(ops.rm.mock.calls).toEqual([['/r/_refs/5/12'], ['/r/_refs/5/12.part']]);
    expect(callOrder(ops.rm, 1)).toBeLessThan(callOrder(ops.run, 0));
    expect(callOrder(ops.run, 1)).toBeLessThan(callOrder(ops.rename, 0));
    expect(callOrder(ops.rename, 0)).toBeLessThan(callOrder(ops.output, 0));
    expect(ops.output).toHaveBeenCalledWith('git', [...NO_PROMPT_GIT_PREFIX, 'rev-parse', 'HEAD'], refOpts('/r/_refs/5/12'));
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
      [
        ...NO_PROMPT_GIT_PREFIX,
        'clone',
        '--depth',
        '1',
        '--branch',
        'develop',
        'https://oauth2:glpat-B@gitlab.hamon.vip/g/sub/p.git',
        '/r/_refs/5/12.part',
      ],
      refOpts(),
    );
    expect(ops.run).toHaveBeenNthCalledWith(
      2,
      'git',
      [...NO_PROMPT_GIT_PREFIX, 'remote', 'set-url', 'origin', 'https://gitlab.hamon.vip/g/sub/p.git'],
      refOpts('/r/_refs/5/12.part'),
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
      ['git', [...NO_PROMPT_GIT_PREFIX, 'rev-parse', '--symbolic-full-name', 'HEAD'], refOpts('/r/_refs/5/12')],
      ['git', [...NO_PROMPT_GIT_PREFIX, 'rev-parse', 'HEAD'], refOpts('/r/_refs/5/12')],
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
      [...NO_PROMPT_GIT_PREFIX, 'clone', '--depth', '1', '--branch', 'release/6.5', 'https://github.com/acme/legacy.git', '/r/_refs/5/12.part'],
      refOpts(),
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
    // `code`가 없는 오류는 일시적 잠금으로 보지 않는다 — 다시 시도하지 않고 바로 실패한다(재시도는 아래 전용 describe)
    ops.rename.mockImplementation(() => {
      throw new Error("EPERM: operation not permitted, rename '/r/_refs/5/12.part' -> '/r/_refs/5/12'");
    });

    const result = await ensureRefRepos([refClaim()], {}, ops);

    expect(result[0]).toMatchObject({ sha: null, error: expect.stringContaining('EPERM') });
    expect(ops.rename).toHaveBeenCalledTimes(1);
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

  // ── 자격증명 도우미·프롬프트 끄기 ──────────────────────────────────────────────
  // 이 PC처럼 시스템 git 설정에 `credential.helper=manager`(GCM)가 있고 로그온 세션에서 돌면, 인증이 필요한 원격(토큰 없는 호스트·
  // 비공개/없는 repo는 401)에서 git이 GCM을 불러 데스크톱 대화상자를 띄운 채 턴 상한(30분)까지 멈춘다 → 세션 FAILED(스펙 §1.2 위반).
  // 운영자의 개인 자격증명이 몰래 쓰이거나 GitLab URL 토큰이 Windows 자격 증명 관리자에 저장되는 부작용도 있다.
  describe('no credential helper and no prompt', () => {
    it('puts the helper-off prefix before the subcommand and the prompt-off env on every git call of a fresh clone', async () => {
      const ops = fakeRefOps();

      await ensureRefRepos([refClaim()], { tokens: { githubPat: 'ghp_SECRET' } }, ops);

      // 새로 받는 경로: clone · set-url(run)과 rev-parse HEAD(output) — 세 번 모두
      const calls = [...ops.run.mock.calls, ...ops.output.mock.calls];
      expect(calls).toHaveLength(3);
      for (const call of calls) {
        expect(call[0]).toBe('git');
        expect((call[1] as string[]).slice(0, NO_PROMPT_GIT_PREFIX.length)).toEqual(NO_PROMPT_GIT_PREFIX);
        expectPromptsOff(optsOf(call).env);
      }
      expect(gitSubcommands(ops.run)).toEqual(['clone', 'remote']);
      expect((ops.output.mock.calls[0]![1] as string[]).slice(NO_PROMPT_GIT_PREFIX.length)).toEqual(['rev-parse', 'HEAD']);
    });

    it('does the same on the reuse path (both rev-parse calls)', async () => {
      const ops = withCheckoutOn(fakeRefOps(), 'release/6.5');

      await ensureRefRepos([refClaim()], {}, ops);

      expect(ops.run).not.toHaveBeenCalled();
      expect(ops.output).toHaveBeenCalledTimes(2);
      for (const call of ops.output.mock.calls) {
        expect(call[0]).toBe('git');
        expect((call[1] as string[]).slice(0, NO_PROMPT_GIT_PREFIX.length)).toEqual(NO_PROMPT_GIT_PREFIX);
        expectPromptsOff(optsOf(call).env);
      }
      expect(ops.output.mock.calls.map((c) => (c[1] as string[]).slice(NO_PROMPT_GIT_PREFIX.length))).toEqual([
        ['rev-parse', '--symbolic-full-name', 'HEAD'],
        ['rev-parse', 'HEAD'],
      ]);
    });

    it('does the same for the git calls of a re-clone over a checkout on another branch', async () => {
      const ops = withCheckoutOn(fakeRefOps(), 'main');

      await ensureRefRepos([refClaim()], {}, ops);

      // rev-parse 두 번(브랜치 확인 · HEAD) + clone · set-url
      const calls = [...ops.run.mock.calls, ...ops.output.mock.calls];
      expect(calls).toHaveLength(4);
      for (const call of calls) {
        expect((call[1] as string[]).slice(0, NO_PROMPT_GIT_PREFIX.length)).toEqual(NO_PROMPT_GIT_PREFIX);
        expectPromptsOff(optsOf(call).env);
      }
    });

    it('builds the env from a copy of process.env without the askpass programs and keeps the rest', async () => {
      vi.stubEnv('GIT_ASKPASS', '/operator/askpass');
      vi.stubEnv('SSH_ASKPASS', '/operator/ssh-askpass');
      vi.stubEnv('GIT_TERMINAL_PROMPT', '1');
      vi.stubEnv('GCM_INTERACTIVE', 'always');
      vi.stubEnv('REFTEST_KEEP', 'kept');
      const ops = fakeRefOps();

      await ensureRefRepos([refClaim()], {}, ops);

      for (const call of [...ops.run.mock.calls, ...ops.output.mock.calls]) {
        const env = optsOf(call).env!;
        expectPromptsOff(env);
        expect(env.REFTEST_KEEP).toBe('kept'); // 나머지 변수(PATH 등)는 그대로 물려준다
        expect(env).not.toBe(process.env);
      }
      // 부모 환경은 건드리지 않는다 — 자식에게 주는 것은 사본이다
      expect(process.env.GIT_ASKPASS).toBe('/operator/askpass');
      expect(process.env.SSH_ASKPASS).toBe('/operator/ssh-askpass');
      expect(process.env.GIT_TERMINAL_PROMPT).toBe('1');
      expect(process.env.GCM_INTERACTIVE).toBe('always');
    });

    it('drops the askpass variables whatever their letter case (Windows env names are case-insensitive)', () => {
      const env = noPromptGitEnv({
        PATH: '/usr/bin',
        git_askpass: '/op/lower',
        Ssh_Askpass: '/op/mixed',
        Git_Terminal_Prompt: '1',
        GCM_interactive: 'always',
        KEEP: 'yes',
      });

      expect(env).toEqual({ PATH: '/usr/bin', KEEP: 'yes', GIT_TERMINAL_PROMPT: '0', GCM_INTERACTIVE: 'never' });
    });

    it('defaults to the real process.env as the base', () => {
      vi.stubEnv('REFTEST_KEEP', 'kept');

      expect(noPromptGitEnv().REFTEST_KEEP).toBe('kept');
    });
  });

  // ── 참조 폴더 검증 ────────────────────────────────────────────────────────────
  // 러너는 API가 보낸 dir을 통째로 지운다(rm dir · rm dir.part). 그래서 어떤 디스크 작업 전에도 dir이 절대경로이고
  // `_refs/<세션 id>/<카탈로그 id>`로 끝나는지(끝 마디 = 이 참조의 catalogId) 먼저 본다 — 아니면 아무것도 건드리지 않고 사유만 돌려준다.
  describe('ref folder validation', () => {
    // 거부된 참조는 API가 보낸 경로를 로그로 남긴다 — 칩에는 고정 문구만 보이기 때문. 테스트 출력이 지저분해지지 않게 막아 둔다.
    const silenceWarn = () => vi.spyOn(console, 'warn').mockImplementation(() => {});
    let warn: ReturnType<typeof silenceWarn>;
    beforeEach(() => {
      warn = silenceWarn();
    });

    it.each([
      ['a relative path', 'r/_refs/5/12'],
      ['a dot-relative path', './_refs/5/12'],
      ['a bare _refs/<session>/<catalog>', '_refs/5/12'],
      ['an empty path', ''],
      ['the filesystem root', '/'],
      ['a folder that is not under _refs', '/r/refs/5/12'],
      ['a parent folder with a similar name', '/r/_ref/5/12'],
      ['a path without a session segment', '/r/_refs/12'],
      ['a session segment that is not numeric', '/r/_refs/abc/12'],
      ['a last segment that is another catalog id', '/r/_refs/5/13'],
      ['a last segment that only starts with the catalog id', '/r/_refs/5/120'],
      ['the .part sibling', '/r/_refs/5/12.part'],
      ['a trailing separator', '/r/_refs/5/12/'],
      ['a parent traversal at the end', '/r/_refs/5/12/..'],
      ['a parent traversal in the session position', '/r/_refs/../12'],
    ])('rejects %s without touching the disk or running git', async (_label, dir) => {
      const ops = fakeRefOps();
      const ref = refClaim({ dir });

      const result = await ensureRefRepos([ref], { tokens: { githubPat: 'ghp_SECRET' } }, ops);

      expect(result).toEqual([{ ref, sha: null, error: INVALID_DIR_REASON }]);
      // exists·output·run·rm·rename·mkdir·sleep 어느 것도 부르지 않는다
      for (const fn of Object.values(ops)) expect(fn).not.toHaveBeenCalled();
      // 운영자가 원인을 볼 수 있게 어느 참조의 어떤 경로였는지 한 줄 남긴다(토큰은 싣지 않는다)
      expect(warn).toHaveBeenCalledTimes(1);
      const message = String(warn.mock.calls[0]![0]);
      expect(message).toContain('catalog=12');
      expect(message).toContain(JSON.stringify(dir));
      expect(message).not.toContain('ghp_SECRET');
    });

    it.each([
      ['an unrelated absolute folder', '/data/projects'],
      ['a folder under _refs without the catalog level', '/r/_refs/5'],
    ])('does not delete %s even though it is absolute', async (_label, dir) => {
      const ops = fakeRefOps();
      ops.exists.mockReturnValue(true); // 있는 폴더여도 — 지우는 쪽으로 가지 않는다

      const result = await ensureRefRepos([refClaim({ dir })], {}, ops);

      expect(result[0]).toMatchObject({ sha: null, error: INVALID_DIR_REASON });
      expect(ops.rm).not.toHaveBeenCalled();
    });

    it('accepts a deep absolute path under a work root and a long session id', async () => {
      const ops = fakeRefOps();
      const ref = refClaim({ catalogId: 7, dir: '/home/u/netis-maker/interviews/_refs/123456/7' });

      const result = await ensureRefRepos([ref], {}, ops);

      expect(result).toEqual([{ ref, sha: SHA_A, error: null }]);
    });

    // API는 Windows에서 `${user.home}/netis-maker/interviews/_refs/{세션}/{카탈로그}`를 만든다 — 구분자가 섞여 있다
    it.runIf(process.platform === 'win32')('accepts the mixed-separator Windows path the API builds', async () => {
      const ops = fakeRefOps();
      const ref = refClaim({ dir: 'C:\\Users\\mic/netis-maker/interviews/_refs/5/12' });

      const result = await ensureRefRepos([ref], {}, ops);

      expect(result).toEqual([{ ref, sha: SHA_A, error: null }]);
      expect(ops.rename).toHaveBeenCalledWith('C:\\Users\\mic/netis-maker/interviews/_refs/5/12.part', ref.dir);
    });

    it.runIf(process.platform === 'win32')('accepts a backslash-only Windows path', async () => {
      const ops = fakeRefOps();
      const ref = refClaim({ dir: 'C:\\r\\_refs\\5\\12' });

      expect(await ensureRefRepos([ref], {}, ops)).toEqual([{ ref, sha: SHA_A, error: null }]);
    });

    it.runIf(process.platform === 'win32')('rejects a drive-relative Windows path', async () => {
      const ops = fakeRefOps();

      const result = await ensureRefRepos([refClaim({ dir: 'C:_refs\\5\\12' })], {}, ops);

      expect(result[0]).toMatchObject({ sha: null, error: INVALID_DIR_REASON });
      for (const fn of Object.values(ops)) expect(fn).not.toHaveBeenCalled();
    });

    it('still reports the rejected ref and goes on with the others', async () => {
      const ops = fakeRefOps();
      const bad = refClaim({ dir: '/r/_refs/5/99' }); // catalogId 12인데 끝 마디가 99
      const good = refClaim({ catalogId: 13, alias: '공통 UI', githubRepo: 'acme/ui', branch: 'main', dir: '/r/_refs/5/13' });

      const result = await ensureRefRepos([bad, good], {}, ops);

      expect(result).toEqual([
        { ref: bad, sha: null, error: INVALID_DIR_REASON },
        { ref: good, sha: SHA_A, error: null },
      ]);
      // 잘못된 참조의 폴더는 건드리지 않았고, 좋은 참조만 받았다
      expect(ops.rm.mock.calls).toEqual([['/r/_refs/5/13'], ['/r/_refs/5/13.part']]);
      expect(ops.rename.mock.calls).toEqual([['/r/_refs/5/13.part', '/r/_refs/5/13']]);
      expect(warn).toHaveBeenCalledTimes(1); // 거부된 참조 하나만 로그에 남는다
    });
  });

  // ── 마지막 rename 재시도 ───────────────────────────────────────────────────────
  // 방금 받은 폴더를 백신·인덱서가 잠깐 잡고 있으면 Windows에서 디렉터리 rename이 EPERM/EBUSY/EACCES로 실패한다.
  // 그대로 ⚠로 끝나면 다음 턴에 다시 받고, 이 PC에서 계속 그렇다면 그 참조는 영영 못 쓴다 — 일시적 코드만 짧게 재시도한다.
  describe('final rename retry', () => {
    const lockError = (code: string): Error =>
      Object.assign(new Error(`${code}: operation not permitted, rename '/r/_refs/5/12.part' -> '/r/_refs/5/12'`), { code });
    const waitsOf = (ops: FakeRefOps): number[] => ops.sleep.mock.calls.map((c) => c[0] as number);

    it.each(['EPERM', 'EBUSY', 'EACCES'])('retries a transient %s and succeeds once the lock clears', async (code) => {
      const ops = fakeRefOps();
      ops.rename
        .mockImplementationOnce(() => {
          throw lockError(code); // renameSync처럼 동기로 던진다
        })
        .mockRejectedValueOnce(lockError(code)) // 비동기 op처럼 거부한다
        .mockResolvedValue(undefined);
      const ref = refClaim();

      const result = await ensureRefRepos([ref], {}, ops);

      expect(result).toEqual([{ ref, sha: SHA_A, error: null }]);
      expect(ops.rename).toHaveBeenCalledTimes(3);
      expect(ops.rename.mock.calls).toEqual([
        ['/r/_refs/5/12.part', '/r/_refs/5/12'],
        ['/r/_refs/5/12.part', '/r/_refs/5/12'],
        ['/r/_refs/5/12.part', '/r/_refs/5/12'],
      ]);
      expect(ops.sleep).toHaveBeenCalledTimes(2);
      const [first, second] = waitsOf(ops);
      expect(first).toBeGreaterThan(0);
      expect(second).toBeGreaterThan(first!); // 기다림은 점점 길어진다
      // 성공했으니 .part를 따로 지우지 않는다 — 준비 단계의 두 번뿐이고, HEAD는 rename이 끝난 뒤에 읽는다
      expect(ops.rm.mock.calls).toEqual([['/r/_refs/5/12'], ['/r/_refs/5/12.part']]);
      expect(callOrder(ops.rename, 2)).toBeLessThan(callOrder(ops.output, 0));
      expect(callOrder(ops.sleep, 1)).toBeLessThan(callOrder(ops.rename, 2));
    });

    it('really waits (timer based) between attempts when no sleep op is injected', async () => {
      // 운영 기본값 — sleep을 주입하지 않으면 타이머로 기다린다. 가짜 타이머로 "바로 다시 시도하지 않고 기다렸다가 시도"를 본다.
      vi.useFakeTimers();
      try {
        const ops = fakeRefOps();
        delete (ops as { sleep?: unknown }).sleep;
        ops.rename
          .mockImplementationOnce(() => {
            throw lockError('EBUSY');
          })
          .mockResolvedValue(undefined);

        const pending = ensureRefRepos([refClaim()], {}, ops);
        await vi.advanceTimersByTimeAsync(50);
        expect(ops.rename).toHaveBeenCalledTimes(1); // 첫 시도가 막혔고, 아직 기다리는 중이다
        await vi.advanceTimersByTimeAsync(3000);
        const result = await pending;

        expect(ops.rename).toHaveBeenCalledTimes(2);
        expect(result).toMatchObject([{ sha: SHA_A, error: null }]);
      } finally {
        vi.useRealTimers();
      }
    });

    it('does not sleep or retry when the first rename works', async () => {
      const ops = fakeRefOps();

      await ensureRefRepos([refClaim()], {}, ops);

      expect(ops.rename).toHaveBeenCalledTimes(1);
      expect(ops.sleep).not.toHaveBeenCalled();
    });

    it.each([
      ['ENOENT', Object.assign(new Error('ENOENT: no such file or directory'), { code: 'ENOENT' })],
      ['EEXIST', Object.assign(new Error('EEXIST: file already exists'), { code: 'EEXIST' })],
      ['ENOSPC', Object.assign(new Error('ENOSPC: no space left on device'), { code: 'ENOSPC' })],
      ['a numeric code', Object.assign(new Error('weird'), { code: 5 })],
      ['no code at all', new Error('rename failed')],
    ])('does not retry a non-transient failure (%s)', async (_label, error) => {
      const ops = fakeRefOps();
      ops.rename.mockImplementation(() => {
        throw error;
      });

      const result = await ensureRefRepos([refClaim()], {}, ops);

      expect(result[0]).toMatchObject({ sha: null, error: expect.stringContaining(error.message) });
      expect(ops.rename).toHaveBeenCalledTimes(1);
      expect(ops.sleep).not.toHaveBeenCalled();
      expect(ops.rm).toHaveBeenLastCalledWith('/r/_refs/5/12.part');
    });

    it('gives up after 5 attempts within about 3 seconds and fails through the normal path when the lock never clears', async () => {
      const ops = fakeRefOps();
      ops.rename.mockImplementation(() => {
        throw lockError('EPERM');
      });

      const result = await ensureRefRepos([refClaim()], {}, ops);

      expect(ops.rename).toHaveBeenCalledTimes(5);
      expect(ops.sleep).toHaveBeenCalledTimes(4); // 시도 사이사이에만 기다린다 — 마지막 실패 뒤에는 기다리지 않는다
      const waits = waitsOf(ops);
      expect(waits.reduce((sum, ms) => sum + ms, 0)).toBeLessThanOrEqual(3000);
      expect(waits).toEqual([...waits].sort((a, b) => a - b)); // 짧게 시작해 길어진다
      // 기존 실패 경로 그대로: 사유는 마지막 rename 오류, .part는 지워진다, HEAD는 읽지 않는다
      expect(result[0]).toMatchObject({ sha: null, error: expect.stringContaining('EPERM') });
      expect(ops.rm).toHaveBeenLastCalledWith('/r/_refs/5/12.part');
      expect(callOrder(ops.rm, 2)).toBeGreaterThan(callOrder(ops.rename, 4));
      expect(ops.output).not.toHaveBeenCalled();
    });

    it('moves on to the next ref after one ref used up its attempts', async () => {
      const ops = fakeRefOps();
      ops.rename.mockImplementation((from: string) => {
        if (from === '/r/_refs/5/12.part') throw lockError('EBUSY');
      });
      const locked = refClaim();
      const free = refClaim({ catalogId: 13, alias: '공통 UI', githubRepo: 'acme/ui', branch: 'main', dir: '/r/_refs/5/13' });

      const result = await ensureRefRepos([locked, free], {}, ops);

      expect(result).toEqual([
        { ref: locked, sha: null, error: expect.stringContaining('EBUSY') },
        { ref: free, sha: SHA_A, error: null },
      ]);
      expect(ops.rename.mock.calls.filter((c) => c[0] === '/r/_refs/5/12.part')).toHaveLength(5);
      expect(ops.rename.mock.calls.filter((c) => c[0] === '/r/_refs/5/13.part')).toHaveLength(1);
    });

    it('stops retrying once the turn is aborted', async () => {
      const controller = new AbortController();
      const ops = fakeRefOps();
      ops.rename.mockImplementation(() => {
        controller.abort(); // 턴 타임아웃이 rename 시도 도중에 왔다
        throw lockError('EPERM');
      });

      const result = await ensureRefRepos([refClaim()], { signal: controller.signal }, ops);

      expect(ops.rename).toHaveBeenCalledTimes(1);
      expect(ops.sleep).not.toHaveBeenCalled();
      expect(result[0]).toMatchObject({ sha: null, error: expect.stringContaining('EPERM') });
      expect(ops.rm).toHaveBeenLastCalledWith('/r/_refs/5/12.part');
    });
  });

  it('prepares refs one after another in the given order', async () => {
    const ops = fakeRefOps();
    let finishFirstClone!: () => void;
    const firstClone = new Promise<void>((resolve) => {
      finishFirstClone = resolve;
    });
    ops.run.mockImplementation((_cmd: string, args: string[]) =>
      args.includes('clone') && args.includes('/r/_refs/5/12.part') ? firstClone : Promise.resolve(undefined),
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
      if (args.includes('clone') && args.includes('release/6.5')) {
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

// ─────────────────────────────────────────────────────────────────────────────
// 기본 ops(execFile·rmSync·renameSync) — 가짜 ops로는 검증되지 않으므로 실제 git으로 확인한다
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 쪽 git — 작성자와 줄바꿈을 고정한다(이 PC의 `core.autocrlf=true`가 커밋 내용을 바꾸지 않게). */
const git = (cwd: string, ...args: string[]): string =>
  execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', '-c', 'core.autocrlf=false', ...args], {
    cwd,
    encoding: 'utf8',
  }).trim();

/** 저장소에 파일 하나(내용 `<이름>\n`)를 커밋하고 그 커밋을 돌려준다. */
function commitFile(repo: string, file: string): string {
  writeFileSync(join(repo, file), `${file}\n`);
  git(repo, 'add', '-A');
  git(repo, 'commit', '-q', '-m', file);
  return git(repo, 'rev-parse', 'HEAD');
}

// 로컬 저장소를 file:// URL로 받는다(planFiles.test.ts와 같은 방식).
describe('ensureRepo with the default ops (real git, local file:// origin)', () => {
  let work: string;
  let origin: string;

  beforeEach(() => {
    work = mkdtempSync(join(tmpdir(), 'ensurerepo-'));
    origin = join(work, 'origin');
    mkdirSync(origin);
    git(origin, 'init', '-q', '-b', 'main');
  });
  afterEach(() => {
    rmSync(work, { recursive: true, force: true });
  });

  it('clones a fresh checkout, then fetches and hard-resets it to the new tip on the next turn, keeping origin plain', async () => {
    // 모든 호출 앞에 붙는 도우미 끄기 인자(-c …)가 clone·remote·fetch·reset 어느 하위 명령도 깨지 않는지 본다
    const c1 = commitFile(origin, 'a.txt');
    const input: RepoInput = {
      githubRepo: 'acme/widgets',
      githubBranch: 'main',
      workDir: join(work, 'session-1'),
      gitUrl: pathToFileURL(origin).href,
      repoHost: 'github',
    };

    await ensureRepo(input);
    expect(git(input.workDir, 'rev-parse', 'HEAD')).toBe(c1);
    expect(git(input.workDir, 'remote', 'get-url', 'origin')).toBe(input.gitUrl);

    // 다음 턴: 원본이 앞서 가 있고, 체크아웃에 남은 수정은 버려진다
    const c2 = commitFile(origin, 'b.txt');
    writeFileSync(join(input.workDir, 'a.txt'), 'local edit\n');
    await ensureRepo(input);
    expect(git(input.workDir, 'rev-parse', 'HEAD')).toBe(c2);
    expect(readFileSync(join(input.workDir, 'a.txt'), 'utf8')).toMatch(/^a\.txt\r?\n$/); // 이 PC는 CRLF로 꺼낸다
    expect(existsSync(join(input.workDir, 'b.txt'))).toBe(true);
  }, 30_000);
});

describe('ensureRefRepos with the default ops (real git, local file:// origin)', () => {
  let work: string;
  let origin: string;

  const commitToOrigin = (file: string): string => commitFile(origin, file);

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

/**
 * 운영자 PC의 자격증명 도우미·askpass 자리에 가짜를 심고, 불린 기록이 쌓일 로그 파일 경로를 돌려준다. 가짜는 빈 답을 주고 바로 끝난다.
 * 이 PC의 시스템 도우미(GCM)는 환경 설정(`GIT_CONFIG_COUNT`)의 빈 값으로 먼저 비운다 — 차단이 빠진 코드로 돌려도 데스크톱 대화상자 대신
 * 가짜가 불려 테스트가 바로 실패한다. git은 환경 설정을 `git -c`보다 먼저 읽으므로 `-c credential.helper=`는 이 가짜 도우미도 비운다.
 * 터미널 프롬프트도 켜 둔다 — 부르는 쪽이 `GIT_TERMINAL_PROMPT=0`으로 덮어써야 한다.
 */
function plantOperatorPrompts(dir: string): string {
  const log = join(dir, 'prompt-calls.log');
  const askpass = join(dir, 'askpass.sh');
  writeFileSync(askpass, '#!/bin/sh\necho "askpass $1" >> "$PROMPT_CALLS_LOG"\necho\n', { mode: 0o755 });
  vi.stubEnv('PROMPT_CALLS_LOG', log.replace(/\\/g, '/'));
  vi.stubEnv('GIT_CONFIG_COUNT', '2');
  vi.stubEnv('GIT_CONFIG_KEY_0', 'credential.helper');
  vi.stubEnv('GIT_CONFIG_VALUE_0', '');
  vi.stubEnv('GIT_CONFIG_KEY_1', 'credential.helper');
  vi.stubEnv('GIT_CONFIG_VALUE_1', '!f() { echo "helper $1" >> "$PROMPT_CALLS_LOG"; }; f');
  vi.stubEnv('GIT_ASKPASS', askpass);
  vi.stubEnv('SSH_ASKPASS', askpass);
  vi.stubEnv('GIT_TERMINAL_PROMPT', '1');
  return log;
}

// 인증이 필요한 원격(401)에서 도우미·프롬프트를 기다리지 않고 바로 실패하는지 실제 git으로 본다 — 대상(`ensureRepo`)과 참조(`ensureRefRepos`)는
// 같은 git 호출을 쓴다. 가짜 ops로는 인자가 붙었다는 것만 확인되고, 이 PC의 시스템 git 설정(`credential.helper=manager`)이 정말 꺼지는지는
// 알 수 없다 — 옵션이 빠지면 GCM이 데스크톱 대화상자를 띄운 채 턴 상한까지 멈춘다. 그래서 운영자 PC의 도우미·askpass 자리에 기록만 남기는
// 가짜를 심어(`plantOperatorPrompts`) 정말 아무것도 불리지 않는지까지 본다. 로컬 HTTP 서버(127.0.0.1)만 쓰고 실제 원격으로는 나가지 않는다.
describe('ensureRepo and ensureRefRepos with the default ops against a remote that answers 401 (real git, local HTTP server)', () => {
  let work: string;
  let server: Server;
  let requests: { method?: string; url?: string; authorization?: string }[];
  let promptLog: string;

  /** 가짜 도우미·askpass가 불린 기록 — 한 번에 한 줄(`helper get`, `askpass Username for …`). */
  const promptCalls = (): string[] =>
    existsSync(promptLog) ? readFileSync(promptLog, 'utf8').split('\n').filter((line) => line.trim() !== '') : [];

  /** 대상 체크아웃 입력 — 토큰을 줄 때는 GitLab 자리(`gitlabToken`)로 준다. */
  const targetAt = (port: number, workDir: string): RepoInput => ({
    githubRepo: 'grp/proj',
    githubBranch: 'main',
    workDir,
    gitUrl: `http://127.0.0.1:${port}/grp/proj.git`,
    repoHost: 'gitlab',
    // 멈추면(차단이 빠져 프롬프트를 기다리는 경우) git을 20초 뒤에 끊어 자식 프로세스를 남기지 않는다
    signal: AbortSignal.timeout(20_000),
  });
  /** 대상 실패를 값으로 받는다 — 성공하면 undefined. */
  const failureOf = (input: RepoInput): Promise<unknown> => ensureRepo(input).then(() => undefined, (e: unknown) => e);

  const refAt = (port: number): RefRepoClaim => ({
    catalogId: 12,
    alias: 'private',
    githubRepo: 'grp/proj',
    gitUrl: `http://127.0.0.1:${port}/grp/proj.git`,
    repoHost: 'gitlab',
    branch: 'main',
    dir: join(work, 'root', '_refs', '5', '12'),
    sha: null,
  });

  beforeEach(async () => {
    work = mkdtempSync(join(tmpdir(), 'refrepos-401-'));
    requests = [];
    server = createServer((req, res) => {
      requests.push({ method: req.method, url: req.url, authorization: req.headers.authorization });
      res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="refs"' });
      res.end();
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    // 환경에 프록시가 있어도 로컬 서버로는 직접 간다
    vi.stubEnv('no_proxy', '127.0.0.1');
    vi.stubEnv('LC_ALL', 'C'); // git 메시지를 영어로 고정한다
    promptLog = plantOperatorPrompts(work);
  });
  afterEach(async () => {
    vi.unstubAllEnvs();
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(work, { recursive: true, force: true });
  });

  it('ensureRefRepos fails fast with git\'s "terminal prompts disabled" instead of waiting for a credential prompt, and leaves no folder', async () => {
    const ref = refAt((server.address() as AddressInfo).port);

    const started = Date.now();
    // 멈추면(옵션이 빠져 GCM 대화상자가 뜨는 경우) git을 20초 뒤에 끊어 자식 프로세스를 남기지 않는다
    const [result] = await ensureRefRepos([ref], { signal: AbortSignal.timeout(20_000) });

    expect(result).toMatchObject({ sha: null, error: expect.stringContaining('terminal prompts disabled') });
    expect(Date.now() - started).toBeLessThan(15_000);
    // git은 서버까지 갔고(연결 오류가 아니다), 인증 정보 없이 평문 URL로 물어봤다 — 개인 자격증명이 끼어들지 않았다
    expect(requests.length).toBeGreaterThan(0);
    expect(requests.every((r) => r.authorization === undefined)).toBe(true);
    expect(promptCalls()).toEqual([]);
    expect(existsSync(ref.dir)).toBe(false);
    expect(existsSync(`${ref.dir}.part`)).toBe(false);
  }, 30_000);

  it('ensureRefRepos fails fast when the supplied token is rejected too, and the reported reason never carries the token', async () => {
    const ref = refAt((server.address() as AddressInfo).port);

    const [result] = await ensureRefRepos([ref], { tokens: { gitlabToken: 'glpat-SECRET' }, signal: AbortSignal.timeout(20_000) });

    expect(result).toMatchObject({ sha: null, error: expect.any(String) });
    expect(result!.error).not.toContain('glpat-SECRET');
    expect(result!.error).not.toMatch(/abort/i); // 멈췄다가 끊긴 것이 아니라 git이 스스로 실패했다
    // 토큰은 clone 전송에만 쓰였다 — 서버는 Basic 인증을 받았다
    expect(requests.some((r) => r.authorization?.startsWith('Basic '))).toBe(true);
    expect(promptCalls()).toEqual([]); // 거부된 자격증명을 지우라고(erase) 도우미를 부르지도 않는다
    expect(existsSync(ref.dir)).toBe(false);
    expect(existsSync(`${ref.dir}.part`)).toBe(false);
  }, 30_000);

  it('ensureRepo fails a fresh clone fast with git\'s "terminal prompts disabled": no helper, no askpass, no credentials sent', async () => {
    const input = targetAt((server.address() as AddressInfo).port, join(work, 'session-1'));

    const started = Date.now();
    const error = await failureOf(input);

    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toContain('terminal prompts disabled');
    expect(Date.now() - started).toBeLessThan(15_000);
    // git은 서버까지 갔고(연결 오류가 아니다), 인증 정보 없이 평문 URL로 물어봤다 — 운영자의 자격증명이 끼어들지 않았다
    expect(requests.length).toBeGreaterThan(0);
    expect(requests.every((r) => r.authorization === undefined)).toBe(true);
    expect(promptCalls()).toEqual([]);
  }, 30_000);

  it('ensureRepo fails a fetch into an existing checkout just as fast', async () => {
    const input = targetAt((server.address() as AddressInfo).port, join(work, 'session-2'));
    mkdirSync(input.workDir);
    git(input.workDir, 'init', '-q');
    git(input.workDir, 'remote', 'add', 'origin', 'https://example.invalid/old.git'); // 지난 턴의 체크아웃 — origin이 있다

    const started = Date.now();
    const error = await failureOf(input);

    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toContain('terminal prompts disabled');
    expect(Date.now() - started).toBeLessThan(15_000);
    expect(git(input.workDir, 'remote', 'get-url', 'origin')).toBe(input.gitUrl); // fetch 앞의 set-url은 했다
    expect(requests.length).toBeGreaterThan(0);
    expect(promptCalls()).toEqual([]);
  }, 30_000);

  it('ensureRepo fails fast when the supplied token is rejected too, and the error never carries the token', async () => {
    const input: RepoInput = {
      ...targetAt((server.address() as AddressInfo).port, join(work, 'session-3')),
      tokens: { gitlabToken: 'glpat-SECRET' },
    };

    const error = (await failureOf(input)) as Error & { stderr?: string; cmd?: string };

    expect(error).toBeInstanceOf(Error);
    expect(error.message).not.toMatch(/abort/i); // 멈췄다가 끊긴 것이 아니라 git이 스스로 실패했다
    // 세션 실패 사유·로그로 나가는 문구 어디에도 토큰이 없다(실제 execFile 오류의 cmd·stderr 포함)
    expect(`${error.message}\n${error.stderr ?? ''}\n${error.cmd ?? ''}`).not.toContain('glpat-SECRET');
    // 토큰은 clone 전송에만 쓰였다 — 서버는 Basic 인증을 받았다
    expect(requests.some((r) => r.authorization?.startsWith('Basic '))).toBe(true);
    expect(promptCalls()).toEqual([]); // 거부된 자격증명을 지우라고(erase) 도우미를 부르지도 않는다
  }, 30_000);
});
