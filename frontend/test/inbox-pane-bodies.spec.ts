import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import InboxTaskPane from '../components/inbox/InboxTaskPane.vue'
import DesignReviewCard from '../components/DesignReviewCard.vue'
import ImplementationRetryDialog from '../components/tasks/ImplementationRetryDialog.vue'
import DeployEnvDialog from '../components/tasks/DeployEnvDialog.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { baseTask } from './fixtures/task'
import { INBOX_GROUPS } from '../composables/taskInbox'

// 처리함 처리 영역 — 승인 계열·실패 계열 본문 (스펙 2026-11-23 §5.3 표). 확인창은 실제 Quasar 다이얼로그를 누른다.

function disposeGlobalDialogs() {
  document.body.querySelectorAll<HTMLElement>('[id^="q-portal--dialog"]').forEach((el) => {
    ;(el as unknown as { __vue_app__?: { unmount: () => void } }).__vue_app__?.unmount()
  })
}
function dialogButton(label: string): HTMLButtonElement | undefined {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('.q-dialog button')).find((b) => b.textContent?.trim() === label)
}
async function clickDialogButton(label: string) {
  await vi.waitFor(() => expect(dialogButton(label)).toBeTruthy())
  dialogButton(label)!.click()
  await flushPromises()
}

const T = '2026-11-23T05:00:00Z'
function task(status: string, over: Record<string, unknown> = {}) {
  return {
    ...baseTask,
    id: 42,
    status,
    statusLabel: status,
    updatedAt: T,
    analysis: null,
    refRepos: [],
    repoCatalogId: 1,
    fix: { approvedRounds: 0, maxRounds: 3, activeRound: null },
    deployment: { deployUrl: 'http://10.0.0.1:18000', deployHostPort: 18000, deployImage: 'img', deployedAt: null, deployLog: null },
    ...over,
  }
}

let detail: Record<string, unknown>
function routeApi() {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (opts?.method === 'POST') return Promise.resolve({})
    if (url === `/api/tasks/${detail.id}`) return Promise.resolve(detail)
    if (url.endsWith('/fix-requests') || url.endsWith('/db-changes') || /\/interviews$/.test(url)) return Promise.resolve([])
    if (/^\/api\/interviews\/\d+$/.test(url)) return Promise.resolve({ statusName: 'AWAITING_INPUT', turns: [], plan: null })
    if (url === '/api/mcp-catalog' || url === '/api/repo-catalog') return Promise.resolve([])
    if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
    if (url.startsWith('/api/db-connections')) return Promise.resolve({ enabled: false, items: [] })
    return Promise.resolve(null)
  })
}

let w: VueWrapper | undefined
async function mountPane(t: Record<string, unknown>, stubs: Record<string, unknown> = {}) {
  detail = t
  routeApi()
  w = mount(InboxTaskPane, {
    props: { taskId: Number(t.id), answerDrafts: new Map(), refDraftStore: new Map(), canPrev: false, canNext: false },
    attachTo: document.body,
    global: { stubs },
  })
  await flushPromises()
  return w
}
const posts = () => useApiMock.mock.calls.filter((c) => c[1]?.method === 'POST')

