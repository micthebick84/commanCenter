import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, inject, nextTick, ref, type Ref } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import QuestionsShell from '../pages/questions.vue'
import { QUESTIONS_LIST_COOKIE } from '../composables/questions'
import { cookieJar, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// useRoute/navigateTo는 Nuxt 자동 임포트 — navigateTo는 setup.ts에 없어 전역 주입, useRoute는 setup.ts 기본값을 덮는다.
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ params: { id: '3' } }),
})

// 자식 라우트 자리 — 렌더 함수 스텁(문자열 템플릿은 런타임 컴파일러가 없어 못 쓴다)
const NuxtPageStub = defineComponent({
  setup: () => () => h('div', { 'data-test': 'child-page' }),
})

const PageWrapper = defineComponent({
  setup: () => () =>
    h(
      QLayout,
      { view: 'hHh lpR fFf' },
      {
        default: () =>
          h(QPageContainer, {}, { default: () => h(QuestionsShell) }),
      },
    ),
})

const today = new Date().toISOString()

describe('pages/questions (셸) — 스펙 2026-09-05 §6', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      Promise.resolve(
        url === '/api/questions'
          ? [
              {
                id: 3,
                title: '인증 흐름 확인',
                githubRepo: 'a/b',
                githubBranch: 'main',
                repoAlias: null,
                requesterId: 'user1',
                status: '',
                statusName: 'AWAITING_INPUT',
                model: 'claude-opus-5',
                effort: 'high',
                totalCostUsd: 0,
                contextTokens: null,
                contextWindow: null,
                createdAt: today,
                updatedAt: today,
              },
            ]
          : { limits: [] },
      ),
    )
  })

  it('사이드바 + 자식 페이지를 그리고, 라우트 id를 활성 행으로 넘기며, 선택/새 질문이 navigateTo를 부른다', async () => {
    const w = mount(PageWrapper, {
      global: { stubs: { NuxtPage: NuxtPageStub } },
    })
    await flushPromises()
    expect(w.find('[data-test="child-page"]').exists()).toBe(true)
    const row = w.find('[data-test="question-row"]')
    expect(row.classes()).toContain('active')
    await row.trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/questions/3')
    await w.find('[data-test="new-question"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/questions')
    w.unmount()
  })

  // 서랍 안 탭이 먹지 않던 결함(2026-09-06 모바일 QA): Quasar CSS는 `.q-dialog__inner { pointer-events:none }`에
  // `.q-dialog__inner > div { pointer-events:all }` 예외만 둔다 — 콘텐츠 루트가 <aside>면 서랍 안 클릭이 전부
  // 백드롭으로 새어 서랍만 닫힌다. vitest는 Quasar CSS를 로드하지 않으므로 DOM 구조(콘텐츠 루트 태그)로 가드한다.
  const NuxtPageStubWithOpener = defineComponent({
    setup() {
      const openDrawer = inject<() => void>('questions:open-drawer', () => {})
      return () =>
        h('button', {
          'data-test': 'open-drawer-stub',
          onClick: () => openDrawer(),
        })
    },
  })

  it('좁은 화면(lt.md)에서는 고정 사이드바 대신 서랍을 쓰고, 서랍 콘텐츠 루트는 div라야 한다(Quasar pointer-events 예외 규칙)', async () => {
    await setViewportWidth(390)
    try {
      const w = mount(PageWrapper, {
        global: { stubs: { NuxtPage: NuxtPageStubWithOpener } },
      })
      await flushPromises()
      expect(w.find('.question-sidebar').exists()).toBe(false) // 고정 사이드바 없음 → 서랍 모드
      await w.find('[data-test="open-drawer-stub"]').trigger('click')
      await flushPromises()
      await nextTick()
      const inner = document.body.querySelector('.q-dialog__inner')
      expect(inner).not.toBeNull()
      const contentRoot = inner!.firstElementChild
      expect(contentRoot?.tagName).toBe('DIV')
      expect(contentRoot?.querySelector('.question-sidebar')).not.toBeNull()
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  it('서랍 다이얼로그 루트는 전역 스타일 훅 클래스(questions-drawer)를 가진다 — QDialog minimized 상하 24px 패딩 제거용', async () => {
    // 패딩이 남으면 100vh 서랍이 24px 아래로 밀려 하단 "전체 보기" 토글이 잘린다 (2026-09-06 모바일 QA).
    await setViewportWidth(390)
    try {
      const w = mount(PageWrapper, {
        global: { stubs: { NuxtPage: NuxtPageStubWithOpener } },
      })
      await flushPromises()
      await w.find('[data-test="open-drawer-stub"]').trigger('click')
      await flushPromises()
      await nextTick()
      const dialogRoot = document.body.querySelector('.q-dialog')
      expect(dialogRoot).not.toBeNull()
      expect(dialogRoot!.classList.contains('questions-drawer')).toBe(true)
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  // 넓은 화면 목록 접기(2026-10-30): 사이드바의 접기 버튼 → 숨김 + 쿠키 기억, 자식 헤더의 목록 버튼(open-drawer) → 다시 펼침.
  // 자식 페이지 자리에는 셸이 주는 list-hidden 값과 열기 함수를 그대로 드러내는 스텁을 둔다.
  const NuxtPageStubWithList = defineComponent({
    setup() {
      const openDrawer = inject<() => void>('questions:open-drawer', () => {})
      const listHidden = inject<Ref<boolean>>('questions:list-hidden', ref(false))
      return () =>
        h('div', [
          h('span', { 'data-test': 'list-hidden' }, String(listHidden.value)),
          h('button', { 'data-test': 'open-drawer-stub', onClick: () => openDrawer() }),
        ])
    },
  })
  const sidebarDisplay = (w: ReturnType<typeof mount>) =>
    (w.find('.question-sidebar').element as HTMLElement).style.display

  it('넓은 화면: 접기 버튼으로 목록을 숨기고(쿠키에 기억) 자식의 목록 버튼으로 다시 펼친다 — 숨겨도 사이드바는 마운트된 채다', async () => {
    const w = mount(PageWrapper, {
      global: { stubs: { NuxtPage: NuxtPageStubWithList } },
    })
    await flushPromises()
    expect(sidebarDisplay(w)).toBe('')
    expect(w.find('[data-test="list-hidden"]').text()).toBe('false')

    await w.find('[data-test="collapse-list"]').trigger('click')
    await nextTick()
    expect(w.find('.question-sidebar').exists()).toBe(true) // v-show — 폴링·refresh·스크롤 유지
    expect(sidebarDisplay(w)).toBe('none')
    expect(cookieJar.get(QUESTIONS_LIST_COOKIE)?.value).toBe('hidden')
    expect(w.find('[data-test="list-hidden"]').text()).toBe('true')
    expect(w.find('.questions-main').exists()).toBe(true) // 대화 영역(col)이 남은 폭을 다 쓴다

    await w.find('[data-test="open-drawer-stub"]').trigger('click')
    await nextTick()
    expect(sidebarDisplay(w)).toBe('')
    expect(cookieJar.get(QUESTIONS_LIST_COOKIE)?.value).toBeNull() // 펼치면 쿠키를 지운다
    expect(w.find('[data-test="list-hidden"]').text()).toBe('false')
    expect(document.body.querySelector('.q-dialog__inner')).toBeNull() // 넓은 화면은 서랍을 열지 않는다
    w.unmount()
  })

  it('넓은 화면: 접힘 쿠키가 있으면 첫 렌더부터 접힌 채 그린다 — 서버와 클라이언트가 같은 쿠키를 읽어 하이드레이션이 일치한다', async () => {
    cookieJar.set(QUESTIONS_LIST_COOKIE, ref<unknown>('hidden'))
    const w = mount(PageWrapper, {
      global: { stubs: { NuxtPage: NuxtPageStubWithList } },
    })
    expect(sidebarDisplay(w)).toBe('none') // flushPromises 전 = 첫 렌더
    expect(w.find('[data-test="list-hidden"]').text()).toBe('true')
    w.unmount()
  })

  it('좁은 화면(lt.md)은 접힘 쿠키와 무관하게 서랍 그대로다 — 자식 버튼은 서랍을 열고, 서랍 안 사이드바에는 접기 버튼이 없다', async () => {
    cookieJar.set(QUESTIONS_LIST_COOKIE, ref<unknown>('hidden'))
    await setViewportWidth(390)
    try {
      const w = mount(PageWrapper, {
        global: { stubs: { NuxtPage: NuxtPageStubWithList } },
      })
      await flushPromises()
      expect(w.find('.question-sidebar').exists()).toBe(false)
      expect(w.find('[data-test="list-hidden"]').text()).toBe('false') // 자식은 lt.md 분기로 버튼을 보인다
      await w.find('[data-test="open-drawer-stub"]').trigger('click')
      await flushPromises()
      await nextTick()
      const inner = document.body.querySelector('.q-dialog__inner')
      expect(inner?.querySelector('.question-sidebar')).not.toBeNull()
      expect(inner?.querySelector('[data-test="collapse-list"]')).toBeNull()
      expect(cookieJar.get(QUESTIONS_LIST_COOKIE)?.value).toBe('hidden') // 서랍을 열어도 넓은 화면 설정은 그대로
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })
})
