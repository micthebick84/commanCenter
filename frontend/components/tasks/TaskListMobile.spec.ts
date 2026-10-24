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
