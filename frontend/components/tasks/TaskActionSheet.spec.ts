import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import TaskActionSheet from './TaskActionSheet.vue'

const pr = {
  id: 18,
  title: '장비 현황 화면 개발',
  status: 'PR_CREATED',
  statusLabel: 'PR생성',
  repoAlias: 'Netis7.0',
  githubRepo: 'o/r',
  githubBranch: 'main',
  createdAt: '2026-08-24T00:00:00Z',
  updatedAt: '2026-08-24T00:00:00Z',
  implementation: { prUrl: 'https://github.com/x/pull/13', prNumber: 13 },
  deployment: null,
}
function open(task: any, isAdmin: boolean, viewerId?: string) {
  return mount(TaskActionSheet, {
    props: { task, isAdmin, viewerId, modelValue: true },
    attachTo: document.body,
  })
}
const body = () => document.body

describe('TaskActionSheet (스펙 2026-09-06 §4.1 ⋮ 액션 시트)', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('관리자 + PR생성: 상세·PR 열기·배포(전이)·삭제 항목, 배포 클릭은 move(MoveDef) emit', async () => {
    const w = open(pr, true)
    await flushPromises()
    expect(body().querySelector('[data-test="sheet-detail"]')).not.toBeNull()
    expect(
      body().querySelector('[data-test="sheet-pr"]')?.getAttribute('href'),
    ).toBe('https://github.com/x/pull/13')
    const move = body().querySelector('[data-test="sheet-move"]') as HTMLElement
    expect(move.textContent).toContain('배포')
    move.click()
    await flushPromises()
    expect(w.emitted('move')?.[0]?.[0]).toMatchObject({
      to: 'DEPLOY_PENDING',
      path: 'deploy',
      confirm: true,
    })
    expect(body().querySelector('[data-test="sheet-cancel"]')).toBeNull() // PR생성은 취소 불가
    w.unmount()
  })

  it('요청자에게는 전이 항목이 없고, 승인대기는 취소 항목이 있다', async () => {
    const w = open(
      {
        ...pr,
        status: 'AWAITING_APPROVAL',
        statusLabel: '승인대기',
        implementation: null,
      },
      false,
    )
    await flushPromises()
    expect(body().querySelector('[data-test="sheet-move"]')).toBeNull()
    ;(body().querySelector('[data-test="sheet-cancel"]') as HTMLElement).click()
    expect(w.emitted('cancel')).toHaveLength(1)
    w.unmount()
  })

  it('배포 활성 상태의 삭제 항목은 비활성 + 이유 캡션', async () => {
    const w = open({ ...pr, status: 'DEPLOYED', statusLabel: '배포완료' }, true)
    await flushPromises()
    const remove = body().querySelector(
      '[data-test="sheet-remove"]',
    ) as HTMLElement
    expect(remove.getAttribute('aria-disabled')).toBe('true')
    expect(remove.textContent).toContain('먼저 중지')
    w.unmount()
  })

  it('배포 활성 상태에서는 삭제를 클릭해도 remove를 emit하지 않고 시트도 닫히지 않는다 — 리뷰 파인딩 2', async () => {
    const w = open({ ...pr, status: 'DEPLOYED', statusLabel: '배포완료' }, true)
    await flushPromises()
    const remove = body().querySelector(
      '[data-test="sheet-remove"]',
    ) as HTMLElement
    remove.click()
    await flushPromises()
    // v-close-popup의 close 처리는 setTimeout(0) 매크로태스크로 지연 실행된다(Quasar
    // ClosePopup 디렉티브) — flushPromises만으로는 마이크로태스크만 비워지고 이 타이머는
    // 그대로 남으므로, 실제로 시트가 닫히는지 보려면 매크로태스크 tick까지 기다려야 한다.
    await new Promise((r) => setTimeout(r, 0))
    expect(w.emitted('remove')).toBeUndefined()
    expect(body().querySelector('[data-test="task-action-sheet"]')).not.toBeNull()
    w.unmount()
  })

  // 스펙 2026-11-03 §6.9 — DB 작업은 PR생성에서 배포 없이 종료를 요청한다. 등록자(viewerId 일치)와 관리자에게만 항목이 보인다.
  describe('DB 작업 PR생성: 확인 완료 — 종료 요청', () => {
    const dbPr = { ...pr, taskType: 'DB_WORK', requesterId: 'alice' }
    const item = () => body().querySelector('[data-test="sheet-request-close"]') as HTMLElement | null

    it('등록자(viewerId 일치, 비관리자)에게 항목이 보이고 클릭하면 request-close를 emit한다 — 배포 전이 항목은 없다', async () => {
      const w = open(dbPr, false, 'alice')
      await flushPromises()
      expect(item()).not.toBeNull()
      expect(item()!.textContent).toContain('확인 완료 — 종료 요청')
      expect(body().querySelector('[data-test="sheet-move"]')).toBeNull()
      item()!.click()
      await flushPromises()
      expect(w.emitted('request-close')).toHaveLength(1)
      w.unmount()
    })

    it('관리자에게는 종료 요청과 배포(전이)가 함께 보인다 — 종료 요청이 먼저', async () => {
      const w = open(dbPr, true, 'someone-else')
      await flushPromises()
      expect(item()).not.toBeNull()
      const move = body().querySelector('[data-test="sheet-move"]') as HTMLElement
      expect(move.textContent).toContain('배포')
      expect(item()!.compareDocumentPosition(move) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      w.unmount()
    })

    it('등록자가 아닌 비관리자, viewerId가 없는 비관리자에게는 없다', async () => {
      let w = open(dbPr, false, 'bob')
      await flushPromises()
      expect(item()).toBeNull()
      w.unmount()
      document.body.innerHTML = ''

      w = open(dbPr, false)
      await flushPromises()
      expect(item()).toBeNull()
      w.unmount()
      document.body.innerHTML = ''

      // 등록자 값이 없는 작업과 viewerId가 없는 사용자가 "둘 다 없음"으로 일치해 버리면 안 된다
      w = open({ ...dbPr, requesterId: undefined }, false)
      await flushPromises()
      expect(item()).toBeNull()
      w.unmount()
    })

    it('다른 타입·타입 없음, 다른 상태에는 없다', async () => {
      for (const t of [{ ...pr, requesterId: 'alice' }, { ...dbPr, taskType: 'BUG_FIX' }, { ...dbPr, taskType: null }]) {
        const w = open(t, true, 'alice')
        await flushPromises()
        expect(item()).toBeNull()
        w.unmount()
        document.body.innerHTML = ''
      }
      const w = open({ ...dbPr, status: 'DEPLOYED', statusLabel: '배포완료' }, true, 'alice')
      await flushPromises()
      expect(item()).toBeNull()
      w.unmount()
    })
  })
})
