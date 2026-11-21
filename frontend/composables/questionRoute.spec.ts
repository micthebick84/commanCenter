import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  fetchQuestionRoute,
  ROUTE_TIMEOUT_MS,
  routeCardModel,
  searchRepos,
  seedDbSelection,
  UNAVAILABLE_ROUTE,
  SIMILAR_MIN_PROBABILITY,
  type QuestionRoute,
  type RouteCatalogEntry,
} from './questionRoute'
import type { DbConnectionView } from './dbConnections'
import { useApiMock } from '../test/mocks/nuxt'

const entry = (id: number, alias: string, over: Partial<RouteCatalogEntry> = {}): RouteCatalogEntry => ({
  id,
  alias,
  description: null,
  host: 'github',
  defaultBranch: 'main',
  ...over,
})
const catalog = [
  entry(1, 'a앱'),
  entry(2, 'b앱'),
  entry(3, 'c앱'),
  entry(4, 'd앱'),
  entry(5, 'e앱'),
  entry(6, 'f앱', { host: 'other' }),
]

const route = (over: Partial<QuestionRoute> = {}): QuestionRoute => ({
  routeId: 1,
  state: 'CONFIDENT',
  recommendedRepoCatalogId: null,
  candidates: [],
  recentRepoCatalogIds: [],
  eligibleRepoCatalogIds: [1, 2, 3, 4, 5],
  dbNeeded: null,
  ...over,
})
const cand = (...ids: number[]) => ids.map((repoCatalogId, i) => ({ repoCatalogId, probability: 0.9 - i * 0.1 }))
const ids = (l: RouteCatalogEntry[]) => l.map((e) => e.id)

describe('fetchQuestionRoute', () => {
  it('POST /api/questions/route에 { question }을 보낸다', async () => {
    useApiMock.mockResolvedValue(route({ recommendedRepoCatalogId: 1 }))
    const r = await fetchQuestionRoute('질문')
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/route', {
      method: 'POST',
      body: { question: '질문' },
      timeout: ROUTE_TIMEOUT_MS,
    })
    expect(r.state).toBe('CONFIDENT')
    expect(r.recommendedRepoCatalogId).toBe(1)
  })

  describe('시간 제한', () => {
    afterEach(() => vi.useRealTimers())

    it('응답이 오지 않으면 5000ms 뒤 UNAVAILABLE을 돌려준다', async () => {
      vi.useFakeTimers()
      useApiMock.mockReturnValue(new Promise(() => {}))
      let result: QuestionRoute | null = null
      const p = fetchQuestionRoute('질문').then((r) => {
        result = r
      })
      await vi.advanceTimersByTimeAsync(ROUTE_TIMEOUT_MS - 1)
      expect(result).toBeNull()
      await vi.advanceTimersByTimeAsync(1)
      await p
      expect(ROUTE_TIMEOUT_MS).toBe(5000)
      expect(result).toEqual(UNAVAILABLE_ROUTE)
    })
  })

  it('호출이 던지면 UNAVAILABLE_ROUTE를 돌려준다(새 객체)', async () => {
    useApiMock.mockRejectedValue(new Error('net'))
    const r = await fetchQuestionRoute('질문')
    expect(r).toEqual(UNAVAILABLE_ROUTE)
    expect(r).not.toBe(UNAVAILABLE_ROUTE)
    r.recentRepoCatalogIds.push(9)
    expect(UNAVAILABLE_ROUTE.recentRepoCatalogIds).toEqual([])
  })

  it('응답에 state가 없거나 모르는 값이면 UNAVAILABLE_ROUTE', async () => {
    useApiMock.mockResolvedValue({ candidates: [] })
    expect((await fetchQuestionRoute('q')).state).toBe('UNAVAILABLE')
    useApiMock.mockResolvedValue({ ...route(), state: 'WAT' })
    expect((await fetchQuestionRoute('q')).state).toBe('UNAVAILABLE')
    useApiMock.mockResolvedValue(null)
    expect((await fetchQuestionRoute('q')).state).toBe('UNAVAILABLE')
  })

  it('목록 필드가 배열이 아니면 UNAVAILABLE_ROUTE', async () => {
    useApiMock.mockResolvedValue({ ...route(), candidates: 'x' })
    expect((await fetchQuestionRoute('q')).state).toBe('UNAVAILABLE')
  })
})

