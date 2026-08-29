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

  it('injects mcpsBase (글로벌+프로젝트 합본) into mcpServers — 디자인/구현 워커 패리티', () => {
    const o = buildOptions({
      ...base,
      claudeSessionId: null,
      mcpsBase: {
        'local-db': { command: 'npx', args: ['x'] },
        'obsidian-vault': { type: 'http', url: 'http://127.0.0.1:27123/mcp' },
      },
    });
    expect(o.mcpServers).toEqual({
      'local-db': { command: 'npx', args: ['x'] },
      'obsidian-vault': { type: 'http', url: 'http://127.0.0.1:27123/mcp' },
    });
  });

  it('merges mcpsBase with mcpsExtra; on a name conflict extras prevail (buildClaudeArgsForTask 정책)', () => {
    const o = buildOptions({
      ...base,
      claudeSessionId: null,
      mcpsBase: {
        'local-db': { command: 'npx', args: ['x'] },
        dup: { type: 'http', url: 'http://base' },
      },
      mcpsExtra: [{ name: 'dup', url: 'http://extra', transport: 'sse' }],
    });
    expect(o.mcpServers).toEqual({
      'local-db': { command: 'npx', args: ['x'] },
      dup: { type: 'sse', url: 'http://extra' },
    });
  });

  it('omits mcpServers when mcpsBase is empty and no extras', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, mcpsBase: {} });
    expect(o.mcpServers).toBeUndefined();
  });

  it('pre-approves mcp__<server> wildcards for every active server (worker --allowedTools 패리티)', () => {
    const o = buildOptions({
      ...base,
      claudeSessionId: null,
      mcpsBase: { 'local-db': { command: 'npx' } },
      mcpsExtra: [{ name: 'ctx7', url: 'https://ctx7', transport: 'http' }],
    });
    const allowed = o.allowedTools as string[];
    expect(allowed).toEqual(expect.arrayContaining(['mcp__local-db', 'mcp__ctx7']));
    // 보안 불변식 유지: MCP 와일드카드가 늘어나도 Write/Bash/Edit는 여전히 canUseTool 게이트.
    expect(allowed).not.toContain('Write');
    expect(allowed).not.toContain('Bash');
    expect(allowed).not.toContain('Edit');
  });

  it('adds no mcp__ wildcard when no MCP server is active', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect((o.allowedTools as string[]).filter((t) => t.startsWith('mcp__'))).toEqual([]);
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

  it('passes abortController through when provided and omits the key otherwise', () => {
    const controller = new AbortController();
    const withAbort = buildOptions({ ...base, claudeSessionId: null, abortController: controller });
    expect(withAbort.abortController).toBe(controller);

    const without = buildOptions({ ...base, claudeSessionId: null });
    expect('abortController' in without).toBe(false);
  });
});
