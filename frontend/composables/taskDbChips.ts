import { dbTypeLabel, type DbConnectionView } from '~/composables/dbConnections'

/**
 * 작업 상세 DB 칩 라벨 (스펙 2026-10-14 §7). 보는 사람이 볼 수 없는 연결(남의 개인 접속정보 등)은 'DB #id'.
 */
export function dbChipLabels(ids: number[], items: Pick<DbConnectionView, 'id' | 'name' | 'dbType'>[]): string[] {
  const byId = new Map(items.map((i) => [i.id, i]))
  return ids.map((id) => {
    const c = byId.get(id)
    return c ? `${c.name} · ${dbTypeLabel(c.dbType)}` : `DB #${id}`
  })
}
