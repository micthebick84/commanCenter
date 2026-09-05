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
