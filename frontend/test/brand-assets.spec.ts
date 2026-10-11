import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

// 브랜드 자산 동기 가드 — 파비콘(public/favicon.svg)은 BrandMark 컴포넌트와 같은 패스를 primary 색으로 박은 사본이다.
// 마크를 손보면 둘 다 바꿔야 하고, 탭 제목·파비콘 링크는 nuxt.config의 app.head에 있어야 한다.
// (happy-dom 환경에서는 import.meta.url이 http 스킴이라 __dirname으로 푼다.)
const root = resolve(__dirname, '..')
const read = (p: string) => readFileSync(resolve(root, p), 'utf8')

function shapes(svg: string) {
  const ds = [...svg.matchAll(/\bd="([^"]+)"/g)].map((m) => m[1]).sort()
  const ellipses = [...svg.matchAll(/<ellipse\s+([^>]*?)\s*\/?>/g)]
    .map((m) => m[1].replace(/\s+/g, ' ').trim())
    .sort()
  return { ds, ellipses }
}

describe('브랜드 자산 동기 (favicon.svg ↔ BrandMark.vue ↔ nuxt.config app.head)', () => {
  it('파비콘은 BrandMark와 같은 오선·스템 패스와 음표 머리를 쓴다', () => {
    const fav = shapes(read('public/favicon.svg'))
    const mark = shapes(read('components/BrandMark.vue'))
    expect(fav.ds).toHaveLength(7) // 오선 3 + 스템 4
    expect(fav.ellipses).toHaveLength(7)
    expect(fav).toEqual(mark)
  })

  it('BrandMark는 currentColor만 쓰고, 파비콘은 primary(#1976D2)를 박는다', () => {
    expect(read('components/BrandMark.vue')).not.toMatch(/#[0-9a-fA-F]{3,6}\b/)
    expect(read('public/favicon.svg')).toContain('#1976D2')
    expect(read('public/favicon.svg')).not.toContain('currentColor')
  })

  it('탭 제목 Symphony와 SVG 파비콘 링크가 nuxt.config app.head에 있다', () => {
    const cfg = read('nuxt.config.ts')
    expect(cfg).toMatch(/title:\s*'Symphony'/)
    expect(cfg).toContain("href: '/favicon.svg'")
    expect(cfg).toContain("type: 'image/svg+xml'")
  })
})
