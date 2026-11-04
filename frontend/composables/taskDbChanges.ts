// 작업 상세 "DB 변경" (스펙 2026-11-03 §6.5) — 구현 단계에서 개발 DB에 한 쓰기 기록의 타입·라벨·묶기·건수 문구.
// 순수 함수 위주(화면 상태 없음). 종료 승인 확인창이 쓰는 건수 조회 하나만 API를 부른다.

/** `GET /api/tasks/{id}/db-changes` 한 건 (서버 `DbChangeView`) — 관리자 실행·실행 안 함의 응답 본문도 같은 모양(갱신된 행). */
export interface DbChangeView {
  id: number
  /** 연결을 지웠으면 null — connectionName은 기록 시점의 이름이라 그래도 보인다. */
  connectionId: number | null
  connectionName: string | null
  /** 구현 시도 번호. */
  attempt: number
  /** 수정 요청 회차에서 남긴 기록이면 그 요청 id. */
  fixRequestId: number | null
  sqlText: string
  /** EXECUTED·DEFERRED·DISMISSED·REJECTED·FAILED — 다른 버전 서버의 모르는 값도 문자열로 온다. */
  classification: 'EXECUTED' | 'DEFERRED' | 'DISMISSED' | 'REJECTED' | 'FAILED' | string
  reason: string | null
  affectedRows: number | null
  error: string | null
  createdAt: string
  /**
   * 세션이 바로 실행한 행은 실행을 마친 시각(null이면 실행 중이거나 마무리 저장이 안 된 것),
   * 승인 대기 행은 관리자가 실행하거나 실행 안 함으로 바꾼 시각.
   */
  executedAt: string | null
  /** 관리자가 처리한 행의 사용자 id — 세션이 바로 실행한 행은 null. */
  executedBy: string | null
}

/** 분류 라벨 — 화면 문구. */
export const DB_CHANGE_LABELS: Record<string, string> = {
  EXECUTED: '실행됨',
  DEFERRED: '승인 대기',
  DISMISSED: '실행 안 함',
  REJECTED: '거부됨',
  FAILED: '실패',
}

/** 모르는 분류는 그 값 그대로. */
export function dbChangeLabel(classification: string): string {
  return DB_CHANGE_LABELS[classification] ?? classification
}

/** 승인 대기 SQL을 관리자가 실행하기 전 확인창에 붙이는 안내 — 병합 여부로 막지는 않는다(스펙 §6.5). */
export const EXECUTE_ADVICE = 'PR 병합 뒤 실행을 권장합니다 — 같은 DB를 쓰는 다른 화면이 아직 옛 컬럼을 쓸 수 있습니다'

/** 응답에서 승인 대기(DEFERRED) 건수 — 배열이 아니면(프록시 오류 페이지 등) 0, 모양이 깨진 원소는 건너뛴다. */
export function deferredCount(list: unknown): number {
  if (!Array.isArray(list)) return 0
  return list.filter((r) => r != null && typeof r === 'object' && (r as { classification?: unknown }).classification === 'DEFERRED').length
}

/**
 * 종료 승인 확인창에 붙이는 한 줄 — 앞에 공백이 있어 앞 문장 뒤에 그대로 이어 붙인다. 0건(이하·숫자 아님)이면 빈 문자열.
 * 목록 배지·필터는 두지 않는다(스펙 §6.5).
 */
export function deferredWarning(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return ''
  return ` 승인 대기 SQL ${n}건 — 병합 뒤 실행 필요`
}

/**
 * 작업의 승인 대기 SQL 건수로 만든 종료 승인 경고 — 조회가 실패하거나 배열이 아닌 응답이면 빈 문자열이다.
 * 경고는 안내일 뿐이라 건수를 못 읽어도 승인은 그대로 진행된다.
 */
export async function fetchDeferredWarning(taskId: number | string): Promise<string> {
  try {
    return deferredWarning(deferredCount(await useApi<unknown>(`/api/tasks/${taskId}/db-changes`)))
  } catch {
    return ''
  }
}

export interface DbChangeGroup {
  /** v-for 키 — 연결 id, 지운 연결은 이름. */
  key: string
  name: string
  items: DbChangeView[]
}

/** 이름이 없는 연결(기록 시점 이름이 비었음)의 표시. */
const DELETED_CONNECTION = '(삭제된 연결)'

/**
 * 연결별 묶음 — 묶음 순서는 시간순으로 먼저 나온 연결이 앞, 묶음 안은 시간순(같은 시각이면 id순). 서버가 시간순으로 주지만
 * 화면이 한 번 더 정렬해 순서를 보장한다. 배열이 아니면 빈 목록.
 */
export function groupByConnection(list: DbChangeView[]): DbChangeGroup[] {
  if (!Array.isArray(list)) return []
  const sorted = [...list].sort((a, b) => {
    const t = Date.parse(a.createdAt) - Date.parse(b.createdAt)
    return Number.isNaN(t) || t === 0 ? a.id - b.id : t
  })
  const groups = new Map<string, DbChangeGroup>()
  for (const r of sorted) {
    const name = r.connectionName ?? DELETED_CONNECTION
    const key = r.connectionId != null ? `c${r.connectionId}` : `n${name}`
    let g = groups.get(key)
    if (!g) {
      g = { key, name, items: [] }
      groups.set(key, g)
    }
    g.items.push(r)
  }
  return [...groups.values()]
}
