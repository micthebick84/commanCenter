import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock, useRuntimeConfigMock } from './mocks/nuxt'
import { baseTask } from './fixtures/task'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const emptyClose = { progress: [] as string[], mergeCommitSha: null, notePath: null }
const deployment = { deployUrl: 'http://x:8080', deployHostPort: 8080, deployImage: 'img', deployedAt: null, deployLog: null }

function setMe(username: string | null) {
  ;(authStub as any).me = username === null ? null : { user_id: 7, username, email: null, authorities: [] }
}

describe('pages/tasks/[id] — 작업 종료 (스펙 2026-10-13 §6)', () => {
  beforeEach(() => { authStub.isAdmin = false; setMe(null) })
  afterEach(() => {
    document.body.innerHTML = ''
    setMe(null)
    useRuntimeConfigMock.mockImplementation(() => ({ public: { apiBaseUrl: '/api', pollIntervalMs: 5000 } }))
  })

  async function mountWith(task: Record<string, unknown>) {
    useApiMock.mockResolvedValue(task)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    return w
  }

  it('요청자 본인(username 일치, 비관리자)은 DEPLOYED에서 "확인 완료 — 종료 요청"이 주 행동이고, 다른 사람이면 아니다', async () => {
    setMe('alice')
    let w = await mountWith({ ...baseTask, status: 'DEPLOYED', statusLabel: '배포완료', requesterId: 'alice', deployment })
    expect(w.find('[data-test="next-action-primary"]').text()).toContain('확인 완료 — 종료 요청')
    w.unmount()
    document.body.innerHTML = ''

    w = await mountWith({ ...baseTask, status: 'DEPLOYED', statusLabel: '배포완료', requesterId: 'bob', deployment })
    expect(w.find('[data-test="next-action-primary"]').text()).not.toContain('종료 요청')
    w.unmount()
  })

  it('CLOSE_REVIEW: 종료 카드와 4단계가 보이고 관리자에게 반려 버튼이 보인다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({ ...baseTask, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', deployment, close: emptyClose })
    expect(w.find('[data-test="close-card"]').exists()).toBe(true)
    for (const k of ['MERGED', 'UNDEPLOYED', 'NOTE_WRITTEN', 'BRANCH_DELETED']) {
      expect(w.find(`[data-test="close-step-${k}"]`).exists()).toBe(true)
    }
    expect(w.find('[data-test="close-reject"]').exists()).toBe(true)
    w.unmount()
  })

  it('비관리자에게는 반려 버튼이 없다', async () => {
    const w = await mountWith({ ...baseTask, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', deployment, close: emptyClose })
    expect(w.find('[data-test="close-card"]').exists()).toBe(true)
    expect(w.find('[data-test="close-reject"]').exists()).toBe(false)
    w.unmount()
  })

  it('관리자 종료 승인: 확인 뒤 close/approve를 POST하고 작업을 다시 읽는다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({ ...baseTask, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', deployment, close: emptyClose })
    useApiMock.mockClear()
    await w.find('[data-test="next-action-primary"]').trigger('click')
    await flushPromises()
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/approve', { method: 'POST', body: undefined })
    const urls = useApiMock.mock.calls.map((c) => c[0])
    expect(urls.indexOf('/api/tasks/42')).toBeGreaterThan(urls.indexOf('/api/tasks/42/close/approve'))
    w.unmount()
  })

  it('병합 전 CLOSE_FAILED: 관리자에게 "종료 취소"가 보이고, 확인 뒤 close/cancel을 POST한다 — 병합 뒤에는 안 보인다', async () => {
    authStub.isAdmin = true
    let w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: 'head 불일치', deployment, close: emptyClose,
    })
    const btn = w.find('[data-test="close-cancel"]')
    expect(btn.exists()).toBe(true)
    useApiMock.mockClear()
    await btn.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('병합 전 단계에서 실패한 종료를 취소하고 배포 상태로 되돌립니다.')
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/cancel', { method: 'POST', body: undefined })
    w.unmount()
    document.body.innerHTML = ''

    w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: '노트 실패', deployment,
      close: { progress: ['MERGED'], mergeCommitSha: 'abcdef1234', notePath: null },
    })
    expect(w.find('[data-test="close-cancel"]').exists()).toBe(false)
    w.unmount()
  })

  it('관리자 종료 재시도: 병합이 되돌릴 수 없다는 확인 뒤 close/retry를 POST한다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: 'CI 대기', deployment, close: emptyClose,
    })
    useApiMock.mockClear()
    await w.find('[data-test="next-action-primary"]').trigger('click')
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalledWith('/api/tasks/42/close/retry', expect.anything())
    expect(document.body.textContent).toContain('남은 종료 단계(병합 포함 가능)를 다시 실행합니다. 병합은 되돌릴 수 없습니다.')
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/retry', { method: 'POST', body: undefined })
    w.unmount()
  })

  it('CLOSED: 노트 경로가 obsidian:// 링크로 나온다(볼트 이름 설정 시)', async () => {
    useRuntimeConfigMock.mockImplementation(() => ({
      public: { apiBaseUrl: '/api', pollIntervalMs: 5000, obsidianVault: 'Obsidian Vault' },
    }))
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { progress: ['MERGED', 'UNDEPLOYED', 'NOTE_WRITTEN', 'BRANCH_DELETED'], mergeCommitSha: 'abcdef1234', notePath: 'HamonSoft/netisMaker/a-작업42.md' },
    })
    const a = w.find('[data-test="close-note"] a')
    expect(a.exists()).toBe(true)
    expect(a.attributes('href')).toMatch(/^obsidian:\/\/open\?vault=Obsidian%20Vault/)
    w.unmount()
  })

  it('볼트 이름이 없으면 노트 경로만 보이고 링크는 없다', async () => {
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { progress: [], mergeCommitSha: null, notePath: 'HamonSoft/netisMaker/a-작업42.md' },
    })
    expect(w.find('[data-test="close-note"]').text()).toContain('a-작업42.md')
    expect(w.find('[data-test="close-note"] a').exists()).toBe(false)
    w.unmount()
  })
})
