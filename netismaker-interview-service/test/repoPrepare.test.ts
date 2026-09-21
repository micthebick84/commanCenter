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

  it('clones with an authenticated gitlab url built from the claim', async () => {
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
  });

  it('refreshes origin with the current authenticated url before fetching an existing checkout', async () => {
    const run = vi.fn().mockResolvedValue(undefined);
    await ensureRepo(
      { githubRepo: 'acme/widgets', githubBranch: 'main', workDir: '/wd/s2', tokens: { githubPat: 'ghp_A' } },
      { run, exists: vi.fn().mockReturnValue(true) },
    );
    const cmds = run.mock.calls.map((c) => [c[0], ...(c[1] as string[])].join(' '));
    expect(cmds[0]).toBe('git remote set-url origin https://oauth2:ghp_A@github.com/acme/widgets.git');
    expect(cmds[1]).toBe('git fetch --depth 1 origin main');
    expect(cmds[2]).toBe('git reset --hard origin/main');
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
