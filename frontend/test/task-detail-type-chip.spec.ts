import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { useApiMock, authStub } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'
import { baseTask } from './fixtures/task'

// 작업 상세의 타입 칩 (스펙 2026-11-03 §4.3) — 데스크톱은 [id].vue 머리줄, 모바일은 TaskDetailMobile의 첫 섹션 머리.
// 타입 없음이면 칩을 그리지 않는다(기존 작업 전부에 같은 칩이 붙지 않게). 하네스는 task-detail-mobile.spec.ts와 같다.

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = {
  attachTo: document.body,
  global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } },
}

afterEach(async () => {
  await setViewportWidth(1024)
  document.body.innerHTML = ''
})

async function mountAt(width: number, taskType: string | null | undefined) {
  authStub.isAdmin = true
  useApiMock.mockResolvedValue(taskType === undefined ? baseTask : { ...baseTask, taskType })
  await setViewportWidth(width)
  const w = mount(PageWrapper, mountOpts)
  await flushPromises()
  return w
}

describe('pages/tasks/[id] — 타입 칩 (스펙 2026-11-03 §4.3)', () => {
  it('1280px: 머리줄에 칩 하나, 텍스트는 "버그 픽스"', async () => {
    const w = await mountAt(1280, 'BUG_FIX')
    const chips = w.findAll('[data-test="task-type-chip"]')
    expect(chips).toHaveLength(1)
    expect(chips[0].text()).toBe('버그 픽스')
    // 모델·effort 칩과 같은 머리줄에 있다
    expect(chips[0].element.closest('.conv-header-chips')).not.toBeNull()
    w.unmount()
  })

  it('390px: 요청 상세 섹션 머리에 칩 하나, 접힌 채로도 보이고 텍스트는 "버그 픽스"', async () => {
    const w = await mountAt(390, 'BUG_FIX')
    expect(w.find('[data-test="section-request"] .q-expansion-item__content').exists()).toBe(false) // 접힘
    const chips = w.findAll('[data-test="task-type-chip"]')
    expect(chips).toHaveLength(1)
    expect(chips[0].text()).toBe('버그 픽스')
    expect(w.find('[data-test="section-request"]').find('[data-test="task-type-chip"]').exists()).toBe(true)
    w.unmount()
  })

  it('다른 타입은 그 이름으로 보인다(두 폭 모두)', async () => {
    const desktop = await mountAt(1280, 'DB_WORK')
    expect(desktop.find('[data-test="task-type-chip"]').text()).toBe('DB 작업')
    desktop.unmount()
    const mobile = await mountAt(390, 'FEATURE_CHANGE')
    expect(mobile.find('[data-test="task-type-chip"]').text()).toBe('기능 수정')
    mobile.unmount()
  })

  it('taskType이 null이거나 키가 없으면 어느 폭에서도 칩이 없다', async () => {
    for (const taskType of [null, undefined]) {
      for (const width of [1280, 390]) {
        const w = await mountAt(width, taskType)
        expect(w.find('[data-test="task-type-chip"]').exists()).toBe(false)
        expect(w.text()).not.toContain('타입 없음')
        w.unmount()
      }
    }
  })
})
