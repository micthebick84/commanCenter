import { buildCanUseTool } from './permissions.js';

export interface SessionOptionsInput {
  superpowersPluginPath: string;
  /** Repo checkout dir = options.cwd. Resume MUST reuse the IDENTICAL cwd (spike 02, cwd-pinned). */
  workDir: string;
  /** Resolved claude CLI binary for subscription auth (Phase-0 spike 00b). */
  claudeCliPath: string;
  claudeSessionId: string | null;
  /** 인터뷰별 추가 MCP 서버 스냅샷 (Java InterviewClaimResponse.mcpsExtra, TaskMcpSpec[] = {name,url,transport}). */
  mcpsExtra?: unknown;
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
 * - PERMISSIONS are enforced via canUseTool — allowedTools does NOT constrain skill-internal
 *   tool calls (spike 04 caveat 1: brainstorming ran Bash despite not being whitelisted).
 */
export function buildOptions(input: SessionOptionsInput): Record<string, unknown> {
  const mcpServers = toMcpServers(input.mcpsExtra);
  return {
    pathToClaudeCodeExecutable: input.claudeCliPath,
    plugins: [{ type: 'local', path: input.superpowersPluginPath }],
    allowedTools: ['Skill', 'Read', 'Grep', 'Glob', 'Write', 'Bash'],
    cwd: input.workDir,
    permissionMode: 'default',
    ...(input.claudeSessionId ? { resume: input.claudeSessionId } : {}),
    ...(mcpServers ? { mcpServers } : {}),
    canUseTool: buildCanUseTool(input.workDir),
  };
}
