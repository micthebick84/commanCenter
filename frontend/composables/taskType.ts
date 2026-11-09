// 작업 타입 (스펙 2026-11-03 §4) — 서버 enum 이름을 그대로 주고받는다. 타입 없음은 null(기존 작업·옛 화면이 등록한 작업).
// 타입은 권한을 가르지 않는다 — 등록 창·승인 창의 select, 목록 카드·상세의 칩, 목록 필터가 쓴다.

export type TaskTypeValue = 'NEW_FEATURE' | 'FEATURE_CHANGE' | 'BUG_FIX' | 'DB_WORK'

/** 고를 수 있는 타입 — 등록 창·승인 창 select의 옵션(타입 없음은 고르는 값이 아니다). */
export const TASK_TYPES: { value: TaskTypeValue; label: string }[] = [
  { value: 'NEW_FEATURE', label: '신규 개발' },
  { value: 'FEATURE_CHANGE', label: '기능 수정' },
  { value: 'BUG_FIX', label: '버그 픽스' },
  { value: 'DB_WORK', label: 'DB 작업' },
]

/** 타입 없음의 화면 이름 — 필터 select와 승인 이력(서버 `TaskType.labelOf`)이 쓴다. 카드·상세는 타입 없음이면 칩을 그리지 않는다. */
export const NO_TYPE_LABEL = '타입 없음'

/** `GET /api/tasks?type=` 의 "타입 없음만" 값 (서버 `TaskTypeFilter.NONE_WIRE`). */
export const NO_TYPE_FILTER = 'NONE'

/**
 * 화면 이름 — null·undefined(·빈 문자열)는 '타입 없음', 모르는 값(다른 버전 API)은 그 값 그대로.
 */
export function taskTypeLabel(v: string | null | undefined): string {
  if (v == null || v === '') return NO_TYPE_LABEL
  return TASK_TYPES.find((t) => t.value === v)?.label ?? v
}

/** 목록 타입 필터 옵션 — `null` = 전체 타입(파라미터를 보내지 않는다), 네 타입, `'NONE'` = 타입 없음. */
export const TASK_TYPE_FILTERS: { value: string | null; label: string }[] = [
  { value: null, label: '전체 타입' },
  ...TASK_TYPES,
  { value: NO_TYPE_FILTER, label: NO_TYPE_LABEL },
]

/**
 * 받은 목록을 화면에서 거를 때 — 서버 `type` 파라미터와 같은 뜻. 모바일 목록은 서버에 type을 보내지 않고 이것으로 거른다.
 * 응답의 `taskType`이 null이든 키가 없든 타입 없음이다.
 */
export function matchesTypeFilter(task: { taskType?: string | null }, filter: string | null): boolean {
  if (filter == null) return true
  if (filter === NO_TYPE_FILTER) return !task.taskType
  return task.taskType === filter
}
