import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { useQuasar, type QVueGlobals } from 'quasar'
import { confirmDialog } from './confirmDialog'

function mountWithQ() {
  let q!: QVueGlobals
  const w = mount(defineComponent({ setup() { q = useQuasar(); return () => h('div') } }), { attachTo: document.body })
  return { w, q }
}

const okBtn = () => document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement | null
const cancelBtn = () => document.body.querySelector('[data-test="confirm-dialog-cancel"]') as HTMLElement | null

describe('confirmDialog — window.confirm 대체', () => {
  afterEach(() => { vi.restoreAllMocks(); document.body.innerHTML = '' })

  it('확인을 누르면 true, 문구·제목·확인 라벨을 그대로 보여준다', async () => {
    const { w, q } = mountWithQ()
    const p = confirmDialog(q, '작업 #9을 삭제하시겠습니까?', { title: '작업 삭제', ok: '삭제' })
    await flushPromises()
    expect(document.body.textContent).toContain('작업 삭제')
    expect(document.body.textContent).toContain('작업 #9을 삭제하시겠습니까?')
    expect(okBtn()!.textContent).toContain('삭제')
    okBtn()!.click()
    await expect(p).resolves.toBe(true)
    w.unmount()
  })

  it('취소를 누르면 false', async () => {
    const { w, q } = mountWithQ()
    const p = confirmDialog(q, '취소하시겠습니까?')
    await flushPromises()
    cancelBtn()!.click()
    await expect(p).resolves.toBe(false)
    w.unmount()
  })

  // 2026-10-08 실화면: Claude 앱 브라우저 창은 window.confirm을 띄우지 않고 즉시 false를 돌려줘
  // 삭제·취소가 "아무 반응 없음"으로 끝났다. 네이티브 대화상자에 기대지 않는지 고정한다.
  it('window.confirm을 호출하지 않는다', async () => {
    const spy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { w, q } = mountWithQ()
    const p = confirmDialog(q, '삭제하시겠습니까?')
    await flushPromises()
    okBtn()!.click()
    await expect(p).resolves.toBe(true)
    expect(spy).not.toHaveBeenCalled()
    w.unmount()
  })
})
