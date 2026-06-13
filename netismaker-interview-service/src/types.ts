/** One persisted turn echoed back on the claim (plan 01 InterviewTurn). */
export interface InterviewTurn {
  seq: number;
  role: string;
  kind: string;
  content: string;
  replyToSeq: number | null;
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
  /** brainstorming | writing-plans — drives skill-dispatch shim */
  currentPhase: 'brainstorming' | 'writing-plans';
  /** Repo checkout dir; cwd for the SDK turn. Java assigns + persists this on first claim
   *  and returns the SAME value on every resume claim (resume is cwd-pinned, spike 02). */
  workDir: string;
  /** The newest user answer to inject this turn (null on the very first claim) */
  lastAnswer: string | null;
  /** seq of the answer above, for idempotency/logging */
  replyToSeq: number | null;
  /** Extra MCP servers to merge for this session (JSONB snapshot) */
  mcpsExtra: unknown;
  /** Prior turns (questions + answers) for context/logging */
  turns: InterviewTurn[];
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
