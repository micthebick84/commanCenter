import { buildCanUseTool } from './permissions.js';
import type { SessionKind } from '../types.js';
import type { SqlDialect } from './sqlReadOnly.js';

export interface SessionOptionsInput {
  superpowersPluginPath: string;
  /** Repo checkout dir = options.cwd. Resume MUST reuse the IDENTICAL cwd (spike 02, cwd-pinned). */
  workDir: string;
  /** Resolved claude CLI binary for subscription auth (Phase-0 spike 00b). */
  claudeCliPath: string;
  claudeSessionId: string | null;
  /** 인터뷰별 추가 MCP 서버 스냅샷 (Java InterviewClaimResponse.mcpsExtra, TaskMcpSpec[] = {name,url,transport}). */
  mcpsExtra?: unknown;
  /**
   * ~/.claude.json 글로벌+프로젝트 합본 (mcpBase.loadBaseMcpServers, 부팅 시 1회 스냅샷) —
   * 디자인/구현 워커의 --mcp-config 베이스와 동일. 이름 충돌 시 mcpsExtra가 prevails
   * (WorkerMcpSupport.buildClaudeArgsForTask 정책).
   */
  mcpsBase?: Record<string, unknown>;
  /** 선택 모델 (blank/미지정이면 CLI 기본값). */
  model?: string;
  /** 추론 effort (blank/미지정이면 CLI 기본값). */
  effort?: string;
  /** 턴 wall-clock 타임아웃용 — abort 시 SDK가 claude CLI 자식 프로세스를 종료한다. */
  abortController?: AbortController;
  /**
   * 세션 종류. 'QUESTION'이면 superpowers 미로드 + Skill/mcp__ 사전승인 없음 + default-deny 게이트
   * (스펙 2026-08-30 §6-①). 미지정 = INTERVIEW(기존 동작 그대로).
   */
  sessionKind?: SessionKind;
  /**
   * 세션 첨부 디렉토리 절대경로 (Java InterviewClaimResponse.attachmentRoot, 스펙 2026-09-13 §6) —
   * QUESTION 게이트의 Read 전용 두 번째 허용 루트로 buildCanUseTool에 전달만 한다(SDK 옵션에는 싣지 않음).
   * null/미지정 = 추가 허용 루트 없음(종전 동작). INTERVIEW에서는 무시된다.
   */
  attachmentRoot?: string | null;
  /**
   * 이번 턴의 내장 DB MCP 서버(스펙 2026-10-02 §6.4) — dbMcp.createDbMcp 결과({type:'sdk', name, instance}).
   * SDK가 CLI에는 이름만 넘긴다. merge 순서 base → extras → db(마지막 우선).
   */
  dbMcpServers?: Record<string, unknown>;
  /** DB 서버별 SQL 방언 — QUESTION 게이트가 query를 검사할 때 쓴다(permissions.ts dbToolGate). */
  dbDialects?: Record<string, SqlDialect>;
}

/**
 * Java mcpsExtra(TaskMcpSpec[] = {name,url,transport})를 SDK options.mcpServers
 * (Record<name, {type, url}>)로 변환. http/sse 전송만 URL 기반이라 매핑 가능.
 * 비어있거나 형식 불명이면 undefined를 반환해 mcpServers 키 자체를 생략한다.
 */
function toMcpServers(mcpsExtra: unknown): Record<string, { type: string; url: string }> | undefined {
  if (!Array.isArray(mcpsExtra) || mcpsExtra.length === 0) return undefined;
  const servers: Record<string, { type: string; url: string }> = {};
  for (const s of mcpsExtra) {
    if (s && typeof s === 'object' && 'name' in s && 'url' in s) {
      const spec = s as { name: string; url: string; transport?: string };
      servers[spec.name] = { type: spec.transport ?? 'http', url: spec.url };
    }
  }
  return Object.keys(servers).length > 0 ? servers : undefined;
}

