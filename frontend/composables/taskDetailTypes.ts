// 작업 상세 응답 타입 — 작업 상세 화면과 처리함이 같이 쓴다.
import type { CloseView } from './taskClose'
import type { FixView } from './taskFix'
import type { RefRepoView } from './refRepos'

export interface AnalysisView {
  markdownResult: string
  subtasksJson: string
  durationMs: number | null
  approved: boolean
  approvedBy: number | null
  approvedAt: string | null
  completedAt: string
}

export interface TaskMcpSpec {
  name: string
  url: string
  transport: string
}

export interface ImplementationView {
  prUrl: string | null
  prNumber: number | null
  headBranch: string | null
  headSha: string | null
  implementationLog: string | null
}

export interface DesignView {
  designMarkdown: string
  mockupFilesJson: string
  designProjectId: string | null
  designUrl: string | null
  rejectCount: number
  feedbackHistoryJson: string
  approved: boolean
  approvedBy: string | null
  approvedAt: string | null
  completedAt: string
}

export interface DeploymentView {
  deployUrl: string | null
  deployHostPort: number | null
  deployImage: string | null
  deployedAt: string | null
  deployLog: string | null
}

export interface EnvVar {
  key: string
  value: string
  secret: boolean
}

export interface AttachmentMeta {
  id: number
  fileName: string
  contentType: string | null
  sizeBytes: number
  createdAt: string
}

export interface StageUsageView {
  stage: string
  costUsd: number
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
}

export interface TaskResponse {
  id: number
  githubRepo: string
  repoAlias: string | null
  repoCatalogId?: number | null
  dbConnectionIds?: number[]
  githubBranch: string
  title: string
  description: string
  status: string
  statusLabel: string
  requesterId: string
  retryCount: number
  maxRetry: number
  failureReason: string | null
  mcpsExtra: TaskMcpSpec[]
  envVars: EnvVar[]
  interviewSessionId: number | null
  /** 확정 때 구현에 넘긴 인터뷰 목업 id (스펙 2026-11-03 인터뷰 목업) — 없으면 키 없음. */
  implementationMockupIds?: number[]
  createdAt: string
  updatedAt: string
  model: string
  effort: string
  designRequested: boolean
  analysis: AnalysisView | null
  design: DesignView | null
  implementation: ImplementationView | null
  deployment: DeploymentView | null
  close: CloseView | null
  attachments: AttachmentMeta[]
  stageUsage: StageUsageView[]
  totalCostUsd: number | null
  totalTokens: number | null
  implementationMode?: string
  implementationAttempt?: number
  claimedAt?: string | null
  implementationTimeoutMinutes?: number | null
  /** 읽기 전용 참조 repo (스펙 2026-10-24 §4.9) — 인터뷰 단계면 진행 중 세션의 참조(상태 포함), 그 밖에는 작업의 참조. */
  refRepos?: RefRepoView[]
  /** 질문 대화에서 이어 등록했으면 그 질문 id (스펙 2026-10-24 §4.1) — "레포" 옆 출처 링크. */
  sourceQuestionId?: number | null
  /** 요청 게시판의 개발 요청에서 등록했으면 그 요청 id (스펙 2026-11-19 §5.4). */
  sourceRequestId?: number | null
  /** 수정 요청 회차 현황 (스펙 2026-11-03 §6) — 상세 응답에만 있다. */
  fix?: FixView | null
  /** 작업 타입 (스펙 2026-11-03 §4) — null·없음은 타입 없음(기존 작업). 칩은 타입이 있을 때만 그린다. */
  taskType?: string | null
  /** 이 작업 인터뷰의 superpowers 켬/끔 (설계 2026-11-03 interview-superpowers-toggle §4) — false(간단 인터뷰)일 때만 칩을 그린다. null·없음은 켬이다. */
  interviewSuperpowersEnabled?: boolean | null
}
