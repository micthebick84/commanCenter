import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer, QSelect } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// 작업 목록의 타입 필터·카드 칩 (스펙 2026-11-03 §4.3) — 데스크톱은 서버에 type 파라미터를 보내고,
// 모바일은 서버 조회를 좁히지 않고(리뷰 파인딩 11) 받은 목록을 화면에서 거른다.

Object.assign(globalThis, { navigateTo: vi.fn() })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }) }),
})

const t = (id: number, taskType: string | null | undefined, extra: Record<string, unknown> = {}) => ({
  id, githubRepo: 'micthebick84/netis7.0', repoAlias: 'Netis7.0', githubBranch: 'main', title: `작업 ${id}`, description: '',
  status: 'PR_CREATED', statusLabel: 'PR생성', requesterId: 'admin', retryCount: 0, maxRetry: 3, failureReason: null,
  designRequested: false, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-24T02:00:00Z',
  implementation: { prUrl: 'https://x/pull/1', prNumber: 1, headBranch: 'b', headSha: 's' }, totalCostUsd: null,
  ...(taskType === undefined ? {} : { taskType }), ...extra,
})
// 21: 버그 픽스 · 22: 타입 null(기존 작업) · 23: 키 자체가 없는 응답(옛 API) · 24: DB 작업
const tasks = [t(21, 'BUG_FIX'), t(22, null), t(23, undefined), t(24, 'DB_WORK')]

function stubApi() {
  useApiMock.mockImplementation((url: string) =>
    url.startsWith('/api/tasks')
      ? Promise.resolve({ content: tasks, totalElements: tasks.length, totalPages: 1 })
      : url === '/api/queue/stats'
        ? Promise.resolve({ avgDurationMs: null })
        : Promise.resolve(null),
  )
}

const mountOpts = {
  attachTo: document.body,
  // Nuxt 자동 임포트 컴포넌트(QueueStatsBar·NuxtLink)는 vitest에서 해석되지 않는다 — 경고 없이 마운트하려고 스텁한다
  global: { stubs: { QueueStatsBar: { template: '<div />' }, NuxtLink: { template: '<a><slot /></a>' } } },
}

const selectOf = (w: ReturnType<typeof mount>, id: string) =>
  w.findAllComponents(QSelect).find((c) => (c.vm.$attrs as Record<string, unknown>)['data-test'] === id)!
const taskListCalls = () => useApiMock.mock.calls.filter((c) => c[0] === '/api/tasks')
const lastParams = () => taskListCalls().at(-1)![1].params
const cardTitles = (w: ReturnType<typeof mount>, sel: string) => w.findAll(sel).map((c) => c.text())

beforeEach(() => {
  authStub.isAdmin = true
  stubApi()
})
afterEach(async () => {
  await setViewportWidth(1024)
  document.body.innerHTML = ''
})

describe('pages/tasks — 데스크톱 타입 필터 (스펙 2026-11-03 §4.3)', () => {
  it('고르기 전에는 type 파라미터를 보내지 않는다', async () => {
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(lastParams().type).toBeUndefined()
    w.unmount()
  })

  it('상태 select 바로 뒤에 있고 옵션은 전체 타입·네 타입·타입 없음이다', async () => {
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const selects = w.findAllComponents(QSelect)
    // tasks-index-mobile.spec.ts가 첫 QSelect를 상태 select로 잡는다 — 타입 select는 그 다음이다
    expect(selects[0].props('options')).toContainEqual({ label: '승인대기', value: 'AWAITING_APPROVAL' })
    expect((selects[1].vm.$attrs as Record<string, unknown>)['data-test']).toBe('task-filter-type')
    const options = selects[1].props('options') as { label: string; value: unknown }[]
    expect(options.map((o) => o.value)).toEqual([null, 'NEW_FEATURE', 'FEATURE_CHANGE', 'BUG_FIX', 'DB_WORK', 'NONE'])
    expect(options[0].label).toBe('전체 타입')
    expect(options.at(-1)!.label).toBe('타입 없음')
    w.unmount()
  })

  it('타입 없음을 고르면 type=NONE으로 다시 조회한다', async () => {
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await selectOf(w, 'task-filter-type').vm.$emit('update:modelValue', 'NONE')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks', expect.objectContaining({ params: expect.objectContaining({ type: 'NONE' }) }))
    w.unmount()
  })

  it('타입을 고르면 그 enum 이름을, 전체 타입으로 되돌리면 파라미터를 뺀다', async () => {
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await selectOf(w, 'task-filter-type').vm.$emit('update:modelValue', 'BUG_FIX')
    await flushPromises()
    expect(lastParams().type).toBe('BUG_FIX')
    await selectOf(w, 'task-filter-type').vm.$emit('update:modelValue', null)
    await flushPromises()
    expect(lastParams().type).toBeUndefined()
    w.unmount()
  })

  it('보드 카드는 타입 칩을 달고, 타입 없음(null·키 없음)이면 칩이 없다', async () => {
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const cards = w.findAll('.task-card')
    expect(cards).toHaveLength(4)
    const chipOf = (id: number) => cards.find((c) => c.text().includes(`#${id}`))!.find('[data-test="task-type-chip"]')
    expect(chipOf(21).text()).toBe('버그 픽스')
    expect(chipOf(24).text()).toBe('DB 작업')
    expect(chipOf(22).exists()).toBe(false)
    expect(chipOf(23).exists()).toBe(false)
    expect(w.text()).not.toContain('타입 없음')
    w.unmount()
  })
})

