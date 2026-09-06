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
function open(task: any, isAdmin: boolean) {
  return mount(TaskActionSheet, {
    props: { task, isAdmin, modelValue: true },
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
})
