// 질문 세션 API 계약 미러 (Java QuestionSummaryResponse / InterviewResponse 부분집합) + 사이드바/헤더 순수 계산.
export interface QuestionSummary {
  id: number
  title: string
  githubRepo: string
  githubBranch: string
  repoAlias: string | null
  requesterId: string
  status: string
  statusName: string
  model: string
  effort: string
  totalCostUsd: number | null
  contextTokens: number | null
  contextWindow: number | null
  createdAt: string
  updatedAt: string
}

/** 첨부 메타(Java AttachmentView) — 절대경로·추출경로는 브라우저로 나오지 않는다 (스펙 2026-09-13 §5.1). */
export interface AttachmentView {
  id: number
  fileName: string
  contentType: string | null
  sizeBytes: number
}

/** GET /api/questions/{id} (InterviewResponse) 중 대화 화면이 쓰는 부분집합. */
export interface QuestionDetail {
  id: number
  title: string
  githubRepo: string
  githubBranch: string
  statusName: string
  model: string | null
  effort: string | null
  totalCostUsd: number | null
  contextTokens: number | null
  contextWindow: number | null
  /** 세션에 현재 적용된 관리자 카탈로그 id — McpPicker 1회 시딩용 (스펙 2026-09-13 §4). 구버전 백엔드는 미제공. */
  mcpCatalogIds?: number[]
  /** 등록 시(킥오프) 첨부 — 첫 질문은 말풍선이 없으므로 헤더 아래 칩 줄로 (스펙 2026-09-13 §2). */
  attachments?: AttachmentView[]
  turns?: Array<{
    seq: number
    role: string
    kind: string
    content: string
    /** 추가 질문(user 턴)에 붙인 첨부 — 사용자 말풍선 아래 칩 */
    attachments?: AttachmentView[]
  }>
}

export interface QuestionGroup {
  key: 'today' | 'week' | 'older'
  label: string
  items: QuestionSummary[]
}

/** updatedAt 기준 오늘/지난 7일/이전 그룹. 빈 그룹 제외, 입력 순서(서버: createdAt DESC) 유지. */
export function groupByRecency(items: QuestionSummary[], now = Date.now()): QuestionGroup[] {
  const startOfToday = new Date(now)
  startOfToday.setHours(0, 0, 0, 0)
  const weekAgo = now - 7 * 86_400_000
  const groups: QuestionGroup[] = [
    { key: 'today', label: '오늘', items: [] },
    { key: 'week', label: '지난 7일', items: [] },
    { key: 'older', label: '이전', items: [] },
  ]
  for (const item of items) {
    const t = new Date(item.updatedAt).getTime()
    const g = t >= startOfToday.getTime() ? groups[0]! : t >= weekAgo ? groups[1]! : groups[2]!
    g.items.push(item)
  }
  return groups.filter((g) => g.items.length > 0)
}

/** 컨텍스트 사용률 0..100 정수. 미보고(null)거나 창이 0이면 null → 칩 숨김 (스펙 2026-09-05 §4.2). */
export function contextPercent(
  tokens: number | null | undefined,
  window: number | null | undefined,
): number | null {
  if (tokens == null || window == null || window <= 0) return null
  return Math.max(0, Math.min(100, Math.round((tokens / window) * 100)))
}