describe('pages/tasks — 모바일 타입 필터 (서버에 보내지 않고 화면에서 거른다)', () => {
  const sheetOption = (key: string) => document.body.querySelector(`[data-test="type-option-${key}"]`) as HTMLElement
  const cards = (w: ReturnType<typeof mount>) => cardTitles(w, '[data-test="task-card-compact"]')

  it('모바일은 type을 보내지 않는다 — 데스크톱에서 골라 두고 좁혀도 서버 조회는 좁아지지 않는다', async () => {
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await selectOf(w, 'task-filter-type').vm.$emit('update:modelValue', 'BUG_FIX')
    await flushPromises()
    expect(lastParams().type).toBe('BUG_FIX')

    useApiMock.mockClear()
    await setViewportWidth(390)
    await w.find('[data-test="mine-chip"]').trigger('click') // 모바일 목록이 다시 조회한다
    await flushPromises()
    expect(taskListCalls().length).toBeGreaterThan(0)
    expect(lastParams().type).toBeUndefined()
    w.unmount()
  })

  it('타입 칩을 눌러 고르면 받은 목록 안에서 그 타입의 카드만 남는다(조회 파라미터는 그대로)', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(cards(w)).toHaveLength(4)
    expect(w.find('[data-test="mobile-type-filter"]').text()).toContain('타입')

    useApiMock.mockClear()
    await w.find('[data-test="mobile-type-filter"]').trigger('click')
    await flushPromises()
    sheetOption('BUG_FIX').click()
    await flushPromises()

    expect(cards(w)).toHaveLength(1)
    expect(cards(w)[0]).toContain('작업 21')
    expect(w.find('[data-test="mobile-type-filter"]').text()).toContain('버그 픽스')
    expect(taskListCalls()).toHaveLength(0) // 서버를 다시 부르지 않는다
    w.unmount()
  })

  it('타입 없음은 null·키 없음 응답을 모두 걸러 내고, 전체 타입으로 되돌리면 다시 모두 보인다', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="mobile-type-filter"]').trigger('click')
    await flushPromises()
    sheetOption('NONE').click()
    await flushPromises()
    expect(cards(w).map((c) => /작업 (\d+)/.exec(c)![1])).toEqual(['22', '23'])

    await w.find('[data-test="mobile-type-filter"]').trigger('click')
    await flushPromises()
    sheetOption('all').click()
    await flushPromises()
    expect(cards(w)).toHaveLength(4)
    w.unmount()
  })

  it('카드는 타입 칩을 달고, 타입 없음이면 칩이 없다', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const compact = w.findAll('[data-test="task-card-compact"]')
    const chipOf = (id: number) => compact.find((c) => c.text().includes(`#${id}`))!.find('[data-test="task-type-chip"]')
    expect(chipOf(21).text()).toBe('버그 픽스')
    expect(chipOf(22).exists()).toBe(false)
    expect(chipOf(23).exists()).toBe(false)
    w.unmount()
  })

  it('고른 타입 칩의 ✕로 필터를 푼다', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="mobile-type-filter"]').trigger('click')
    await flushPromises()
    sheetOption('DB_WORK').click()
    await flushPromises()
    expect(cards(w)).toHaveLength(1)
    await w.find('[data-test="mobile-type-filter"] .q-chip__icon--remove').trigger('click')
    await flushPromises()
    expect(cards(w)).toHaveLength(4)
    w.unmount()
  })
})
