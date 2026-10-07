// 배포 다이얼로그(DeployEnvDialog)의 순수 판정 함수 (스펙 2026-10-07 §8). 화면 상태와 떼어 단위 테스트한다.

export interface EnvVar {
  key: string
  value: string
  secret: boolean
}

export type EnvRowSource = 'SAVED' | 'TEMPLATE' | 'PREVIOUS'

export interface SuggestedRow extends EnvVar {
  description: string
  required: boolean
  source: EnvRowSource
  valueFromPrevious: boolean
}

/** GET /api/tasks/{id}/deploy-env 응답 (스펙 §6.1). */
export interface DeployEnvSuggestion {
  rows: SuggestedRow[]
  templateCount: number
  previousTaskId: number | null
}

/** 편집용 행 — fixedKey면 KEY를 라벨로만 보여 준다(서버가 채운 행). id는 v-for의 안정 키. */
export interface EnvRow extends EnvVar {
  id: number
  reveal: boolean
  fixedKey: boolean
  description: string
  required: boolean
  valueFromPrevious: boolean
}

export function isSuggestion(v: unknown): v is DeployEnvSuggestion {
  return !!v && typeof v === 'object' && Array.isArray((v as DeployEnvSuggestion).rows)
}

export function rowsFromSuggestion(s: DeployEnvSuggestion, nextId: () => number): EnvRow[] {
  return s.rows.map((r) => ({
    key: r.key,
    value: r.value ?? '',
    secret: !!r.secret,
    id: nextId(),
    reveal: false,
    fixedKey: true,
    description: r.description ?? '',
    required: !!r.required,
    valueFromPrevious: !!r.valueFromPrevious,
  }))
}

/** 추천을 못 받았을 때(대체 경로) — 저장된 env를 지금처럼 KEY까지 편집 가능한 행으로. */
export function rowsFromEnvVars(envVars: EnvVar[] | null | undefined, nextId: () => number): EnvRow[] {
  return (envVars ?? []).map((e) => ({
    key: e.key,
    value: e.value,
    secret: e.secret,
    id: nextId(),
    reveal: false,
    fixedKey: false,
    description: '',
    required: false,
    valueFromPrevious: false,
  }))
}

export function blankRow(nextId: () => number): EnvRow {
  return {
    key: '',
    value: '',
    secret: false,
    id: nextId(),
    reveal: false,
    fixedKey: false,
    description: '',
    required: false,
    valueFromPrevious: false,
  }
}

const isEmpty = (s: string) => s.trim() === ''

/** 전송 형식 — KEY는 trim, 빈 KEY·빈 값 행은 뺀다(빈 문자열이 앱 기본값을 덮지 않게). 값은 그대로. */
export function toWireEnvVars(rows: EnvRow[]): EnvVar[] {
  return rows
    .map((r) => ({ key: r.key.trim(), value: r.value, secret: r.secret }))
    .filter((r) => r.key !== '' && !isEmpty(r.value))
}

/** 필수인데 값이 빈 행의 KEY 목록 — 막지 않고 "그래도 배포"로 경고만. */
export function missingRequired(rows: EnvRow[]): string[] {
  return rows.filter((r) => r.required && isEmpty(r.value)).map((r) => r.key)
}

/** 상단 안내 — 추출 개수·직전 배포 출처. 둘 다 없으면 null(기본 안내만). */
export function suggestionCaption(s: DeployEnvSuggestion): string | null {
  const parts: string[] = []
  if (s.templateCount > 0) parts.push(`구현 시 추출한 변수 ${s.templateCount}개`)
  if (s.previousTaskId != null) parts.push(`task #${s.previousTaskId} 배포에서 값을 가져왔습니다`)
  return parts.length ? parts.join(' · ') : null
}
