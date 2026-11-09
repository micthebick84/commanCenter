import { describe, it, expect, beforeEach } from 'vitest'
import {
  DB_CHANGE_LABELS,
  EXECUTE_ADVICE,
  dbChangeLabel,
  deferredCount,
  deferredWarning,
  fetchDeferredWarning,
  groupByConnection,
  type DbChangeView,
} from './taskDbChanges'
import { useApiMock } from '../test/mocks/nuxt'

const row = (over: Partial<DbChangeView> & { id: number }): DbChangeView => ({
  connectionId: 7,
  connectionName: '개발 DB',
  attempt: 1,
  fixRequestId: null,
  sqlText: 'select 1',
  classification: 'EXECUTED',
  reason: null,
  affectedRows: null,
  error: null,
  createdAt: '2026-11-04T00:00:00Z',
  executedAt: null,
  executedBy: null,
  ...over,
})

describe('taskDbChanges — 라벨·건수·경고 (스펙 2026-11-03 §6.5)', () => {
  it('분류 라벨은 화면 문구 그대로, 모르는 값은 그 값 그대로', () => {
    expect(DB_CHANGE_LABELS).toEqual({
      EXECUTED: '실행됨',
      DEFERRED: '승인 대기',
      DISMISSED: '실행 안 함',
      REJECTED: '거부됨',
      FAILED: '실패',
    })
    expect(dbChangeLabel('DEFERRED')).toBe('승인 대기')
    expect(dbChangeLabel('FUTURE')).toBe('FUTURE')
  })

  it('승인 대기 건수는 배열의 DEFERRED만 센다 — 배열이 아니면 0', () => {
    expect(deferredCount([row({ id: 1, classification: 'DEFERRED' }), row({ id: 2 }), row({ id: 3, classification: 'DEFERRED' })])).toBe(2)
    expect(deferredCount([])).toBe(0)
    expect(deferredCount(null)).toBe(0)
    expect(deferredCount(undefined)).toBe(0)
    expect(deferredCount({ content: [] })).toBe(0) // 프록시 오류 페이지 등 배열이 아닌 응답
    expect(deferredCount([null, 'x', 3, { classification: 'DEFERRED' }])).toBe(1) // 모양이 깨진 원소는 건너뛴다
  })

  it('경고 문구는 앞에 공백을 두어 확인창 문장 뒤에 바로 붙고, 0건이면 빈 문자열', () => {
    expect(deferredWarning(0)).toBe('')
    expect(deferredWarning(-1)).toBe('')
    expect(deferredWarning(Number.NaN)).toBe('')
    expect(deferredWarning(2)).toBe(' 승인 대기 SQL 2건 — 병합 뒤 실행 필요')
  })

  it('실행 권장 문구', () => {
    expect(EXECUTE_ADVICE).toBe('PR 병합 뒤 실행을 권장합니다 — 같은 DB를 쓰는 다른 화면이 아직 옛 컬럼을 쓸 수 있습니다')
  })
})

describe('groupByConnection — 연결별·시간순', () => {
  it('연결 순서는 처음 나온 순, 안은 시간순(같으면 id순)', () => {
    const groups = groupByConnection([
      row({ id: 3, connectionId: 8, connectionName: '통합 DB', createdAt: '2026-11-04T00:03:00Z' }),
      row({ id: 2, connectionId: 7, createdAt: '2026-11-04T00:02:00Z' }),
      row({ id: 1, connectionId: 7, createdAt: '2026-11-04T00:01:00Z' }),
      row({ id: 4, connectionId: 7, createdAt: '2026-11-04T00:02:00Z' }),
    ])
    // 시간순으로 먼저 나온 연결(7)이 앞 — 서버가 시간순으로 주지만 화면이 다시 정렬해도 같다
    expect(groups.map((g) => g.name)).toEqual(['개발 DB', '통합 DB'])
    expect(groups[0]!.items.map((r) => r.id)).toEqual([1, 2, 4])
    expect(groups[1]!.items.map((r) => r.id)).toEqual([3])
  })

  it('연결을 지웠거나 이름이 없어도 한 묶음으로 보인다', () => {
    const groups = groupByConnection([
      row({ id: 1, connectionId: null, connectionName: '옛 연결' }),
      row({ id: 2, connectionId: null, connectionName: null }),
    ])
    expect(groups.map((g) => g.name)).toEqual(['옛 연결', '(삭제된 연결)'])
  })

  it('배열이 아니면 빈 목록', () => {
    expect(groupByConnection(null as unknown as DbChangeView[])).toEqual([])
  })
})

describe('fetchDeferredWarning — 종료 승인 확인창용 건수 조회', () => {
  beforeEach(() => useApiMock.mockReset())

  it('GET db-changes의 승인 대기 건수로 경고를 만든다', async () => {
    useApiMock.mockResolvedValue([row({ id: 1, classification: 'DEFERRED' }), row({ id: 2, classification: 'DEFERRED' })])
    expect(await fetchDeferredWarning(42)).toBe(' 승인 대기 SQL 2건 — 병합 뒤 실행 필요')
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/db-changes')
  })

  it('조회가 실패하거나 배열이 아니면 경고 없이 빈 문자열(승인을 막지 않는다)', async () => {
    useApiMock.mockRejectedValue(new Error('500'))
    expect(await fetchDeferredWarning(42)).toBe('')
    useApiMock.mockReset()
    useApiMock.mockResolvedValue({ id: 42 })
    expect(await fetchDeferredWarning(42)).toBe('')
  })
})
