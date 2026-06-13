import { describe, expect, it } from 'vitest';
import { buildCanUseTool } from '../src/sdk/permissions.js';

const canUse = buildCanUseTool('/tmp/repo');

describe('canUseTool', () => {
  it('allows Write inside docs/superpowers/**', async () => {
    const r = await canUse('Write', { file_path: '/tmp/repo/docs/superpowers/specs/x.md' });
    expect(r.behavior).toBe('allow');
  });

  it('denies Write outside docs/superpowers (no repo mutation, spec §9)', async () => {
    const r = await canUse('Write', { file_path: '/tmp/repo/src/main.ts' });
    expect(r.behavior).toBe('deny');
  });

  it('denies Write that escapes the repo via traversal', async () => {
    const r = await canUse('Write', { file_path: '/tmp/repo/docs/superpowers/../../etc/passwd' });
    expect(r.behavior).toBe('deny');
  });

  it('allows whitelisted Bash (git status, ls, grep, rg, cat)', async () => {
    for (const cmd of ['git status', 'ls -la', 'grep -r foo .', 'rg bar', 'cat README.md']) {
      expect((await canUse('Bash', { command: cmd })).behavior).toBe('allow');
    }
  });

  it('denies non-whitelisted Bash (git push, rm, curl)', async () => {
    for (const cmd of ['git push origin main', 'rm -rf /', 'curl http://evil']) {
      expect((await canUse('Bash', { command: cmd })).behavior).toBe('deny');
    }
  });

  it('allows read/skill tools', async () => {
    expect((await canUse('Read', { file_path: '/tmp/repo/src/main.ts' })).behavior).toBe('allow');
    expect((await canUse('Skill', { name: 'writing-plans' })).behavior).toBe('allow');
  });
});
