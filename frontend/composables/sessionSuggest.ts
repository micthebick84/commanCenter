// 질문 대화 중 Claude가 내는 제안(DB 연결·참조 레포)의 화면 공용 타입과 순수 함수.
// 제안은 제안일 뿐 — 붙이는 길은 기존 ask 바디(dbConnectionIds·refRepos) 하나다.
import type { RefRepoInput } from './refRepos'

export type SuggestionKind = 'DB' | 'REF_REPO'
export type SuggestionStatus = 'PENDING' | 'ACCEPTED' | 'DISMISSED' | 'SUPERSEDED'
export interface SuggestDbOption {
  id: number
  name: string
  dbType: string
}
export interface SessionSuggestion {
  id: number
  turnSeq: number
  kind: SuggestionKind
  status: SuggestionStatus
  reason: string
  targets: string[]
  dbConnectionId: number | null
  repoCatalogId: number | null
  repoAlias: string | null
  defaultBranch: string | null
  dbOptions: SuggestDbOption[]
  dbSlotsLeft: number
  refSlotsLeft: number
}
export interface SuggestPicks {
  dbConnectionIds: number[]
  refs: RefRepoInput[]
  suggestionIds: number[]
}

export const SUGGEST_FOLLOW_UP = {
  db: '방금 연결한 DB로 확인해서 앞의 답을 보완해 주세요.',
  ref: '방금 추가한 참조 레포를 보고 앞의 답을 보완해 주세요.',
  both: '방금 연결한 DB와 추가한 참조 레포를 보고 앞의 답을 보완해 주세요.',
} as const

/** id가 같으면 교체, 없으면 끝에 더한다. 입력 배열은 건드리지 않는다. */
export function upsertSuggestion(list: SessionSuggestion[], s: SessionSuggestion): SessionSuggestion[] {
  const idx = list.findIndex((x) => x.id === s.id)
  if (idx < 0) return [...list, s]
  const next = list.slice()
  next[idx] = s
  return next
}

export function pendingForTurn(list: SessionSuggestion[], turnSeq: number): SessionSuggestion[] {
  return list.filter((s) => s.turnSeq === turnSeq && s.status === 'PENDING')
}

export function decidedForTurn(list: SessionSuggestion[], turnSeq: number): SessionSuggestion[] {
  return list.filter((s) => s.turnSeq === turnSeq && s.status !== 'PENDING')
}

/** 카드가 처음 체크해 둘 DB 연결 id. */
export function initialDbPicks(s: SessionSuggestion): number[] {
  if (s.dbSlotsLeft <= 0) return []
  if (s.dbConnectionId != null && s.dbOptions.some((o) => o.id === s.dbConnectionId)) {
    return [s.dbConnectionId]
  }
  if (s.dbOptions.length === 1) return [s.dbOptions[0].id]
  return []
}

export function canPickMore(pickedCount: number, slotsLeft: number): boolean {
  return pickedCount < slotsLeft
}

export function followUpText(hasDb: boolean, hasRef: boolean): string {
  if (hasDb && hasRef) return SUGGEST_FOLLOW_UP.both
  if (hasDb) return SUGGEST_FOLLOW_UP.db
  if (hasRef) return SUGGEST_FOLLOW_UP.ref
  return ''
}

/** 지난 제안 한 줄. PENDING(오래된 답변의 남은 제안)은 연결하지 않은 것으로 읽는다. */
export function decidedLine(s: SessionSuggestion): string {
  const outcome = s.status === 'ACCEPTED' ? '연결함' : s.status === 'DISMISSED' ? '닫음' : '연결하지 않음'
  const subject = s.kind === 'DB' ? 'DB 연결' : `참조 레포 ${s.repoAlias ?? '(삭제된 레포)'}`
  return `제안: ${subject} — ${outcome}`
}

/** 초안 뒤에 새 id를 붙이고 중복을 뺀다(순서 유지). */
export function mergeDbIds(draft: number[], picked: number[]): number[] {
  return [...new Set([...draft, ...picked])]
}

/** repoCatalogId 기준 합치기 — 같은 레포면 초안의 브랜치가 이긴다. */
export function mergeRefInputs(draft: RefRepoInput[], picked: RefRepoInput[]): RefRepoInput[] {
  const have = new Set(draft.map((r) => r.repoCatalogId))
  return [...draft, ...picked.filter((r) => !have.has(r.repoCatalogId))]
}
