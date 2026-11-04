import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { baseTask } from './fixtures/task'
import { setViewportWidth } from './mocks/screen'

// 작업 상세 "DB 변경" 카드·연결 문구·종료 승인 경고 (스펙 2026-11-03 §6.5) — 하네스는 task-detail-fix.spec.ts와 같다.
;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const ADVICE = 'PR 병합 뒤 실행을 권장합니다 — 같은 DB를 쓰는 다른 화면이 아직 옛 컬럼을 쓸 수 있습니다'
const prCreated = { ...baseTask, status: 'PR_CREATED', statusLabel: 'PR생성', requesterId: 'alice' }

type Change = Record<string, unknown> & { id: number }
const change = (id: number, over: Record<string, unknown> = {}): Change => ({
  id, connectionId: 7, connectionName: '개발 DB', attempt: 1, fixRequestId: null, sqlText: `SELECT ${id}`,
  classification: 'EXECUTED', reason: null, affectedRows: null, error: null,
  createdAt: `2026-11-04T00:0${id}:00Z`, executedAt: `2026-11-04T00:0${id}:05Z`, executedBy: null, ...over,
})

function setMe(username: string | null) {
  ;(authStub as any).me = username === null ? null : { user_id: 7, username, email: null, authorities: [] }
}

interface Stub {
  changes?: unknown | (() => unknown)
  dbConnections?: unknown
  post?: (url: string) => Promise<unknown>
}
/** 작업·DB 변경 목록·연결 목록·POST를 URL로 가른다 — 모든 호출에 작업을 돌려주면 목록이 배열이 아니게 된다. */
function stubApi(task: Record<string, unknown>, s: Stub = {}) {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/tasks/42/fix-requests') return Promise.resolve([])
    if (url === '/api/tasks/42/db-changes') {
      const v = typeof s.changes === 'function' ? (s.changes as () => unknown)() : s.changes
      return v instanceof Promise ? v : Promise.resolve(v ?? [])
    }
    if (url.startsWith('/api/db-connections?repoCatalogId=')) return Promise.resolve(s.dbConnections ?? { enabled: false, items: [] })
    if (opts?.method === 'POST' && url.includes('/db-changes/')) return s.post ? s.post(url) : Promise.resolve({})
    return Promise.resolve(task)
  })
}

const body = () => document.body
const click = async (selector: string) => {
  const el = body().querySelector(selector) as HTMLElement
  expect(el, selector).not.toBeNull()
  el.click()
  await flushPromises()
}
const confirmOk = () => click('[data-test="confirm-dialog-ok"]')

