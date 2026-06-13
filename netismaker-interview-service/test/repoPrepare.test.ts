import { describe, expect, it, vi } from 'vitest';
import { ensureRepo } from '../src/runner/repoPrepare.js';

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
    expect(cmds).toContain('git fetch --depth 1 origin main');
    expect(cmds).toContain('git reset --hard origin/main');
  });
});
