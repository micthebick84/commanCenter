import { execFile } from 'node:child_process';
import { existsSync, mkdirSync, renameSync, rmSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
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

/** git 자식 프로세스 호출 옵션. `env`가 없으면 부모 환경을 그대로 물려받는다 — 이 파일의 git 호출은 늘 `noPromptGit`이 만든 환경을 넘긴다. */
export interface GitCallOptions {
  cwd?: string;
  signal?: AbortSignal;
  env?: NodeJS.ProcessEnv;
}

export interface RepoOps {
  run: (cmd: string, args: string[], opts: GitCallOptions) => Promise<unknown>;
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
 * 대상 체크아웃(`ensureRepo`)과 참조 repo(`ensureRefRepos`)의 모든 git 호출이 하위 명령 앞에 달고 가는 인자. 운영자 PC의 자격증명 도우미와
 * askpass 프로그램을 쓰지 않는다. 이 PC처럼 시스템 git 설정에 `credential.helper=manager`(GCM)가 있으면, 인증이 필요한 원격(토큰이 없는
 * 호스트의 비공개·이름이 바뀐·지워진 repo는 401 — GitHub는 없는 repo에도 401을 준다)에서 git이 GCM을 불러 데스크톱 대화상자를 띄운 채
 * 턴 상한(30분)까지 멈춘다. 대상이면 세션이 그제야 FAILED로 끝나고, 참조면 참조 문제로 세션이 FAILED가 된다(참조 스펙 2026-10-22 §1.2 위반).
 * GCM이 운영자의 개인 GitHub 자격증명을 몰래 쓰거나 URL 토큰을 Windows 자격 증명 관리자에 저장하는 부작용도 막는다. 빈 값은 도우미 목록을
 * 비운다(Java 워커 `GitRepoCache.GIT_NO_HELPER`와 같은 방식). 프롬프트 쪽은 환경(`noPromptGitEnv`)이 막는다.
 */
const NO_PROMPT_GIT_PREFIX: readonly string[] = ['-c', 'credential.helper=', '-c', 'core.askPass='];

/**
 * 자식에게서 지울 askpass 프로그램 변수. git은 `GIT_ASKPASS` 변수 → `core.askPass` 설정 → `SSH_ASKPASS` 변수 순으로 찾으므로
 * (git의 `git_prompt`), 변수가 남아 있으면 `-c core.askPass=`가 있어도 운영자 PC의 프로그램이 먼저 뜬다.
 */
const ASKPASS_ENV_NAMES: readonly string[] = ['GIT_ASKPASS', 'SSH_ASKPASS'];
/** 자식에게 강제하는 값 — git의 터미널 프롬프트와 GCM의 대화상자를 끈다. 인증이 필요하면 묻지 않고 바로 실패한다. */
const PROMPT_OFF_ENV: Readonly<Record<string, string>> = { GIT_TERMINAL_PROMPT: '0', GCM_INTERACTIVE: 'never' };

/**
 * git 자식 프로세스 환경 — `base`(기본 `process.env`)의 사본에서 askpass 프로그램 변수를 지우고 프롬프트를 끈 것.
 * 부모 환경은 건드리지 않는다. Windows는 환경변수 이름의 대소문자를 구분하지 않아 `git_askpass`도 같은 변수이므로 이름은
 * 대소문자 무시로 비교한다(Node는 대소문자만 다른 키가 여럿이면 그중 하나만 자식에게 넘긴다).
 */
export function noPromptGitEnv(base: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
  const overridden = new Set([...ASKPASS_ENV_NAMES, ...Object.keys(PROMPT_OFF_ENV)]);
  const env: NodeJS.ProcessEnv = {};
  for (const [name, value] of Object.entries(base)) {
    if (!overridden.has(name.toUpperCase())) env[name] = value;
  }
  return Object.assign(env, PROMPT_OFF_ENV);
}

/**
 * 운영자 PC의 자격증명 도우미·askpass·터미널 프롬프트 없이 도는 git 호출 — 대상·참조의 모든 git 호출이 같은 앞 인자·환경·중단 신호를
 * 달고 나가도록 여기 한 곳을 거친다. 돌려주는 함수는 `ops.run`·`ops.output`에 그대로 펼쳐 넘길 `[명령, 인자, 옵션]`을 만든다.
 * 환경은 이 함수를 부를 때(= 체크아웃 하나에) 한 번만 만든다.
 */
function noPromptGit(signal: AbortSignal | undefined): (args: string[], cwd?: string) => [string, string[], GitCallOptions] {
  const env = noPromptGitEnv();
  return (args, cwd) => [
    'git',
    [...NO_PROMPT_GIT_PREFIX, ...args],
    { ...(cwd === undefined ? {} : { cwd }), env, ...(signal ? { signal } : {}) },
  ];
}

/**
 * Ensures the repo is checked out at workDir BEFORE the first turn AND before every resume
 * (resume is cwd-pinned — spike 02). Fresh => shallow clone; existing => fetch + hard reset.
 * URL은 claim의 gitUrl + 호스트별 토큰으로 만든다(비공개 GitHub/사내 GitLab).
 * 자격증명은 전송에만 쓰고 `.git/config`(origin)에는 절대 남기지 않는다 — QUESTION 세션은
 * workDir 안의 파일을 읽을 수 있기 때문. 그래서 clone/fetch는 인증 URL로 하되, origin은
 * 항상 토큰 없는 평문 URL로 맞춘다(기존 체크아웃은 먼저 origin부터 정리해 취약 버전이
 * 남긴 인증 URL도 함께 지운다).
 * git 호출은 전부 운영자 PC의 자격증명 도우미·askpass·터미널 프롬프트 없이 돈다(`noPromptGit`, 참조 repo와 같다).
 * 인증이 필요한 원격(토큰이 없는 호스트의 비공개·이름이 바뀐·지워진 repo)은 묻지 않고 바로 실패해 세션이 git의 fatal 줄을
 * 사유로 끝난다 — 그래서 비공개 GitHub 대상은 `GITHUB_PAT`이 있어야 받는다.
 * git 오류 메시지는 자격증명을 가린 뒤 다시 던진다 — 세션 실패 사유로 화면에 노출되기 때문.
 */
export async function ensureRepo(input: RepoInput, ops: RepoOps = defaultOps): Promise<void> {
  const { githubBranch, workDir } = input;
  const git = noPromptGit(input.signal);
  const plainUrl = buildCloneUrl(input, {});
  const authUrl = buildCloneUrl(input, input.tokens ?? {});
  try {
    if (ops.exists(join(workDir, '.git'))) {
      await ops.run(...git(['remote', 'set-url', 'origin', plainUrl], workDir));
      await ops.run(
        ...git(['fetch', '--depth', '1', authUrl, `+refs/heads/${githubBranch}:refs/remotes/origin/${githubBranch}`], workDir),
      );
      await ops.run(...git(['reset', '--hard', `origin/${githubBranch}`], workDir));
      return;
    }
    ops.mkdir?.(workDir);
    await ops.run(...git(['clone', '--depth', '1', '--branch', githubBranch, authUrl, workDir]));
    await ops.run(...git(['remote', 'set-url', 'origin', plainUrl], workDir));
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
  output: (cmd: string, args: string[], opts: GitCallOptions) => Promise<string>;
  /** 폴더를 통째로 지운다. 없으면 아무 일도 하지 않는다. */
  rm: (path: string) => void;
  /** 받은 임시 폴더를 최종 이름으로 옮긴다. 동기든 비동기든 기다린다. 일시적 잠금 오류는 부르는 쪽(`renameWithRetry`)이 다시 시도한다. */
  rename: (from: string, to: string) => void | Promise<void>;
  /** rename 재시도 사이의 대기(ms). 없으면 실제로 기다린다 — 테스트가 갈아 끼워 느려지지 않게 한다. */
  sleep?: (ms: number) => Promise<void>;
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

/** API가 만드는 참조 폴더의 위쪽 폴더 이름 — `{체크아웃 루트}/_refs/{세션 id}/{카탈로그 id}`(InterviewWorkPaths.refDir). */
const REFS_DIR_NAME = '_refs';

/** 받은 참조 폴더 경로가 약속된 모양이 아닐 때의 사유 — 러너(refReason)가 그대로 한 줄 사유로 쓰고 칩 툴팁에 보인다. */
const REF_DIR_INVALID_REASON = '참조 폴더 경로가 올바르지 않습니다';

/**
 * 이 참조의 폴더로 믿을 수 있는 경로인지 — 절대경로이고 `_refs/<숫자>/<이 참조의 catalogId>`로 끝나야 한다.
 * 러너는 이 폴더를(그리고 `.part`를) 통째로 지우므로 API가 보낸 값을 그대로 믿지 않는다. 끝 마디가 catalogId와 다르면
 * 같은 세션의 다른 참조 폴더를 지울 수 있어 거부한다. 구분자는 `/`와 `\` 둘 다 본다(API는 Windows에서도 `/`를 섞어 만든다).
 * 끝에 구분자가 붙으면(`…/12/`) 끝 마디가 비어 거부된다.
 */
function isRefDir(ref: RefRepoClaim): boolean {
  const { dir } = ref;
  if (typeof dir !== 'string' || !isAbsolute(dir)) return false;
  const [refsDir, session, catalog] = dir.split(/[\\/]+/).slice(-3);
  return (
    refsDir === REFS_DIR_NAME &&
    /^\d+$/.test(session ?? '') &&
    /^\d+$/.test(catalog ?? '') &&
    catalog === String(ref.catalogId)
  );
}

/** 디렉터리 rename이 일시적으로 막힐 때 Windows가 돌려주는 코드 — 백신·검색 인덱서가 방금 쓴 파일의 핸들을 잠깐 잡고 있는 경우. */
const TRANSIENT_RENAME_CODES: ReadonlySet<string> = new Set(['EPERM', 'EBUSY', 'EACCES']);
/** rename 재시도 사이의 대기(ms) — 처음 시도 + 재시도 4번 = 최대 5번 시도하고, 기다림은 합쳐 3초다. */
const RENAME_RETRY_DELAYS_MS: readonly number[] = [200, 400, 800, 1600];

const defaultSleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

function isTransientRenameError(e: unknown): boolean {
  const code = (e as { code?: unknown } | null | undefined)?.code;
  return typeof code === 'string' && TRANSIENT_RENAME_CODES.has(code);
}

/**
 * `from`을 `to`로 옮긴다. 일시적 잠금 코드(EPERM·EBUSY·EACCES)만 짧게 다시 시도하고(`RENAME_RETRY_DELAYS_MS`),
 * 그 밖의 오류·시도를 다 쓴 뒤의 오류·턴이 이미 중단된 뒤의 오류는 그대로 던져 기존 실패 경로(.part 삭제 + 사유 반환)를 탄다.
 */
async function renameWithRetry(ops: RefRepoOps, from: string, to: string, signal: AbortSignal | undefined): Promise<void> {
  const sleep = ops.sleep ?? defaultSleep;
  for (let attempt = 0; ; attempt += 1) {
    try {
      await ops.rename(from, to);
      return;
    } catch (e) {
      const wait = RENAME_RETRY_DELAYS_MS[attempt];
      if (wait === undefined || !isTransientRenameError(e) || signal?.aborted) throw e;
      await sleep(wait);
    }
  }
}

/**
 * 실패 사유가 (가린 뒤) 비어 있을 때 쓰는 문구. 러너(interviewRunner.refReason)도 사유를 한 줄로 줄이고 나서
 * 아무것도 안 남으면 같은 문구를 쓴다 — 사용자에게 보이는 문구가 한 곳에서만 정해지도록 내보낸다.
 */
export const REF_PREPARE_FALLBACK_REASON = '참조 repo를 받지 못했습니다';

/** 참조 하나당 받기 상한 기본값(ms) — 5분 (스펙 2026-10-24 §5.1). 설정 `INTERVIEW_REF_TIMEOUT_MS`로 바꾼다. */
export const DEFAULT_REF_TIMEOUT_MS = 300_000;

/**
 * 참조 하나를 받다가 상한에 끊겼을 때의 사유 — 칩 툴팁·프롬프트의 "사용할 수 없는 참조" 줄에 그대로 보인다.
 * 분은 반올림하되 1분보다 짧아도 1분으로 적는다(기본값이면 `참조 repo 받기 시간 초과(5분)`).
 */
export function refTimeoutReason(timeoutMs: number): string {
  return `참조 repo 받기 시간 초과(${Math.max(1, Math.round(timeoutMs / 60_000))}분)`;
}

/** 참조 하나를 준비할 때 받는 옵션 — 토큰과 그 참조의 git 호출에 넘길 중단 신호. */
interface RefPrepareOptions {
  tokens?: GitTokens;
  signal?: AbortSignal;
}

/**
 * 질문 세션과 작업 인터뷰의 읽기 전용 참조 repo를 준비한다 (스펙 2026-10-22 §5.1, 2026-10-24 §5.1). claim마다(새 세션·답변 턴 모두)
 * 대상 `ensureRepo` 다음에 부른다. 참조마다 입력 순서대로 하나씩 처리하고, 하나가 실패해도 나머지는 계속한다.
 * 예외를 던지지 않는다 — 실패한 참조는 `{ sha: null, error }`로 돌려주고 턴은 계속된다.
 *
 * - 폴더에 `.git`이 있고 HEAD 브랜치가 요청 브랜치와 같으면 그대로 쓴다. fetch하지 않는 것이 곧 커밋 고정이다.
 * - 폴더가 없거나 브랜치가 다르면 폴더를 지우고 `<dir>.part`에 shallow clone → origin을 평문 URL로 → `<dir>`로 rename.
 *   임시 폴더에 받아 성공했을 때만 제자리로 옮기므로, 받는 도중 실패·중단돼도(턴 타임아웃 포함) 반쪽 체크아웃이
 *   남지 않는다. 남으면 다음 턴의 "그대로 쓴다" 판정이 그 폴더를 성공으로 보고 엉뚱한 커밋에 고정하게 된다.
 * - 자격증명은 clone 전송에만 쓰고 `.git/config`(origin)에는 남기지 않는다(대상 `ensureRepo`와 같은 규칙) —
 *   세션이 참조 폴더도 읽을 수 있기 때문. 실패 사유는 `maskSecrets`로 가린다.
 * - git 호출은 전부 운영자 PC의 자격증명 도우미·askpass·터미널 프롬프트 없이 돈다(`noPromptGit`, 대상 `ensureRepo`와 같다). 인증이 필요한
 *   원격은 묻지 않고 바로 실패해 ⚠로 끝난다 — 도우미가 데스크톱 대화상자를 띄우고 턴 상한까지 멈추면 세션이 FAILED가 되기 때문이다
 *   (스펙 §1.2). 그래서 비공개 GitHub 참조는 `GITHUB_PAT`을 설정해야 하고, 설정하지 않은 호스트의 비공개 repo는 받지 못한다.
 * - claim에 `sha`가 있는데 폴더가 사라져 새로 받았다면 브랜치 최신을 받는다. 고정 커밋 재현은 하지 않고(스펙의
 *   단순화 허용) 받은 커밋이 다르면 경고 로그만 남긴다 — API는 이미 정해진 sha를 바꾸지 않는다.
 * - `.git`이 있는 폴더는 HEAD가 다른 브랜치로 확인됐을 때만 지운다. HEAD를 읽지 못하면 지우지 않고 오류만 돌려준다
 *   (일시 오류가 고정한 체크아웃을 날리지 않게). `.git`이 없는 폴더는 체크아웃이 아니므로 함께 지우고 새로 받는다.
 * - 지우기 전에 `dir`이 절대경로이고 `_refs/<숫자>/<catalogId>`로 끝나는지 본다(`isRefDir`). 아니면 어떤 디스크 작업·git 호출도
 *   하지 않고 `REF_DIR_INVALID_REASON`을 돌려준다 — API가 보낸 값을 그대로 지우지 않기 위한 방어다.
 * - 마지막 rename이 EPERM·EBUSY·EACCES로 막히면(백신·인덱서가 방금 받은 파일을 잡고 있을 때) 최대 5번까지 짧게 다시 시도한다.
 * - `signal`(턴 타임아웃)은 모든 git 자식 프로세스에 넘긴다.
 * - `timeoutMs`가 있으면 참조마다 따로 받기 상한을 건다(스펙 2026-10-24 §5.1 — 러너는 설정값, 기본 5분을 준다). 참조마다 자체 중단 신호를
 *   만들어 턴 신호에 묶고(턴이 이미 끝났으면 git을 부르기 전에 바로 중단) 그 신호를 git 호출과 rename 재시도에 넘긴다. 상한에 걸리면
 *   그 참조의 git만 멈추고 위의 실패 경로대로 `.part`를 지운 뒤(지우지 못해도 다음 턴이 받기 전에 지운다) 사유를
 *   `refTimeoutReason(timeoutMs)`로 돌려주고 다음 참조로 넘어간다 — 참조 하나 때문에 턴 상한(30분)까지 묶여 세션이 FAILED가 되지 않게.
 *   턴 신호로 끊긴 것은 시간 초과로 적지 않는다(사유는 중단 그대로). 참조 하나가 끝나면 타이머와 턴 신호의 리스너를 떼어 낸다.
 *   `timeoutMs`가 없으면 턴 신호를 그대로 넘긴다(상한 없음).
 */
export async function ensureRefRepos(
  refs: RefRepoClaim[],
  opts: { tokens?: GitTokens; signal?: AbortSignal; timeoutMs?: number },
  ops: RefRepoOps = defaultRefOps,
): Promise<RefPrepared[]> {
  const results: RefPrepared[] = [];
  for (const ref of refs) {
    results.push(
      opts.timeoutMs === undefined
        ? await prepareRefRepo(ref, opts, ops)
        : await prepareRefRepoWithin(ref, opts, opts.timeoutMs, ops),
    );
  }
  return results;
}

/**
 * 참조 하나를 `timeoutMs` 안에 준비한다. AbortSignal.any(Node 20.3+)는 engines(`>=20`)가 보장하지 않으므로 직접 묶는다 —
 * 턴 신호가 이미 중단돼 있으면 리스너가 불리지 않으니(abort 이벤트는 한 번만 난다) 먼저 확인해 바로 중단한다.
 */
async function prepareRefRepoWithin(
  ref: RefRepoClaim,
  opts: RefPrepareOptions,
  timeoutMs: number,
  ops: RefRepoOps,
): Promise<RefPrepared> {
  const turnSignal = opts.signal;
  const limit = new AbortController();
  let timedOut = false;
  const onTurnAbort = (): void => limit.abort(turnSignal?.reason);
  if (turnSignal?.aborted) onTurnAbort();
  else turnSignal?.addEventListener('abort', onTurnAbort, { once: true });
  const timer = setTimeout(() => {
    timedOut = true;
    limit.abort(new Error(refTimeoutReason(timeoutMs)));
  }, timeoutMs);
  try {
    const result = await prepareRefRepo(ref, { tokens: opts.tokens, signal: limit.signal }, ops);
    // 상한이 끊었고 턴은 살아 있을 때만 시간 초과다 — 끊긴 git의 오류(AbortError)를 사유로 보이지 않는다
    if (result.error !== null && timedOut && !turnSignal?.aborted) {
      return { ref, sha: null, error: refTimeoutReason(timeoutMs) };
    }
    return result;
  } finally {
    clearTimeout(timer);
    turnSignal?.removeEventListener('abort', onTurnAbort);
  }
}

async function prepareRefRepo(ref: RefRepoClaim, opts: RefPrepareOptions, ops: RefRepoOps): Promise<RefPrepared> {
  // 어떤 디스크 작업·git 호출보다 먼저 — 아래에서 dir과 dir.part를 통째로 지우므로 API가 보낸 경로를 그대로 믿지 않는다.
  if (!isRefDir(ref)) {
    // 칩에는 고정 문구만 보이므로 API가 보낸 경로는 로그로만 남는다(경로에는 자격증명이 없다).
    console.warn(
      `[refRepos] 참조 폴더 경로가 올바르지 않아 건너뜀: ${ref.alias}(${ref.branch}) catalog=${ref.catalogId} dir=${JSON.stringify(ref.dir)}`,
    );
    return { ref, sha: null, error: REF_DIR_INVALID_REASON };
  }
  const { dir, branch } = ref;
  const part = `${dir}${PART_SUFFIX}`;
  const git = noPromptGit(opts.signal);
  const headSha = async (): Promise<string> => (await ops.output(...git(['rev-parse', 'HEAD'], dir))).trim();
  try {
    if (ops.exists(join(dir, '.git'))) {
      // 브랜치는 전체 이름(refs/heads/…)으로 비교한다. `--abbrev-ref`는 브랜치와 같은 이름의 태그가 있으면 모호성을
      // 피하려고 `heads/<이름>`을 돌려줘 재사용 판정이 어긋나고, 그러면 매 턴 다시 받아 커밋 고정이 풀린다.
      // 분리된 HEAD는 (종료 코드 0으로) `HEAD`를 돌려주므로 다른 브랜치로 취급된다.
      const head = (await ops.output(...git(['rev-parse', '--symbolic-full-name', 'HEAD'], dir))).trim();
      if (head === `refs/heads/${branch}`) return prepared(ref, await headSha());
    }
    // 체크아웃이 없거나(.git 없음) 다른 브랜치다 — 낡은 폴더와 지난 시도의 찌꺼기를 지우고 .part에 새로 받는다.
    ops.rm(dir);
    ops.rm(part);
    const plainUrl = buildCloneUrl(ref, {});
    const authUrl = buildCloneUrl(ref, opts.tokens ?? {});
    await ops.run(...git(['clone', '--depth', '1', '--branch', branch, authUrl, part]));
    await ops.run(...git(['remote', 'set-url', 'origin', plainUrl], part));
    await renameWithRetry(ops, part, dir, opts.signal);
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
