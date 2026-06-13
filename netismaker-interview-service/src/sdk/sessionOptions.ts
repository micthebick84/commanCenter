import { buildCanUseTool } from './permissions.js';

export interface SessionOptionsInput {
  superpowersPluginPath: string;
  /** Repo checkout dir = options.cwd. Resume MUST reuse the IDENTICAL cwd (spike 02, cwd-pinned). */
  workDir: string;
  /** Resolved claude CLI binary for subscription auth (Phase-0 spike 00b). */
  claudeCliPath: string;
  claudeSessionId: string | null;
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
  return {
    pathToClaudeCodeExecutable: input.claudeCliPath,
    plugins: [{ type: 'local', path: input.superpowersPluginPath }],
    allowedTools: ['Skill', 'Read', 'Grep', 'Glob', 'Write', 'Bash'],
    cwd: input.workDir,
    permissionMode: 'default',
    ...(input.claudeSessionId ? { resume: input.claudeSessionId } : {}),
    canUseTool: buildCanUseTool(input.workDir),
  };
}
