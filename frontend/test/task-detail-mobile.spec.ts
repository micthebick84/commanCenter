import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, vi } from 'vitest'
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

  it('분석/디자인/구현/배포 데이터가 모두 없으면 분석·디자인 섹션은 아예 없고 구현·배포는 폴백 문구를 보여준다', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue({
      ...baseTask, status: 'AWAITING_APPROVAL', statusLabel: '승인대기',
      analysis: null, design: null, implementation: null, deployment: null,
    })
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="section-analysis"]').exists()).toBe(false)
    expect(w.find('[data-test="section-design"]').exists()).toBe(false)
    await w.find('[data-test="section-impl"] .q-item').trigger('click')
    await flushPromises()
    await w.find('[data-test="section-deploy"] .q-item').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="section-impl"]').text()).toContain('플랜 확정·승인 뒤 워커가 구현합니다.')
    expect(w.find('[data-test="section-deploy"]').text()).toContain('PR생성 상태에서 관리자가 배포할 수 있습니다.')
    w.unmount()
  })

  it('정보 섹션의 첨부 칩을 클릭하면 download가 downloadAttachment까지 이어진다', async () => {
    authStub.isAdmin = true
    const attTask = {
      ...baseTask,
      attachments: [{ id: 7, fileName: 'spec.pdf', contentType: 'application/pdf', sizeBytes: 2048, createdAt: '2026-08-16T00:00:00Z' }],
    }
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/tasks/42/attachments/7') return Promise.resolve(new Blob(['pdf']))
      return Promise.resolve(attTask)
    })
    if (!URL.createObjectURL) (URL as any).createObjectURL = () => ''
    if (!URL.revokeObjectURL) (URL as any).revokeObjectURL = () => {}
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="section-info"] .q-item').trigger('click')
    await flushPromises()
    const chip = w.findAll('.q-chip').find((c) => c.text().includes('spec.pdf'))
    expect(chip).toBeTruthy()
    await chip!.trigger('click')
    await flushPromises()
    await new Promise((r) => setTimeout(r, 0))
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/attachments/7', expect.objectContaining({ responseType: 'blob' }))
    w.unmount()
    vi.restoreAllMocks()
  })

  it('정보 섹션의 재시도 버튼을 클릭하면 retry API를 호출한다', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue({
      ...baseTask, status: 'FAILED', statusLabel: '분석실패', failureReason: '타임아웃',
      analysis: null, implementation: null,
    })
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="section-info"] .q-item').trigger('click')
    await flushPromises()
    useApiMock.mockClear()
    const retryBtn = w.findAll('button').find((b) => b.text().includes('재시도'))
    expect(retryBtn).toBeTruthy()
    await retryBtn!.trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/retry', { method: 'POST' })
    w.unmount()
  })

  it('하단 바 ⋮ 메뉴에 PR 링크가 있다 (관리자, PR생성)', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(baseTask)
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="bar-more"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-test="bar-more-pr"]')?.getAttribute('href')).toBe(
      'https://github.com/acme/widgets/pull/1',
    )
    w.unmount()
  })

  it('하단 바 ⋮ 메뉴에서 취소를 실행하면 cancel API를 호출한다 (요청자, 승인대기)', async () => {
    authStub.isAdmin = false
    useApiMock.mockResolvedValue({
      ...baseTask, status: 'AWAITING_APPROVAL', statusLabel: '승인대기',
      analysis: null, implementation: null,
    })
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="bar-more"]').trigger('click')
    await flushPromises()
    const cancelItem = document.body.querySelector('[data-test="bar-more-cancel"]') as HTMLElement | null
    expect(cancelItem).not.toBeNull()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    useApiMock.mockClear()
    cancelItem!.click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/cancel', { method: 'POST' })
    confirmSpy.mockRestore()
    w.unmount()
  })

  it('아코디언 헤더의 aria-controls는 펼친 콘텐츠의 id와 일치한다', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(baseTask)
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const header = w.find('[data-test="section-info"] [role="button"]')
    await header.trigger('click')
    await flushPromises()
    const controls = header.attributes('aria-controls')
    expect(controls).toBeTruthy()
    const content = w.find('[data-test="section-info"] .q-expansion-item__content')
    expect(content.attributes('id')).toBe(controls)
    w.unmount()
  })
})
