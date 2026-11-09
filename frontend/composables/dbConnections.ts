// 질문 세션 DB 접속정보 (스펙 2026-10-02 §7). 비밀번호는 서버가 절대 돌려주지 않는다 — 수정 폼은 비워 두면 유지.
export type DbType = 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE'
export type DbScope = 'REPO' | 'USER'

export interface DbConnectionView {
  id: number
  scope: DbScope
  repoCatalogId: number
  name: string
  dbType: DbType
  host: string
  port: number
  databaseName: string
  username: string
  enabled: boolean
  mine: boolean
  /** 구현 단계 쓰기 허용 표시(스펙 2026-11-03 §6.1 조건 4) — 관리자가 켠 REPO 연결만 true, USER 연결은 늘 false. */
  allowWrite: boolean
}

export interface DbConnectionList {
  enabled: boolean
  items: DbConnectionView[]
  /** 그 레포에서 구현 단계 쓰기가 열릴 수 있는지(전역 스위치 켜짐 + 자동 마이그레이션 레포 아님). 옛 응답에는 없다 — 모르면 안내하지 않는다. */
  writeEnabled?: boolean
}

export interface DbConnectionChip {
  id: number
  name: string
  dbType: string
}

export interface DbConnectionInput {
  scope: DbScope
  repoCatalogId: number
  name: string
  dbType: DbType
  host: string
  port: number
  databaseName: string
  username: string
  password: string
  enabled?: boolean
}

export const DB_TYPES: Array<{ value: DbType; label: string; defaultPort: number }> = [
  { value: 'POSTGRESQL', label: 'PostgreSQL', defaultPort: 5432 },
  { value: 'MYSQL', label: 'MySQL', defaultPort: 3306 },
  { value: 'MARIADB', label: 'MariaDB', defaultPort: 3306 },
  { value: 'ORACLE', label: 'Oracle', defaultPort: 1521 },
]

export const MAX_DB_SELECTION = 3

export function dbTypeLabel(t: string): string {
  return DB_TYPES.find((d) => d.value === t)?.label ?? t
}

export async function fetchDbConnections(repoCatalogId: number): Promise<DbConnectionList> {
  try {
    const r = await useApi<DbConnectionList>(`/api/db-connections?repoCatalogId=${repoCatalogId}`)
    return r && typeof r === 'object' && Array.isArray(r.items) ? r : { enabled: false, items: [] }
  } catch {
    return { enabled: false, items: [] }
  }
}

export function saveDbConnection(input: DbConnectionInput, id?: number) {
  return id == null
    ? useApi<DbConnectionView>('/api/db-connections', { method: 'POST', body: input })
    : useApi<DbConnectionView>(`/api/db-connections/${id}`, { method: 'PUT', body: input })
}

/** 쓰기 허용 표시만 바꾸는 관리자 전용 경로 — 일반 등록·수정 바디에는 이 값이 없다. */
export function setDbConnectionAllowWrite(id: number, allowWrite: boolean) {
  return useApi<DbConnectionView>(`/api/db-connections/${id}/allow-write`, { method: 'PUT', body: { allowWrite } })
}

export function deleteDbConnection(id: number) {
  return useApi(`/api/db-connections/${id}`, { method: 'DELETE' })
}

export function testDbConnection(body: Record<string, unknown>) {
  return useApi<{ ok: boolean; message: string }>('/api/db-connections/test', { method: 'POST', body })
}
