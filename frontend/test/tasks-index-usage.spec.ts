import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

// Wrap QPage in QLayout > QPageContainer for testing
const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }),
      })
  },
})

const taskList = [
  {
    id: 1,
    githubRepo: 'org/repo1',
    repoAlias: 'TestRepo1',
    githubBranch: 'main',
    title: '작업 1',
    description: '설명 1',
    status: 'PR_CREATED',
    statusLabel: 'PR생성',
    requesterId: 'user1',
    retryCount: 0,
    maxRetry: 3,
    failureReason: null,
    designRequested: false,
    createdAt: '2026-08-30T01:00:00Z',
    updatedAt: '2026-08-30T01:00:00Z',
    implementation: null,
    totalCostUsd: 1.23,
  },
  {
    id: 2,
    githubRepo: 'org/repo2',
    repoAlias: 'TestRepo2',
    githubBranch: 'dev',
    title: '작업 2',
    description: '설명 2',
    status: 'IMPLEMENTING',
    statusLabel: '구현중',
    requesterId: 'user2',
    retryCount: 0,
    maxRetry: 3,
    failureReason: null,
    designRequested: false,
    createdAt: '2026-08-30T02:00:00Z',
    updatedAt: '2026-08-30T02:00:00Z',
    implementation: null,
    totalCostUsd: 0.5,
  },
  {
    id: 3,
    githubRepo: 'org/repo3',
    repoAlias: 'TestRepo3',
    githubBranch: 'feature',
    title: '작업 3',
    description: '설명 3',
    status: 'PR_CREATED',
    statusLabel: 'PR생성',
    requesterId: 'user3',
    retryCount: 0,
    maxRetry: 3,
    failureReason: null,
    designRequested: false,
    createdAt: '2026-08-30T03:00:00Z',
    updatedAt: '2026-08-30T03:00:00Z',
    implementation: null,
    totalCostUsd: null,
  },
]

function stubApi() {
  useApiMock.mockImplementation((url: string, opts?: { method?: string; params?: any }) => {
    if (url === '/api/tasks') return Promise.resolve({ content: taskList, totalElements: 3, totalPages: 1 })
    if (url === '/api/repo-catalog') return Promise.resolve([])
    return Promise.resolve(null)
  })
}

describe('pages/tasks/index — 작업 목록 총비용 배지 (스펙 §6)', () => {
  beforeEach(() => {
    stubApi()
  })

  it('PR_CREATED + 1.23 → $1.23 배지 노출, IMPLEMENTING + 0.5 → 숨김, PR_CREATED + null → 숨김', async () => {
    const w = mount(PageWrapper)
    await flushPromises()
    const text = w.text()

    // 배지 노출되어야 함 (PR_CREATED + 1.23)
    expect(text).toContain('$1.23')

    // 배지 숨겨져야 함 (IMPLEMENTING 진행중 상태)
    expect(text).not.toContain('$0.50')

    // 배지 숨겨져야 함 (null) — 작업 3은 PR생성 상태이지만 totalCostUsd가 null이므로 배지 불표시
    // 작업 3 섹션: #3PR생성delete작업 3 (배지 없음)
    const task3Match = text.match(/#3PR생성[^#]*?작업 3/)
    expect(task3Match).toBeTruthy()
    const task3Section = task3Match![0]
    expect(task3Section).not.toContain('$')

    w.unmount()
  })
})
