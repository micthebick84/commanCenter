import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { loadBaseMcpServers } from '../src/sdk/mcpBase.js';

const tmp: string[] = [];
function claudeJson(content: string): string {
  const dir = mkdtempSync(join(tmpdir(), 'mcpbase-'));
  tmp.push(dir);
  const p = join(dir, '.claude.json');
  writeFileSync(p, content);
  return p;
}

afterEach(() => {
  for (const d of tmp.splice(0)) rmSync(d, { recursive: true, force: true });
});

describe('loadBaseMcpServers', () => {
  it('merges global mcpServers with every projects[*].mcpServers (WorkerMcpSupport parity)', () => {
    const p = claudeJson(
      JSON.stringify({
        mcpServers: { 'obsidian-vault': { type: 'http', url: 'http://127.0.0.1:27123/mcp' } },
        projects: {
          '/a': { mcpServers: { 'local-db': { command: 'npx', args: ['x'] } } },
          '/b': { mcpServers: { obsidian: { command: 'uvx', args: ['y'] } } },
        },
      }),
    );
    expect(loadBaseMcpServers(p)).toEqual({
      'obsidian-vault': { type: 'http', url: 'http://127.0.0.1:27123/mcp' },
      'local-db': { command: 'npx', args: ['x'] },
      obsidian: { command: 'uvx', args: ['y'] },
    });
  });

  it('on a name conflict the later (project-scope) entry prevails, matching Java copyServers', () => {
    const p = claudeJson(
      JSON.stringify({
        mcpServers: { dup: { type: 'http', url: 'http://global' } },
        projects: { '/a': { mcpServers: { dup: { type: 'http', url: 'http://project' } } } },
      }),
    );
    expect(loadBaseMcpServers(p)).toEqual({ dup: { type: 'http', url: 'http://project' } });
  });

  it('returns {} when the file is missing (진행은 MCP 없이 — 부팅 실패 금지)', () => {
    expect(loadBaseMcpServers('/nonexistent/.claude.json')).toEqual({});
  });

  it('returns {} on malformed JSON instead of throwing', () => {
    const p = claudeJson('{not json');
    expect(loadBaseMcpServers(p)).toEqual({});
  });

  it('ignores non-object server entries and non-object mcpServers containers', () => {
    const p = claudeJson(
      JSON.stringify({
        mcpServers: { ok: { type: 'http', url: 'http://ok' }, bad: 'oops' },
        projects: { '/a': { mcpServers: 'nope' }, '/b': {} },
      }),
    );
    expect(loadBaseMcpServers(p)).toEqual({ ok: { type: 'http', url: 'http://ok' } });
  });
});
