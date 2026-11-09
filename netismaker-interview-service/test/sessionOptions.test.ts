import { describe, expect, it } from 'vitest';
import { buildOptions } from '../src/sdk/sessionOptions.js';

const base = {
  superpowersPluginPath: '/sp/5.1.0',
  workDir: '/tmp/repo',
  claudeCliPath: '/home/me/.local/bin/claude',
};

describe('buildOptions', () => {
  it('claude CLI에 넘기는 env에서 스택 비밀을 뺀다 (INTERVIEW·QUESTION 모두)', () => {
    process.env.GITLAB_TOKEN = 'glpat-test';
    process.env.WORKER_API_KEY = 'k';
    process.env.SESSION_OPTIONS_TEST_KEEP = 'keep';
    try {
      for (const sessionKind of ['INTERVIEW', 'QUESTION'] as const) {
        const env = buildOptions({ ...base, claudeSessionId: null, sessionKind }).env as Record<string, string>;
        expect(env.GITLAB_TOKEN).toBeUndefined();
        expect(env.WORKER_API_KEY).toBeUndefined();
        expect(env.SESSION_OPTIONS_TEST_KEEP).toBe('keep');
      }
      // 인터뷰 서비스 자신의 env는 그대로 — clone·API 호출에 계속 쓴다
      expect(process.env.GITLAB_TOKEN).toBe('glpat-test');
    } finally {
      delete process.env.GITLAB_TOKEN;
      delete process.env.WORKER_API_KEY;
      delete process.env.SESSION_OPTIONS_TEST_KEEP;
    }
  });

  it('INTERVIEW: DB 서버는 사전승인하지 않는다 — canUseTool(dbToolGate)을 거쳐야 한다', () => {
    const o = buildOptions({
      ...base,
      claudeSessionId: null,
      dbMcpServers: { 'db-7': { type: 'sdk', name: 'db-7', instance: {} } as never },
      dbDialects: { 'db-7': 'mysql' },
    });
    expect((o.mcpServers as Record<string, unknown>)['db-7']).toBeDefined();
    expect(o.allowedTools as string[]).not.toContain('mcp__db-7');
  });

  it('INTERVIEW: 붙지 않은 db-N 이름의 베이스/추가 서버도 사전승인하지 않는다(dbToolGate 우회 방지)', () => {
    const o = buildOptions({
      ...base,
      claudeSessionId: null,
      mcpsBase: { 'db-9': { type: 'http', url: 'http://fake' }, obsidian: { type: 'http', url: 'http://o' } },
      mcpsExtra: [{ name: 'db-12', url: 'http://x', transport: 'http' }],
    });
    const allowed = o.allowedTools as string[];
    expect(allowed).not.toContain('mcp__db-9');
    expect(allowed).not.toContain('mcp__db-12');
    expect(allowed).toContain('mcp__obsidian');
  });

  it('loads superpowers as a local plugin (isolation: plugins-only)', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect(o.plugins).toEqual([{ type: 'local', path: '/sp/5.1.0' }]);
  });

  it('isolates every session kind from the operator settings and claude.ai connectors', () => {
    // SECURITY INVARIANT (2026-10-01 질문 세션 #17 라이브 실측 + SDK 0.2.117/CLI 2.1.284 init 프로브):
    // settingSources를 생략하면 SDK가 `--setting-sources`를 아예 안 넘겨 CLI가 user/project/local을 전부 읽는다
    // → 운영자의 enabledPlugins(playwright·claude-mem 등)·훅·`permissions.allow`(mcp__obsidian 사전승인으로
    // canUseTool 우회)가 세션에 붙었다. `[]`로 명시해야 파일 설정이 빠지고, claude.ai 커넥터(Gmail/Drive/Calendar)는
    // 설정 소스와 무관하게 붙으므로 strictMcpConfig까지 켜야 주입한 mcpServers만 남는다.
    for (const sessionKind of ['INTERVIEW', 'QUESTION'] as const) {
      const o = buildOptions({ ...base, claudeSessionId: null, sessionKind });
      expect(o.settingSources).toEqual([]);
      expect(o.strictMcpConfig).toBe(true);
    }
  });

  it('uses subscription auth: pathToClaudeCodeExecutable set, no env.ANTHROPIC_API_KEY', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect(o.pathToClaudeCodeExecutable).toBe('/home/me/.local/bin/claude');
    // env는 서비스 env의 복사본(스택 비밀 제외) — API 키를 새로 넣지 않는다
    expect((o.env as Record<string, string | undefined>).ANTHROPIC_API_KEY).toBe(process.env.ANTHROPIC_API_KEY);
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

describe('buildOptions — refRoots passthrough (스펙 2026-10-22 §5.3)', () => {
  type Gate = (t: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;

  it('passes refRoots to the QUESTION gate', async () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', refRoots: ['/tmp/refs/5/12'] });
    const gate = o.canUseTool as Gate;
    expect((await gate('Read', { file_path: '/tmp/refs/5/12/a' })).behavior).toBe('allow');
    expect((await gate('Grep', { pattern: 'x', path: '/tmp/refs/5/12' })).behavior).toBe('allow');
    expect((await gate('Glob', { pattern: '*.java', path: '/tmp/refs/5/12' })).behavior).toBe('allow');
    expect((await gate('Bash', { command: 'cat /tmp/refs/5/12/a' })).behavior).toBe('deny'); // Bash는 대상 체크아웃 안만
    expect((await gate('Read', { file_path: '/tmp/refs/5/120/a' })).behavior).toBe('deny'); // 이름 접두 형제
    expect(o).not.toHaveProperty('refRoots');
  });

  it('keeps QUESTION pre-approving nothing — reading a reference still goes through canUseTool', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', refRoots: ['/tmp/refs/5/12'] });
    expect(o.allowedTools).toEqual([]);
  });

  it('QUESTION without refRoots (omitted or empty): a reference path stays denied', async () => {
    for (const input of [
      { ...base, claudeSessionId: null, sessionKind: 'QUESTION' as const },
      { ...base, claudeSessionId: null, sessionKind: 'QUESTION' as const, refRoots: [] },
    ]) {
      const gate = buildOptions(input).canUseTool as Gate;
      expect((await gate('Read', { file_path: '/tmp/refs/5/12/a' })).behavior).toBe('deny');
    }
  });

  it('INTERVIEW ignores refRoots (no gate change, no options change)', async () => {
    const o = buildOptions({ ...base, claudeSessionId: null, refRoots: ['/tmp/refs/5/12'] });
    const gate = o.canUseTool as Gate;
    expect((await gate('Write', { file_path: '/tmp/refs/5/12/a' })).behavior).toBe('deny'); // docs/superpowers 밖
    expect((await gate('Write', { file_path: '/tmp/repo/docs/superpowers/x.md' })).behavior).toBe('allow');
    expect(o).not.toHaveProperty('refRoots');
    expect(o.allowedTools).toEqual(['Skill', 'Read', 'Grep', 'Glob']);
  });
});

describe('buildOptions — DB MCP (스펙 2026-10-02 §6.4)', () => {
  const dbServer = { type: 'sdk', name: 'db-7', instance: {} };

  it('db 서버를 마지막에 머지(같은 이름이면 db가 이긴다), QUESTION 사전승인은 여전히 없음', () => {
    const o = buildOptions({
      ...base, claudeSessionId: null, sessionKind: 'QUESTION',
      mcpsBase: { 'db-7': { type: 'http', url: 'http://fake' }, obsidian: { type: 'http', url: 'http://o' } },
      dbMcpServers: { 'db-7': dbServer },
      dbDialects: { 'db-7': 'mysql' },
    });
    expect((o.mcpServers as Record<string, unknown>)['db-7']).toBe(dbServer); // 인스턴스를 복사하지 않고 그대로
    expect(Object.keys(o.mcpServers as object)).toContain('obsidian');
    expect(o.allowedTools).toEqual([]);
  });

  it('canUseTool이 dbDialects를 받아 DB 쓰기를 막는다', async () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', dbMcpServers: { 'db-7': dbServer }, dbDialects: { 'db-7': 'mysql' } });
    const gate = o.canUseTool as (n: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
    expect((await gate('mcp__db-7__query', { sql: 'SELECT 1' })).behavior).toBe('allow');
    expect((await gate('mcp__db-7__query', { sql: 'DELETE FROM t' })).behavior).toBe('deny');
  });
});

describe('buildOptions — superpowers 끔 (스펙 2026-11-03 간단 인터뷰 §5-1)', () => {
  type Gate = (t: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
  // canUseTool은 호출마다 새 함수이고 env는 process.env 사본이라 값 비교에서 뺀다.
  const comparable = (o: Record<string, unknown>) => {
    const { canUseTool: _gate, env: _env, ...rest } = o;
    return rest;
  };

  it('끔: 플러그인 없음, Skill은 사전 승인에서 빼고 목록에서도 뺀다', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, superpowers: false, mcpsBase: { obsidian: { command: 'x' } } });
    expect(o.plugins).toEqual([]);
    expect(o.allowedTools).toEqual(['Read', 'Grep', 'Glob', 'mcp__obsidian']);
    expect(o.disallowedTools).toEqual(['AskUserQuestion', 'Skill']);
    expect(o.settingSources).toEqual([]);
    expect(o.strictMcpConfig).toBe(true);
  });

  it('끔은 세 값(plugins·allowedTools의 Skill·disallowedTools)만 바꾼다 — 나머지 옵션은 켬과 같다', () => {
    const input = {
      ...base,
      claudeSessionId: 'sess-abc',
      model: 'claude-sonnet-5',
      effort: 'medium',
      mcpsBase: { obsidian: { command: 'x' } },
      mcpsExtra: [{ name: 'ctx7', url: 'https://ctx7', transport: 'http' }],
    };
    const on = buildOptions({ ...input, superpowers: true });
    const off = buildOptions({ ...input, superpowers: false });
    const { plugins: _p, allowedTools: _a, disallowedTools: _d, ...onRest } = comparable(on);
    const { plugins: _p2, allowedTools: _a2, disallowedTools: _d2, ...offRest } = comparable(off);
    expect(offRest).toEqual(onRest);
    expect(off.allowedTools).toEqual((on.allowedTools as string[]).filter((t) => t !== 'Skill'));
    expect(typeof off.canUseTool).toBe('function');
    expect(off.env).toEqual(on.env);
  });

  it('끔: DB 서버·db-N 이름은 켬과 같이 사전 승인에서 빠지고 INTERVIEW 게이트도 그대로다', async () => {
    const o = buildOptions({
      ...base,
      claudeSessionId: null,
      superpowers: false,
      mcpsBase: { 'db-9': { type: 'http', url: 'http://fake' }, obsidian: { type: 'http', url: 'http://o' } },
      dbMcpServers: { 'db-7': { type: 'sdk', name: 'db-7', instance: {} } },
      dbDialects: { 'db-7': 'mysql' },
    });
    expect(o.allowedTools).toEqual(['Read', 'Grep', 'Glob', 'mcp__obsidian']);
    expect(Object.keys(o.mcpServers as object)).toEqual(expect.arrayContaining(['db-7', 'db-9', 'obsidian']));
    const gate = o.canUseTool as Gate;
    expect((await gate('Write', { file_path: '/tmp/repo/docs/superpowers/x.md' })).behavior).toBe('allow');
    expect((await gate('Write', { file_path: '/tmp/repo/src/a.ts' })).behavior).toBe('deny');
    expect((await gate('mcp__db-7__query', { sql: 'DELETE FROM t' })).behavior).toBe('deny');
  });

  it('켬 가드: 생략·true는 지금과 같다', () => {
    const omitted = buildOptions({ ...base, claudeSessionId: null });
    const on = buildOptions({ ...base, claudeSessionId: null, superpowers: true });
    expect(comparable(on)).toEqual(comparable(omitted));
    expect(on.plugins).toEqual([{ type: 'local', path: base.superpowersPluginPath }]);
    expect((on.allowedTools as string[])[0]).toBe('Skill');
    expect(on.disallowedTools).toEqual(['AskUserQuestion']);
    // 생략과 true가 같이 어긋나도 잡도록 켬의 전체 모양도 값으로 고정한다.
    expect(comparable(omitted)).toEqual({
      pathToClaudeCodeExecutable: base.claudeCliPath,
      plugins: [{ type: 'local', path: base.superpowersPluginPath }],
      settingSources: [],
      strictMcpConfig: true,
      allowedTools: ['Skill', 'Read', 'Grep', 'Glob'],
      disallowedTools: ['AskUserQuestion'],
      cwd: base.workDir,
      permissionMode: 'default',
      includePartialMessages: true,
    });
  });

  it('QUESTION은 superpowers 값과 무관', () => {
    for (const superpowers of [true, false]) {
      const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', superpowers });
      expect(o.plugins).toEqual([]);
      expect(o.allowedTools).toEqual([]);
      expect(o.disallowedTools).toEqual(['AskUserQuestion']);
    }
    const none = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION' });
    for (const superpowers of [true, false]) {
      const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', superpowers });
      expect(comparable(o)).toEqual(comparable(none));
    }
  });
});

