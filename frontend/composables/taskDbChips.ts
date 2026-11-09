import { dbTypeLabel, type DbConnectionList, type DbConnectionView } from '~/composables/dbConnections'

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

/**
 * 고른 연결 중 구현 단계 쓰기가 열릴 수 있는 것이 있는가 (스펙 2026-11-03 §6.1 조건 1·4·5의 화면 쪽 근사) —
 * 레포에서 쓰기가 열려 있고(`writeEnabled`: 전역 스위치 켜짐 + 자동 마이그레이션 레포 아님) 고른 연결이 활성·공용·쓰기 허용이다.
 * 화면의 "읽기 전용" 문구와 쓰기 배지를 바꿀지 정하는 용도라 마지막 판단은 서버다. `writeEnabled`가 없는 옛 응답과 목록에 없는 id는
 * 모르는 쪽(false)으로 둔다 — 안내를 틀리게 바꾸는 것보다 예전 문구가 낫다.
 */
export function hasWritableSelection(
  ids: number[],
  list: Pick<DbConnectionList, 'writeEnabled'> & { items: Pick<DbConnectionView, 'id' | 'scope' | 'enabled' | 'allowWrite'>[] },
): boolean {
  if (list.writeEnabled !== true) return false
  return ids.some((id) => list.items.some((c) => c.id === id && c.scope === 'REPO' && c.enabled && c.allowWrite))
}
