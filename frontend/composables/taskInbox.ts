// 처리함(관리자가 확인해야 할 작업을 한 줄로 세워 차례로 처리하는 화면)의 순수 로직.
// 화면·API 호출은 없다 — 묶음 분류, 처리한 건 숨기기, 다음 건 고르기, 단축키 판정, 자동 이동 저장값.

export type InboxGroupKey = 'answer' | 'plan' | 'approve' | 'design' | 'fix' | 'close' | 'failed'

/**
 * 처리함 묶음. 순서가 화면 순서다.
 * attentionGroup(관리자)이 '확인 필요'로 보는 상태는 정확히 한 묶음에 들어야 한다(시험이 지킨다).
 * COMPLETED(분석 완료 — 구현 승인 대기)는 계획을 확인하고 승인하는 단계라 '계획 확인'에 둔다.
 */
export const INBOX_GROUPS: { key: InboxGroupKey; label: string; statuses: string[] }[] = [
  { key: 'answer', label: '답변 대기', statuses: ['INTERVIEW_INPUT'] },
  { key: 'plan', label: '계획 확인', statuses: ['INTERVIEW_REVIEW', 'COMPLETED'] },
  { key: 'approve', label: '인터뷰 시작 승인', statuses: ['AWAITING_APPROVAL'] },
  { key: 'design', label: '디자인 검토', statuses: ['DESIGN_REVIEW'] },
  { key: 'fix', label: '수정 요청 승인', statuses: ['FIX_REVIEW'] },
  { key: 'close', label: '종료 승인', statuses: ['CLOSE_REVIEW'] },
  {
    key: 'failed',
    label: '실패',
    statuses: ['FAILED', 'IMPLEMENTATION_FAILED', 'DESIGN_FAILED', 'DEPLOY_FAILED', 'DEPLOY_LOST', 'CLOSE_FAILED'],
  },
]

export interface InboxRow {
  id: number
  status: string
  statusLabel: string
  updatedAt: string
  title: string
  repoAlias: string | null
  githubRepo: string
  taskType?: string | null
  retryCount: number
  maxRetry: number
}

/** 처리함에 올릴 만한 상태인가. 재시도 한도를 다 쓴 FAILED는 사람이 할 수 있는 게 없어 뺀다. */
export function isInboxCandidate(t: Pick<InboxRow, 'status' | 'retryCount' | 'maxRetry'>): boolean {
  if (!INBOX_GROUPS.some((g) => g.statuses.includes(t.status))) return false
  if (t.status === 'FAILED' && t.retryCount >= t.maxRetry) return false
  return true
}

/** 처리했다고 표시한 건. 같은 status·updatedAt인 동안만 숨긴다. */
export interface HandledMark {
  id: number
  status: string
  updatedAt: string
}

export function isHidden(row: Pick<InboxRow, 'id' | 'status' | 'updatedAt'>, handled: HandledMark[]): boolean {
  return handled.some((h) => h.id === row.id && h.status === row.status && h.updatedAt === row.updatedAt)
}

/** 행이 사라졌거나 status·updatedAt이 바뀐 기록은 지운다. */
export function pruneHandled(
  handled: HandledMark[],
  rows: Pick<InboxRow, 'id' | 'status' | 'updatedAt'>[],
): HandledMark[] {
  return handled.filter((h) => rows.some((r) => r.id === h.id && r.status === h.status && r.updatedAt === h.updatedAt))
}

export interface InboxGroup<T> {
  key: InboxGroupKey
  label: string
  items: T[]
}

/** 후보만 남기고 숨긴 건은 빼서 묶음 순서로 나눈다. 묶음 안은 updatedAt 오름차순(오래 기다린 건이 위), 빈 묶음은 제외. */
export function buildInbox<T extends InboxRow>(rows: T[], handled: HandledMark[]): InboxGroup<T>[] {
  const visible = rows.filter((r) => isInboxCandidate(r) && !isHidden(r, handled))
  return INBOX_GROUPS.map((g) => ({
    key: g.key,
    label: g.label,
    items: visible
      .filter((r) => g.statuses.includes(r.status))
      .sort((a, b) => Date.parse(a.updatedAt) - Date.parse(b.updatedAt)),
  })).filter((g) => g.items.length > 0)
}

export function flattenInbox<T>(groups: InboxGroup<T>[]): T[] {
  return groups.flatMap((g) => g.items)
}

/** 처리 뒤 이어서 볼 건 — 제외할 건을 뺀 맨 위. 없으면 null. */
export function pickNext(flat: { id: number }[], excludeId?: number | null): number | null {
  const next = flat.find((r) => r.id !== excludeId)
  return next ? next.id : null
}

/** 현재 건의 앞·뒤 이웃. 끝에서 감싸지 않는다. 현재 건이 목록에 없으면 맨 위. */
export function neighborId(flat: { id: number }[], currentId: number | null, dir: 1 | -1): number | null {
  const idx = currentId == null ? -1 : flat.findIndex((r) => r.id === currentId)
  if (idx < 0) return flat.length ? flat[0].id : null
  const n = flat[idx + dir]
  return n ? n.id : null
}

export const INBOX_PAGE_SIZE = 200

export function inboxListParams(): { mine: string; size: number; sort: string } {
  return { mine: 'false', size: INBOX_PAGE_SIZE, sort: 'updatedAt,desc' }
}

/** 한 쪽(200건)에 다 담기지 않았는가. */
export function isTruncated(page: { content: unknown[]; totalElements?: number } | null): boolean {
  if (!page) return false
  return page.totalElements != null && page.totalElements > page.content.length
}

export function countInbox(rows: Pick<InboxRow, 'status' | 'retryCount' | 'maxRetry'>[]): number {
  return rows.filter(isInboxCandidate).length
}

/** 목록 이동 단축키(j/k)를 무시할 상황인가. */
export function shouldIgnoreShortcut(
  e: Pick<KeyboardEvent, 'key' | 'target' | 'altKey' | 'ctrlKey' | 'metaKey'>,
  dialogOpen: boolean,
): boolean {
  if (e.key !== 'j' && e.key !== 'k') return true
  if (e.altKey || e.ctrlKey || e.metaKey) return true
  if (dialogOpen) return true
  const t = e.target as HTMLElement | null
  if (!t || typeof t.closest !== 'function') return false
  if (t.isContentEditable) return true
  return !!t.closest('input, textarea, select, [contenteditable]')
}

export const AUTO_NEXT_KEY = 'netis-maker-inbox-auto-next'

/** 처리 뒤 다음 건으로 자동 이동할지. 저장값이 '0'일 때만 끈다. 저장소를 못 읽으면 켬. */
export function readAutoNext(): boolean {
  try {
    return localStorage.getItem(AUTO_NEXT_KEY) !== '0'
  } catch {
    return true
  }
}

export function writeAutoNext(on: boolean): void {
  try {
    if (on) localStorage.removeItem(AUTO_NEXT_KEY)
    else localStorage.setItem(AUTO_NEXT_KEY, '0')
  } catch {
    // 저장소를 쓸 수 없으면 이번 화면에서만 유지
  }
}
