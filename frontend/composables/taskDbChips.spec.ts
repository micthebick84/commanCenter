import { describe, it, expect } from 'vitest'
import { dbChipLabels } from './taskDbChips'

describe('dbChipLabels', () => {
  it('보이는 연결은 이름 · 종류, 안 보이는 연결은 DB #id', () => {
    const items = [
      { id: 3, name: '운영', dbType: 'MYSQL' },
      { id: 4, name: '분석', dbType: 'POSTGRESQL' },
    ] as never
    expect(dbChipLabels([4, 9, 3], items)).toEqual(['분석 · PostgreSQL', 'DB #9', '운영 · MySQL'])
  })
})
