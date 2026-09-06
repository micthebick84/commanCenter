import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { useApiMock } from './mocks/nuxt'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = {
  attachTo: document.body,
  global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } },
}

export const baseTask = {
  id: 42, githubRepo: 'acme/widgets', repoAlias: 'Widgets', githubBranch: 'main', title: '제목', description: '설명',
  status: 'PR_CREATED', statusLabel: 'PR생성', requesterId: 'user1', retryCount: 0, maxRetry: 3, failureReason: null,
  mcpsExtra: [], envVars: [], interviewSessionId: null, createdAt: '2026-08-16T00:00:00Z', updatedAt: '2026-08-16T00:00:00Z',
  model: 'claude-opus-5', effort: 'high', designRequested: false,
  analysis: { markdownResult: '# 요약\n\n| a | b |\n|---|---|\n| 1 | 2 |', subtasksJson: '[]', durationMs: 1000, approved: true, approvedBy: null, approvedAt: null, completedAt: '2026-08-16T00:00:00Z' },
  design: null,
  implementation: { prUrl: 'https://github.com/acme/widgets/pull/1', prNumber: 1, headBranch: 'feature/x', headSha: 'abcdef1234567', implementationLog: null },
  deployment: null, attachments: [], stageUsage: [], totalCostUsd: 0.42, totalTokens: 123000,
}

describe('pages/tasks/[id] — 가로 오버플로 봉쇄 (스펙 2026-09-06 D1)', () => {
  it('요청 상세와 분석 결과 블록은 md-scroll 컨테이너 안에 있다', async () => {
    useApiMock.mockResolvedValue(baseTask)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const scrollers = w.findAll('.md-scroll')
    expect(scrollers.length).toBeGreaterThanOrEqual(2)
    w.unmount()
  })
})
