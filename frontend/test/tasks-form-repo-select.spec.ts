import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

// QPage requires QLayout > QPageContainer ancestry; wrap for testing.
const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }),
      })
  },
})

describe('tasks form — repo alias select', () => {
  it('loads repo catalog when the form dialog is opened', async () => {
    // onMounted fires useTaskPolling (GET /api/tasks)
    // Use mockResolvedValue([]) as the blanket fallback for all API calls
    useApiMock.mockResolvedValue([])

    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()

    // Clear call history so we can assert only the dialog-open calls
    useApiMock.mockClear()
    useApiMock.mockResolvedValue([])

    // Find and click the '작업 등록' button
    const addBtn = w.findAll('button').find((b) => b.text().includes('작업 등록'))
    expect(addBtn).toBeTruthy()
    await addBtn!.trigger('click')
    await flushPromises()

    // Catalog load must have been called
    expect(useApiMock).toHaveBeenCalledWith('/api/repo-catalog')

    // The repo-select element should be present in the dialog
    const repoSelect = document.querySelector('[data-test="repo-select"]')
    expect(repoSelect).not.toBeNull()

    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  it('renders repo alias in the task list when repoAlias is present', async () => {
    useApiMock.mockResolvedValue({
      content: [
        {
          id: 1,
          githubRepo: 'owner/repo',
          repoAlias: 'Netis7.0',
          githubBranch: 'main',
          title: '테스트 작업',
          description: 'desc',
          status: 'PENDING',
          statusLabel: '작업대기',
          requesterId: 1,
          retryCount: 0,
          maxRetry: 3,
          failureReason: null,
          createdAt: '2026-01-01T00:00:00Z',
          updatedAt: '2026-01-01T00:00:00Z',
          implementation: null,
        },
      ],
      totalElements: 1,
      totalPages: 1,
    })

    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()

    // The table should show the alias, not the raw repo
    expect(w.text()).toContain('Netis7.0')

    w.unmount()
  })
})

describe('tasks form — 등록 payload', () => {
  it('모델/effort/MCP 없이 4필드만 보낸다', async () => {
    useApiMock.mockResolvedValue([])
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()

    const vm = w.findComponent(TasksIndex).vm as any
    vm.draft.repoCatalogId = 1
    vm.draft.githubBranch = 'main'
    vm.draft.title = 'RBAC 추가'
    vm.draft.description = '역할 기반 권한'

    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    await vm.submit()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks', {
      method: 'POST',
      body: {
        repoCatalogId: 1,
        githubBranch: 'main',
        title: 'RBAC 추가',
        description: '역할 기반 권한',
      },
    })

    w.unmount()
  })

  it('등록 다이얼로그에 모델·MCP 선택 UI가 없다', async () => {
    useApiMock.mockResolvedValue([])
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()

    const addBtn = w.findAll('button').find((b) => b.text().includes('작업 등록'))
    await addBtn!.trigger('click')
    await flushPromises()

    const dialog = document.querySelector('.q-dialog')!
    expect(dialog.textContent).not.toContain('모델')
    expect(dialog.textContent).not.toContain('MCP')

    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })
})