describe('pages/tasks/[id] — DB 변경 카드 (스펙 2026-11-03 §6.5)', () => {
  beforeEach(() => { authStub.isAdmin = false; setMe(null) })
  afterEach(async () => {
    document.body.innerHTML = ''
    setMe(null)
    await setViewportWidth(1024)
  })

  async function mountWith(task: Record<string, unknown>, s: Stub = {}) {
    stubApi(task, s)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    return w
  }
  const rows = () => Array.from(body().querySelectorAll('[data-test^="db-change-row-"]')).map((e) => e.getAttribute('data-test'))

  describe('목록', () => {
    it('DB 변경 카드는 연결별·시간순으로 분류 라벨과 함께 그린다', async () => {
      const w = await mountWith(prCreated, {
        changes: [
          change(3, { connectionId: 8, connectionName: '통합 DB', classification: 'REJECTED', reason: '남의 테이블 DROP은 거부됩니다' }),
          change(2, { classification: 'DEFERRED', sqlText: 'ALTER TABLE t DROP COLUMN c', reason: '승인 대기로 기록됨', executedAt: null }),
          change(1, { sqlText: 'CREATE TABLE t_new (id int)', affectedRows: 0 }),
          change(4, { classification: 'FAILED', error: 'relation "x" does not exist', executedAt: null }),
          change(5, { classification: 'DISMISSED', executedBy: 'admin', executedAt: '2026-11-04T00:09:00Z' }),
        ],
      })
      expect(body().querySelector('[data-test="db-changes"]')).not.toBeNull()
      expect(body().querySelector('[data-test="db-changes"]')!.textContent).toContain('DB 변경')
      // 묶음은 시간순으로 먼저 나온 연결(개발 DB)이 앞, 안은 시간순
      expect(Array.from(body().querySelectorAll('[data-test="db-change-group-name"]')).map((e) => e.textContent)).toEqual(['개발 DB', '통합 DB'])
      expect(rows()).toEqual(['db-change-row-1', 'db-change-row-2', 'db-change-row-4', 'db-change-row-5', 'db-change-row-3'])
      const label = (id: number) => body().querySelector(`[data-test="db-change-label-${id}"]`)!.textContent!.trim()
      expect([1, 2, 3, 4, 5].map(label)).toEqual(['실행됨', '승인 대기', '거부됨', '실패', '실행 안 함'])
      expect(body().querySelector('[data-test="db-change-sql-1"]')!.textContent).toBe('CREATE TABLE t_new (id int)')
      expect(body().querySelector('[data-test="db-change-reason-3"]')!.textContent).toContain('남의 테이블 DROP은 거부됩니다')
      expect(body().querySelector('[data-test="db-change-error-4"]')!.textContent).toContain('relation "x" does not exist')
      expect(body().querySelector('[data-test="db-changes-pending"]')!.textContent).toContain('승인 대기 1건')
      w.unmount()
    })

    it('구현 결과 카드 뒤, 배포 카드 앞에 놓인다 — 구현 결과 카드가 없어도 기록이 있으면 보인다', async () => {
      const w = await mountWith(prCreated, { changes: [change(1)] })
      const card = body().querySelector('[data-test="db-changes"]')!
      const text = (el: Element | null) => el?.textContent ?? ''
      const prev = card.previousElementSibling
      expect(text(prev)).toContain('구현 결과')
      w.unmount()
      document.body.innerHTML = ''

      const failedNoPr = { ...baseTask, status: 'IMPLEMENTATION_FAILED', statusLabel: '구현실패', implementation: null }
      const w2 = await mountWith(failedNoPr, { changes: [change(1)] })
      expect(body().querySelector('[data-test="db-changes"]')).not.toBeNull()
      w2.unmount()
    })

    it('시도 번호·수정 요청·영향 행 수·처리한 관리자를 보여 준다', async () => {
      const w = await mountWith(prCreated, {
        changes: [
          change(1, { attempt: 2, fixRequestId: 5, affectedRows: 120 }),
          change(2, { classification: 'EXECUTED', executedBy: 'admin', executedAt: '2026-11-04T00:30:00Z' }),
        ],
      })
      const row1 = body().querySelector('[data-test="db-change-row-1"]')!.textContent!
      expect(row1).toContain('2회째 시도')
      expect(row1).toContain('수정 요청 #5')
      expect(row1).toContain('120행')
      expect(body().querySelector('[data-test="db-change-row-2"]')!.textContent).toContain('관리자 admin')
      w.unmount()
    })

    it('실행됨인데 실행 시각이 없으면 실행됐다고 단정하지 않고 확인을 안내한다', async () => {
      const w = await mountWith(prCreated, { changes: [change(1, { executedAt: null })] })
      expect(body().querySelector('[data-test="db-change-unconfirmed-1"]')!.textContent).toContain('DB에서 확인하세요')
      w.unmount()
    })

    it('응답이 배열이 아니면(작업 객체·오류 페이지) 카드를 그리지 않는다', async () => {
      useApiMock.mockResolvedValue(prCreated) // 모든 URL이 작업을 돌려주는 하네스 — db-changes도 배열이 아니다
      const w = mount(PageWrapper, mountOpts)
      await flushPromises()
      expect(body().querySelector('[data-test="db-changes"]')).toBeNull()
      w.unmount()
    })

    it('빈 목록이거나 조회가 실패하면 카드를 그리지 않는다', async () => {
      const w = await mountWith(prCreated, { changes: [] })
      expect(body().querySelector('[data-test="db-changes"]')).toBeNull()
      w.unmount()
      document.body.innerHTML = ''

      const w2 = await mountWith(prCreated, { changes: () => Promise.reject({ statusCode: 403 }) })
      expect(body().querySelector('[data-test="db-changes"]')).toBeNull()
      w2.unmount()
    })

    it('모바일에서도 카드가 보인다 — 수정 요청 기록 옆', async () => {
      await setViewportWidth(390)
      const w = await mountWith({ ...prCreated, fix: { activeRound: null, activeMode: null, approvedRounds: 0, maxRounds: 3 } }, {
        changes: [change(1)],
      })
      expect(body().querySelector('[data-test="db-changes"]')).not.toBeNull()
      expect(w.findAll('[data-test="db-changes"]')).toHaveLength(1) // 두 배치 중 하나만 마운트된다
      w.unmount()
    })
  })

  describe('승인 대기 — 관리자 실행·실행 안 함', () => {
    const changes = () => [change(1), change(2, { classification: 'DEFERRED', sqlText: 'ALTER TABLE t DROP COLUMN c', executedAt: null }), change(3, { classification: 'REJECTED' })]

    it('승인 대기는 관리자에게만 실행·실행 안 함 버튼이 보이고, 다른 분류에는 없다', async () => {
      const wUser = await mountWith(prCreated, { changes: changes() })
      expect(body().querySelector('[data-test="db-change-row-2"]')).not.toBeNull()
      expect(body().querySelector('[data-test="db-change-execute-2"]')).toBeNull()
      expect(body().querySelector('[data-test="db-change-dismiss-2"]')).toBeNull()
      wUser.unmount()
      document.body.innerHTML = ''

      authStub.isAdmin = true
      setMe('admin')
      const wAdmin = await mountWith(prCreated, { changes: changes() })
      expect(body().querySelector('[data-test="db-change-execute-2"]')).not.toBeNull()
      expect(body().querySelector('[data-test="db-change-dismiss-2"]')).not.toBeNull()
      for (const id of [1, 3]) {
        expect(body().querySelector(`[data-test="db-change-execute-${id}"]`), `실행 ${id}`).toBeNull()
        expect(body().querySelector(`[data-test="db-change-dismiss-${id}"]`), `실행 안 함 ${id}`).toBeNull()
      }
      wAdmin.unmount()
    })

    it('실행은 권장 문구를 확인한 뒤에만 POST하고, 성공하면 그 행을 실행됨으로 바꾸고 작업을 다시 읽는다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const executed = change(2, { classification: 'EXECUTED', sqlText: 'ALTER TABLE t DROP COLUMN c', executedBy: 'admin', executedAt: '2026-11-04T01:00:00Z' })
      const w = await mountWith(prCreated, { changes: changes(), post: () => Promise.resolve(executed) })
      const vm = w.findComponent(TaskDetail).vm as unknown as { $q: { notify: (o: unknown) => void } }
      const notifySpy = vi.spyOn(vm.$q, 'notify')
      useApiMock.mockClear()

      await click('[data-test="db-change-execute-2"]')
      expect(body().textContent).toContain(ADVICE)
      expect(body().textContent).toContain("'개발 DB'")
      expect(useApiMock).not.toHaveBeenCalledWith('/api/tasks/42/db-changes/2/execute', expect.anything()) // 확인 전에는 보내지 않는다

      await confirmOk()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/db-changes/2/execute', { method: 'POST' })
      expect(notifySpy).toHaveBeenLastCalledWith({ type: 'positive', message: 'SQL을 실행했습니다' })
      // 같은 행이 실행됨으로 바뀌고 버튼이 사라진다
      expect(body().querySelector('[data-test="db-change-label-2"]')!.textContent!.trim()).toBe('실행됨')
      expect(body().querySelector('[data-test="db-change-execute-2"]')).toBeNull()
      expect(body().querySelector('[data-test="db-changes-pending"]')).toBeNull()
      // 부모가 작업을 다시 읽는다(이력 갱신)
      expect(useApiMock.mock.calls.some((c) => c[0] === '/api/tasks/42' && !c[1])).toBe(true)
      w.unmount()
    })

    it('확인창을 취소하면 아무것도 보내지 않는다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(prCreated, { changes: changes() })
      useApiMock.mockClear()
      await click('[data-test="db-change-execute-2"]')
      await click('[data-test="confirm-dialog-cancel"]')
      expect(useApiMock.mock.calls.some((c) => (c[1] as { method?: string } | undefined)?.method === 'POST')).toBe(false)
      expect(body().querySelector('[data-test="db-change-execute-2"]')).not.toBeNull()
      w.unmount()
    })

    it('실행 안 함은 확인 뒤 POST …/dismiss — 성공하면 실행 안 함으로 바뀐다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const dismissed = change(2, { classification: 'DISMISSED', executedBy: 'admin', executedAt: '2026-11-04T01:00:00Z' })
      const w = await mountWith(prCreated, { changes: changes(), post: () => Promise.resolve(dismissed) })
      useApiMock.mockClear()
      await click('[data-test="db-change-dismiss-2"]')
      expect(body().textContent).toContain('병합 뒤 실행')
      await confirmOk()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/db-changes/2/dismiss', { method: 'POST' })
      expect(body().querySelector('[data-test="db-change-label-2"]')!.textContent!.trim()).toBe('실행 안 함')
      expect(body().querySelector('[data-test="db-change-dismiss-2"]')).toBeNull()
      w.unmount()
    })

    it('서버가 거절하면(이미 처리됨 등) 서버 메시지로 알리고 목록을 다시 읽는다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      let list: Change[] = changes()
      const w = await mountWith(prCreated, {
        changes: () => list,
        post: () => {
          list = [change(1), change(2, { classification: 'EXECUTED', executedBy: 'other', executedAt: '2026-11-04T01:00:00Z' }), change(3, { classification: 'REJECTED' })]
          return Promise.reject({ data: { message: '이미 처리된 SQL입니다' } })
        },
      })
      const vm = w.findComponent(TaskDetail).vm as unknown as { $q: { notify: (o: unknown) => void } }
      const notifySpy = vi.spyOn(vm.$q, 'notify')
      await click('[data-test="db-change-execute-2"]')
      await confirmOk()
      expect(notifySpy).toHaveBeenLastCalledWith({ type: 'negative', message: '이미 처리된 SQL입니다' })
      expect(body().querySelector('[data-test="db-change-label-2"]')!.textContent!.trim()).toBe('실행됨') // 다시 읽은 결과
      expect(body().querySelector('[data-test="db-change-execute-2"]')).toBeNull()
      w.unmount()
    })

    it('처리 중에는 두 버튼이 잠겨 같은 SQL을 두 번 보내지 못한다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      let release: (v: unknown) => void = () => {}
      const w = await mountWith(prCreated, {
        changes: changes(),
        post: () => new Promise((res) => { release = res }),
      })
      useApiMock.mockClear()
      await click('[data-test="db-change-execute-2"]')
      await confirmOk()
      const posts = () => useApiMock.mock.calls.filter((c) => (c[1] as { method?: string } | undefined)?.method === 'POST').length
      expect(posts()).toBe(1)
      const dismissBtn = body().querySelector('[data-test="db-change-dismiss-2"]') as HTMLButtonElement
      expect(dismissBtn.disabled || dismissBtn.getAttribute('aria-disabled') === 'true').toBe(true)
      ;(body().querySelector('[data-test="db-change-execute-2"]') as HTMLElement).click()
      await flushPromises()
      expect(body().querySelector('[data-test="confirm-dialog-ok"]')).toBeNull() // 두 번째 확인창도 안 뜬다
      expect(posts()).toBe(1)
      release(change(2, { classification: 'EXECUTED', executedBy: 'admin' }))
      await flushPromises()
      w.unmount()
    })
  })

  describe('DB 연결 문구 — 쓰기 가능한 연결이 있을 때만 "읽기 전용"을 바꾼다', () => {
    const conn = (id: number, over: Record<string, unknown> = {}) => ({
      id, repoCatalogId: 1, scope: 'REPO', name: `연결${id}`, dbType: 'POSTGRESQL', host: 'h', port: 5432,
      databaseName: 'dev', username: 'u', enabled: true, mine: false, allowWrite: false, ...over,
    })
    const task = { ...prCreated, repoCatalogId: 1, dbConnectionIds: [7] }
    const caption = () => body().querySelector('[data-test="task-db-caption"]')?.textContent?.replace(/\s+/g, ' ').trim()

    it('데스크톱: 고른 연결이 쓰기 허용이고 레포에서 쓰기가 열려 있으면 구현 단계 쓰기를 알린다', async () => {
      const w = await mountWith(task, { dbConnections: { enabled: true, writeEnabled: true, items: [conn(7, { allowWrite: true })] } })
      expect(caption()).toContain('구현 단계에서는 쓰기 가능')
      expect(caption()).toContain('인터뷰·디자인은 읽기 전용')
      w.unmount()
    })

    it('쓰기 허용이 아니거나, 레포에서 쓰기가 안 열렸거나, 옛 응답이면 읽기 전용 그대로', async () => {
      for (const list of [
        { enabled: true, writeEnabled: true, items: [conn(7)] },
        { enabled: true, writeEnabled: false, items: [conn(7, { allowWrite: true })] },
        { enabled: true, items: [conn(7, { allowWrite: true })] },
      ]) {
        const w = await mountWith(task, { dbConnections: list })
        expect(caption()).toBe('DB 연결 (읽기 전용 조회)')
        w.unmount()
        document.body.innerHTML = ''
      }
    })

    it('모바일 정보 영역: 쓰기 가능이면 문구가 바뀌고 아니면 "(읽기 전용)"이다', async () => {
      await setViewportWidth(390)
      for (const [list, expected] of [
        [{ enabled: true, writeEnabled: true, items: [conn(7, { allowWrite: true })] }, '1개 연결 (구현 단계에서는 쓰기 가능)'],
        [{ enabled: true, writeEnabled: true, items: [conn(7)] }, '1개 연결 (읽기 전용)'],
      ] as const) {
        const w = await mountWith(task, { dbConnections: list })
        await w.find('[data-test="section-info"] .q-item').trigger('click')
        await flushPromises()
        expect(w.find('[data-test="task-db-caption"]').text()).toContain(expected)
        w.unmount()
        document.body.innerHTML = ''
      }
    })
  })

  describe('종료 승인 확인창의 승인 대기 SQL 경고', () => {
    const closeReview = { ...baseTask, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', requesterId: 'alice', close: { progress: [] as string[], mergeCommitSha: null, notePath: null } }
    const BASE = 'PR을 main에 병합하고, 배포를 중지하고, 옵시디언 노트를 쓴 뒤 브랜치를 삭제합니다. 되돌릴 수 없습니다.'
    const deferred = (id: number) => change(id, { classification: 'DEFERRED', executedAt: null })

    async function openApprove(s: Stub) {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(closeReview, s)
      useApiMock.mockClear()
      await w.find('[data-test="next-action-primary"]').trigger('click')
      await flushPromises()
      return w
    }

    it('승인 대기 SQL이 2건이면 병합 문장 뒤에 한 줄이 붙고, 승인은 그대로 close/approve를 POST한다', async () => {
      const w = await openApprove({ changes: [deferred(1), deferred(2), change(3)] })
      expect(body().textContent).toContain(`${BASE} 승인 대기 SQL 2건 — 병합 뒤 실행 필요`)
      await confirmOk()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/approve', { method: 'POST', body: undefined })
      w.unmount()
    })

    it('승인 대기가 없으면 문장은 그대로다', async () => {
      const w = await openApprove({ changes: [change(1)] })
      expect(body().textContent).toContain(BASE)
      expect(body().textContent).not.toContain('승인 대기 SQL')
      w.unmount()
    })

    it('건수 조회가 실패하면 경고 없이 확인창을 띄우고 그대로 승인할 수 있다', async () => {
      const w = await openApprove({ changes: () => Promise.reject({ statusCode: 500 }) })
      expect(body().textContent).toContain(BASE)
      expect(body().textContent).not.toContain('승인 대기 SQL')
      await confirmOk()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/approve', { method: 'POST', body: undefined })
      w.unmount()
    })
  })
})
