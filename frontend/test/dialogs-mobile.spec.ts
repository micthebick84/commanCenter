import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import ApproveDialog from '../components/ApproveDialog.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// q-dialog는 body 포털로 렌더된다 — document.body에서 찾는다.
function maximizedInner() {
  return document.body.querySelector('.q-dialog__inner--maximized')
}

describe('다이얼로그 모바일 전체화면 (스펙 2026-09-06 §4.3)', () => {
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('lt.md에서는 ApproveDialog가 maximized로 열리고 고정 min-width가 없다', async () => {
    useApiMock.mockResolvedValue([]) // McpPicker의 GET /api/mcp-catalog
    await setViewportWidth(390)
    const w = mount(ApproveDialog, { props: { taskId: 1, modelValue: true }, attachTo: document.body })
    await flushPromises()
    expect(maximizedInner()).not.toBeNull()
    const card = document.body.querySelector('.q-dialog .q-card') as HTMLElement
    expect(card.getAttribute('style') ?? '').not.toMatch(/min-width:\s*\d+px/)
    w.unmount()
  })

  it('데스크톱(1024)에서는 maximized가 아니다', async () => {
    useApiMock.mockResolvedValue([])
    const w = mount(ApproveDialog, { props: { taskId: 1, modelValue: true }, attachTo: document.body })
    await flushPromises()
    expect(maximizedInner()).toBeNull()
    w.unmount()
  })
})
