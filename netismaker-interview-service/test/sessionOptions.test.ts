import { describe, expect, it } from 'vitest';
import { buildOptions } from '../src/sdk/sessionOptions.js';

const base = {
  superpowersPluginPath: '/sp/5.1.0',
  workDir: '/tmp/repo',
  claudeCliPath: '/home/me/.local/bin/claude',
};

describe('buildOptions', () => {
  it('loads superpowers as a local plugin (isolation: plugins-only, no settingSources)', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect(o.plugins).toEqual([{ type: 'local', path: '/sp/5.1.0' }]);
    // Phase-0 spike 04 caveat 2: settingSources:['user','project'] loads ALL user plugins;
    // for isolation we rely on plugins:[] only and do NOT set settingSources.
    expect(o.settingSources).toBeUndefined();
  });

  it('uses subscription auth: pathToClaudeCodeExecutable set, no env.ANTHROPIC_API_KEY', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect(o.pathToClaudeCodeExecutable).toBe('/home/me/.local/bin/claude');
    expect(o.env).toBeUndefined();
  });

  it('allows Skill + read tools + Write + Bash (Skill must be present so the tool appears in init.tools)', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect(o.allowedTools).toEqual(
      expect.arrayContaining(['Skill', 'Read', 'Grep', 'Glob', 'Write', 'Bash']),
    );
  });

  it('omits resume on a fresh start', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect(o.resume).toBeUndefined();
  });

  it('sets resume when a claudeSessionId is present', () => {
    const o = buildOptions({ ...base, claudeSessionId: 'sess-abc' });
    expect(o.resume).toBe('sess-abc');
  });

  it('sets cwd to workDir (resume MUST reuse the identical cwd) and provides canUseTool', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect(o.cwd).toBe('/tmp/repo');
    expect(typeof o.canUseTool).toBe('function');
  });

  it('maps claim.mcpsExtra (TaskMcpSpec[]) to options.mcpServers; omits when empty/absent', () => {
    expect(buildOptions({ ...base, claudeSessionId: null }).mcpServers).toBeUndefined();
    expect(buildOptions({ ...base, claudeSessionId: null, mcpsExtra: [] }).mcpServers).toBeUndefined();
    const o = buildOptions({
      ...base,
      claudeSessionId: null,
      mcpsExtra: [{ name: 'ctx7', url: 'https://ctx7', transport: 'http' }],
    });
    expect(o.mcpServers).toEqual({ ctx7: { type: 'http', url: 'https://ctx7' } });
  });
});
