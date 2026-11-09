import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, vi } from 'vitest'
import MockupCard from './MockupCard.vue'
import { useApiMock } from '../../test/mocks/nuxt'

// 모듈 Map 캐시가 시험 사이에 이어지지 않도록 시험마다 다른 sid·id를 쓴다.
function summary(id: number, over: Record<string, unknown> = {}) {
  return {
    id,
    n: 1,
    name: 'a-plan',
    version: 1,
    title: 'A안',
    description: '왼쪽 메뉴형',
    turnSeq: 1,
    turnKind: 'question' as const,
    ...over,
  }
}

let observerCb: ((entries: { isIntersecting: boolean }[]) => void) | null = null
const disconnect = vi.fn()
const observe = vi.fn()

function stubObserver() {
  observerCb = null
  disconnect.mockClear()
  observe.mockClear()
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      constructor(cb: (entries: { isIntersecting: boolean }[]) => void) {
        observerCb = cb
      }
      observe = observe
      disconnect = disconnect
    },
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MockupCard', () => {
  it('화면에 들어오기 전에는 단건 GET이 없다', async () => {
    stubObserver()
    useApiMock.mockResolvedValue({ html: '<p>x</p>' })
    const w = mount(MockupCard, { props: { sessionId: 11, mockup: summary(101) } })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledTimes(0)
    expect(observe).toHaveBeenCalledTimes(1)
    observerCb!([{ isIntersecting: true }])
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledTimes(1)
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/11/mockups/101')
    expect(disconnect).toHaveBeenCalled()
    w.unmount()
  })

  it('미리보기는 sandbox="" 와 CSP srcdoc, 탭 이동·클릭 불가', async () => {
    stubObserver()
    useApiMock.mockResolvedValue({ html: '<!doctype html><html><body>hi</body></html>' })
    const w = mount(MockupCard, { props: { sessionId: 12, mockup: summary(102) } })
    observerCb!([{ isIntersecting: true }])
    await flushPromises()
    const f = w.find('[data-test="mockup-preview-102"]')
    expect(f.exists()).toBe(true)
    expect(f.attributes('sandbox')).toBe('')
    expect(f.attributes('srcdoc')).toContain('Content-Security-Policy')
    expect(f.attributes('tabindex')).toBe('-1')
    expect(f.attributes('loading')).toBe('lazy')
    w.unmount()
  })

  it('들어오지 않은 채(isIntersecting false)는 받지 않는다', async () => {
    stubObserver()
    const w = mount(MockupCard, { props: { sessionId: 16, mockup: summary(106) } })
    observerCb!([{ isIntersecting: false }])
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledTimes(0)
    expect(disconnect).not.toHaveBeenCalled()
    w.unmount()
  })

  it('IntersectionObserver가 없으면 마운트 때 받는다', async () => {
    vi.stubGlobal('IntersectionObserver', undefined)
    useApiMock.mockResolvedValue({ html: '<p>x</p>' })
    const w = mount(MockupCard, { props: { sessionId: 15, mockup: summary(105) } })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/15/mockups/105')
    expect(w.find('[data-test="mockup-preview-105"]').exists()).toBe(true)
    w.unmount()
  })

  it('제목(버전 꼬리 포함)·설명·새 탭 링크', () => {
    stubObserver()
    const w = mount(MockupCard, {
      props: { sessionId: 9, mockup: summary(103, { n: 3, version: 2, title: 'B안' }) },
    })
    expect(w.find('[data-test="mockup-card-103"]').text()).toContain('목업 3 · B안 (v2)')
    expect(w.text()).toContain('왼쪽 메뉴형')
    const a = w.find('[data-test="mockup-open-103"]')
    expect(a.attributes('href')).toBe('/interviews/9/mockups/103')
    expect(a.attributes('target')).toBe('_blank')
    expect(a.attributes('rel')).toBe('noopener')
    expect(a.text()).toContain('새 탭에서 보기')
    w.unmount()
  })

  it('html 조회가 실패하면 미리보기 없이 카드만', async () => {
    stubObserver()
    useApiMock.mockRejectedValueOnce(new Error('500'))
    const w = mount(MockupCard, { props: { sessionId: 14, mockup: summary(104) } })
    observerCb!([{ isIntersecting: true }])
    await flushPromises()
    expect(w.find('[data-test="mockup-card-104"]').exists()).toBe(true)
    expect(w.find('[data-test="mockup-preview-104"]').exists()).toBe(false)
    w.unmount()
  })
})
