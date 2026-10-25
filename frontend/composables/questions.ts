// 질문 세션 API 계약 미러 (Java QuestionSummaryResponse / InterviewResponse 부분집합) + 사이드바/헤더 순수 계산.
import type { DbConnectionChip } from './dbConnections'
import type { RefRepoView, RepoCatalogOption } from './refRepos'

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
  /** 종료된 질문의 첨부를 정리 잡이 보존 기간 뒤 지웠다 — 칩을 비활성으로 그린다(다운로드는 410). */
  expired?: boolean
}

export const EXPIRED_ATTACHMENT_TIP = '보존 기간이 지나 정리된 파일입니다'

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
  /** 레포 카탈로그 id — DB 접속정보 목록 조회 키 (스펙 2026-10-02 §5.3). 구버전 세션은 null. */
  repoCatalogId?: number | null
  /** 선택한 DB 접속정보 id — 대화 화면 DB 선택 시딩. */
  dbConnectionIds?: number[]
  /** 헤더 칩용 {id,name,dbType} — 현재 행 기준. */
  dbConnections?: DbConnectionChip[]
  /** 읽기 전용 참조 repo(고른 순서) — 헤더 아래 칩 줄(커밋·상태)과 "참조 변경" 초안 시딩용 (스펙 2026-10-22 §7.3). 구버전 백엔드는 미제공. */
  refRepos?: RefRepoView[]
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

/**
 * 질문 대화 → 작업 등록 창에 채울 값 (작업 스펙 2026-10-24 §7.2). 대상·참조는 지금 카탈로그에서 고를 수 있는 것만 옮긴다 —
 * 나머지는 비우거나(`targetMissing`) 빼서(`droppedRefs`) 등록 창이 알린다. `branch`는 질문의 브랜치 그대로이고,
 * 그 브랜치가 지금 레포에 있는지는 등록 창이 브랜치 목록을 받은 뒤에 본다.
 */
export interface TaskPrefill {
  repoCatalogId: number | null
  branch: string
  title: string
  refRepos: RefRepoView[]
  droppedRefs: RefRepoView[]
  targetMissing: boolean
}

/**
 * 질문 상세 → 작업 등록 창 프리필. 대상은 카탈로그에 있고 지원하는 호스트(`host !== 'other'`)일 때만 옮긴다.
 * 참조도 같은 조건에 대상과 다른 레포만 옮기되 커밋·받은 시각·사유는 지운다 — 작업은 인터뷰에서 처음부터 다시 받으므로
 * 등록 창에서는 아직 안 받은 대기 칩이다. 옮기지 못한 참조는 고른 순서대로 `droppedRefs`(알림에 쓰는 별칭·브랜치).
 */
export function taskPrefillFromQuestion(q: QuestionDetail, catalog: RepoCatalogOption[]): TaskPrefill {
  const usable = (id: number | null | undefined): id is number =>
    id != null && catalog.some((c) => c.id === id && c.host !== 'other')
  const target = usable(q.repoCatalogId) ? q.repoCatalogId : null
  const refRepos: RefRepoView[] = []
  const droppedRefs: RefRepoView[] = []
  for (const r of q.refRepos ?? []) {
    const bare = { catalogId: r.catalogId, alias: r.alias, branch: r.branch }
    if (usable(r.catalogId) && r.catalogId !== target) refRepos.push(bare)
    else droppedRefs.push(bare)
  }
  return {
    repoCatalogId: target,
    branch: q.githubBranch,
    title: q.title,
    refRepos,
    droppedRefs,
    targetMissing: target == null,
  }
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
