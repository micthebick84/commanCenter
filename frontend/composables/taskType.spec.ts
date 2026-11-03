import { describe, it, expect } from 'vitest'
import {
  NO_TYPE_FILTER,
  NO_TYPE_LABEL,
  TASK_TYPES,
  TASK_TYPE_FILTERS,
  matchesTypeFilter,
  taskTypeLabel,
} from './taskType'

describe('taskType (스펙 2026-11-03 §4)', () => {
  it('고르는 타입은 네 개이고 서버 enum 이름과 화면 이름을 그대로 쓴다', () => {
    expect(TASK_TYPES).toEqual([
      { value: 'NEW_FEATURE', label: '신규 개발' },
      { value: 'FEATURE_CHANGE', label: '기능 수정' },
      { value: 'BUG_FIX', label: '버그 픽스' },
      { value: 'DB_WORK', label: 'DB 작업' },
    ])
  })

  it('taskTypeLabel — null·undefined는 타입 없음, 모르는 값은 그 값 그대로', () => {
    expect(taskTypeLabel('BUG_FIX')).toBe('버그 픽스')
    expect(taskTypeLabel('DB_WORK')).toBe('DB 작업')
    expect(taskTypeLabel(null)).toBe('타입 없음')
    expect(taskTypeLabel(undefined)).toBe('타입 없음')
    expect(taskTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })

  it('필터 옵션 — 전체 타입(null) · 네 타입 · 타입 없음(NONE) 순서', () => {
    expect(TASK_TYPE_FILTERS.map((f) => f.value)).toEqual([null, 'NEW_FEATURE', 'FEATURE_CHANGE', 'BUG_FIX', 'DB_WORK', 'NONE'])
    expect(TASK_TYPE_FILTERS[0].label).toBe('전체 타입')
    expect(TASK_TYPE_FILTERS.at(-1)).toEqual({ value: NO_TYPE_FILTER, label: NO_TYPE_LABEL })
    expect(NO_TYPE_FILTER).toBe('NONE')
  })

  it('matchesTypeFilter — 타입 없음은 null이든 키가 없든 NONE에 걸린다', () => {
    expect(matchesTypeFilter({ taskType: null }, 'NONE')).toBe(true)
    expect(matchesTypeFilter({}, 'NONE')).toBe(true)
    expect(matchesTypeFilter({ taskType: 'BUG_FIX' }, 'NONE')).toBe(false)
  })

  it('matchesTypeFilter — 필터가 null(전체 타입)이면 모두 통과', () => {
    expect(matchesTypeFilter({ taskType: 'BUG_FIX' }, null)).toBe(true)
    expect(matchesTypeFilter({ taskType: null }, null)).toBe(true)
    expect(matchesTypeFilter({}, null)).toBe(true)
  })

  it('matchesTypeFilter — 타입을 고르면 그 타입만(타입 없음은 제외)', () => {
    expect(matchesTypeFilter({ taskType: 'BUG_FIX' }, 'BUG_FIX')).toBe(true)
    expect(matchesTypeFilter({ taskType: 'DB_WORK' }, 'BUG_FIX')).toBe(false)
    expect(matchesTypeFilter({ taskType: null }, 'BUG_FIX')).toBe(false)
    expect(matchesTypeFilter({}, 'BUG_FIX')).toBe(false)
  })
})
