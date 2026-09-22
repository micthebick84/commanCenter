import { describe, expect, it } from 'vitest';
import { buildCloneUrl, maskSecrets } from '../src/sdk/gitRemote.js';

describe('buildCloneUrl', () => {
  it('legacy claim (no gitUrl) → anonymous github url, or PAT url when configured', () => {
    expect(buildCloneUrl({ githubRepo: 'acme/widgets' }, {})).toBe('https://github.com/acme/widgets.git');
    expect(buildCloneUrl({ githubRepo: 'acme/widgets' }, { githubPat: 'ghp_A' })).toBe(
      'https://oauth2:ghp_A@github.com/acme/widgets.git',
    );
  });

  it('gitlab claim → token injected into the claim gitUrl', () => {
    const claim = {
      githubRepo: 'product/netis/web/netis-v7.0',
      gitUrl: 'https://gitlab.hamon.vip/product/netis/web/netis-v7.0.git',
      repoHost: 'gitlab',
    };
    expect(buildCloneUrl(claim, { gitlabToken: 'glpat-B', githubPat: 'ghp_A' })).toBe(
      'https://oauth2:glpat-B@gitlab.hamon.vip/product/netis/web/netis-v7.0.git',
    );
    expect(buildCloneUrl(claim, {})).toBe(claim.gitUrl);
  });

  it('host falls back to the gitUrl hostname when repoHost is missing', () => {
    expect(
      buildCloneUrl({ githubRepo: 'g/p', gitUrl: 'https://gitlab.hamon.vip/g/p.git' }, { gitlabToken: 'T', githubPat: 'P' }),
    ).toBe('https://oauth2:T@gitlab.hamon.vip/g/p.git');
    expect(
      buildCloneUrl({ githubRepo: 'a/b', gitUrl: 'https://github.com/a/b.git' }, { gitlabToken: 'T', githubPat: 'P' }),
    ).toBe('https://oauth2:P@github.com/a/b.git');
  });
});

describe('maskSecrets', () => {
  it('hides credentials embedded in urls', () => {
    const masked = maskSecrets('Command failed: git clone https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git /wd');
    expect(masked).toBe('Command failed: git clone https://***@gitlab.hamon.vip/g/p.git /wd');
    expect(maskSecrets('plain https://github.com/a/b.git')).toBe('plain https://github.com/a/b.git');
  });
});
