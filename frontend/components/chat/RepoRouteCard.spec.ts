import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import RepoRouteCard from './RepoRouteCard.vue'
import DbConnectionDialog from '~/components/DbConnectionDialog.vue'
import type { QuestionRoute, RouteCatalogEntry } from '~/composables/questionRoute'
import type { DbConnectionList, DbConnectionView } from '~/composables/dbConnections'
import { useApiMock } from '~/test/mocks/nuxt'
import { setViewportWidth } from '~/test/mocks/screen'

const entry = (id: number, alias: string, over: Partial<RouteCatalogEntry> = {}): RouteCatalogEntry => ({
  id,
  alias,
  description: `${alias} 설명`,
  host: 'github',
  defaultBranch: 'main',
  ...over,
})
const catalog = [entry(1, '강원대시보드'), entry(2, 'Netis7.0'), entry(3, '사천시청'), entry(4, '우리은행'), entry(5, 'Netis6.8')]

const route = (over: Partial<QuestionRoute> = {}): QuestionRoute => ({
  routeId: 9,
  state: 'CONFIDENT',
  recommendedRepoCatalogId: 1,
  candidates: [
    { repoCatalogId: 1, probability: 0.9 },
    { repoCatalogId: 2, probability: 0.05 },
    { repoCatalogId: 3, probability: 0.03 },
  ],
  recentRepoCatalogIds: [],
  eligibleRepoCatalogIds: null,
  dbNeeded: null,
  ...over,
})
const ambiguous = (over: Partial<QuestionRoute> = {}) =>
  route({
    state: 'AMBIGUOUS',
    recommendedRepoCatalogId: null,
    candidates: [
      { repoCatalogId: 3, probability: 0.3 },
      { repoCatalogId: 4, probability: 0.2 },
    ],
    recentRepoCatalogIds: [2, 5],
    ...over,
  })

const conn = (id: number, scope: 'REPO' | 'USER', over: Partial<DbConnectionView> = {}): DbConnectionView => ({
  id,
  scope,
  repoCatalogId: 1,
  name: `conn${id}`,
  dbType: 'POSTGRESQL',
  host: 'h',
  port: 5432,
  databaseName: 'app',
  username: 'u',
  enabled: true,
  mine: scope === 'USER',
  allowWrite: false,
  ...over,
})

interface ApiSetup {
  branches?: Record<number, () => Promise<unknown>>
  db?: Record<number, () => Promise<DbConnectionList>>
}
const branchRes = (names: string[] = ['main', 'develop'], def: string | null = 'main') => ({
  repo: 'x',
  defaultBranch: def,
  branches: names.map((name) => ({ name, sha: 'abc' })),
})
function api(setup: ApiSetup = {}) {
  useApiMock.mockImplementation((url: string, opts?: { params?: { catalogId?: number } }) => {
    if (url === '/api/repos/branches') {
      const id = opts?.params?.catalogId as number
      return (setup.branches?.[id] ?? (() => Promise.resolve(branchRes())))()
    }
    const m = /^\/api\/db-connections\?repoCatalogId=(\d+)$/.exec(url)
    if (m) {
      const id = Number(m[1])
      return (setup.db?.[id] ?? (() => Promise.resolve({ enabled: false, items: [] })))()
    }
    return Promise.reject(new Error(`unexpected ${url}`))
  })
}
const dbList = (items: DbConnectionView[]) => () => Promise.resolve({ enabled: true, items })

function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function mountCard(r: QuestionRoute, props: Record<string, unknown> = {}, cat = catalog) {
  const w = mount(RepoRouteCard, { props: { route: r, catalog: cat, starting: false, ...props } })
  await flushPromises()
  return w
}
const startBtn = (w: ReturnType<typeof mount>) => w.find('[data-test="route-start"]')
const isDisabled = (el: { attributes: (n: string) => string | undefined }) =>
  el.attributes('disabled') !== undefined || el.attributes('aria-disabled') === 'true'

