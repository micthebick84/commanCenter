import { describe, it, expect } from 'vitest'
import { deployConfigChanged, duplicateEntryKeys, emptyDeployConfig, newDbSlot, toWireDeployConfig } from '../composables/deployConfig'

describe('deployConfig', () => {
  it('duplicateEntryKeys는 앞뒤 공백을 뗀 키가 두 번 이상인 것만 처음 나온 순서로', () => {
    expect(duplicateEntryKeys(['a', 'b', 'c'])).toEqual([])
    expect(duplicateEntryKeys(['b', 'a', 'b', ' a ', 'b'])).toEqual(['b', 'a'])
    expect(duplicateEntryKeys(['', '  ', ''])).toEqual([]) // 빈 키 행은 내보내지 않으므로 겹쳐도 문제없다
  })

  it('newDbSlot_기본값', () => {
    const s = newDbSlot([])
    expect(s).toEqual({
      slot: 'main',
      label: '주 DB',
      dbTypes: ['MARIADB'],
      target: {
        kind: 'FILE',
        path: 'WEB-INF/classes/config/jdbc_maria.properties',
        entries: { 'jdbc.url': '', 'jdbc.username': '{username}', 'jdbc.password': '{password}' },
      },
    })
  })

  it('두번째_자리는_db2', () => {
    const first = newDbSlot([])
    const second = newDbSlot([first])
    expect(second.slot).toBe('db2')
    expect(second.label).toBe('DB 2')
    expect(newDbSlot([first, second]).slot).toBe('db3')
  })

  it('빈설정과_null은_같다', () => {
    expect(deployConfigChanged(null, emptyDeployConfig())).toBe(false)
    expect(deployConfigChanged(emptyDeployConfig(), null)).toBe(false)
    expect(deployConfigChanged(null, { databases: [newDbSlot([])], variables: [] })).toBe(true)
    const a = { databases: [newDbSlot([])], variables: [] }
    expect(deployConfigChanged(a, JSON.parse(JSON.stringify(a)))).toBe(false)
  })

  it('toWire가_빈_항목을_뺀다', () => {
    const slot = newDbSlot([])
    slot.target.entries = { 'jdbc.url': 'jdbc:x', '': 'orphan', ' ': 'blank' }
    const wire = toWireDeployConfig({ databases: [slot], variables: [] })
    expect(wire.databases[0].target.entries).toEqual({ 'jdbc.url': 'jdbc:x' })
  })
})
