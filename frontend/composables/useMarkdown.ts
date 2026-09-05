import MarkdownIt from 'markdown-it'

// 답변 마크다운 렌더러 (스펙 2026-09-05 §2): html:false = 원시 HTML 미렌더(XSS 차단), linkify:false,
// breaks:true = 채팅 줄바꿈. javascript:/vbscript:/file: 링크는 markdown-it 기본 validateLink가 거른다.
// 모듈 스코프 싱글턴 — SSR/클라이언트 양쪽에서 순수 문자열 변환만 한다(DOM 불필요).
const md = new MarkdownIt({ html: false, linkify: false, breaks: true })

// 링크는 새 탭 + noopener — SPA 세션을 떠나지 않게.
const renderLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx]!.attrSet('target', '_blank')
  tokens[idx]!.attrSet('rel', 'noopener noreferrer')
  return renderLinkOpen(tokens, idx, options, env, self)
}

/** 마크다운 → HTML 문자열. assistant 말풍선 전용(v-html). 빈 입력은 빈 문자열. */
export function renderMarkdown(src: string | null | undefined): string {
  if (!src) return ''
  return md.render(src)
}
