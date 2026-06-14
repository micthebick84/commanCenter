export interface Config {
  apiBaseUrl: string;
  workerApiKey: string;
  workerId: string;
  /** SHADOW per-session quota guard (turns/quota, NOT dollars — subscription auth). */
  quotaGuard: number;
  claimPollIntervalMs: number;
  heartbeatIntervalMs: number;
  superpowersPluginPath: string;
  /** Optional override for the claude CLI binary; auto-resolved by resolveClaudeCli() when unset. */
  claudeCliPath?: string;
}

function req(env: Record<string, string | undefined>, key: string): string {
  const v = env[key];
  if (!v) throw new Error(`Missing required env: ${key}`);
  return v;
}

function num(env: Record<string, string | undefined>, key: string, dflt: number): number {
  const v = env[key];
  return v === undefined ? dflt : Number(v);
}

export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
  return {
    apiBaseUrl: req(env, 'API_BASE_URL'),
    workerApiKey: req(env, 'WORKER_API_KEY'),
    workerId: req(env, 'WORKER_ID'),
    quotaGuard: num(env, 'INTERVIEW_QUOTA_GUARD', 5),
    claimPollIntervalMs: num(env, 'CLAIM_POLL_INTERVAL_MS', 3000),
    heartbeatIntervalMs: num(env, 'HEARTBEAT_INTERVAL_MS', 15000),
    superpowersPluginPath: req(env, 'SUPERPOWERS_PLUGIN_PATH'),
    // Auth = subscription via the local claude CLI (Phase-0 spike 00b). No ANTHROPIC_API_KEY.
    claudeCliPath: env.CLAUDE_CLI,
  };
}
