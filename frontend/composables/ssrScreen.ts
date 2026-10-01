// SSR 화면 폭 힌트 — plugins/ssr-screen.ts가 쓰는 순수 함수 모음 (2026-09-30 SSR 하이드레이션 정리).
// Quasar(nuxt-quasar-ui)는 SSR과 하이드레이션 동안 $q.screen을 xs(좁은 화면) 기본값으로 고정했다가 하이드레이션이
// 끝나면(app:suspense:resolve) 실제 폭을 잰다 — 불일치 경고는 없지만 데스크톱 최초 로드가 모바일 마크업(서랍·하단
// 내비 자리·모바일 리스트)으로 그려졌다가 데스크톱으로 뒤바뀐다. 직전 실제 폭(쿠키)이 넓은 화면이면 SSR·하이드레이션을
// 둘 다 데스크톱 값으로 맞춰 이 점프를 없앤다. 쿠키가 틀려도(창 크기를 바꾼 뒤 다시 연 경우) 하이드레이션 뒤 실제 폭으로
// 바뀔 뿐 불일치는 없고, 쿠키는 실제 폭을 따라 갱신된다.
import type { Screen } from 'quasar'

/** 직전 실제 폭(lt.md 여부)을 기억하는 쿠키. 값은 'wide' | 'narrow'. 레이아웃 힌트일 뿐 민감 정보 없음. */
export const SCREEN_HINT_COOKIE = 'netis-maker-screen'

export type ScreenHint = 'wide' | 'narrow'

/**
 * 쿠키(직전 실제 폭)가 있으면 그대로 쓰고, 없으면 좁은 화면(Quasar 기본값 — 기존 동작)으로 둔다.
 * UA로 넓은 화면을 추정하지 않는다: 데스크톱 UA를 보내는 태블릿(iPadOS Safari 기본 Mac UA)·좁은 데스크톱 창의
 * 첫 로드가 데스크톱으로 SSR됐다가 모바일로 뒤바뀌는, 기존에 없던 점프가 생긴다(2026-09-30 리뷰 재현).
 * 첫 방문은 비로그인이라 사실상 /login(폭 의존 마크업은 눈에 안 보이는 브랜드 클래스뿐)을 거치고 거기서 쿠키가 남으므로
 * 데스크톱 효과는 그대로다.
 */
export function resolveScreenHint(cookie: unknown): ScreenHint {
  return cookie === 'wide' ? 'wide' : 'narrow'
}

type ScreenFlags = Pick<
  Screen,
  'name' | 'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'lt' | 'gt'
>

/** Quasar Screen이 md 폭(1024~1439px)에서 계산하는 값 — 앱의 데스크톱 분기(lt.md=false, gt.sm=true)에 해당. */
export function wideScreenFlags(): ScreenFlags {
  return {
    name: 'md',
    xs: false,
    sm: false,
    md: true,
    lg: false,
    xl: false,
    lt: { sm: false, md: false, lg: true, xl: true },
    gt: { xs: true, sm: true, md: false, lg: false },
  }
}

/**
 * 서버: 요청마다 새 screen 객체를 만든다. 서버의 Quasar Screen은 모든 요청이 공유하는 모듈 싱글턴이라
 * 직접 고치면 동시 요청끼리 값이 섞인다. width/height는 원래 값(0) 그대로 — QLayout/QPage가 읽는다.
 */
export function wideScreenCopy(screen: Screen): Screen {
  return { ...screen, ...wideScreenFlags() }
}

/**
 * 클라이언트: 하이드레이션 전(Screen이 아직 폭을 재기 전, width === 0)에만 넓은 화면 값으로 바꾼다.
 * width는 0으로 남겨 둔다 — 하이드레이션 뒤 Screen이 실제 폭을 재면 width가 달라져 모든 플래그를 다시 계산한다.
 * 이미 폭을 잰 뒤라면 실제 값을 덮어쓰지 않도록 아무것도 하지 않는다. 적용했으면 true.
 */
export function applyWideScreenBeforeMeasure(screen: Screen): boolean {
  if (screen.width !== 0) return false
  const { lt, gt, ...flags } = wideScreenFlags()
  Object.assign(screen, flags)
  Object.assign(screen.lt, lt)
  Object.assign(screen.gt, gt)
  return true
}
