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