describe('RepoRouteCard (스펙 2026-11-20 §5.3·§5.4)', () => {
  beforeEach(() => api())
  afterEach(async () => {
    await setViewportWidth(1024)
  })

  it('CONFIDENT: 추천 레포가 강조되고 추천 표시·설명이 보이며 브랜치를 받은 뒤 시작 버튼이 켜진다', async () => {
    const w = await mountCard(route())
    expect(w.find('[data-test="route-title"]').text()).toBe('이 레포에 대한 질문이 맞나요?')
    const hero = w.find('[data-test="route-hero"]')
    expect(hero.text()).toContain('강원대시보드')
    expect(hero.text()).toContain('강원대시보드 설명')
    expect(hero.text()).toContain('추천')
    expect((w.vm as any).branch).toBe('main')
    expect(w.find('[data-test="route-branch"]').exists()).toBe(true)
    expect(startBtn(w).text()).toContain('이 레포로 질문 시작')
    expect(isDisabled(startBtn(w))).toBe(false)
    expect(w.text()).toContain('다른 후보')
    expect(w.find('[data-test="route-chip-2"]').exists()).toBe(true)
    expect(w.find('[data-test="route-chip-3"]').exists()).toBe(true)
    w.unmount()
  })

  it('CONFIDENT: 다른 후보 칩을 누르면 그 레포가 강조되고 추천 표시는 사라진다(추천은 칩으로 남는다)', async () => {
    const w = await mountCard(route())
    await w.find('[data-test="route-chip-2"]').trigger('click')
    await flushPromises()
    const hero = w.find('[data-test="route-hero"]')
    expect(hero.text()).toContain('Netis7.0')
    expect(hero.text()).not.toContain('추천')
    expect((w.vm as any).selectedId).toBe(2)
    expect(w.find('[data-test="route-chip-1"]').exists()).toBe(true)
    w.unmount()
  })

  it('AMBIGUOUS: 고르기 전에는 버튼이 "레포를 골라 주세요"로 꺼져 있다', async () => {
    const w = await mountCard(ambiguous())
    expect(w.find('[data-test="route-title"]').text()).toBe('어느 버전·사이트에 대한 질문인가요?')
    expect(w.find('[data-test="route-hero"]').exists()).toBe(false)
    expect(startBtn(w).text()).toContain('레포를 골라 주세요')
    expect(isDisabled(startBtn(w))).toBe(true)
    expect((w.vm as any).selectedId).toBeNull()
    expect(useApiMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('AMBIGUOUS: 최근 사용·비슷한 후보 칩과 "전체 N개에서 찾기"가 보인다', async () => {
    const w = await mountCard(ambiguous())
    expect(w.text()).toContain('최근 사용')
    expect(w.text()).toContain('비슷한 후보')
    for (const id of [2, 5, 3, 4]) expect(w.find(`[data-test="route-chip-${id}"]`).exists()).toBe(true)
    expect(w.text()).toContain('🔍 전체 5개에서 찾기')
    w.unmount()
  })

  it('NO_MATCH: 검색과 사이트 추가 요청 링크(/requests, target=_blank)가 보인다', async () => {
    const w = await mountCard(route({ state: 'NO_MATCH', recommendedRepoCatalogId: null, candidates: [] }))
    expect(w.find('[data-test="route-title"]').text()).toBe('등록된 레포 중에 맞는 곳을 찾지 못했어요')
    expect(w.find('[data-test="route-search"]').exists()).toBe(true)
    const link = w.find('a[href="/requests"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('target')).toBe('_blank')
    expect(link.text()).toContain('찾는 레포가 없다면 사이트 추가 요청 →')
    w.unmount()
  })

  it('UNAVAILABLE: 비슷한 후보 줄이 없고 실패 안내 문구도 없다', async () => {
    const w = await mountCard(
      route({ state: 'UNAVAILABLE', recommendedRepoCatalogId: null, candidates: [], recentRepoCatalogIds: [2] }),
    )
    expect(w.text()).not.toContain('비슷한 후보')
    expect(w.text()).not.toMatch(/실패|오류|꺼져|사용할 수 없/)
    expect(w.text()).toContain('최근 사용')
    expect(w.find('[data-test="route-title"]').text()).toBe('어느 버전·사이트에 대한 질문인가요?')
    w.unmount()
  })

  it('검색해서 고른 레포로 시작할 수 있다', async () => {
    const w = await mountCard(ambiguous())
    await w.find('button[data-test="route-search-toggle"]').trigger('click')
    const input = w.find('input[data-test="route-search"]')
    expect(input.attributes('placeholder')).toBe('레포 이름·설명으로 찾기')
    await input.setValue('우리')
    expect(w.find('[data-test="route-result-4"]').exists()).toBe(true)
    expect(w.find('[data-test="route-result-1"]').exists()).toBe(false)
    await w.find('[data-test="route-result-4"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="route-hero"]').text()).toContain('우리은행')
    expect(isDisabled(startBtn(w))).toBe(false)
    await startBtn(w).trigger('click')
    expect(w.emitted('start')![0][0]).toMatchObject({ repoCatalogId: 4, githubBranch: 'main' })
    w.unmount()
  })

  it('시작을 누르면 { repoCatalogId, githubBranch, dbConnectionIds }를 낸다', async () => {
    api({ db: { 1: dbList([conn(10, 'REPO'), conn(11, 'USER')]) } })
    const w = await mountCard(route({ dbNeeded: true }))
    expect(w.emitted('start')).toBeUndefined() // 자동 확정 없음
    await startBtn(w).trigger('click')
    expect(w.emitted('start')![0][0]).toEqual({ repoCatalogId: 1, githubBranch: 'main', dbConnectionIds: [10, 11] })
    w.unmount()
  })

  it('브랜치 조회 중에는 시작 버튼이 꺼져 있다', async () => {
    const d = deferred<unknown>()
    api({ branches: { 1: () => d.promise } })
    const w = await mountCard(route())
    expect(isDisabled(startBtn(w))).toBe(true)
    d.resolve(branchRes())
    await flushPromises()
    expect(isDisabled(startBtn(w))).toBe(false)
    w.unmount()
  })

  it('브랜치 조회가 실패하면 오류 문구를 보여 주고 시작을 막되 다른 레포는 고를 수 있다', async () => {
    api({ branches: { 1: () => Promise.reject({ data: { message: '레포 접근 불가' } }) } })
    const w = await mountCard(route())
    expect(w.find('[data-test="route-branch-error"]').text()).toContain('레포 접근 불가')
    expect(isDisabled(startBtn(w))).toBe(true)
    await w.find('[data-test="route-chip-2"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="route-branch-error"]').exists()).toBe(false)
    expect(isDisabled(startBtn(w))).toBe(false)
    w.unmount()
  })

  it('레포를 연달아 고르면 늦게 온 이전 레포의 브랜치·DB 응답이 지금 선택을 덮지 않는다', async () => {
    const b1 = deferred<unknown>()
    const d1 = deferred<DbConnectionList>()
    api({
      branches: { 1: () => b1.promise, 2: () => Promise.resolve(branchRes(['main', 'dev2'], 'dev2')) },
      db: { 1: () => d1.promise, 2: dbList([conn(20, 'REPO', { repoCatalogId: 2 })]) },
    })
    // 카탈로그 기본 브랜치가 없어야 조회 응답의 기본 브랜치가 그대로 드러난다(있으면 initialBranch가 늘 그 값을 고른다).
    const noDefault = catalog.map((e) => (e.id === 1 || e.id === 2 ? { ...e, defaultBranch: null } : e))
    const w = await mountCard(route({ dbNeeded: true }), {}, noDefault)
    await w.find('[data-test="route-chip-2"]').trigger('click')
    await flushPromises()
    const options = () => w.findComponent({ name: 'QSelect' }).props('options')
    expect((w.vm as any).branch).toBe('dev2')
    expect(options()).toEqual(['main', 'dev2'])
    expect((w.vm as any).dbIds).toEqual([20])
    b1.resolve(branchRes(['old'], 'old'))
    d1.resolve({ enabled: true, items: [conn(10, 'REPO')] })
    await flushPromises()
    expect((w.vm as any).selectedId).toBe(2)
    expect((w.vm as any).branch).toBe('dev2')
    expect(options()).toEqual(['main', 'dev2'])
    expect((w.vm as any).dbIds).toEqual([20])
    await startBtn(w).trigger('click')
    expect(w.emitted('start')![0][0]).toEqual({ repoCatalogId: 2, githubBranch: 'dev2', dbConnectionIds: [20] })
    w.unmount()
  })

  it('이전 레포의 늦은 브랜치 오류도 지금 선택에 오류를 띄우지 않는다', async () => {
    const b1 = deferred<unknown>()
    api({ branches: { 1: () => b1.promise } })
    const w = await mountCard(route())
    await w.find('[data-test="route-chip-2"]').trigger('click')
    await flushPromises()
    b1.reject(new Error('늦은 오류'))
    await flushPromises()
    expect(w.find('[data-test="route-branch-error"]').exists()).toBe(false)
    expect(isDisabled(startBtn(w))).toBe(false)
    w.unmount()
  })

  it('DB: 접속 있음 + dbNeeded=true → 미리 체크되고 안내 문구가 보인다', async () => {
    api({ db: { 1: dbList([conn(10, 'REPO'), conn(11, 'USER'), conn(12, 'REPO', { enabled: false })]) } })
    const w = await mountCard(route({ dbNeeded: true }))
    expect(w.find('[data-test="route-db-row"]').exists()).toBe(true)
    expect(w.find('[data-test="route-db-row"]').text()).toContain('DB 조회가 필요한 질문으로 보여 미리 골랐어요')
    expect(w.find('[data-test="route-db-12"]').exists()).toBe(false) // 꺼진 접속은 안 보인다
    expect((w.vm as any).dbIds).toEqual([10, 11])
    expect(w.find('[data-test="route-db-row"]').text()).toContain('PostgreSQL')
    w.unmount()
  })

  it('DB: 접속 있음 + dbNeeded=false → 체크 없이 보인다', async () => {
    api({ db: { 1: dbList([conn(10, 'REPO')]) } })
    const w = await mountCard(route({ dbNeeded: false }))
    expect(w.find('[data-test="route-db-10"]').exists()).toBe(true)
    expect((w.vm as any).dbIds).toEqual([])
    expect(w.text()).not.toContain('미리 골랐어요')
    w.unmount()
  })

  it('DB: 접속 있음 + dbNeeded=null → 공용만 체크된다', async () => {
    api({ db: { 1: dbList([conn(10, 'REPO'), conn(11, 'USER')]) } })
    const w = await mountCard(route({ dbNeeded: null }))
    expect((w.vm as any).dbIds).toEqual([10])
    expect(w.find('[data-test="route-db-11"]').exists()).toBe(true)
    w.unmount()
  })

  it('DB: 접속 없음 + dbNeeded=true → 등록 안내와 "내 DB 접속 추가"가 보인다', async () => {
    api({ db: { 1: dbList([]) } })
    const w = await mountCard(route({ dbNeeded: true }))
    expect(w.find('[data-test="route-db-row"]').text()).toContain('이 레포에 연결할 DB 접속이 없습니다')
    expect(w.find('[data-test="route-db-add"]').text()).toContain('내 DB 접속 추가')
    w.unmount()
  })

  it('DB: 접속 없음 + dbNeeded=false → DB 줄이 없다', async () => {
    api({ db: { 1: dbList([]) } })
    const w = await mountCard(route({ dbNeeded: false }))
    expect(w.find('[data-test="route-db-row"]').exists()).toBe(false)
    w.unmount()
  })

  it('DB: 기능이 꺼져 있으면 줄이 없고 dbConnectionIds는 null로 낸다', async () => {
    const w = await mountCard(route({ dbNeeded: true })) // 기본 api(): enabled=false
    expect(w.find('[data-test="route-db-row"]').exists()).toBe(false)
    await startBtn(w).trigger('click')
    expect(w.emitted('start')![0][0]).toEqual({ repoCatalogId: 1, githubBranch: 'main', dbConnectionIds: null })
    w.unmount()
  })

  it('DB: 켜진 상태에서 아무것도 안 고르면 빈 배열로 낸다', async () => {
    api({ db: { 1: dbList([conn(10, 'REPO')]) } })
    const w = await mountCard(route({ dbNeeded: false }))
    await startBtn(w).trigger('click')
    expect(w.emitted('start')![0][0]).toMatchObject({ dbConnectionIds: [] })
    w.unmount()
  })

  it('DB: 4번째 접속은 체크할 수 없다(상한 3)', async () => {
    api({ db: { 1: dbList([conn(1, 'REPO'), conn(2, 'REPO'), conn(3, 'REPO'), conn(4, 'REPO')]) } })
    const w = await mountCard(route({ dbNeeded: true }))
    expect((w.vm as any).dbIds).toEqual([1, 2, 3])
    await w.find('[data-test="route-db-4"]').trigger('click')
    expect((w.vm as any).dbIds).toEqual([1, 2, 3])
    await w.find('[data-test="route-db-1"]').trigger('click') // 해제는 가능
    expect((w.vm as any).dbIds).toEqual([2, 3])
    w.unmount()
  })

  it('DB: 접속을 새로 등록하면 목록을 다시 받고 그 접속이 체크된다', async () => {
    let items: DbConnectionView[] = []
    api({ db: { 1: () => Promise.resolve({ enabled: true, items }) } })
    const w = await mountCard(route({ dbNeeded: true }))
    expect(w.find('[data-test="route-db-add"]').exists()).toBe(true)
    await w.find('[data-test="route-db-add"]').trigger('click')
    const dlg = w.findComponent(DbConnectionDialog)
    expect(dlg.props('scope')).toBe('USER')
    expect(dlg.props('repoCatalogId')).toBe(1)
    items = [conn(30, 'USER')]
    dlg.vm.$emit('saved', items[0])
    await flushPromises()
    expect(w.find('[data-test="route-db-30"]').exists()).toBe(true)
    expect((w.vm as any).dbIds).toEqual([30])
    w.unmount()
  })

  it('DB: 새 접속이 상한을 넘기면 기존 3개를 그대로 둔다', async () => {
    let items = [conn(1, 'REPO'), conn(2, 'REPO'), conn(3, 'REPO')]
    api({ db: { 1: () => Promise.resolve({ enabled: true, items }) } })
    const w = await mountCard(route({ dbNeeded: true }))
    items = [...items, conn(30, 'USER')]
    w.findComponent(DbConnectionDialog).vm.$emit('saved', items[3])
    await flushPromises()
    expect((w.vm as any).dbIds).toEqual([1, 2, 3])
    w.unmount()
  })

  it('starting이면 시작 버튼이 로딩이고 칩·검색이 잠긴다', async () => {
    const w = await mountCard(route(), { starting: true })
    expect(startBtn(w).find('.q-btn__content--hidden').exists()).toBe(true)
    expect(startBtn(w).find('.q-spinner').exists()).toBe(true)
    expect(isDisabled(w.find('[data-test="route-chip-2"]'))).toBe(true)
    expect(isDisabled(w.find('[data-test="route-search-toggle"]'))).toBe(true)
    await startBtn(w).trigger('click')
    expect(w.emitted('start')).toBeUndefined()
    w.unmount()
  })

  it('등록 가능한 레포가 없으면 안내 문구만 보인다', async () => {
    const w = await mountCard(route({ eligibleRepoCatalogIds: [] }))
    expect(w.text()).toContain('등록된 레포가 없습니다 — 관리자에게 문의')
    expect(w.find('[data-test="route-start"]').exists()).toBe(false)
    expect(w.find('[data-test="route-hero"]').exists()).toBe(false)
    expect(useApiMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('폰 폭(390px)에서도 강조 영역·칩·버튼이 모두 그려진다', async () => {
    await setViewportWidth(390)
    const w = await mountCard(route())
    expect(w.find('[data-test="route-hero"]').exists()).toBe(true)
    expect(w.find('[data-test="route-chip-2"]').exists()).toBe(true)
    expect(startBtn(w).exists()).toBe(true)
    w.unmount()
  })

  it('카드 안에 Quasar 반응형 헬퍼 이름의 클래스가 없다', async () => {
    const w = await mountCard(route())
    const bad = /^(xs|sm|md|lg|xl|gt-.*|lt-.*|.*-hide)$/
    const classes = new Set<string>()
    w.element.querySelectorAll('[class]').forEach((el) => el.classList.forEach((c) => classes.add(c)))
    expect([...classes].filter((c) => bad.test(c))).toEqual([])
    w.unmount()
  })
})
