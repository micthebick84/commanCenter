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

  it('removes AskUserQuestion from the model context for every session kind (headless — no one can answer it)', () => {
    // canUseTool을 넘기면 CLI가 AskUserQuestion을 init.tools에 노출한다(2026-09-29 SDK 0.2.117 + CLI 2.1.284 프로브).
    // 헤드리스라 "The user did not answer"로 끝나고, 모델이 텍스트로 재질문하며 헛턴을 쓴다(세션 #12).
    // disallowedTools에 넣어야 도구가 목록에서 빠진다 — canUseTool deny로는 호출 시도 자체를 못 막는다.
    for (const sessionKind of ['INTERVIEW', 'QUESTION'] as const) {
      const o = buildOptions({ ...base, claudeSessionId: null, sessionKind });
      expect(o.disallowedTools).toEqual(expect.arrayContaining(['AskUserQuestion']));
      expect(o.allowedTools as string[]).not.toContain('AskUserQuestion');
    }
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

describe('buildOptions — sessionKind QUESTION (스펙 §6-①)', () => {
  const mcps = {
    mcpsBase: { 'local-db': { command: 'npx', args: ['x'] } },
    mcpsExtra: [{ name: 'ctx7', url: 'https://ctx7', transport: 'http' }],
  };

  it('loads NO plugins and pre-approves NOTHING — Read/Grep/Glob도 canUseTool 경로 confinement를 거쳐야 한다', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', ...mcps });
    expect(o.plugins).toEqual([]);
    // SECURITY INVARIANT: allowedTools 등재 도구는 CLI가 사전승인해 canUseTool을 아예 호출하지 않는다.
    // Read/Grep/Glob을 여기 두면 permissions.ts의 repoDir/attachmentRoot confinement가 죽은 코드가 돼
    // 질문 세션이 레포 밖(`C:\Windows\win.ini`, `~/.ssh` 등)을 읽는다(2026-09-29 라이브 세션 실측).
    expect(o.allowedTools).toEqual([]);
  });

  it('still injects merged mcpServers (base+extras, 워커 패리티) — 게이트는 canUseTool 단일 관문', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', ...mcps });
    expect(o.mcpServers).toEqual({
      'local-db': { command: 'npx', args: ['x'] },
      ctx7: { type: 'http', url: 'https://ctx7' },
    });
  });

  it('canUseTool is the QUESTION gate (denies Write even under docs/superpowers)', async () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION' });
    const gate = o.canUseTool as (t: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
    expect((await gate('Write', { file_path: '/tmp/repo/docs/superpowers/x.md' })).behavior).toBe('deny');
    expect((await gate('Read', { file_path: '/tmp/repo/a.ts' })).behavior).toBe('allow');
  });

  it('keeps resume/cwd/model/effort/includePartialMessages wiring identical to INTERVIEW', () => {
    const o = buildOptions({
      ...base, claudeSessionId: 'sess-q', sessionKind: 'QUESTION', model: 'claude-sonnet-5', effort: 'medium',
    });
    expect(o.resume).toBe('sess-q');
    expect(o.cwd).toBe('/tmp/repo');
    expect(o.model).toBe('claude-sonnet-5');
    expect(o.effort).toBe('medium');
    expect(o.includePartialMessages).toBe(true);
    expect(o.permissionMode).toBe('default');
  });

  it('sessionKind omitted → INTERVIEW behaviour (superpowers plugin + Skill + mcp__ wildcards)', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, ...mcps });
    expect(o.plugins).toEqual([{ type: 'local', path: '/sp/5.1.0' }]);
    expect(o.allowedTools).toEqual(['Skill', 'Read', 'Grep', 'Glob', 'mcp__local-db', 'mcp__ctx7']);
  });
});

describe('buildOptions — attachmentRoot passthrough (스펙 2026-09-13 §6)', () => {
  type Gate = (t: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;

  it('QUESTION + attachmentRoot: canUseTool allows Read under the attachment root, Grep still repoDir-only', async () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', attachmentRoot: '/att/question-77' });
    const gate = o.canUseTool as Gate;
    expect((await gate('Read', { file_path: '/att/question-77/create/1-a.docx.txt' })).behavior).toBe('allow');
    expect((await gate('Read', { file_path: '/tmp/repo/a.ts' })).behavior).toBe('allow');
    expect((await gate('Grep', { pattern: 'x', path: '/att/question-77' })).behavior).toBe('deny');
  });

  it('QUESTION without attachmentRoot (omitted or null): Read outside repoDir stays denied', async () => {
    for (const input of [
      { ...base, claudeSessionId: null, sessionKind: 'QUESTION' as const },
      { ...base, claudeSessionId: null, sessionKind: 'QUESTION' as const, attachmentRoot: null },
    ]) {
      const gate = buildOptions(input).canUseTool as Gate;
      expect((await gate('Read', { file_path: '/att/question-77/create/1-a.docx.txt' })).behavior).toBe('deny');
    }
  });

  it('attachmentRoot is not leaked into the SDK options object (canUseTool-internal only)', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', attachmentRoot: '/att/question-77' });
    expect('attachmentRoot' in o).toBe(false);
  });
});
