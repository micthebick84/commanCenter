import { buildCanUseTool } from './permissions.js';
import type { SessionKind } from '../types.js';

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
 * - Isolation: load superpowers via plugins:[{type:'local'}] ONLY and do NOT set
 *   settingSources (settingSources:['user','project'] would load ALL user plugins, spike 04 caveat 2).
 *   'Skill' is whitelisted so the Skill tool appears in init.tools.
 * - cwd = workDir; resume reuses the identical cwd (the on-disk session store is cwd-hashed, spike 02).
 * - MCP: settingSources를 안 쓰는 대신 mcpsBase(~/.claude.json 합본 스냅샷)를 options.mcpServers로
 *   명시 주입 — 디자인/구현 워커(--mcp-config + --strict-mcp-config + --allowedTools mcp__*)와 동일 목록.
 *   플러그인 격리(superpowers만 로드)는 그대로 유지된다.
 * - PERMISSIONS: Write/Bash/Edit MUST NOT be in allowedTools. Tools listed in allowedTools are
 *   PRE-APPROVED by the CLI and skip the canUseTool callback entirely — so listing Write/Bash there
 *   made buildCanUseTool's confinement (Write→docs/superpowers, Bash read-only) dead code and let the
 *   interview agent implement arbitrary code / run arbitrary shell (RCE). Only read-only/inspection
 *   tools are pre-approved here; Write/Bash/Edit fall through to canUseTool, which confines them.
 *   ('Skill' stays so the Skill tool appears in init.tools; skill-internal Write/Bash still route
 *   through canUseTool because they are no longer pre-approved.)
 */
export function buildOptions(input: SessionOptionsInput): Record<string, unknown> {
  // 베이스(글로벌+프로젝트 합본) + 작업별 extras — 충돌 시 extras 우선 (워커 패리티).
  // settingSources 미설정이므로 여기 명시한 것 외 다른 MCP 소스는 안 붙는다 (--strict-mcp-config 등가).
  const merged: Record<string, unknown> = { ...(input.mcpsBase ?? {}), ...(toMcpServers(input.mcpsExtra) ?? {}) };
  const mcpServers = Object.keys(merged).length > 0 ? merged : undefined;
  const question = input.sessionKind === 'QUESTION';
  return {
    pathToClaudeCodeExecutable: input.claudeCliPath,
    // QUESTION: 플러그인 자체를 안 붙인다(스킬 없음). INTERVIEW: superpowers만 로컬 플러그인으로.
    plugins: question ? [] : [{ type: 'local', path: input.superpowersPluginPath }],
    // INTERVIEW: mcp__<server>는 해당 서버의 모든 도구 매칭 — 워커의 --allowedTools 와일드카드와 동일.
    //   MCP 도구는 어차피 canUseTool 기본 분기(allow)를 타므로 보안 경계 변화 없음; Write/Bash/Edit 불변식 유지.
    // QUESTION: mcp__ 와일드카드도 미등재 → MCP 호출까지 canUseTool(default-deny) 단일 관문 경유.
    allowedTools: question
      ? ['Read', 'Grep', 'Glob']
      : ['Skill', 'Read', 'Grep', 'Glob', ...Object.keys(merged).map((n) => `mcp__${n}`)],
    cwd: input.workDir,
    permissionMode: 'default',
    // 활동 스트림: stream_event(텍스트/thinking 델타)를 relay가 실시간 방출할 수 있게 켠다 (스펙 §5.1).
    includePartialMessages: true,
    ...(input.claudeSessionId ? { resume: input.claudeSessionId } : {}),
    ...(mcpServers ? { mcpServers } : {}),
    ...(input.model ? { model: input.model } : {}),
    ...(input.effort ? { effort: input.effort } : {}),
    ...(input.abortController ? { abortController: input.abortController } : {}),
    canUseTool: buildCanUseTool(input.workDir, input.sessionKind ?? 'INTERVIEW'),
  };
}
