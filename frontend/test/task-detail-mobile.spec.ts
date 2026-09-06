import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { useApiMock, authStub } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

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

describe('pages/tasks/[id] — 모바일 (스펙 2026-09-06 §4.2)', () => {
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = '' })

  it('lt.md: 아코디언 섹션 + 현재 단계(구현)만 펼침 + 하단 액션 바(관리자 배포) + 헤더에 모델 칩 없음', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(baseTask)
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    for (const k of ['request', 'analysis', 'impl', 'deploy', 'interviews', 'info']) {
      expect(w.find(`[data-test="section-${k}"]`).exists()).toBe(true)
    }
    expect(w.find('[data-test="section-impl"] .q-expansion-item__content').exists()).toBe(true) // 펼침
    expect(w.find('[data-test="section-request"] .q-expansion-item__content').exists()).toBe(false) // 접힘
    const bar = w.find('.next-action--bar')
    expect(bar.exists()).toBe(true)
    expect(bar.find('[data-test="next-action-primary"]').text()).toContain('배포')
    expect(w.find('.conv-header-chips').exists()).toBe(false)
    expect(w.text()).not.toContain('claude-opus-5') // 정보 섹션은 접힘 상태
    w.unmount()
  })

  it('분석 결과 섹션의 "전체 보기"는 전체화면 마크다운 뷰어를 연다', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(baseTask)
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="section-analysis"] .q-item').trigger('click')
    await flushPromises()
    await w.find('[data-test="analysis-open-viewer"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('.q-dialog__inner--maximized')).not.toBeNull()
    expect(document.body.querySelector('[data-test="markdown-viewer"] h1')?.textContent).toBe('요약')
    w.unmount()
  })
})
