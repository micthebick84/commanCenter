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
}

/** Body for POST /worker/interviews/{id}/question */
export interface WorkerQuestionRequest {
  content: string;
  claudeSessionId: string;
  kind: 'question' | 'design' | 'gate' | 'note';
  /** SHADOW cost for this turn (quota accounting, not dollars). */
  costUsd: number;
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
  durationMs: number;
}
