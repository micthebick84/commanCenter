import { execFile } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { buildCloneUrl, maskSecrets, type GitTokens } from '../sdk/gitRemote.js';

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
 * URL은 claim의 gitUrl + 호스트별 토큰으로 만든다(비공개 GitHub/사내 GitLab). 기존 체크아웃도
 * 매번 origin을 현재 인증 URL로 맞춰 토큰 교체가 재clone 없이 반영된다.
 * git 오류 메시지는 자격증명을 가린 뒤 다시 던진다 — 세션 실패 사유로 화면에 노출되기 때문.
 */
export async function ensureRepo(input: RepoInput, ops: RepoOps = defaultOps): Promise<void> {
  const { githubBranch, workDir } = input;
  const abort = input.signal ? { signal: input.signal } : {};
  const url = buildCloneUrl(input, input.tokens ?? {});
  try {
    if (ops.exists(join(workDir, '.git'))) {
      await ops.run('git', ['remote', 'set-url', 'origin', url], { cwd: workDir, ...abort });
      await ops.run('git', ['fetch', '--depth', '1', 'origin', githubBranch], { cwd: workDir, ...abort });
      await ops.run('git', ['reset', '--hard', `origin/${githubBranch}`], { cwd: workDir, ...abort });
      return;
    }
    ops.mkdir?.(workDir);
    await ops.run('git', ['clone', '--depth', '1', '--branch', githubBranch, url, workDir], { ...abort });
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
