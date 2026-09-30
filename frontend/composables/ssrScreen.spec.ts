import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { Screen } from 'quasar'
import type { Screen as ScreenState } from 'quasar'
import {
  resolveScreenHint,
  wideScreenFlags,
  wideScreenCopy,
  applyWideScreenBeforeMeasure,
} from './ssrScreen'
import { setViewportWidth } from '../test/mocks/screen'

// Quasar Screen 기본값(SSR·하이드레이션 전) — 폭을 재기 전 상태
function unmeasuredScreen(): ScreenState {
  return {
    width: 0,
    height: 0,
    name: 'xs',
    sizes: { sm: 600, md: 1024, lg: 1440, xl: 1920 },
    lt: { sm: true, md: true, lg: true, xl: true },
    gt: { xs: false, sm: false, md: false, lg: false },
    xs: true,
    sm: false,
    md: false,
    lg: false,
    xl: false,
    setSizes: () => {},
    setDebounce: () => {},
  }
}

describe('resolveScreenHint — SSR 화면 폭 추정', () => {
  it('쿠키(직전 실제 폭)가 있으면 UA보다 우선한다', () => {
    expect(resolveScreenHint('narrow', false)).toBe('narrow')
    expect(resolveScreenHint('wide', true)).toBe('wide')
  })

  it('쿠키가 없거나 알 수 없는 값이면 UA로 추정한다 — 모바일 UA는 좁은 화면(Quasar 기본값 그대로)', () => {
    expect(resolveScreenHint(undefined, true)).toBe('narrow')
    expect(resolveScreenHint(null, false)).toBe('wide')
    expect(resolveScreenHint('garbage', true)).toBe('narrow')
  })
})

describe('wideScreenFlags — Quasar Screen 계산과 일치', () => {
  it('실제 Quasar Screen이 1280px에서 계산하는 플래그와 같다 (브레이크포인트가 바뀌면 여기서 드러난다)', async () => {
    // Screen 플러그인은 Quasar가 앱에 설치될 때 폭 측정을 시작한다 — 빈 컴포넌트를 한 번 마운트해 설치시킨다
    mount(defineComponent({ render: () => null })).unmount()
    await setViewportWidth(1280)
    try {
      const flags = wideScreenFlags()
      expect(Screen.width).toBe(1280) // 기본값(xs, width 0)이 아니라 실제로 잰 값과 비교하는지
      expect(Screen.name).toBe(flags.name)
      expect({
        xs: Screen.xs,
        sm: Screen.sm,
        md: Screen.md,
        lg: Screen.lg,
        xl: Screen.xl,
      }).toEqual({
        xs: flags.xs,
        sm: flags.sm,
        md: flags.md,
        lg: flags.lg,
        xl: flags.xl,
      })
      expect({ ...Screen.lt }).toEqual(flags.lt)
      expect({ ...Screen.gt }).toEqual(flags.gt)
      // 앱의 데스크톱 분기 기준
      expect(flags.lt.md).toBe(false)
      expect(flags.gt.sm).toBe(true)
    } finally {
      await setViewportWidth(1024)
    }
  })
})

describe('wideScreenCopy — 서버용 요청별 복사본', () => {
  it('원본(요청 간 공유 싱글턴)을 건드리지 않고 넓은 화면 값의 새 객체를 돌려준다', () => {
    const shared = unmeasuredScreen()
    const copy = wideScreenCopy(shared)
    expect(copy).not.toBe(shared)
    expect(copy.lt).not.toBe(shared.lt)
    expect(copy.lt.md).toBe(false)
    expect(copy.gt.sm).toBe(true)
    expect(copy.name).toBe('md')
    expect(copy.width).toBe(0) // QLayout/QPage가 읽는 폭·높이는 원래 값
    expect(shared.lt.md).toBe(true)
    expect(shared.name).toBe('xs')
  })
})

describe('applyWideScreenBeforeMeasure — 클라이언트 하이드레이션 전 적용', () => {
  it('폭을 재기 전(width 0)이면 넓은 화면 값으로 바꾸되 width는 0으로 남긴다(하이드레이션 뒤 실제 폭으로 전부 재계산)', () => {
    const s = unmeasuredScreen()
    const lt = s.lt
    expect(applyWideScreenBeforeMeasure(s)).toBe(true)
    expect(s.lt.md).toBe(false)
    expect(s.gt.sm).toBe(true)
    expect(s.xs).toBe(false)
    expect(s.md).toBe(true)
    expect(s.name).toBe('md')
    expect(s.width).toBe(0)
    expect(s.lt).toBe(lt) // 반응형 중첩 객체를 교체하지 않고 제자리에서 고친다
  })

  it('이미 실제 폭을 쟀으면 아무것도 바꾸지 않는다', () => {
    const s = { ...unmeasuredScreen(), width: 390 }
    expect(applyWideScreenBeforeMeasure(s)).toBe(false)
    expect(s.lt.md).toBe(true)
    expect(s.name).toBe('xs')
  })
})
