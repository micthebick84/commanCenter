import MarkdownIt from 'markdown-it'
import type { Token } from 'markdown-it'

// 답변 마크다운 렌더러 (스펙 2026-09-05 §2): html:false = 원시 HTML 미렌더(XSS 차단), linkify:false,
// breaks:true = 채팅 줄바꿈. javascript:/vbscript:/file: 링크는 markdown-it 기본 validateLink가 거른다.
// 모듈 스코프 싱글턴 — SSR/클라이언트 양쪽에서 순수 문자열 변환만 한다(DOM 불필요).
// 에이전트/사용자 생성 마크다운을 v-html로 그리는 곳(ChatBubble·MarkdownViewerDialog·TaskDetailMobile·
// pages/tasks/[id].vue)은 전부 이 렌더러 하나만 쓴다 — 다른 렌더러를 새로 들이지 말 것.
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

// 이미지: 에이전트 생성 마크다운은 어떤 경우에도 자동 네트워크 요청을 만들지 않는다. <img>는 사용자가 아무것도
// 안 해도 브라우저가 src를 요청하므로, 레포에 섞인 프롬프트 인젝션이 `![](https://…?q=<데이터>)`를 답변에 넣으면
// 유출 채널이 된다 → <img>를 절대 내보내지 않고 클릭해야만 열리는 링크로 바꾼다.
// - 참조식(`![a][ref]`)·엔티티/대소문자 인코딩도 파싱 후엔 같은 image 토큰이라 이 규칙 하나로 막힌다.
// - 링크 안 이미지(`[![a](u)](v)` 배지 패턴)는 중첩 <a>를 피해 표식+alt만 남기고 바깥 링크(v)에 맡긴다.
// - data:image(png/gif/jpeg/webp)는 validateLink가 통과시키고 요청도 없지만 인라인 <img>로도 그리지 않는다 —
//   LLM 답변에 쓸 일이 없고 압축폭탄·UI 위장 표면만 늘린다. 브라우저가 data: 최상위 이동을 막으니 링크도 없이 텍스트만.
// - validateLink가 거른 URL(javascript: 등)은 이미지로 파싱되지 않거나 src가 ''로 온다 → 역시 텍스트만.
const IMAGE_MARK = '[이미지]'

/** 이미지 alt 평문. 기본 renderInlineAsText는 엔티티/이스케이프(text_special)·인라인 코드를 빠뜨려 직접 모은다. */
function altText(tokens: Token[] | null): string {
  return (tokens ?? [])
    .map((t) => {
      if (t.type === 'image') return altText(t.children)
      if (t.type === 'text' || t.type === 'text_special' || t.type === 'code_inline') return t.content
      if (t.type === 'softbreak' || t.type === 'hardbreak') return ' '
      return ''
    })
    .join('')
}

/** 같은 인라인 토큰열에서 idx 앞에 열린 채 닫히지 않은 링크가 있는가. */
function insideLink(tokens: Token[], idx: number): boolean {
  let depth = 0
  for (let i = 0; i < idx; i++) {
    if (tokens[i]!.type === 'link_open') depth++
    else if (tokens[i]!.type === 'link_close') depth--
  }
  return depth > 0
}

md.renderer.rules.image = (tokens, idx) => {
  const token = tokens[idx]!
  const src = (token.attrGet('src') ?? '').trim()
  const alt = altText(token.children).trim()
  const esc = md.utils.escapeHtml
  const label = (text: string) => esc(text ? `${IMAGE_MARK} ${text}` : IMAGE_MARK)

  if (insideLink(tokens, idx)) return label(alt)
  if (!src || /^data:/i.test(src)) {
    return `<span class="md-image-omitted" title="인라인 이미지는 표시하지 않습니다">${label(alt)}</span>`
  }
  const title = token.attrGet('title')
  // alt가 비면 URL을 보여주되 퓨니코드/퍼센트 디코딩 없이 실제 href 그대로 — 동형 문자로 목적지를 위장하지 못하게.
  return (
    `<a href="${esc(src)}" target="_blank" rel="noopener noreferrer" class="md-image-link"` +
    `${title ? ` title="${esc(title)}"` : ''}>${label(alt || src)}</a>`
  )
}

/** 마크다운 → HTML 문자열(v-html용 — assistant 말풍선·분석 문서 뷰 공용). 빈 입력은 빈 문자열. */
export function renderMarkdown(src: string | null | undefined): string {
  if (!src) return ''
  return md.render(src)
}
