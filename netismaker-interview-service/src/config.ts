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
  /** 인터뷰 1세션 최대 assistant 질문 턴(초과 시 FAILED). 무력한 비용 가드를 대체하는 실효 백스톱. */
  maxTurns: number;
  /** 이 턴 수 이상이면 force-finish(정규 형식 plan 강제 요청) 프롬프트 사용. 기본 maxTurns-1. */
  forceFinishTurns: number;
  /** 한 claim(턴 전체)의 wall-clock 상한(ms). 초과 시 SDK abort + FAILED 보고. 기본 30분. */
  turnTimeoutMs: number;
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

/**
 * 양수 기간(ms) env — 비숫자('30m')/0/음수는 경고 후 기본값 폴백. NaN이 setTimeout에
 * 들어가면 1ms로 클램프돼 모든 턴이 즉시 abort+FAILED되는 워커 전면 장애가 되므로,
 * 오설정은 여기서 무해화한다.
 */
function posNum(env: Record<string, string | undefined>, key: string, dflt: number): number {
  const v = env[key];
  if (v === undefined) return dflt;
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) {
    // eslint-disable-next-line no-console
    console.warn(`[config] ${key}=${v} 무시 — 양수 ms가 아님, 기본값 ${dflt} 사용`);
    return dflt;
  }
  return n;
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
    maxTurns: num(env, 'INTERVIEW_MAX_TURNS', 20),
    forceFinishTurns: num(env, 'INTERVIEW_FORCE_FINISH_TURNS', 19),
    turnTimeoutMs: posNum(env, 'INTERVIEW_TURN_TIMEOUT_MS', 1_800_000),
  };
}
