// 활성 인터뷰 목록 타입 + 새로고침 후 재오픈 결정(순수). GET /api/interviews/active 응답 형태.
// status=한글 dbValue(표시), statusName=영문 enum(로직) — 백엔드 InterviewSummary와 동일.
export interface InterviewSummary {
  id: number
  status: string
  statusName: string
  title: string
  githubRepo: string
  githubBranch: string
  currentPhase: string | null
  lastActivityAt: string
  createdAt: string
}

export type ResumeDecision =
  | { mode: 'none' }
  | { mode: 'auto'; id: number }
  | { mode: 'pick'; candidates: InterviewSummary[] }

// 하이브리드: 0건=무동작, 1건=자동 재오픈, 2건 이상=선택 목록.
// 목록은 백엔드에서 lastActivityAt DESC로 정렬되어 오므로 첫 항목이 가장 최근.
export function decideResume(list: InterviewSummary[] | null | undefined): ResumeDecision {
  const arr = list ?? []
  if (arr.length === 0) return { mode: 'none' }
  if (arr.length === 1) return { mode: 'auto', id: arr[0].id }
  return { mode: 'pick', candidates: arr }
}
