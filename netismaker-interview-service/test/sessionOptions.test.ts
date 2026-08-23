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

  it('pre-approves ONLY Skill + read/inspection tools; Write/Bash/Edit must fall through to canUseTool', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    const allowed = o.allowedTools as string[];
    // Skill must be present so the tool appears in init.tools; Read/Grep/Glob are read-only.
    expect(allowed).toEqual(expect.arrayContaining(['Skill', 'Read', 'Grep', 'Glob']));
    // SECURITY INVARIANT: tools in allowedTools are PRE-APPROVED and skip canUseTool. Listing
    // Write/Bash/Edit here would make buildCanUseTool's confinement dead code (RCE / agent
    // implements during the interview). They must be gated by canUseTool, not pre-approved.
    expect(allowed).not.toContain('Write');
    expect(allowed).not.toContain('Bash');
    expect(allowed).not.toContain('Edit');
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

  it('passes model and effort through to options when present', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, model: 'claude-sonnet-4-6', effort: 'medium' });
    expect(o.model).toBe('claude-sonnet-4-6');
    expect(o.effort).toBe('medium');
  });

  it('omits model/effort keys when absent', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect('model' in o).toBe(false);
    expect('effort' in o).toBe(false);
  });

  it('enables includePartialMessages so relay can stream activity deltas (스펙 §5.1)', () => {
    const opts = buildOptions({
      superpowersPluginPath: '/sp',
      workDir: '/w',
      claudeCliPath: '/bin/claude',
      claudeSessionId: null,
    });
    expect(opts.includePartialMessages).toBe(true);
  });
});
