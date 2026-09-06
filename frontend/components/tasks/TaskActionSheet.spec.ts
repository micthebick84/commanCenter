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
})
