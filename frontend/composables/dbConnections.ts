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
}

export interface DbConnectionList {
  enabled: boolean
  items: DbConnectionView[]
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

export function deleteDbConnection(id: number) {
  return useApi(`/api/db-connections/${id}`, { method: 'DELETE' })
}

export function testDbConnection(body: Record<string, unknown>) {
  return useApi<{ ok: boolean; message: string }>('/api/db-connections/test', { method: 'POST', body })
}
