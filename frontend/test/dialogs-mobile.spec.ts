import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import ApproveDialog from '../components/ApproveDialog.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'
import { baseTask } from './fixtures/task'

// q-dialog는 body 포털로 렌더된다 — document.body에서 찾는다.
function maximizedInner() {
  return document.body.querySelector('.q-dialog__inner--maximized')
}

const task = { ...baseTask, id: 1, status: 'AWAITING_APPROVAL', statusLabel: '승인대기', implementation: null, analysis: null }

function mountApprove() {
  // 다이얼로그가 부르는 GET(카탈로그·지난 인터뷰·사용량)은 전부 빈 응답 — 여기서는 레이아웃만 본다.
  useApiMock.mockImplementation((url: string) => Promise.resolve(url === '/api/usage/claude' ? { limits: [] } : []))
  return mount(ApproveDialog, { props: { task, modelValue: true }, attachTo: document.body })
}

describe('다이얼로그 모바일 전체화면 (스펙 2026-09-06 §4.3)', () => {
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('lt.md에서는 ApproveDialog가 maximized로 열리고 고정 min-width가 없다', async () => {
    await setViewportWidth(390)
    const w = mountApprove()
    await flushPromises()
    expect(maximizedInner()).not.toBeNull()
    const card = document.body.querySelector('.q-dialog .q-card') as HTMLElement
    expect(card.getAttribute('style') ?? '').not.toMatch(/min-width:\s*\d+px/)
    w.unmount()
  })

  it('데스크톱(1024)에서는 maximized가 아니고 상단 닫기 바도 없다', async () => {
    const w = mountApprove()
    await flushPromises()
    expect(maximizedInner()).toBeNull()
    expect(document.body.querySelector('[data-test="approve-topbar"]')).toBeNull()
    w.unmount()
  })

  it('lt.md에서는 상단 고정 바(닫기) → 스크롤 본문(.dialog-body) → 액션 바 순서로 카드가 구성된다', async () => {
    await setViewportWidth(390)
    const w = mountApprove()
    await flushPromises()
    const card = document.body.querySelector('.q-dialog .q-card') as HTMLElement
    // happy-dom의 querySelector는 `:scope` 콤비네이터를 지원하지 않는다 — 직계 자식을 직접 확인한다.
    const children = Array.from(card.children)
    expect(children[0]?.getAttribute('data-test')).toBe('approve-topbar')
    expect(children.some((c) => c.classList.contains('dialog-body'))).toBe(true)
    expect(card.lastElementChild?.classList.contains('q-card__actions')).toBe(true)
    w.unmount()
  })

  it('lt.md 상단 바의 닫기 버튼을 누르면 다이얼로그가 닫힌다', async () => {
    await setViewportWidth(390)
    const w = mountApprove()
    await flushPromises()
    const close = document.body.querySelector('[data-test="approve-close"]') as HTMLElement
    expect(close).not.toBeNull()
    close.click()
    await flushPromises()
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([false])
    w.unmount()
  })
})
