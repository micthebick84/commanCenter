import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { useApiMock } from './mocks/nuxt'

// useRoute는 Nuxt 자동 임포트 — test/setup.ts의 다른 auto-import처럼 전역 스텁으로 제공
// (vi.mock('vue-router')는 SFC가 명시 임포트할 때만 듣는다)
;(globalThis as any).useRoute = () => ({ params: { id: '42' } })

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }),
      })
  },
})

// q-breadcrumbs-el의 :to가 router-link를 요구 — 라우터 없이 마운트하므로 스텁.
// DesignReviewCard는 이 스펙만 [id].vue를 마운트하므로 발생하는
// "Failed to resolve component" 소음을 stub으로 제거한다.
const mountOpts = {
  attachTo: document.body,
  global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } },
}

const taskFixture = {
  id: 42,
  githubRepo: 'acme/widgets',
  repoAlias: null,
  githubBranch: 'main',
  title: '제목',
  description: '설명',
  status: 'PR_CREATED',
  statusLabel: 'PR생성',
  requesterId: 'user1',
  retryCount: 0,
  maxRetry: 3,
  failureReason: null,
  mcpsExtra: [],
  envVars: [],
  interviewSessionId: null,
  createdAt: '2026-08-16T00:00:00Z',
  updatedAt: '2026-08-16T00:00:00Z',
  model: 'claude-opus-5',
  effort: 'high',
  designRequested: false,
  analysis: null,
  design: null,
  // PR_CREATED 상태에서 실제로 나타나는 구현 결과 — IMPLEMENTATION 단계 chip이 앵커하는
  // "구현 결과" 카드(v-if="task.implementation")가 렌더링되려면 필요하다.
  implementation: {
    prUrl: 'https://github.com/acme/widgets/pull/1',
    prNumber: 1,
    headBranch: 'feature/x',
    headSha: 'abcdef1234567',
    implementationLog: null,
  },
  deployment: null,
  attachments: [],
  stageUsage: [
    { stage: 'INTERVIEW', costUsd: 0.5, inputTokens: 12300, outputTokens: 4500,
      cacheCreationTokens: 100, cacheReadTokens: 90000 },
    { stage: 'IMPLEMENTATION', costUsd: 0.42, inputTokens: 8000, outputTokens: 2000,
      cacheCreationTokens: 0, cacheReadTokens: 50000 },
  ],
  totalCostUsd: 0.92,
  totalTokens: 26800,
}

describe('task detail — stage usage chips', () => {
  it('usage가 있으면 헤더 총합 chip과 단계 chip을 렌더링한다', async () => {
    useApiMock.mockResolvedValueOnce(taskFixture)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.text()).toContain('$0.92')
    expect(w.text()).toContain('26.8k')
    expect(w.text()).toContain('$0.42')
  })

  it('usage가 없으면 usage chip을 렌더링하지 않는다', async () => {
    useApiMock.mockResolvedValueOnce({ ...taskFixture, stageUsage: [], totalCostUsd: null, totalTokens: null })
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.text()).not.toContain('$0.92')
  })
})
