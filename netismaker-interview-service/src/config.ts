export interface Config {
  apiBaseUrl: string;
  workerApiKey: string;
  workerId: string;
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
  /** 참조 repo 하나당 받기 상한(ms). 넘으면 그 참조만 ⚠(시간 초과)로 끝나고 턴은 계속된다. 기본 5분 (스펙 2026-10-24 §5.1). */
  refTimeoutMs: number;
  /** 비공개 GitHub 레포 clone용(선택). Java 워커와 같은 GITHUB_PAT. */
  githubPat?: string;
  /** 사내 GitLab clone용(선택). Java 워커와 같은 GITLAB_TOKEN. */
  gitlabToken?: string;
  /** 동시에 돌릴 턴 수(INTERVIEW_CONCURRENCY, 기본 1 = 직렬). 질문·인터뷰 공용 큐에서 최대 N건을 함께 claim한다. */
  concurrency: number;
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
 * setTimeout이 받는 가장 긴 지연(ms, 2^31-1 ≈ 24.8일). 넘는 값은 Node가 경고와 함께 1ms로 바꾸므로 모든 턴·참조가 바로 끊긴다 —
 * 기간 설정은 이 값으로 줄인다(config `posNum`과 러너의 이중 방어).
 */
export const MAX_TIMER_MS = 2_147_483_647;

/**
 * 양수 기간(ms) env — 비숫자('30m')/0/음수는 경고 후 기본값 폴백. NaN이 setTimeout에
 * 들어가면 1ms로 클램프돼 모든 턴이 즉시 abort+FAILED되는 워커 전면 장애가 되므로,
 * 오설정은 여기서 무해화한다. 타이머 한도(`MAX_TIMER_MS`)를 넘는 값도 같은 이유로 경고 후 그 한도로 줄인다.
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
  if (n > MAX_TIMER_MS) {
    // eslint-disable-next-line no-console
    console.warn(`[config] ${key}=${v} — 타이머 한도를 넘어 ${MAX_TIMER_MS}ms로 줄임`);
    return MAX_TIMER_MS;
  }
  return n;
}

/** 양의 정수 env — 비정수·0·음수는 경고 후 기본값 폴백(posNum과 같은 규칙). */
function posInt(env: Record<string, string | undefined>, key: string, dflt: number): number {
  const v = env[key];
  if (v === undefined || v === '') return dflt;
  const n = Number(v);
  if (!Number.isInteger(n) || n <= 0) {
    // eslint-disable-next-line no-console
    console.warn(`[config] ${key}=${v} 무시 — 양의 정수가 아님, 기본값 ${dflt} 사용`);
    return dflt;
  }
  return n;
}

export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
  return {
    apiBaseUrl: req(env, 'API_BASE_URL'),
    workerApiKey: req(env, 'WORKER_API_KEY'),
    workerId: req(env, 'WORKER_ID'),
    claimPollIntervalMs: num(env, 'CLAIM_POLL_INTERVAL_MS', 3000),
    heartbeatIntervalMs: num(env, 'HEARTBEAT_INTERVAL_MS', 15000),
    superpowersPluginPath: req(env, 'SUPERPOWERS_PLUGIN_PATH'),
    // Auth = subscription via the local claude CLI (Phase-0 spike 00b). No ANTHROPIC_API_KEY.
    claudeCliPath: env.CLAUDE_CLI,
    maxTurns: num(env, 'INTERVIEW_MAX_TURNS', 20),
    forceFinishTurns: num(env, 'INTERVIEW_FORCE_FINISH_TURNS', 19),
    turnTimeoutMs: posNum(env, 'INTERVIEW_TURN_TIMEOUT_MS', 1_800_000),
    refTimeoutMs: posNum(env, 'INTERVIEW_REF_TIMEOUT_MS', 300_000),
    githubPat: env.GITHUB_PAT || undefined,
    gitlabToken: env.GITLAB_TOKEN || undefined,
    concurrency: posInt(env, 'INTERVIEW_CONCURRENCY', 1),
  };
}
