import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { baseTask } from './fixtures/task'
import { setViewportWidth } from './mocks/screen'
import { BASE_SYNC_ABORT_CONFIRM } from '../composables/taskFix'

// 배포 뒤 수정 요청 화면 (스펙 2026-11-03 §6·§7) — 요청·승인·반려·철회·중단·이력. 하네스는 task-detail-close.spec.ts와 같다.
;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const deployment = { deployUrl: 'http://x:8080', deployHostPort: 8080, deployImage: 'img', deployedAt: null, deployLog: null }
const fixNone = { activeRound: null, activeMode: null, approvedRounds: 0, maxRounds: 3 }
const deployed = {
  ...baseTask, status: 'DEPLOYED', statusLabel: '배포완료', requesterId: 'alice', deployment, fix: fixNone,
}
const fixReview = { ...deployed, status: 'FIX_REVIEW', statusLabel: '수정승인대기' }
const roundFailed = {
  ...baseTask, status: 'IMPLEMENTATION_FAILED', statusLabel: '구현실패', requesterId: 'alice', deployment,
  failureReason: 'claude exec 실패: timeout', implementationMode: 'SDD',
  fix: { activeRound: 2, activeMode: 'DIRECT', approvedRounds: 2, maxRounds: 3 },
}

function setMe(username: string | null) {
  ;(authStub as any).me = username === null ? null : { user_id: 7, username, email: null, authorities: [] }
}

/** 작업 조회·수정 요청 기록 조회(GET)·그 밖의 POST를 URL로 가른다 — 모든 호출에 작업을 돌려주면 기록 목록이 배열이 아니게 된다. */
function stubApi(task: Record<string, unknown>, history: unknown[] = []) {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/tasks/42/fix-requests' && opts?.method !== 'POST') return Promise.resolve(history)
    return Promise.resolve(task)
  })
}

const body = () => document.body

