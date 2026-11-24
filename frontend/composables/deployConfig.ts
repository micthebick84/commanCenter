import type { DbType } from '~/composables/dbConnections'

// 레포 카탈로그 배포 설정(DB 자리 + 변수) — API JSON과 같은 모양
export interface DbSlotTarget {
  kind: 'ENV' | 'FILE'
  path: string | null
  entries: Record<string, string>
}

export interface DbSlot {
  slot: string
  label: string
  dbTypes: DbType[]
  target: DbSlotTarget
}

export interface DeployVariable {
  key: string
  description: string
  secret: boolean
  required: boolean
  format: string | null
  filePath: string | null
  fileEntry: string | null
}

export interface DeployConfig {
  databases: DbSlot[]
  variables: DeployVariable[]
}

export const MAX_DB_SLOTS = 3
export const MAX_DEPLOY_VARIABLES = 50
export const PLACEHOLDER_HINT = '{host} {port} {database} {username} {password} — URL 안 계정은 {username:url} {password:url}'

export function emptyDeployConfig(): DeployConfig {
  return { databases: [], variables: [] }
}

export function isEmptyDeployConfig(c: DeployConfig | null | undefined): boolean {
  return !c || (c.databases.length === 0 && c.variables.length === 0)
}

export function newDbSlot(existing: DbSlot[]): DbSlot {
  const used = new Set(existing.map((s) => s.slot))
  let n = 1
  let slot = 'main'
  while (used.has(slot)) {
    n += 1
    slot = `db${n}`
  }
  return {
    slot,
    label: n === 1 ? '주 DB' : `DB ${n}`,
    dbTypes: ['MARIADB'],
    target: {
      kind: 'FILE',
      path: 'WEB-INF/classes/config/jdbc_maria.properties',
      entries: { 'jdbc.url': '', 'jdbc.username': '{username}', 'jdbc.password': '{password}' },
    },
  }
}

export function newDeployVariable(): DeployVariable {
  return { key: '', description: '', secret: false, required: false, format: null, filePath: null, fileEntry: null }
}

export function cloneDeployConfig(c: DeployConfig | null | undefined): DeployConfig {
  return c ? (JSON.parse(JSON.stringify(c)) as DeployConfig) : emptyDeployConfig()
}

/** 서버로 보낼 모양 — 키가 빈 entries 행을 빼고 빈 문자열 선택값은 null로 */
export function toWireDeployConfig(c: DeployConfig): DeployConfig {
  return {
    databases: c.databases.map((d) => ({
      ...d,
      target: {
        ...d.target,
        path: d.target.kind === 'FILE' ? d.target.path?.trim() || null : null,
        entries: Object.fromEntries(Object.entries(d.target.entries).filter(([k]) => k.trim() !== '')),
      },
    })),
    variables: c.variables.map((v) => ({
      ...v,
      format: v.format?.trim() ? v.format : null,
      filePath: v.filePath?.trim() ? v.filePath : null,
      fileEntry: v.fileEntry?.trim() ? v.fileEntry : null,
    })),
  }
}

/** 빈 설정과 null은 같다. 나머지는 전송 모양 JSON 비교 */
export function deployConfigChanged(a: DeployConfig | null | undefined, b: DeployConfig | null | undefined): boolean {
  const norm = (c: DeployConfig | null | undefined) => (isEmptyDeployConfig(c) ? '' : JSON.stringify(toWireDeployConfig(c as DeployConfig)))
  return norm(a) !== norm(b)
}
