import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import TaskListMobile from './TaskListMobile.vue'

const task = (status: string, statusLabel: string) => ({
  id: 7, title: '작업 7', status, statusLabel, repoAlias: 'Netis7.0', githubRepo: 'micthebick84/netis7.0',
  githubBranch: 'main', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-24T00:00:00Z', requesterId: 'admin',
})

describe('TaskListMobile — ⋮ 시트는 최신 목록의 작업을 그린다 (TODOS 10-08 관찰)', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('시트를 연 채로 폴링이 상태를 바꾸면 시트도 새 상태를 보여 준다', async () => {
    const w = mount(TaskListMobile, {
      props: { tasks: [task('DEPLOYED', '배포완료')], isAdmin: true },
      attachTo: document.body,
    })
    await w.find('[data-test="card-menu"]').trigger('click')
    await flushPromises()
    const sheet = () => document.body.querySelector('[data-test="task-action-sheet"]')!
    expect(sheet().textContent).toContain('배포완료')

    await w.setProps({ tasks: [task('PR_CREATED', 'PR생성')] })
    await flushPromises()
    expect(sheet().textContent).toContain('PR생성')
    expect(sheet().textContent).not.toContain('배포완료')
    w.unmount()
  })

  it('작업이 목록에서 빠져도 시트는 마지막으로 본 작업을 유지한다', async () => {
    const w = mount(TaskListMobile, {
      props: { tasks: [task('DEPLOYED', '배포완료')], isAdmin: true },
      attachTo: document.body,
    })
    await w.find('[data-test="card-menu"]').trigger('click')
    await flushPromises()
    await w.setProps({ tasks: [] })
    await flushPromises()
    expect(document.body.querySelector('[data-test="task-action-sheet"]')!.textContent).toContain('작업 7')
    w.unmount()
  })
})

describe('TaskListMobile — 타입 필터 (스펙 2026-11-03 §4.3)', () => {
  const typed = (id: number, status: string, statusLabel: string, taskType?: string | null) => ({
    ...task(status, statusLabel),
    id,
    title: `작업 ${id}`,
    ...(taskType === undefined ? {} : { taskType }),
  })
  const list = [
    typed(1, 'PR_CREATED', 'PR생성', 'BUG_FIX'),
    typed(2, 'DEPLOYED', '배포완료', 'BUG_FIX'),
    typed(3, 'PR_CREATED', 'PR생성', null),
    typed(4, 'DEPLOYED', '배포완료'),
    typed(5, 'DEPLOYED', '배포완료', 'DB_WORK'),
  ]
  const ids = (w: ReturnType<typeof mount>) =>
    w.findAll('[data-test="task-card-compact"]').map((c) => /#(\d+)/.exec(c.text())![1])
  const sheetOption = (key: string) => document.body.querySelector(`[data-test="type-option-${key}"]`) as HTMLElement
  async function pickType(w: ReturnType<typeof mount>, key: string) {
    await w.find('[data-test="mobile-type-filter"]').trigger('click')
    await flushPromises()
    sheetOption(key).click()
    await flushPromises()
  }

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('시트는 전체 타입·네 타입·타입 없음을 건수와 함께 보여 준다', async () => {
    const w = mount(TaskListMobile, { props: { tasks: list, isAdmin: true }, attachTo: document.body })
    await w.find('[data-test="mobile-type-filter"]').trigger('click')
    await flushPromises()
    const rows = Array.from(document.body.querySelectorAll('[data-test^="type-option-"]'))
    expect(rows.map((r) => r.getAttribute('data-test'))).toEqual([
      'type-option-all',
      'type-option-NEW_FEATURE',
      'type-option-FEATURE_CHANGE',
      'type-option-BUG_FIX',
      'type-option-DB_WORK',
      'type-option-NONE',
    ])
    // [이름, 건수]
    const row = (key: string) => Array.from(sheetOption(key).querySelectorAll('.q-item__section')).map((s) => s.textContent!.trim())
    expect(row('all')).toEqual(['전체 타입', '5'])
    expect(row('BUG_FIX')).toEqual(['버그 픽스', '2'])
    expect(row('NONE')).toEqual(['타입 없음', '2']) // null과 키 없음
    expect(row('NEW_FEATURE')).toEqual(['신규 개발', '0'])
    w.unmount()
  })

  it('타입 필터는 상태 필터와 함께 걸린다', async () => {
    const w = mount(TaskListMobile, { props: { tasks: list, isAdmin: true }, attachTo: document.body })
    await pickType(w, 'BUG_FIX')
    expect(ids(w).sort()).toEqual(['1', '2'])

    await w.find('[data-test="status-filter-chip"]').trigger('click')
    await flushPromises()
    ;(document.body.querySelector('[data-test="status-option-DEPLOYED"]') as HTMLElement).click()
    await flushPromises()
    expect(ids(w)).toEqual(['2'])
    w.unmount()
  })

  it('받은 목록이 폴링으로 바뀌어도 고른 타입으로 계속 거른다', async () => {
    const w = mount(TaskListMobile, { props: { tasks: list, isAdmin: true }, attachTo: document.body })
    await pickType(w, 'NONE')
    expect(ids(w).sort()).toEqual(['3', '4'])
    await w.setProps({ tasks: [...list, typed(6, 'PR_CREATED', 'PR생성', null), typed(7, 'PR_CREATED', 'PR생성', 'BUG_FIX')] })
    await flushPromises()
    expect(ids(w).sort()).toEqual(['3', '4', '6'])
    w.unmount()
  })

  it('조건에 맞는 작업이 없으면 빈 안내와 "전체 보기"가 나오고, 누르면 타입 필터도 풀린다', async () => {
    const w = mount(TaskListMobile, { props: { tasks: list, isAdmin: true }, attachTo: document.body })
    await pickType(w, 'FEATURE_CHANGE')
    expect(w.find('[data-test="empty-filter"]').exists()).toBe(true)
    await w.find('[data-test="empty-reset"]').trigger('click')
    expect(ids(w)).toHaveLength(5)
    expect(w.find('[data-test="mobile-type-filter"]').text()).toContain('타입')
    expect(w.find('[data-test="mobile-type-filter"]').text()).not.toContain('기능 수정')
    w.unmount()
  })
})