describe('routeCardModel', () => {
  it('SIMILAR_MIN_PROBABILITY는 0이다', () => {
    expect(SIMILAR_MIN_PROBABILITY).toBe(0)
  })

  it('CONFIDENT: 추천 1 + 다른 후보 2(추천 제외, 확률 순), recent·similar는 비어 있다', () => {
    const m = routeCardModel(
      route({ recommendedRepoCatalogId: 2, candidates: cand(2, 1, 3, 4), recentRepoCatalogIds: [5] }),
      catalog,
    )
    expect(m.recommended?.id).toBe(2)
    expect(ids(m.others)).toEqual([1, 3])
    expect(m.recent).toEqual([])
    expect(m.similar).toEqual([])
    expect(ids(m.searchable)).toEqual([1, 2, 3, 4, 5])
  })

  it('CONFIDENT인데 추천이 eligible에 없으면 AMBIGUOUS로 다룬다', () => {
    const m = routeCardModel(
      route({ recommendedRepoCatalogId: 9, candidates: cand(1, 2), recentRepoCatalogIds: [3] }),
      catalog,
    )
    expect(m.recommended).toBeNull()
    expect(ids(m.recent)).toEqual([3])
    expect(ids(m.similar)).toEqual([1, 2])
  })

  it('AMBIGUOUS: recent 최대 3, similar는 recent와 겹치지 않고 최대 3', () => {
    const m = routeCardModel(
      route({ state: 'AMBIGUOUS', candidates: cand(1, 2, 3, 4, 5), recentRepoCatalogIds: [2, 5, 4, 3] }),
      catalog,
    )
    expect(ids(m.recent)).toEqual([2, 5, 4])
    expect(ids(m.similar)).toEqual([1, 3])
    expect(m.recommended).toBeNull()
    expect(m.others).toEqual([])
  })

  it('AMBIGUOUS: 하한보다 낮은 후보는 similar에서 뺀다', () => {
    const m = routeCardModel(
      route({
        state: 'AMBIGUOUS',
        candidates: [
          { repoCatalogId: 1, probability: 0.5 },
          { repoCatalogId: 2, probability: -0.1 },
        ],
      }),
      catalog,
    )
    expect(ids(m.similar)).toEqual([1])
  })

  it('NO_MATCH: 추천·칩 없이 searchable만', () => {
    const m = routeCardModel(route({ state: 'NO_MATCH', candidates: cand(1), recentRepoCatalogIds: [2] }), catalog)
    expect(m.recommended).toBeNull()
    expect(m.others).toEqual([])
    expect(m.recent).toEqual([])
    expect(m.similar).toEqual([])
    expect(m.searchable.length).toBe(5)
  })

  it('UNAVAILABLE: recent만 있고 similar는 없다', () => {
    const m = routeCardModel(
      route({ state: 'UNAVAILABLE', candidates: cand(1), recentRepoCatalogIds: [2, 3, 4, 5] }),
      catalog,
    )
    expect(ids(m.recent)).toEqual([2, 3, 4])
    expect(m.similar).toEqual([])
  })

  it('eligible에 없는 id는 어느 목록에도 나오지 않는다', () => {
    const m = routeCardModel(
      route({
        state: 'AMBIGUOUS',
        eligibleRepoCatalogIds: [1, 2],
        candidates: cand(1, 3, 2),
        recentRepoCatalogIds: [3, 2],
      }),
      catalog,
    )
    expect(ids(m.recent)).toEqual([2])
    expect(ids(m.similar)).toEqual([1])
    expect(ids(m.searchable)).toEqual([1, 2])
  })

  it('eligible이 null이면 host가 other가 아닌 카탈로그 전체가 searchable이다', () => {
    const m = routeCardModel(route({ state: 'NO_MATCH', eligibleRepoCatalogIds: null }), catalog)
    expect(ids(m.searchable)).toEqual([1, 2, 3, 4, 5])
  })

  it('searchable은 별칭 한글 순으로 정렬된다', () => {
    const m = routeCardModel(route({ state: 'NO_MATCH', eligibleRepoCatalogIds: [1, 2] }), [
      entry(1, '하늘'),
      entry(2, '가람'),
    ])
    expect(m.searchable.map((e) => e.alias)).toEqual(['가람', '하늘'])
  })

  it('카탈로그에 없는 id(그새 꺼짐)는 조용히 빠진다', () => {
    const m = routeCardModel(
      route({
        state: 'AMBIGUOUS',
        eligibleRepoCatalogIds: [1, 99],
        candidates: cand(99, 1),
        recentRepoCatalogIds: [99],
      }),
      catalog,
    )
    expect(m.recent).toEqual([])
    expect(ids(m.similar)).toEqual([1])
    expect(ids(m.searchable)).toEqual([1])
  })
})

describe('searchRepos', () => {
  it('별칭과 설명에서 대소문자 없이 찾는다', () => {
    const list = [entry(1, 'Netis7.0'), entry(2, '옛앱', { description: 'Legacy PORTAL' })]
    expect(ids(searchRepos(list, 'netis'))).toEqual([1])
    expect(ids(searchRepos(list, 'portal'))).toEqual([2])
    expect(ids(searchRepos(list, '  '))).toEqual([1, 2])
    expect(ids(searchRepos(list, ''))).toEqual([1, 2])
    expect(searchRepos(list, 'zzz')).toEqual([])
  })
})

describe('seedDbSelection', () => {
  const conn = (id: number, scope: 'REPO' | 'USER', enabled = true): DbConnectionView => ({
    id,
    scope,
    repoCatalogId: 1,
    name: `c${id}`,
    dbType: 'POSTGRESQL',
    host: 'h',
    port: 1,
    databaseName: 'd',
    username: 'u',
    enabled,
    mine: scope === 'USER',
    allowWrite: false,
  })
  const items = [conn(1, 'USER'), conn(2, 'REPO'), conn(3, 'REPO', false), conn(4, 'USER'), conn(5, 'REPO')]

  it('true면 공용 먼저 최대 3개(꺼진 접속 제외)', () => {
    expect(seedDbSelection(items, true)).toEqual([2, 5, 1])
  })
  it('false면 빈 배열', () => {
    expect(seedDbSelection(items, false)).toEqual([])
  })
  it('null이면 켜진 공용만 최대 3개', () => {
    expect(seedDbSelection(items, null)).toEqual([2, 5])
    const many = [1, 2, 3, 4, 5].map((i) => conn(i, 'REPO'))
    expect(seedDbSelection(many, null)).toEqual([1, 2, 3])
  })
})