describe('buildOptions — 목업 MCP (스펙 2026-11-03 인터뷰 목업 §4)', () => {
  // canUseTool은 호출마다 새 함수이고 env는 process.env 사본이라 값 비교에서 뺀다.
  const comparable = (o: Record<string, unknown>) => {
    const { canUseTool: _gate, env: _env, ...rest } = o;
    return rest;
  };
  const mockupServer = { type: 'sdk', name: 'mockup', instance: {} };

  it('목업 서버는 마지막에 병합돼 같은 이름을 이기고 사전 승인된다', () => {
    const o = buildOptions({
      ...base,
      claudeSessionId: null,
      mcpsBase: { mockup: { command: 'x' }, obsidian: { command: 'o' } },
      mcpsExtra: [{ name: 'mockup', url: 'http://extra', transport: 'http' }],
      dbMcpServers: { 'db-7': { type: 'sdk', name: 'db-7', instance: {} } },
      dbDialects: { 'db-7': 'mysql' },
      mockupMcpServers: { mockup: mockupServer },
    });
    const servers = o.mcpServers as Record<string, unknown>;
    expect(servers.mockup).toBe(mockupServer); // 인스턴스를 복사하지 않고 그대로, 베이스·추가 서버의 같은 이름을 이긴다
    expect(Object.keys(servers)).toEqual(expect.arrayContaining(['obsidian', 'db-7', 'mockup']));
    const allowed = o.allowedTools as string[];
    expect(allowed).toContain('mcp__mockup');
    expect(allowed.filter((t) => t === 'mcp__mockup')).toHaveLength(1);
    expect(allowed).not.toContain('mcp__db-7'); // DB 서버는 여전히 사전 승인에서 빠진다
  });

  it('목업 서버가 없으면 지금과 같다', () => {
    const inputs = [
      { ...base, claudeSessionId: null },
      {
        ...base,
        claudeSessionId: 'sess-abc',
        mcpsBase: { obsidian: { command: 'o' } },
        dbMcpServers: { 'db-7': { type: 'sdk', name: 'db-7', instance: {} } },
        dbDialects: { 'db-7': 'mysql' as const },
      },
    ];
    for (const input of inputs) {
      expect(comparable(buildOptions({ ...input, mockupMcpServers: undefined }))).toEqual(comparable(buildOptions(input)));
      expect(comparable(buildOptions({ ...input, mockupMcpServers: {} }))).toEqual(comparable(buildOptions(input)));
    }
    expect(buildOptions({ ...base, claudeSessionId: null, mockupMcpServers: undefined })).not.toHaveProperty('mcpServers');
  });
});
