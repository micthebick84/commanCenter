import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, vi } from 'vitest'
import MarkdownViewerDialog from './MarkdownViewerDialog.vue'

function open(markdown: string | null) {
  return mount(MarkdownViewerDialog, {
    props: { modelValue: true, title: '분석 결과', markdown },
    attachTo: document.body,
  })
}
const body = () => document.body

describe('MarkdownViewerDialog — 복사 (스펙 2026-09-06 §4.2, 리뷰 파인딩 10)', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('복사 버튼을 클릭하면 마크다운 원문을 클립보드에 복사하고 성공 알림을 띄운다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    // navigator.clipboard는 happy-dom에서 getter만 있는 접근자 프로퍼티라 Object.assign으로는
    // 못 바꾼다 — defineProperty로 재정의해야 한다.
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })
    const w = open('# 제목\n\n본문')
    await flushPromises()
    const notifySpy = vi.spyOn((w.vm as any).$q, 'notify')
    ;(body().querySelector('[data-test="viewer-copy"]') as HTMLElement).click()
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('# 제목\n\n본문')
    expect(notifySpy).toHaveBeenCalledWith({
      type: 'positive',
      message: '복사했습니다',
    })
    w.unmount()
  })

  it('클립보드 API가 없으면 경고 알림만 띄운다', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: undefined,
      configurable: true,
    })
    const w = open('본문')
    await flushPromises()
    const notifySpy = vi.spyOn((w.vm as any).$q, 'notify')
    ;(body().querySelector('[data-test="viewer-copy"]') as HTMLElement).click()
    await flushPromises()
    expect(notifySpy).toHaveBeenCalledWith({
      type: 'warning',
      message: '이 브라우저에서는 복사를 지원하지 않습니다',
    })
    w.unmount()
  })
})
