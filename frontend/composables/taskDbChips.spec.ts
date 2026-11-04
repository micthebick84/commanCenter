import { describe, it, expect } from 'vitest'
import { dbChipLabels, hasWritableSelection } from './taskDbChips'

describe('dbChipLabels', () => {
  it('보이는 연결은 이름 · 종류, 안 보이는 연결은 DB #id', () => {
    const items = [
      { id: 3, name: '운영', dbType: 'MYSQL' },
      { id: 4, name: '분석', dbType: 'POSTGRESQL' },
    ] as never
    expect(dbChipLabels([4, 9, 3], items)).toEqual(['분석 · PostgreSQL', 'DB #9', '운영 · MySQL'])
  })
})

describe('hasWritableSelection — 고른 연결에 구현 단계 쓰기가 열릴 수 있는가 (스펙 2026-11-03 §6.1)', () => {
  const c = (id: number, over: Record<string, unknown> = {}) => ({ id, scope: 'REPO', enabled: true, allowWrite: false, ...over })
  const list = (items: ReturnType<typeof c>[], writeEnabled?: boolean) => ({ items, ...(writeEnabled === undefined ? {} : { writeEnabled }) }) as never

  it('고른 연결 중 쓰기 허용·활성·공용이 하나라도 있고 레포에서 쓰기가 열려 있으면 true', () => {
    expect(hasWritableSelection([1, 2], list([c(1), c(2, { allowWrite: true })], true))).toBe(true)
  })

  it('쓰기 허용 연결이 있어도 고르지 않았으면 false', () => {
    expect(hasWritableSelection([1], list([c(1), c(2, { allowWrite: true })], true))).toBe(false)
    expect(hasWritableSelection([], list([c(2, { allowWrite: true })], true))).toBe(false)
  })

  it('레포에서 쓰기가 안 열렸거나(writeEnabled false) 옛 응답(키 없음)이면 모르는 쪽으로 false', () => {
    const items = [c(2, { allowWrite: true })]
    expect(hasWritableSelection([2], list(items, false))).toBe(false)
    expect(hasWritableSelection([2], list(items))).toBe(false)
  })

  it('비활성·개인 연결·목록에 없는 id는 쓰기 가능으로 치지 않는다', () => {
    expect(hasWritableSelection([2], list([c(2, { allowWrite: true, enabled: false })], true))).toBe(false)
    expect(hasWritableSelection([2], list([c(2, { allowWrite: true, scope: 'USER' })], true))).toBe(false)
    expect(hasWritableSelection([9], list([c(2, { allowWrite: true })], true))).toBe(false)
  })
})