describe('InboxTaskPane — 승인·실패 본문', () => {
  beforeEach(() => {
    authStub.isAdmin = true
    ;(authStub as any).isAuthenticated = true
  })
  afterEach(() => {
    w?.unmount()
    w = undefined
    disposeGlobalDialogs()
    document.body.innerHTML = ''
    authStub.isAdmin = false
  })

  it.each([
    ['COMPLETED', 'inbox-act-approve-impl', '/approve', '승인'],
    ['FAILED', 'inbox-act-retry', '/retry', null],
    ['CLOSE_REVIEW', 'inbox-act-approve-close', '/close/approve', '병합하고 종료'],
    ['CLOSE_FAILED', 'inbox-act-retry-close', '/close/retry', '종료 재시도'],
    ['DEPLOY_LOST', 'inbox-act-undeploy', '/undeploy', '배포 중지'],
    ['FIX_REVIEW', 'inbox-act-approve-fix', '/fix-requests/approve', '승인'],
  ])('%s: 버튼을_누르고_확인하면_API를_부르고_done', async (status, testId, suffix, confirmLabel) => {
    const wr = await mountPane(task(status, status === 'CLOSE_FAILED' ? { close: { progress: [] } } : {}))
    await wr.find(`[data-test="${testId}"]`).trigger('click')
    if (confirmLabel) await clickDialogButton(confirmLabel)
    await flushPromises()
    expect(posts().some((c) => String(c[0]).endsWith(suffix))).toBe(true)
    const done = wr.emitted('done')
    expect(done).toHaveLength(1)
    expect(done![0][0]).toMatchObject({ id: 42, status, updatedAt: T })
  })

  it('FIX_REVIEW_반려_확인창_취소면_done이_없다', async () => {
    const wr = await mountPane(task('FIX_REVIEW'))
    await wr.find('[data-test="inbox-act-reject-fix"]').trigger('click')
    await clickDialogButton('취소')
    expect(posts()).toHaveLength(0)
    expect(wr.emitted('done')).toBeUndefined()
  })

  it('CLOSE_FAILED_병합_전이면_종료_취소·기준_브랜치_반영이_보이고_병합_뒤면_안_보인다', async () => {
    const before = await mountPane(task('CLOSE_FAILED', { close: { progress: [] } }))
    expect(before.find('[data-test="inbox-act-cancel-close"]').exists()).toBe(true)
    expect(before.find('[data-test="inbox-act-sync-base"]').exists()).toBe(true)
    expect(before.find('[data-test="inbox-close-steps"]').exists()).toBe(true)
    before.unmount()
    w = undefined

    const after = await mountPane(task('CLOSE_FAILED', { close: { progress: ['MERGED'] } }))
    expect(after.find('[data-test="inbox-act-cancel-close"]').exists()).toBe(false)
    expect(after.find('[data-test="inbox-act-sync-base"]').exists()).toBe(false)
    expect(after.find('[data-test="inbox-act-retry-close"]').exists()).toBe(true)
  })

  it('IMPLEMENTATION_FAILED_재시도_창이_submitted면_done', async () => {
    const wr = await mountPane(task('IMPLEMENTATION_FAILED', { failureReason: '실패', implementationMode: 'SDD' }))
    expect(wr.find('[data-test="inbox-pr-link"]').exists()).toBe(true)
    await wr.find('[data-test="inbox-act-impl-retry"]').trigger('click')
    await flushPromises()
    const dlg = wr.findComponent(ImplementationRetryDialog)
    expect(dlg.props('modelValue')).toBe(true)
    expect(wr.emitted('done')).toBeUndefined()
    dlg.vm.$emit('submitted')
    await flushPromises()
    expect(wr.emitted('done')).toHaveLength(1)
  })

  it('DEPLOY_FAILED_재배포_창이_submitted면_done', async () => {
    const wr = await mountPane(task('DEPLOY_FAILED', { failureReason: '배포 실패' }))
    expect(wr.find('[data-test="inbox-act-fix-request"]').exists()).toBe(true)
    await wr.find('[data-test="inbox-act-redeploy"]').trigger('click')
    await flushPromises()
    const dlg = wr.findComponent(DeployEnvDialog)
    expect(dlg.props()).toMatchObject({ modelValue: true, mode: 'redeploy', taskId: '42' })
    dlg.vm.$emit('submitted')
    await flushPromises()
    expect(wr.emitted('done')).toHaveLength(1)
  })

  it('DEPLOY_LOST_종료_요청·수정_요청도_보인다', async () => {
    const wr = await mountPane(task('DEPLOY_LOST', { failureReason: '컨테이너 없음' }))
    expect(wr.find('[data-test="inbox-deploy-url"]').text()).toContain('http://10.0.0.1:18000')
    for (const id of ['redeploy', 'request-close', 'undeploy', 'fix-request']) {
      expect(wr.find(`[data-test="inbox-act-${id}"]`).exists()).toBe(true)
    }
  })

  it('DESIGN_REVIEW_카드가_refresh를_보내면_done', async () => {
    const design = {
      designMarkdown: '# d', mockupFilesJson: '[]', designProjectId: null, designUrl: null, rejectCount: 0,
      feedbackHistoryJson: '[]', approved: false, approvedBy: null, approvedAt: null,
    }
    const wr = await mountPane(task('DESIGN_REVIEW', { design }), { DesignReviewCard: true })
    const card = wr.findComponent(DesignReviewCard)
    expect(card.exists()).toBe(true)
    expect(card.props('isAdmin')).toBe(true)
    card.vm.$emit('refresh')
    await flushPromises()
    expect(wr.emitted('done')).toHaveLength(1)
  })

  it('확인_필요_상태마다_본문이_있다', async () => {
    const design = {
      designMarkdown: '# d', mockupFilesJson: '[]', designProjectId: null, designUrl: null, rejectCount: 0,
      feedbackHistoryJson: '[]', approved: false, approvedBy: null, approvedAt: null,
    }
    for (const status of INBOX_GROUPS.flatMap((g) => g.statuses)) {
      const wr = await mountPane(
        task(status, { interviewSessionId: 55, design, close: { progress: [] }, failureReason: '사유' }),
        { DesignReviewCard: true },
      )
      expect(wr.find('[data-test="inbox-body-pending"]').exists(), status).toBe(false)
      expect(wr.find('[data-test="inbox-body-idle"]').exists(), status).toBe(false)
      wr.unmount()
      w = undefined
    }
  })
})
