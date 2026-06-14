import { execFile } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

export interface RepoInput {
  githubRepo: string; // "owner/repo"
  githubBranch: string;
  workDir: string; // = claim.workDir, also options.cwd
}

export interface RepoOps {
  run: (cmd: string, args: string[], opts: { cwd?: string }) => Promise<unknown>;
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
 * Uses an unauthenticated https URL for read-only interview checkouts; swap in a PAT URL if
 * the interview repos are private (mirrors netisMaker GitRepoCache).
 */
export async function ensureRepo(input: RepoInput, ops: RepoOps = defaultOps): Promise<void> {
  const { githubRepo, githubBranch, workDir } = input;
  const gitDir = join(workDir, '.git');
  if (ops.exists(gitDir)) {
    await ops.run('git', ['fetch', '--depth', '1', 'origin', githubBranch], { cwd: workDir });
    await ops.run('git', ['reset', '--hard', `origin/${githubBranch}`], { cwd: workDir });
    return;
  }
  ops.mkdir?.(workDir);
  const url = `https://github.com/${githubRepo}.git`;
  await ops.run('git', ['clone', '--depth', '1', '--branch', githubBranch, url, workDir], {});
}