describe('pages/tasks/[id] — 수정 요청 (스펙 2026-11-03 §6)', () => {
  beforeEach(() => { authStub.isAdmin = false; setMe(null) })
  afterEach(async () => {
    document.body.innerHTML = ''
    setMe(null)
    await setViewportWidth(1024)
  })

  async function mountWith(task: Record<string, unknown>, history: unknown[] = []) {
    stubApi(task, history)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    return w
  }
  async function type(selector: string, value: string) {
    const el = body().querySelector(selector) as HTMLInputElement | HTMLTextAreaElement
    expect(el, selector).not.toBeNull()
    el.value = value
    el.dispatchEvent(new Event('input'))
    await flushPromises()
  }
  const click = async (selector: string) => {
    const el = body().querySelector(selector) as HTMLElement
    expect(el, selector).not.toBeNull()
    el.click()
    await flushPromises()
  }

  describe('수정 요청 내기', () => {
    it('등록자는 배포완료에서 수정 요청을 낸다 — 방식 선택은 없고 안내 문구가 있다', async () => {
      setMe('alice')
      const w = await mountWith(deployed)
      await w.find('[data-test="fix-request-open"]').trigger('click')
      await flushPromises()
      const dialog = body().querySelector('[data-test="fix-request-dialog"]')!
      expect(dialog).not.toBeNull()
      expect(dialog.textContent).toContain('수정 요청 (1/3)')
      expect(dialog.textContent).toContain('같은 브랜치에서 이어 고친 뒤 자동으로 다시 배포해요. 등록자 요청은 관리자 승인 뒤 시작돼요.')
      expect(dialog.querySelector('[data-test="fix-request-mode"]')).toBeNull() // 등록자는 방식을 고르지 않는다

      useApiMock.mockClear()
      stubApi(deployed)
      await type('[data-test="fix-request-body"]', '대상장비 탭 갱신 안 됨')
      await click('[data-test="fix-request-submit"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests', {
        method: 'POST',
        body: { body: '대상장비 탭 갱신 안 됨', implementationMode: undefined },
      })
      // 성공하면 창이 닫히고 작업을 다시 읽는다
      expect(useApiMock.mock.calls.some((c) => c[0] === '/api/tasks/42' && !c[1])).toBe(true)
      w.unmount()
    })

    it('관리자는 방식을 고른다 — 기본 리뷰, 선택지는 리뷰·직접(SDD 없음), 회차 표기는 다음 회차/상한', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const task = { ...deployed, fix: { ...fixNone, approvedRounds: 1 } }
      const w = await mountWith(task)
      await w.find('[data-test="fix-request-open"]').trigger('click')
      await flushPromises()
      const dialog = body().querySelector('[data-test="fix-request-dialog"]')!
      expect(dialog.textContent).toContain('수정 요청 (2/3)')
      expect(dialog.querySelector('[data-test="fix-request-mode"]')).not.toBeNull()
      expect(dialog.querySelector('[data-test="impl-mode-REVIEW"]')).not.toBeNull()
      expect(dialog.querySelector('[data-test="impl-mode-DIRECT"]')).not.toBeNull()
      expect(dialog.querySelector('[data-test="impl-mode-SDD"]')).toBeNull()

      // 고르지 않으면 리뷰, 직접을 고르면 직접
      await type('[data-test="fix-request-body"]', '버튼이 안 눌려요')
      useApiMock.mockClear()
      stubApi(task)
      await click('[data-test="fix-request-submit"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests', {
        method: 'POST', body: { body: '버튼이 안 눌려요', implementationMode: 'REVIEW' },
      })
      w.unmount()
      document.body.innerHTML = ''

      const w2 = await mountWith(task)
      await w2.find('[data-test="fix-request-open"]').trigger('click')
      await flushPromises()
      await click('[data-test="impl-mode-DIRECT"]')
      await type('[data-test="fix-request-body"]', '문구 오타')
      useApiMock.mockClear()
      stubApi(task)
      await click('[data-test="fix-request-submit"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests', {
        method: 'POST', body: { body: '문구 오타', implementationMode: 'DIRECT' },
      })
      w2.unmount()
    })

    it('본문이 비어 있으면 보낼 수 없다', async () => {
      setMe('alice')
      const w = await mountWith(deployed)
      await w.find('[data-test="fix-request-open"]').trigger('click')
      await flushPromises()
      const submit = () => body().querySelector('[data-test="fix-request-submit"]') as HTMLButtonElement
      expect(submit().disabled).toBe(true)
      await type('[data-test="fix-request-body"]', '   ')
      expect(submit().disabled).toBe(true)
      await type('[data-test="fix-request-body"]', '내용')
      expect(submit().disabled).toBe(false)
      w.unmount()
    })

    it('서버가 거절하면 서버 메시지로 알리고 창과 본문을 그대로 둔다', async () => {
      setMe('alice')
      const w = await mountWith(deployed)
      const vm = w.findComponent(TaskDetail).vm as unknown as { $q: { notify: (opts: unknown) => void } }
      const notifySpy = vi.spyOn(vm.$q, 'notify')
      await w.find('[data-test="fix-request-open"]').trigger('click')
      await flushPromises()
      await type('[data-test="fix-request-body"]', '고쳐 주세요')
      useApiMock.mockImplementation((url: string, opts?: { method?: string }) =>
        opts?.method === 'POST' ? Promise.reject({ data: { message: '열린 수정 회차가 있습니다' } }) : Promise.resolve(deployed),
      )
      await click('[data-test="fix-request-submit"]')
      expect(notifySpy).toHaveBeenLastCalledWith({ type: 'negative', message: '열린 수정 회차가 있습니다' })
      expect(body().querySelector('[data-test="fix-request-dialog"]')).not.toBeNull()
      expect((body().querySelector('[data-test="fix-request-body"]') as HTMLTextAreaElement).value).toBe('고쳐 주세요')
      w.unmount()
    })

    it('배포중단됨·배포실패에서도 낼 수 있다', async () => {
      setMe('alice')
      for (const status of ['DEPLOY_LOST', 'DEPLOY_FAILED']) {
        const w = await mountWith({ ...deployed, status })
        expect(w.find('[data-test="fix-request-open"]').exists(), status).toBe(true)
        w.unmount()
        document.body.innerHTML = ''
      }
    })

    it('조건이 안 맞으면 버튼이 없다 — 다른 사용자·브랜치 없음·열린 회차·다른 상태', async () => {
      const cases: Array<[string, string, Record<string, unknown>]> = [
        ['다른 사용자', 'bob', deployed],
        ['브랜치 없음', 'alice', { ...deployed, implementation: { ...baseTask.implementation, headBranch: null } }],
        ['열린 회차', 'alice', { ...deployed, fix: { activeRound: 1, activeMode: 'REVIEW', approvedRounds: 1, maxRounds: 3 } }],
        ['PR생성', 'alice', { ...deployed, status: 'PR_CREATED' }],
        ['수정승인대기', 'alice', fixReview],
        ['회차 현황 없음', 'alice', { ...deployed, fix: null }],
      ]
      for (const [name, me, task] of cases) {
        setMe(me)
        const w = await mountWith(task)
        expect(w.find('[data-test="fix-request-open"]').exists(), name).toBe(false)
        w.unmount()
        document.body.innerHTML = ''
      }
    })

    it('모바일: 하단 더 보기에 "수정 요청"이 있고 카드 버튼은 숨는다', async () => {
      setMe('alice')
      await setViewportWidth(390)
      const w = await mountWith(deployed)
      expect(w.find('[data-test="fix-request-open"]').exists()).toBe(false)
      await w.find('[data-test="bar-more"]').trigger('click')
      await flushPromises()
      await click('[data-test="bar-more-fix-request"]')
      expect(body().querySelector('[data-test="fix-request-dialog"]')).not.toBeNull()
      w.unmount()
    })
  })

  describe('승인·반려·철회', () => {
    it('관리자는 수정승인대기에서 리뷰 기본으로 승인한다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(fixReview)
      expect(w.find('[data-test="next-action-primary"]').text()).toContain('수정 승인')
      await w.find('[data-test="next-action-primary"]').trigger('click')
      await flushPromises()
      const radios = body().querySelectorAll('.q-dialog [role="radio"]')
      expect(radios).toHaveLength(2)
      expect(radios[0]!.textContent).toContain('리뷰')
      expect(radios[1]!.textContent).toContain('직접')
      expect(radios[0]!.getAttribute('aria-checked')).toBe('true')
      expect(radios[1]!.getAttribute('aria-checked')).toBe('false')

      useApiMock.mockClear()
      stubApi(fixReview)
      await click('[data-test="fix-approve-ok"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/approve', {
        method: 'POST', body: { implementationMode: 'REVIEW' },
      })
      w.unmount()
    })

    it('직접을 고르면 DIRECT로 승인한다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(fixReview)
      await w.find('[data-test="next-action-primary"]').trigger('click')
      await flushPromises()
      ;(body().querySelectorAll('.q-dialog [role="radio"]')[1] as HTMLElement).click()
      await flushPromises()
      useApiMock.mockClear()
      stubApi(fixReview)
      await click('[data-test="fix-approve-ok"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/approve', {
        method: 'POST', body: { implementationMode: 'DIRECT' },
      })
      w.unmount()
    })

    it('관리자 반려는 사유를 받는다 — 데스크톱 버튼', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(fixReview)
      await w.find('[data-test="fix-reject"]').trigger('click')
      await flushPromises()
      const ok = () => body().querySelector('[data-test="fix-reject-ok"]') as HTMLButtonElement
      expect(ok().disabled).toBe(true) // 사유 없이는 못 보낸다
      await type('.q-dialog input', '  범위 밖 요청이에요  ')
      useApiMock.mockClear()
      stubApi(fixReview)
      await click('[data-test="fix-reject-ok"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/reject', {
        method: 'POST', body: { reason: '범위 밖 요청이에요' },
      })
      w.unmount()
    })

    it('관리자 반려는 사유를 받는다 — 모바일 더 보기', async () => {
      authStub.isAdmin = true
      setMe('admin')
      await setViewportWidth(390)
      const w = await mountWith(fixReview)
      expect(w.find('[data-test="fix-reject"]').exists()).toBe(false)
      await w.find('[data-test="bar-more"]').trigger('click')
      await flushPromises()
      await click('[data-test="bar-more-fix-reject"]')
      await type('.q-dialog input', '지금은 어려워요')
      useApiMock.mockClear()
      stubApi(fixReview)
      await click('[data-test="fix-reject-ok"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/reject', {
        method: 'POST', body: { reason: '지금은 어려워요' },
      })
      w.unmount()
    })

    it('비관리자에게는 승인·반려가 없다', async () => {
      setMe('alice')
      const w = await mountWith(fixReview)
      expect(w.find('[data-test="fix-reject"]').exists()).toBe(false)
      expect(w.find('[data-test="next-action-primary"]').text()).not.toContain('수정 승인')
      w.unmount()
    })

    it('등록자는 수정승인대기에서 확인 뒤 철회한다', async () => {
      setMe('alice')
      const w = await mountWith(fixReview)
      expect(w.find('[data-test="next-action-primary"]').text()).toContain('수정 요청 철회')
      await w.find('[data-test="next-action-primary"]').trigger('click')
      await flushPromises()
      expect(useApiMock).not.toHaveBeenCalledWith('/api/tasks/42/fix-requests/withdraw', expect.anything())
      useApiMock.mockClear()
      stubApi(fixReview)
      await click('[data-test="confirm-dialog-ok"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/withdraw', { method: 'POST', body: undefined })
      w.unmount()
    })

    it('수정승인대기는 승인 대기 중임을 알리고 요청 버튼은 없다', async () => {
      setMe('alice')
      const w = await mountWith(fixReview)
      expect(w.find('[data-test="next-action-text"]').text()).toContain('관리자 승인을 기다리는 중')
      expect(w.find('[data-test="fix-request-open"]').exists()).toBe(false)
      expect(w.find('[data-test="fix-round-banner"]').exists()).toBe(false) // 회차는 승인 때 열린다
      w.unmount()
    })

    it('열린 회차가 있으면 회차·방식 안내가 보이고 배포 카드가 남는다', async () => {
      setMe('alice')
      const w = await mountWith({ ...baseTask, status: 'IMPLEMENTING', statusLabel: '구현중', requesterId: 'alice', deployment, fix: { activeRound: 2, activeMode: 'DIRECT', approvedRounds: 2, maxRounds: 3 } })
      expect(w.find('[data-test="fix-round-banner"]').text()).toContain('수정 2회차 진행 중 (직접)')
      expect(w.find('[data-test="fix-request-open"]').exists()).toBe(false)
      w.unmount()
    })
  })

  describe('상한·종료된 작업', () => {
    it('상한에 닿으면 버튼 대신 새 작업 등록 링크가 나온다', async () => {
      setMe('alice')
      const w = await mountWith({ ...deployed, fix: { ...fixNone, approvedRounds: 3 } })
      expect(w.find('[data-test="fix-request-open"]').exists()).toBe(false)
      const link = w.find('[data-test="fix-request-new-task"]')
      expect(link.exists()).toBe(true)
      expect(link.attributes('href')).toContain('fromTask=42')
      expect(w.find('[data-test="fix-limit-note"]').text()).toContain('3/3')
      w.unmount()
    })

    it('상한 전에는 새 작업 등록 링크가 없다', async () => {
      setMe('alice')
      const w = await mountWith({ ...deployed, fix: { ...fixNone, approvedRounds: 2 } })
      expect(w.find('[data-test="fix-request-new-task"]').exists()).toBe(false)
      w.unmount()
    })

    it('모바일: 상한에 닿으면 더 보기에 새 작업 등록이 있다', async () => {
      setMe('alice')
      await setViewportWidth(390)
      const w = await mountWith({ ...deployed, fix: { ...fixNone, approvedRounds: 3 } })
      await w.find('[data-test="bar-more"]').trigger('click')
      await flushPromises()
      expect(body().querySelector('[data-test="bar-more-fix-request"]')).toBeNull()
      expect(body().querySelector('[data-test="bar-more-fix-new-task"]')?.getAttribute('href')).toContain('fromTask=42')
      w.unmount()
    })

    it('작업종료된 작업에도 새 작업 등록 링크가 있다 (등록자·관리자만)', async () => {
      setMe('alice')
      const closed = { ...baseTask, status: 'CLOSED', statusLabel: '작업종료', requesterId: 'alice', deployment, fix: fixNone }
      let w = await mountWith(closed)
      expect(w.find('[data-test="fix-request-new-task"]').attributes('href')).toContain('fromTask=42')
      w.unmount()
      document.body.innerHTML = ''
      setMe('bob')
      w = await mountWith(closed)
      expect(w.find('[data-test="fix-request-new-task"]').exists()).toBe(false)
      w.unmount()
    })
  })

  describe('수정 중단', () => {
    it('회차 구현실패에서 관리자는 수정 중단을 고를 수 있다 — 확인 뒤 abort를 POST한다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(roundFailed)
      expect(w.find('[data-test="next-action-primary"]').text()).toContain('구현 재시도')
      await w.find('[data-test="fix-abort"]').trigger('click')
      await flushPromises()
      expect(body().textContent).toContain('수정 회차를 닫고')
      useApiMock.mockClear()
      stubApi(roundFailed)
      await click('[data-test="confirm-dialog-ok"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/abort', { method: 'POST', body: undefined })
      w.unmount()
    })

    it('모바일: 더 보기에 수정 중단이 있다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      await setViewportWidth(390)
      const w = await mountWith(roundFailed)
      expect(w.find('[data-test="fix-abort"]').exists()).toBe(false)
      await w.find('[data-test="bar-more"]').trigger('click')
      await flushPromises()
      await click('[data-test="bar-more-fix-abort"]')
      useApiMock.mockClear()
      stubApi(roundFailed)
      await click('[data-test="confirm-dialog-ok"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/abort', { method: 'POST', body: undefined })
      w.unmount()
    })

    it('첫 구현의 구현실패(열린 회차 없음)와 비관리자에게는 수정 중단이 없다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      let w = await mountWith({ ...roundFailed, fix: fixNone })
      expect(w.find('[data-test="fix-abort"]').exists()).toBe(false)
      w.unmount()
      document.body.innerHTML = ''
      authStub.isAdmin = false
      setMe('alice')
      w = await mountWith(roundFailed)
      expect(w.find('[data-test="fix-abort"]').exists()).toBe(false)
      w.unmount()
    })
  })

  describe('기준 브랜치 반영 회차 (BASE_SYNC)', () => {
    const syncRound = { activeRound: 1, activeMode: 'DIRECT', activeKind: 'BASE_SYNC', approvedRounds: 0, maxRounds: 3 }
    const syncRunning = {
      ...baseTask, status: 'IMPLEMENTING', statusLabel: '구현중', requesterId: 'alice', deployment, fix: syncRound,
    }
    const syncFailed = {
      ...baseTask, status: 'IMPLEMENTATION_FAILED', statusLabel: '구현실패', requesterId: 'alice', deployment,
      failureReason: '병합 충돌 해결 실패', fix: syncRound,
    }

    it('열린 회차의 배너는 "기준 브랜치 반영 1회차 진행 중 — 끝나면 자동으로 다시 배포됩니다"다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(syncRunning)
      expect(w.find('[data-test="fix-round-banner"]').text()).toContain('기준 브랜치 반영 1회차 진행 중 — 끝나면 자동으로 다시 배포됩니다')
      expect(w.find('[data-test="fix-round-banner"]').text()).not.toContain('수정 1회차')
      w.unmount()
    })

    it('구현실패에서 중단 버튼 라벨은 "반영 중단"이고 확인 문구는 BASE_SYNC_ABORT_CONFIRM이다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(syncFailed)
      const btn = w.find('[data-test="fix-abort"]')
      expect(btn.text()).toContain('반영 중단')
      expect(btn.text()).not.toContain('수정 중단')
      await btn.trigger('click')
      await flushPromises()
      expect(body().textContent).toContain(BASE_SYNC_ABORT_CONFIRM)
      expect(body().textContent).not.toContain('수정 회차를 닫고')
      useApiMock.mockClear()
      stubApi(syncFailed)
      await click('[data-test="confirm-dialog-ok"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/abort', { method: 'POST', body: undefined })
      w.unmount()
    })

    it('FIX 회차의 중단 버튼은 그대로 "수정 중단"이다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(roundFailed)
      expect(w.find('[data-test="fix-abort"]').text()).toContain('수정 중단')
      w.unmount()
    })

    it('이력 카드: BASE_SYNC 행은 "기준 브랜치 반영 1회차"·"변경 없음"이고 방식 칩이 없다 (FIX 행은 그대로)', async () => {
      setMe('alice')
      const history = [
        {
          id: 21, kind: 'BASE_SYNC', round: 1, status: 'APPROVED', implementationMode: 'DIRECT', body: '기준 브랜치 main 반영',
          requestedBy: 'admin', requestedAt: '2026-11-13T01:00:00Z', decidedBy: 'admin', decidedAt: '2026-11-13T01:00:00Z',
          decisionReason: null, attempt: 1, outcome: 'NO_CHANGE', closedAt: '2026-11-13T01:10:00Z',
        },
        {
          id: 20, kind: 'FIX', round: 1, status: 'APPROVED', implementationMode: 'DIRECT', body: '고칠 내용',
          requestedBy: 'alice', requestedAt: '2026-11-12T01:00:00Z', decidedBy: 'admin', decidedAt: '2026-11-12T01:10:00Z',
          decisionReason: null, attempt: 1, outcome: 'DEPLOYED', closedAt: '2026-11-12T02:00:00Z',
        },
      ]
      const w = await mountWith(deployed, history)
      const items = w.find('[data-test="fix-history"]').findAll('[data-test="fix-history-item"]')
      expect(items).toHaveLength(2)
      expect(items[0]!.text()).toContain('기준 브랜치 반영 1회차')
      expect(items[0]!.text()).toContain('변경 없음')
      expect(items[0]!.text()).toContain('기준 브랜치 main 반영')
      expect(items[0]!.text()).not.toContain('직접')
      expect(items[1]!.text()).not.toContain('기준 브랜치 반영')
      expect(items[1]!.text()).toContain('직접')
      expect(items[1]!.text()).toContain('재배포 완료')
      w.unmount()
    })

    it('구현 재시도 창: 제목·안내가 BASE_SYNC 문구이고 방식 선택이 없으며 바디에 implementationMode가 없다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(syncFailed)
      await w.find('[data-test="next-action-primary"]').trigger('click')
      await flushPromises()
      const dialog = body().querySelector('[data-test="impl-retry-dialog"]')!
      expect(dialog.textContent).toContain('기준 브랜치 반영 1회차 재시도')
      expect(dialog.textContent).toContain('기준 브랜치 반영을 다시 시도합니다 (같은 브랜치)')
      expect(dialog.querySelector('[data-test="impl-mode-REVIEW"]')).toBeNull()
      expect(dialog.querySelector('[data-test="impl-mode-DIRECT"]')).toBeNull()
      useApiMock.mockClear()
      stubApi(syncFailed)
      await click('[data-test="impl-retry-submit"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/implementation/retry', { method: 'POST', body: {} })
      w.unmount()
    })
  })

  describe('구현 재시도 창 — 열린 회차 (스펙 §7)', () => {
    it('제목이 "수정 N회차 구현 재시도 (같은 브랜치)"이고 방식은 리뷰·직접뿐이며 회차의 방식으로 시작한다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(roundFailed)
      await w.find('[data-test="next-action-primary"]').trigger('click')
      await flushPromises()
      const dialog = body().querySelector('[data-test="impl-retry-dialog"]')!
      expect(dialog.textContent).toContain('수정 2회차 구현 재시도 (같은 브랜치)')
      expect(dialog.textContent).toMatch(/수정 \d+회차/) // 아래 '열린 회차가 없으면' 시험의 부정 검사가 실제 문구에 걸리는 모양인지
      expect(dialog.textContent).toContain('같은 브랜치')
      expect(dialog.textContent).not.toContain('베이스에서 처음부터 새 브랜치')
      expect(dialog.querySelector('[data-test="impl-mode-REVIEW"]')).not.toBeNull()
      expect(dialog.querySelector('[data-test="impl-mode-DIRECT"]')).not.toBeNull()
      expect(dialog.querySelector('[data-test="impl-mode-SDD"]')).toBeNull()
      // 작업의 방식(SDD)이 아니라 이 회차의 방식(DIRECT)으로 시작한다
      useApiMock.mockClear()
      stubApi(roundFailed)
      await click('[data-test="impl-retry-submit"]')
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/implementation/retry', {
        method: 'POST', body: { implementationMode: 'DIRECT' },
      })
      w.unmount()
    })

    it('열린 회차가 없으면 예전 그대로다 (제목·SDD 선택지)', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith({ ...roundFailed, fix: fixNone })
      await w.find('[data-test="next-action-primary"]').trigger('click')
      await flushPromises()
      const dialog = body().querySelector('[data-test="impl-retry-dialog"]')!
      expect(dialog.textContent).toContain('구현 재시도')
      expect(dialog.textContent).not.toMatch(/수정 \d+회차/)
      expect(dialog.textContent).toContain('베이스에서 처음부터 새 브랜치')
      expect(dialog.querySelector('[data-test="impl-mode-SDD"]')).not.toBeNull()
      w.unmount()
    })
  })

  describe('수정 요청 기록 카드', () => {
    const history = [
      {
        id: 12, round: 2, status: 'APPROVED', implementationMode: 'DIRECT', body: '두 번째로 고칠 내용',
        requestedBy: 'alice', requestedAt: '2026-11-03T01:00:00Z', decidedBy: 'admin', decidedAt: '2026-11-03T01:10:00Z',
        decisionReason: null, attempt: 1, outcome: null, closedAt: null,
      },
      {
        id: 11, round: 1, status: 'APPROVED', implementationMode: 'REVIEW', body: '대상장비 탭 갱신 안 됨',
        requestedBy: 'alice', requestedAt: '2026-11-02T01:00:00Z', decidedBy: 'admin', decidedAt: '2026-11-02T01:10:00Z',
        decisionReason: null, attempt: 2, outcome: 'DEPLOYED', closedAt: '2026-11-02T02:00:00Z',
      },
      {
        id: 10, round: null, status: 'REJECTED', implementationMode: null, body: '오타 하나',
        requestedBy: 'alice', requestedAt: '2026-11-01T01:00:00Z', decidedBy: 'admin', decidedAt: '2026-11-01T01:05:00Z',
        decisionReason: '범위 밖', attempt: 0, outcome: null, closedAt: null,
      },
    ]

    it('요청이 있으면 회차·상태·방식·본문·결과·반려 사유가 보인다 (최신순 그대로)', async () => {
      setMe('alice')
      const w = await mountWith(deployed, history)
      const card = w.find('[data-test="fix-history"]')
      expect(card.exists()).toBe(true)
      const items = card.findAll('[data-test="fix-history-item"]')
      expect(items).toHaveLength(3)
      expect(items[0]!.text()).toContain('2회차')
      expect(items[0]!.text()).toContain('승인됨')
      expect(items[0]!.text()).toContain('직접')
      expect(items[0]!.text()).toContain('두 번째로 고칠 내용')
      expect(items[1]!.text()).toContain('1회차')
      expect(items[1]!.text()).toContain('리뷰')
      expect(items[1]!.text()).toContain('재배포 완료')
      expect(items[1]!.text()).toContain('대상장비 탭 갱신 안 됨')
      expect(items[2]!.text()).toContain('반려됨')
      expect(items[2]!.text()).toContain('범위 밖')
      expect(items[2]!.text()).not.toContain('회차')
      w.unmount()
    })

    it('상태가 바뀌어 작업을 다시 읽으면 기록도 다시 조회한다', async () => {
      setMe('alice')
      let latest: Record<string, unknown> = fixReview
      useApiMock.mockImplementation((url: string, opts?: { method?: string }) =>
        url === '/api/tasks/42/fix-requests' && opts?.method !== 'POST' ? Promise.resolve(history) : Promise.resolve(latest),
      )
      const w = mount(PageWrapper, mountOpts)
      await flushPromises()
      const listCalls = () => useApiMock.mock.calls.filter((c) => c[0] === '/api/tasks/42/fix-requests' && c[1]?.method !== 'POST').length
      expect(listCalls()).toBe(1)

      await w.find('[data-test="next-action-primary"]').trigger('click') // 철회 확인창 열기
      await flushPromises()
      latest = { ...deployed, updatedAt: '2026-11-03T05:00:00Z' } // 철회되어 배포완료로 돌아간 응답
      await click('[data-test="confirm-dialog-ok"]')
      expect(listCalls()).toBe(2)
      w.unmount()
    })

    it('요청이 하나도 없으면 카드 자체가 없다', async () => {
      setMe('alice')
      const w = await mountWith(deployed, [])
      expect(w.find('[data-test="fix-history"]').exists()).toBe(false)
      w.unmount()
    })

    it('회차 현황이 없는 응답(구 API)은 기록을 조회하지 않는다', async () => {
      const w = await mountWith({ ...deployed, fix: null }, history)
      expect(useApiMock.mock.calls.some((c) => c[0] === '/api/tasks/42/fix-requests')).toBe(false)
      expect(w.find('[data-test="fix-history"]').exists()).toBe(false)
      w.unmount()
    })

    it('모바일에서도 보인다', async () => {
      setMe('alice')
      await setViewportWidth(390)
      const w = await mountWith(deployed, history)
      expect(w.find('[data-test="fix-history"]').exists()).toBe(true)
      w.unmount()
    })
  })
})
