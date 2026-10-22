import { execFile } from 'node:child_process';
import { existsSync, mkdirSync, renameSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { buildCloneUrl, maskSecrets, type GitTokens } from '../sdk/gitRemote.js';
import type { RefRepoClaim } from '../types.js';

const execFileAsync = promisify(execFile);

export interface RepoInput {
  githubRepo: string; // GitHub "owner/repo" 또는 GitLab 프로젝트 전체 경로
  githubBranch: string;
  workDir: string; // = claim.workDir, also options.cwd
  /** 정식 Git URL + 호스트(claim에서 전달). 없으면 GitHub로 간주. */
  gitUrl?: string | null;
  repoHost?: string | null;
  /** clone/fetch 인증 토큰. 없으면 익명(공개 레포만). */
  tokens?: GitTokens;
  /** 턴 wall-clock 타임아웃 전파용 — abort 시 git 자식 프로세스를 종료해 좀비를 남기지 않는다. */
  signal?: AbortSignal;
}

export interface RepoOps {
  run: (cmd: string, args: string[], opts: { cwd?: string; signal?: AbortSignal }) => Promise<unknown>;
  exists: (path: string) => boolean;
  /** Create the checkout dir before a fresh clone. Optional so unit tests can omit fs side effects. */
  mkdir?: (path: string) => void;
}

const defaultOps: RepoOps = {
  run: (cmd, args, opts) => execFileAsync(cmd, args, opts),
  exists: existsSync,
  mkdir: (path) => {
    mkdirSync(path, { recursive: true });
  },
};

/**
 * Ensures the repo is checked out at workDir BEFORE the first turn AND before every resume
 * (resume is cwd-pinned — spike 02). Fresh => shallow clone; existing => fetch + hard reset.
 * URL은 claim의 gitUrl + 호스트별 토큰으로 만든다(비공개 GitHub/사내 GitLab).
 * 자격증명은 전송에만 쓰고 `.git/config`(origin)에는 절대 남기지 않는다 — QUESTION 세션은
 * workDir 안의 파일을 읽을 수 있기 때문. 그래서 clone/fetch는 인증 URL로 하되, origin은
 * 항상 토큰 없는 평문 URL로 맞춘다(기존 체크아웃은 먼저 origin부터 정리해 취약 버전이
 * 남긴 인증 URL도 함께 지운다).
 * git 오류 메시지는 자격증명을 가린 뒤 다시 던진다 — 세션 실패 사유로 화면에 노출되기 때문.
 */
export async function ensureRepo(input: RepoInput, ops: RepoOps = defaultOps): Promise<void> {
  const { githubBranch, workDir } = input;
  const abort = input.signal ? { signal: input.signal } : {};
  const plainUrl = buildCloneUrl(input, {});
  const authUrl = buildCloneUrl(input, input.tokens ?? {});
  try {
    if (ops.exists(join(workDir, '.git'))) {
      await ops.run('git', ['remote', 'set-url', 'origin', plainUrl], { cwd: workDir, ...abort });
      await ops.run(
        'git',
        ['fetch', '--depth', '1', authUrl, `+refs/heads/${githubBranch}:refs/remotes/origin/${githubBranch}`],
        { cwd: workDir, ...abort },
      );
      await ops.run('git', ['reset', '--hard', `origin/${githubBranch}`], { cwd: workDir, ...abort });
      return;
    }
    ops.mkdir?.(workDir);
    await ops.run('git', ['clone', '--depth', '1', '--branch', githubBranch, authUrl, workDir], { ...abort });
    await ops.run('git', ['remote', 'set-url', 'origin', plainUrl], { cwd: workDir, ...abort });
  } catch (e) {
    if (e instanceof Error) {
      e.message = maskSecrets(e.message);
      const withStd = e as Error & { stderr?: unknown; cmd?: unknown };
      if (typeof withStd.stderr === 'string') withStd.stderr = maskSecrets(withStd.stderr);
      if (typeof withStd.cmd === 'string') withStd.cmd = maskSecrets(withStd.cmd);
    }
    throw e;
  }
}

/** 참조 repo 1개의 준비 결과. `error === null`이면 사용 가능 — `ref.dir`에 체크아웃이 있고 `sha`가 그 HEAD 커밋이다. */
export interface RefPrepared {
  ref: RefRepoClaim;
  /** 사용 가능하면 HEAD 커밋, 실패면 null. */
  sha: string | null;
  /**
   * 실패 사유 — 자격증명을 가린, 비어 있지 않은 문구. 성공이면 null.
   * API는 error를 있는 그대로(빈 문자열이어도) 저장하므로 실패 사유는 절대 비워 두지 않는다.
   */
  error: string | null;
}

export interface RefRepoOps extends RepoOps {
  /** git 출력(stdout)을 돌려준다 — rev-parse용. 끝의 줄바꿈은 부르는 쪽이 다듬는다. */
  output: (cmd: string, args: string[], opts: { cwd?: string; signal?: AbortSignal }) => Promise<string>;
  /** 폴더를 통째로 지운다. 없으면 아무 일도 하지 않는다. */
  rm: (path: string) => void;
  rename: (from: string, to: string) => void;
}

const defaultRefOps: RefRepoOps = {
  ...defaultOps,
  output: async (cmd, args, opts) => (await execFileAsync(cmd, args, opts)).stdout,
  rm: (path) => {
    rmSync(path, { recursive: true, force: true });
  },
  rename: renameSync,
};

/** 받는 동안 쓰는 임시 폴더 접미어 — 다 받았을 때만 최종 이름으로 바꾼다. */
const PART_SUFFIX = '.part';

/**
 * 실패 사유가 (가린 뒤) 비어 있을 때 쓰는 문구. 러너(interviewRunner.refReason)도 사유를 한 줄로 줄이고 나서
 * 아무것도 안 남으면 같은 문구를 쓴다 — 사용자에게 보이는 문구가 한 곳에서만 정해지도록 내보낸다.
 */
export const REF_PREPARE_FALLBACK_REASON = '참조 repo를 받지 못했습니다';

/**
 * 질문 세션의 읽기 전용 참조 repo를 준비한다 (스펙 2026-10-22 §5.1). claim마다(새 질문·추가 질문 모두)
 * 대상 `ensureRepo` 다음에 부른다. 참조마다 입력 순서대로 하나씩 처리하고, 하나가 실패해도 나머지는 계속한다.
 * 예외를 던지지 않는다 — 실패한 참조는 `{ sha: null, error }`로 돌려주고 턴은 계속된다.
 *
 * - 폴더에 `.git`이 있고 HEAD 브랜치가 요청 브랜치와 같으면 그대로 쓴다. fetch하지 않는 것이 곧 커밋 고정이다.
 * - 폴더가 없거나 브랜치가 다르면 폴더를 지우고 `<dir>.part`에 shallow clone → origin을 평문 URL로 → `<dir>`로 rename.
 *   임시 폴더에 받아 성공했을 때만 제자리로 옮기므로, 받는 도중 실패·중단돼도(턴 타임아웃 포함) 반쪽 체크아웃이
 *   남지 않는다. 남으면 다음 턴의 "그대로 쓴다" 판정이 그 폴더를 성공으로 보고 엉뚱한 커밋에 고정하게 된다.
 * - 자격증명은 clone 전송에만 쓰고 `.git/config`(origin)에는 남기지 않는다(대상 `ensureRepo`와 같은 규칙) —
 *   세션이 참조 폴더도 읽을 수 있기 때문. 실패 사유는 `maskSecrets`로 가린다.
 * - claim에 `sha`가 있는데 폴더가 사라져 새로 받았다면 브랜치 최신을 받는다. 고정 커밋 재현은 하지 않고(스펙의
 *   단순화 허용) 받은 커밋이 다르면 경고 로그만 남긴다 — API는 이미 정해진 sha를 바꾸지 않는다.
 * - `.git`이 있는 폴더는 HEAD가 다른 브랜치로 확인됐을 때만 지운다. HEAD를 읽지 못하면 지우지 않고 오류만 돌려준다
 *   (일시 오류가 고정한 체크아웃을 날리지 않게). `.git`이 없는 폴더는 체크아웃이 아니므로 함께 지우고 새로 받는다.
 * - `signal`(턴 타임아웃)은 모든 git 자식 프로세스에 넘긴다.
 */
export async function ensureRefRepos(
  refs: RefRepoClaim[],
  opts: { tokens?: GitTokens; signal?: AbortSignal },
  ops: RefRepoOps = defaultRefOps,
): Promise<RefPrepared[]> {
  const results: RefPrepared[] = [];
  for (const ref of refs) {
    results.push(await prepareRefRepo(ref, opts, ops));
  }
  return results;
}

async function prepareRefRepo(
  ref: RefRepoClaim,
  opts: { tokens?: GitTokens; signal?: AbortSignal },
  ops: RefRepoOps,
): Promise<RefPrepared> {
  const { dir, branch } = ref;
  const part = `${dir}${PART_SUFFIX}`;
  const abort = opts.signal ? { signal: opts.signal } : {};
  const headSha = async (): Promise<string> =>
    (await ops.output('git', ['rev-parse', 'HEAD'], { cwd: dir, ...abort })).trim();
  try {
    if (ops.exists(join(dir, '.git'))) {
      // 브랜치는 전체 이름(refs/heads/…)으로 비교한다. `--abbrev-ref`는 브랜치와 같은 이름의 태그가 있으면 모호성을
      // 피하려고 `heads/<이름>`을 돌려줘 재사용 판정이 어긋나고, 그러면 매 턴 다시 받아 커밋 고정이 풀린다.
      // 분리된 HEAD는 (종료 코드 0으로) `HEAD`를 돌려주므로 다른 브랜치로 취급된다.
      const head = (await ops.output('git', ['rev-parse', '--symbolic-full-name', 'HEAD'], { cwd: dir, ...abort })).trim();
      if (head === `refs/heads/${branch}`) return prepared(ref, await headSha());
    }
    // 체크아웃이 없거나(.git 없음) 다른 브랜치다 — 낡은 폴더와 지난 시도의 찌꺼기를 지우고 .part에 새로 받는다.
    ops.rm(dir);
    ops.rm(part);
    const plainUrl = buildCloneUrl(ref, {});
    const authUrl = buildCloneUrl(ref, opts.tokens ?? {});
    await ops.run('git', ['clone', '--depth', '1', '--branch', branch, authUrl, part], { ...abort });
    await ops.run('git', ['remote', 'set-url', 'origin', plainUrl], { cwd: part, ...abort });
    ops.rename(part, dir);
    return prepared(ref, await headSha());
  } catch (e) {
    try {
      ops.rm(part);
    } catch {
      // best effort — 지우지 못해도 원래 실패 사유를 돌려주는 것이 먼저다
    }
    return { ref, sha: null, error: describeFailure(e) };
  }
}

/** 성공 결과. claim에 고정 커밋이 있는데 받은 커밋이 다르면 경고만 남긴다(재현하지 않음). */
function prepared(ref: RefRepoClaim, sha: string): RefPrepared {
  if (ref.sha && ref.sha !== sha) {
    console.warn(
      `[refRepos] 고정 커밋과 다른 커밋을 받음: ${ref.alias}(${ref.branch}) catalog=${ref.catalogId}` +
        ` 고정=${ref.sha.slice(0, 7)} 받음=${sha.slice(0, 7)} — 고정 커밋 재현은 하지 않는다`,
    );
  }
  return { ref, sha, error: null };
}

/** 실패 사유 — 자격증명을 가리고, 비면 고정 문구. */
function describeFailure(e: unknown): string {
  const raw = e instanceof Error ? e.message : typeof e === 'string' ? e : '';
  return maskSecrets(raw).trim() || REF_PREPARE_FALLBACK_REASON;
}
