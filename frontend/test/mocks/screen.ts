import { nextTick } from 'vue'
import { Screen } from 'quasar'

/**
 * Quasar Screen 플러그인은 window.innerWidth로 breakpoint($q.screen.lt.md 등)를 계산한다.
 * 폭을 바꾸고 resize를 즉시 반영시켜 좁은 화면(서랍/아이콘 버튼) 분기를 테스트한다.
 * 테스트 끝에 반드시 setViewportWidth(1024)로 되돌릴 것 (jsdom 기본 폭).
 */
export async function setViewportWidth(width: number): Promise<void> {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: width,
  })
  const screen = Screen as unknown as { setDebounce?: (ms: number) => void }
  if (typeof screen.setDebounce === 'function') screen.setDebounce(0)
  window.dispatchEvent(new Event('resize'))
  await new Promise((r) => setTimeout(r, 120)) // setDebounce 미지원 시 기본 디바운스 대비
  await nextTick()
}
