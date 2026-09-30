// SSR 화면 폭 힌트 (2026-09-30, TODOS "질문 셸 SSR 하이드레이션 점검"·"전역 레이아웃 SSR 하이드레이션 불일치").
// 서버가 쿠키(직전 실제 폭)나 UA로 넓은 화면을 추정하면 $q.screen을 데스크톱 값으로 바꿔 SSR하고, 클라이언트는 같은
// 추정값(페이로드)으로 하이드레이션 전 Screen을 맞춘다 → 데스크톱 최초 로드가 모바일 마크업으로 그려졌다 뒤바뀌지 않는다.
// 좁은 화면 추정은 Quasar 기본값(xs) 그대로라 모바일 첫 로드 동작은 바뀌지 않는다. 판정은 composables/ssrScreen.ts.
// nuxt-quasar-ui 플러그인(모듈 플러그인이라 먼저 실행)이 $q를 설치한 뒤에 돈다.
import { useQuasar } from 'quasar'
import {
  SCREEN_HINT_COOKIE,
  applyWideScreenBeforeMeasure,
  resolveScreenHint,
  wideScreenCopy,
  type ScreenHint,
} from '~/composables/ssrScreen'

export default defineNuxtPlugin((nuxtApp) => {
  const $q = useQuasar()
  const cookie = useCookie<ScreenHint | null>(SCREEN_HINT_COOKIE, {
    path: '/',
    sameSite: 'lax',
    maxAge: 60 * 60 * 24 * 365,
  })
  // 서버에서 한 번 정한 값을 페이로드로 넘긴다 — 클라이언트가 쿠키/UA로 다시 추정하면 SSR과 어긋날 수 있다.
  const hint = useState<ScreenHint>('ssr-screen-hint', () =>
    resolveScreenHint(cookie.value, $q.platform.is.mobile === true),
  )

  if (import.meta.server) {
    if (hint.value === 'wide') $q.screen = wideScreenCopy($q.screen)
    return
  }

  if (hint.value === 'wide') applyWideScreenBeforeMeasure($q.screen)

  // 하이드레이션이 끝나 Quasar Screen이 실제 폭을 잰 뒤(같은 훅의 nuxt-quasar-ui 핸들러가 먼저 돈다)부터
  // lt.md 여부를 쿠키에 남긴다 — 추정이 틀렸던 기기(태블릿·좁은 데스크톱 창)도 다음 로드부터는 정확해진다.
  nuxtApp.hooks.hookOnce('app:suspense:resolve', () => {
    watch(
      () => $q.screen.lt.md,
      (narrow) => {
        cookie.value = narrow ? 'narrow' : 'wide'
      },
      { immediate: true },
    )
  })
})
