import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import BrandMark from './BrandMark.vue'

// Symphony 브랜드 마크 — 2026-10-11 디자인 검토에서 승인된 A4(악보 S): 오선 3줄 위 4분음표 7개가 S 궤적을 그리고
// 바깥쪽 4개에 스템. 색은 currentColor라 헤더(흰색)·로그인(primary)·탭 등 쓰는 곳의 글자색을 따른다.
describe('components/BrandMark — 승인된 A4 마크', () => {
  it('24 그리드 인라인 SVG, 기본 24px, 장식용(aria-hidden)', () => {
    const w = mount(BrandMark)
    const svg = w.find('svg[data-test="brand-mark"]')
    expect(svg.exists()).toBe(true)
    expect(svg.attributes('viewBox')).toBe('0 0 24 24')
    expect(svg.attributes('width')).toBe('24')
    expect(svg.attributes('height')).toBe('24')
    expect(svg.attributes('aria-hidden')).toBe('true')
  })

  it('size prop이 width/height에 그대로 들어간다', () => {
    const w = mount(BrandMark, { props: { size: 44 } })
    const svg = w.find('svg')
    expect(svg.attributes('width')).toBe('44')
    expect(svg.attributes('height')).toBe('44')
  })

  it('A4 구성: 오선 3줄 + 스템 4개 + 음표 머리 7개', () => {
    const w = mount(BrandMark)
    expect(w.findAll('.brand-mark__staff path')).toHaveLength(3)
    expect(w.findAll('.brand-mark__stems path')).toHaveLength(4)
    expect(w.findAll('.brand-mark__heads ellipse')).toHaveLength(7)
  })

  it('색은 currentColor만 쓴다 — 하드코딩된 hex 없음', () => {
    const html = mount(BrandMark).html()
    expect(html).not.toMatch(/#[0-9a-fA-F]{3,6}\b/)
    expect(html).toContain('currentColor')
  })
})