/**
 * SDK options for one interview turn.
 * - Auth = subscription via the local claude CLI: pathToClaudeCodeExecutable points at
 *   the resolved binary; ANTHROPIC_API_KEY is NOT set (apiKeySource:"none", spike 00b).
 * - Isolation: load superpowers via plugins:[{type:'local'}] ONLY, with settingSources:[] +
 *   strictMcpConfig:true. settingSources를 **생략하면** SDK가 `--setting-sources`를 안 넘겨 CLI가
 *   user/project/local을 전부 읽는다(d.ts 주석과 반대 — 2026-10-01 SDK 0.2.117/CLI 2.1.284 init 프로브,
 *   질문 세션 #17에 운영자 플러그인 16개·claude-mem 훅·`permissions.allow: mcp__obsidian`이 붙음).
 *   `[]`이면 `--setting-sources=`로 파일 설정이 빠지지만 claude.ai 커넥터(Gmail/Drive/Calendar)는 남아서
 *   strictMcpConfig로 options.mcpServers 외 MCP를 끊는다. 대상 레포의 CLAUDE.md·.claude/settings.json도
 *   안 읽는다 — 'project'를 넣으면 레포가 permissions.allow/hooks로 canUseTool 게이트를 우회할 수 있다.
 *   'Skill' is whitelisted so the Skill tool appears in init.tools.
 * - cwd = workDir; resume reuses the identical cwd (the on-disk session store is cwd-hashed, spike 02).
 * - MCP: settingSources를 안 쓰는 대신 mcpsBase(~/.claude.json 합본 스냅샷)를 options.mcpServers로
 *   명시 주입 — 디자인/구현 워커(--mcp-config + --strict-mcp-config + --allowedTools mcp__*)와 동일 목록.
 *   플러그인 격리(superpowers만 로드)는 그대로 유지된다.
 *   질문 세션 내장 DB 서버(dbMcpServers, type:'sdk')는 마지막에 머지 — SDK가 CLI에 이름만 넘기므로 --mcp-config 명령줄에 접속정보가 실리지 않는다. stdio 서버에 접속정보를 넣지 말 것.
 * - PERMISSIONS: Write/Bash/Edit MUST NOT be in allowedTools. Tools listed in allowedTools are
 *   PRE-APPROVED by the CLI and skip the canUseTool callback entirely — so listing Write/Bash there
 *   made buildCanUseTool's confinement (Write→docs/superpowers, Bash read-only) dead code and let the
 *   interview agent implement arbitrary code / run arbitrary shell (RCE). Only read-only/inspection
 *   tools are pre-approved here; Write/Bash/Edit fall through to canUseTool, which confines them.
 *   ('Skill' stays so the Skill tool appears in init.tools; skill-internal Write/Bash still route
 *   through canUseTool because they are no longer pre-approved.)
 *
 * QUESTION variant (sessionKind:'QUESTION'): plugins is [] (superpowers not loaded, no Skill tool),
 *   allowedTools is [] — NOTHING is pre-approved, so every tool call (Read/Grep/Glob/MCP included)
 *   reaches canUseTool. Pre-approving Read/Grep/Glob made their path confinement dead code: the CLI
 *   skipped canUseTool and a question session read `C:\Windows\win.ini` (2026-09-29 라이브 실측,
 *   SDK 프로브로 allowedTools 등재 시 canUseTool 0회 호출 확인). canUseTool itself is
 *   buildCanUseTool(repoDir,'QUESTION',attachmentRoot): default-deny
 *   with Read/Grep/Glob path-confined to repoDir (Read additionally allowed under attachmentRoot, the
 *   session attachment dir — 스펙 2026-09-13 §6) and Bash further restricted beyond the read-only
 *   whitelist (see permissions.ts).
 */
export function buildOptions(input: SessionOptionsInput): Record<string, unknown> {
  // 베이스(글로벌+프로젝트 합본) + 작업별 extras — 충돌 시 extras 우선 (워커 패리티).
  // settingSources:[] + strictMcpConfig라 여기 명시한 것 외 다른 MCP 소스는 안 붙는다 (워커 --strict-mcp-config 패리티).
  const merged: Record<string, unknown> = {
    ...(input.mcpsBase ?? {}),
    ...(toMcpServers(input.mcpsExtra) ?? {}),
    ...(input.dbMcpServers ?? {}),
  };
  const mcpServers = Object.keys(merged).length > 0 ? merged : undefined;
  const question = input.sessionKind === 'QUESTION';
  return {
    pathToClaudeCodeExecutable: input.claudeCliPath,
    // QUESTION: 플러그인 자체를 안 붙인다(스킬 없음). INTERVIEW: superpowers만 로컬 플러그인으로.
    plugins: question ? [] : [{ type: 'local', path: input.superpowersPluginPath }],
    // 운영자 설정(플러그인·훅·permissions.allow)과 claude.ai 커넥터 차단 — 위 Isolation 주석 참고.
    settingSources: [],
    strictMcpConfig: true,
    // INTERVIEW: mcp__<server>는 해당 서버의 모든 도구 매칭 — 워커의 --allowedTools 와일드카드와 동일.
    //   MCP 도구는 어차피 canUseTool 기본 분기(allow)를 타므로 보안 경계 변화 없음; Write/Bash/Edit 불변식 유지.
    // QUESTION: 사전승인 없음 → Read/Grep/Glob/MCP 전부 canUseTool(default-deny + 경로 confinement) 단일 관문 경유.
    allowedTools: question
      ? []
      :['Skill', 'Read', 'Grep', 'Glob', ...Object.keys(merged).map((n) => `mcp__${n}`)],
    // 헤드리스라 AskUserQuestion에 답할 사람이 없다 — canUseTool을 넘기면 CLI가 이 도구를 노출하고,
    // 호출은 "The user did not answer"로 끝나 모델이 텍스트로 재질문한다(2026-09-29 세션 #12).
    // 질문은 턴 종료 텍스트 → postQuestion → UI 경로로만 오가야 하므로 목록에서 뺀다(canUseTool deny로는 시도를 못 막음).
    disallowedTools: ['AskUserQuestion'],
    cwd: input.workDir,
    permissionMode: 'default',
    // 활동 스트림: stream_event(텍스트/thinking 델타)를 relay가 실시간 방출할 수 있게 켠다 (스펙 §5.1).
    includePartialMessages: true,
    ...(input.claudeSessionId ? { resume: input.claudeSessionId } : {}),
    ...(mcpServers ? { mcpServers } : {}),
    ...(input.model ? { model: input.model } : {}),
    ...(input.effort ? { effort: input.effort } : {}),
    ...(input.abortController ? { abortController: input.abortController } : {}),
    canUseTool: buildCanUseTool(input.workDir, input.sessionKind ?? 'INTERVIEW', input.attachmentRoot ?? null, input.dbDialects ?? {}),
  };
}
