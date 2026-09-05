/** 세션 종류 — Java InterviewKind. QUESTION = Q&A 전용(superpowers 미로드, default-deny 게이트, plan 경로 없음). */
export type SessionKind = 'INTERVIEW' | 'QUESTION';

/** One persisted turn echoed back on the claim (plan 01 InterviewTurn). */
export interface InterviewTurn {
  seq: number;
  role: string;
  kind: string;
  content: string;
  replyToSeq: number | null;
}

/** Java TaskMcpSpec — one element of the mcps_extra JSONB array snapshot. */
export interface McpSpec {
  name: string;
  url: string;
  transport: string;
}

/** Java InterviewClaimResponse.AttachmentRef — 등록 시 업로드된 첨부 1건. */
export interface AttachmentRef {
  id: number;
  fileName: string;
  /** 공유 FS 절대경로 (workDir과 동일한 단일 호스트 전제) — 에이전트가 Read로 직접 읽는다. */
  absolutePath: string;
  contentType: string | null;
  sizeBytes: number;
}

/**
 * Returned by POST /worker/interviews/claim?workerId=... — the Java `InterviewClaimResponse`
 * record. All pending state lives here — never in memory.
 */
export interface InterviewClaimResponse {
  sessionId: number;
  githubRepo: string;
  githubBranch: string;
  title: string;
  description: string;
  /** null => fresh brainstorming start; present => options.resume target */
  claudeSessionId: string | null;
  /** null on a fresh (never-questioned) claim; 'brainstorming' after first question; 'writing-plans' after plan.
   *  Java sets it only in recordQuestion/recordPlan, so claim() returns null until then. */
  currentPhase: 'brainstorming' | 'writing-plans' | null;
  /** Repo checkout dir; cwd for the SDK turn. Java assigns + persists this on first claim
   *  and returns the SAME value on every resume claim (resume is cwd-pinned, spike 02). */
  workDir: string;
  /** The newest user answer to inject this turn (null on the very first claim) */
  lastAnswer: string | null;
  /** seq of the answer above, for idempotency/logging */
  replyToSeq: number | null;
  /** Extra MCP servers to merge for this session (JSONB snapshot). Java never sends null — [] or populated. */
  mcpsExtra: McpSpec[];
  /** Prior turns (questions + answers) for context/logging */
  turns: InterviewTurn[];
  /** Java가 보내는 선택 모델/effort (NOT NULL — 기본 claude-opus-5/high). */
  model: string;
  effort: 'low' | 'medium' | 'high' | 'xhigh' | 'max';
  /** 세션 누적 SHADOW 비용(이전 턴들까지). CostGuard를 이 값으로 시드해 가드가 세션 전체에 걸쳐
   *  누적되도록 한다(단일 턴이 아니라). Java는 NOT NULL — 신규 claim은 0. */
  totalCostUsd: number;
  /**
   * 등록 시 업로드된 첨부. 신버전 Java는 항상 []-이상을 보내지만 구버전 백엔드는 필드
   * 자체가 없다 — 읽는 쪽은 무조건 Array.isArray 가드 (스펙 §7/§11, mcpsExtra 선례).
   */
  attachments?: AttachmentRef[];
  /**
   * 세션 종류. 구버전 백엔드는 필드가 없다 — 미존재는 INTERVIEW로 취급(attachments 선례).
   * 러너는 `kind === 'QUESTION'`로만 판정한다.
   */
  kind?: SessionKind;
}

/** SSE activity 와이어 계약의 활동 type (스펙 §4.1). */
export type ActivityEventType = 'tool' | 'text' | 'thinking';

/** relay → ActivityPoster 콜백 입력 (seq는 poster가 부여). */
export interface ActivityInput {
  type: ActivityEventType;
  /** tool 전용: 'Read'|'Grep'|'Glob'|'Skill'|'환경 준비'… */
  label?: string;
  /** tool 전용: 파일경로/패턴 (workDir prefix 제거, 120자 절단) */
  detail?: string;
  /** text/thinking 전용: 델타 텍스트 청크 */
  content?: string;
}

/** 와이어 ActivityEvent = ActivityInput + 워커 run() 내 단조증가 seq. */
export interface ActivityEvent extends ActivityInput {
  seq: number;
}

/** Body for POST /worker/interviews/{id}/activity — 배치 최대 100건 (Java @Size와 동기). */
export interface WorkerActivityRequest {
  events: ActivityEvent[];
}

/** Body for POST /worker/interviews/{id}/question */
export interface WorkerQuestionRequest {
  content: string;
  claudeSessionId: string;
  kind: 'question' | 'design' | 'gate' | 'note';
  /** SHADOW cost for this turn (quota accounting, not dollars). */
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
  /** 마지막 최상위 assistant 메시지의 컨텍스트 토큰(input+cache_creation+cache_read). 없으면 null (스펙 2026-09-05 §4.2). */
  contextTokens: number | null;
  /** result.modelUsage 주 모델의 contextWindow. 없으면 null. */
  contextWindow: number | null;
}

/**
 * Body for POST /worker/interviews/{id}/plan.
 * planJson is a **serialized JSON string** — Java stores it as TEXT/JSONB and the frontend
 * parses it on use. The TS service must call JSON.stringify(harvest.planJson) before sending.
 */
export interface WorkerPlanRequest {
  designMarkdown: string;
  planMarkdown: string;
  /** Serialized JSON string (Array<{task,title}>). Java stores as text; frontend parses on use. */
  planJson: string;
  /** SHADOW cost for this turn (quota accounting, not dollars). */
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
  durationMs: number;
}

/**
 * SDK SDKRateLimitInfo 부분집합 (@anthropic-ai/claude-agent-sdk@0.2.117 sdk.d.ts:2923).
 * relay는 그대로 넘기고, 정규화는 runner/rateLimitReport.ts가 한다 (스펙 2026-09-05 §4.3).
 */
export interface RateLimitInfo {
  status?: 'allowed' | 'allowed_warning' | 'rejected';
  resetsAt?: number;
  rateLimitType?: 'five_hour' | 'seven_day' | 'seven_day_opus' | 'seven_day_sonnet' | 'overage';
  /** 구형(flat) 이벤트에만 존재. 실측(CLI 2.1.261)에서는 없고 unifiedWindows 안에 창별로 온다. */
  utilization?: number;
  isUsingOverage?: boolean;
  /**
   * sdk.d.ts 미선언 — 실측(test/fixtures/RATE_LIMIT_FINDINGS.md ①): 한 이벤트에 five_hour/seven_day 창이 동봉되고
   * 각 창의 utilization(0..1 분수)/resetsAt(epoch 초)은 여기에만 있다. 키는 rateLimitType 유니온과 같은 문자열.
   */
  unifiedWindows?: Record<string, { utilization?: number; resetsAt?: number } | undefined>;
}

/** Body for POST /worker/usage/rate-limits?workerId=… (Java WorkerRateLimitRequest). */
export interface WorkerRateLimitRequest {
  limitType: string;
  status: string;
  /** 0..1 분수 (정규화 완료). */
  utilization: number;
  /** ISO-8601 또는 null. */
  resetsAt: string | null;
  isUsingOverage: boolean;
}
