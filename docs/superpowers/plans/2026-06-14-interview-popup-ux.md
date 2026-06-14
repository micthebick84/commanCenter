# 대화형 인터뷰 팝업 UI/UX 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** netisMaker 대화형 분석 인터뷰 팝업을 메신저다운 대화 UX로 개선한다 — 말풍선 리프레시, 응답 대기 타이핑 애니메이션, 스마트 자동 스크롤(+새 메시지 칩), 명확한 대화 취소 버튼+확인 단계, persistent 다이얼로그 종료-닫기 버그 수정.

**Architecture:** 표현·스크롤 로직을 작은 단위로 분리한다 — `ChatBubble.vue`(턴 1개), `TypingIndicator.vue`(점 3개), `useAutoScroll.ts`(스크롤 정책). `InterviewPanel.vue`는 스트림 구독 + 답변/취소/등록 + 자식 조립만 담당. SSE 계약·상태머신·`useInterviewStream.ts`·부모 다이얼로그(`pages/tasks/index.vue`)는 불변.

**Tech Stack:** Nuxt 3 + Vue 3 `<script setup lang="ts">` + Quasar(`nuxt-quasar-ui`, Dialog/Notify 플러그인) + Vitest + @vue/test-utils + happy-dom. 작업 디렉토리는 `netisMaker/frontend`. Quasar 기본 primary `#1976D2`. Nuxt 자동 import(`ref`/`computed`/`watch`/`nextTick`/`useApi` 등 — 명시적 import 불필요, 기존 `useInterviewStream.ts` 패턴 준수).

**스펙:** `docs/superpowers/specs/2026-06-14-interview-popup-ux-design.md`
**브랜치:** `feat/interview-popup-ux` (이미 생성됨)

---

## File Structure

| 파일 | 책임 | 상태 |
|---|---|---|
| `frontend/composables/useAutoScroll.ts` | 스크롤 컨테이너의 nearBottom 추적 + unread 카운트 + scrollToBottom | 신규 |
| `frontend/composables/useAutoScroll.spec.ts` | 위 순수 로직 단위 테스트 | 신규 |
| `frontend/components/chat/TypingIndicator.vue` | AI 응답 대기 점 3개 애니메이션 | 신규 |
| `frontend/components/chat/TypingIndicator.spec.ts` | 렌더/접근성 테스트 | 신규 |
| `frontend/components/chat/ChatBubble.vue` | 대화 턴 1개를 말풍선으로 렌더(assistant/user/system) | 신규 |
| `frontend/components/chat/ChatBubble.spec.ts` | role별 렌더 테스트 | 신규 |
| `frontend/components/InterviewPanel.vue` | 트랜스크립트 말풍선화 + 타이핑/스크롤/취소확인/종료닫기 배선 | 수정 |
| `frontend/components/InterviewPanel.spec.ts` | 기존 케이스 유지 + 타이핑/취소/닫기 케이스 추가 | 수정 |

모든 명령은 `cd /Users/micthebick/IdeaProjects/netisMaker/frontend` 기준.

---

## Task 1: `useAutoScroll` 컴포저블 (스마트 스크롤 로직)

순수 DOM 산술이라 가짜 element로 단위 테스트한다. 하단 근처(threshold 이내)면 따라 내려가고, 위로 올라가 있으면 unread 카운트만 올린다.

**Files:**
- Create: `composables/useAutoScroll.ts`
- Test: `composables/useAutoScroll.spec.ts`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `composables/useAutoScroll.spec.ts`:

```ts
import { ref } from 'vue'
import { describe, it, expect, vi } from 'vitest'
import { useAutoScroll } from './useAutoScroll'

// distance = scrollHeight - scrollTop - clientHeight
function fakeEl(
  over: Partial<{ scrollHeight: number; scrollTop: number; clientHeight: number }> = {},
) {
  return {
    scrollHeight: 1000,
    scrollTop: 0,
    clientHeight: 300,
    scrollTo: vi.fn(),
    ...over,
  } as unknown as HTMLElement
}

describe('useAutoScroll', () => {
  it('onScroll marks nearBottom true within threshold and resets unread', () => {
    const el = ref(fakeEl({ scrollTop: 650 })) // distance = 50 <= 80
    const a = useAutoScroll(el)
    a.unread.value = 3
    a.onScroll()
    expect(a.nearBottom.value).toBe(true)
    expect(a.unread.value).toBe(0)
  })

  it('onScroll marks nearBottom false beyond threshold and keeps unread', () => {
    const el = ref(fakeEl({ scrollTop: 0 })) // distance = 700 > 80
    const a = useAutoScroll(el)
    a.unread.value = 2
    a.onScroll()
    expect(a.nearBottom.value).toBe(false)
    expect(a.unread.value).toBe(2)
  })

  it('notifyNewContent scrolls to bottom when near bottom', () => {
    const el = ref(fakeEl({ scrollTop: 700 })) // distance 0 -> near
    const a = useAutoScroll(el)
    a.onScroll()
    a.notifyNewContent()
    expect((el.value as any).scrollTo).toHaveBeenCalledWith({ top: 1000, behavior: 'smooth' })
    expect(a.unread.value).toBe(0)
  })

  it('notifyNewContent bumps unread when scrolled up', () => {
    const el = ref(fakeEl({ scrollTop: 0 })) // far
    const a = useAutoScroll(el)
    a.onScroll()
    a.notifyNewContent()
    expect(a.unread.value).toBe(1)
    expect((el.value as any).scrollTo).not.toHaveBeenCalled()
  })

  it('notifyNewContent with force scrolls even when scrolled up', () => {
    const el = ref(fakeEl({ scrollTop: 0 }))
    const a = useAutoScroll(el)
    a.onScroll()
    a.notifyNewContent({ force: true })
    expect((el.value as any).scrollTo).toHaveBeenCalled()
    expect(a.unread.value).toBe(0)
  })

  it('scrollToBottom uses scrollTop fallback when scrollTo is absent', () => {
    const el = ref({ scrollHeight: 500, scrollTop: 0, clientHeight: 100 } as unknown as HTMLElement)
    const a = useAutoScroll(el)
    a.scrollToBottom('auto')
    expect(el.value!.scrollTop).toBe(500)
  })

  it('is a no-op when the element ref is null', () => {
    const el = ref<HTMLElement | null>(null)
    const a = useAutoScroll(el)
    expect(() => {
      a.onScroll()
      a.scrollToBottom()
      a.notifyNewContent()
    }).not.toThrow()
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run composables/useAutoScroll.spec.ts`
Expected: FAIL — `Failed to resolve import "./useAutoScroll"` (파일 없음).

- [ ] **Step 3: 최소 구현 작성**

Create `composables/useAutoScroll.ts`:

```ts
import type { Ref } from 'vue'

export interface AutoScroll {
  nearBottom: Ref<boolean>
  unread: Ref<number>
  onScroll: () => void
  scrollToBottom: (behavior?: ScrollBehavior) => void
  notifyNewContent: (opts?: { force?: boolean }) => void
}

// 채팅 트랜스크립트용 스마트 자동 스크롤.
// - 사용자가 하단 근처(threshold px 이내)인지 추적.
// - 새 콘텐츠가 생기면: 하단 근처(또는 force)면 따라 내려가고, 아니면 unread++.
// 순수 DOM 산술이라 가짜 element로 단위 테스트 가능. (ref는 Nuxt 자동 import — 기존 컴포저블 패턴.)
export function useAutoScroll(
  scrollEl: Ref<HTMLElement | null>,
  opts: { threshold?: number } = {},
): AutoScroll {
  const threshold = opts.threshold ?? 80
  const nearBottom = ref(true)
  const unread = ref(0)

  function onScroll() {
    const el = scrollEl.value
    if (!el) return
    nearBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight <= threshold
    if (nearBottom.value) unread.value = 0
  }

  function scrollToBottom(behavior: ScrollBehavior = 'smooth') {
    const el = scrollEl.value
    if (!el) return
    if (typeof el.scrollTo === 'function') {
      el.scrollTo({ top: el.scrollHeight, behavior })
    } else {
      el.scrollTop = el.scrollHeight // happy-dom 등 scrollTo 미구현 환경 폴백
    }
    nearBottom.value = true
    unread.value = 0
  }

  function notifyNewContent(o: { force?: boolean } = {}) {
    if (o.force || nearBottom.value) {
      scrollToBottom('smooth')
    } else {
      unread.value += 1
    }
  }

  return { nearBottom, unread, onScroll, scrollToBottom, notifyNewContent }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `npx vitest run composables/useAutoScroll.spec.ts`
Expected: PASS (7 passed).

- [ ] **Step 5: 커밋**

```bash
git add composables/useAutoScroll.ts composables/useAutoScroll.spec.ts
git commit -m "feat(interview): useAutoScroll 컴포저블 (스마트 자동 스크롤)"
```

---

## Task 2: `TypingIndicator.vue` (응답 대기 점 3개)

AI 말풍선 안 점 3개가 통통 튀는 애니메이션. `prefers-reduced-motion`에서 정지.

**Files:**
- Create: `components/chat/TypingIndicator.vue`
- Test: `components/chat/TypingIndicator.spec.ts`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `components/chat/TypingIndicator.spec.ts`:

```ts
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TypingIndicator from './TypingIndicator.vue'

describe('TypingIndicator', () => {
  it('renders three dots with a status role and default aria-label', () => {
    const w = mount(TypingIndicator)
    expect(w.attributes('role')).toBe('status')
    expect(w.attributes('aria-label')).toBe('AI가 응답을 준비 중')
    expect(w.findAll('.dot')).toHaveLength(3)
  })

  it('uses a custom label when provided', () => {
    const w = mount(TypingIndicator, { props: { label: '분석 중' } })
    expect(w.attributes('aria-label')).toBe('분석 중')
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run components/chat/TypingIndicator.spec.ts`
Expected: FAIL — `Failed to resolve import "./TypingIndicator.vue"`.

- [ ] **Step 3: 최소 구현 작성**

Create `components/chat/TypingIndicator.vue`:

```vue
<script setup lang="ts">
// AI "응답 대기 중" 표시: assistant 말풍선 안의 점 3개 통통 애니메이션.
// prefers-reduced-motion이면 애니메이션을 끄고 정적 점으로.
defineProps<{ label?: string }>()
</script>

<template>
  <div class="typing-row" role="status" :aria-label="label ?? 'AI가 응답을 준비 중'">
    <div class="avatar ai">AI</div>
    <div class="typing-bubble">
      <span class="typing-dots" aria-hidden="true">
        <span class="dot" /><span class="dot" /><span class="dot" />
      </span>
    </div>
  </div>
</template>

<style scoped>
.typing-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-bottom: 11px;
}
.avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  flex: 0 0 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
}
.avatar.ai {
  background: #e3f2fd;
  color: #1565c0;
}
.typing-bubble {
  background: #eef2f8;
  border-radius: 14px;
  border-bottom-left-radius: 4px;
  padding: 11px 14px;
}
.typing-dots {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #7f8ea3;
  animation: typing-bounce 1.2s infinite ease-in-out;
}
.dot:nth-child(2) {
  animation-delay: 0.18s;
}
.dot:nth-child(3) {
  animation-delay: 0.36s;
}
@keyframes typing-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-5px);
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .dot {
    animation: none;
    opacity: 0.6;
  }
}
</style>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `npx vitest run components/chat/TypingIndicator.spec.ts`
Expected: PASS (2 passed).

- [ ] **Step 5: 커밋**

```bash
git add components/chat/TypingIndicator.vue components/chat/TypingIndicator.spec.ts
git commit -m "feat(interview): TypingIndicator 컴포넌트 (응답 대기 점 3개)"
```

---

## Task 3: `ChatBubble.vue` (대화 턴 말풍선)

턴 1개를 역할별 말풍선으로. assistant=좌측 회색+AI 아바타, user=우측 파랑+나 아바타, system=가운데 노트.

**Files:**
- Create: `components/chat/ChatBubble.vue`
- Test: `components/chat/ChatBubble.spec.ts`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `components/chat/ChatBubble.spec.ts`:

```ts
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ChatBubble from './ChatBubble.vue'

describe('ChatBubble', () => {
  it('renders an assistant bubble with the AI avatar', () => {
    const w = mount(ChatBubble, { props: { role: 'assistant', content: '안녕하세요' } })
    expect(w.text()).toContain('안녕하세요')
    expect(w.find('.bubble.assistant').exists()).toBe(true)
    expect(w.find('.avatar.assistant').text()).toBe('AI')
  })

  it('renders a user bubble on the right with the 나 avatar', () => {
    const w = mount(ChatBubble, { props: { role: 'user', content: '네 맞아요' } })
    expect(w.find('.bubble.user').exists()).toBe(true)
    expect(w.find('.avatar.user').text()).toBe('나')
  })

  it('renders a system turn as a centered note without an avatar', () => {
    const w = mount(ChatBubble, { props: { role: 'system', content: '인터뷰를 시작합니다' } })
    expect(w.find('.system-note').text()).toBe('인터뷰를 시작합니다')
    expect(w.find('.avatar').exists()).toBe(false)
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run components/chat/ChatBubble.spec.ts`
Expected: FAIL — `Failed to resolve import "./ChatBubble.vue"`.

- [ ] **Step 3: 최소 구현 작성**

Create `components/chat/ChatBubble.vue`:

```vue
<script setup lang="ts">
// 대화 턴 1개를 말풍선으로. 순수 프레젠테이션, 내부 상태 없음.
// assistant → 좌측(회색 말풍선 + AI 아바타), user → 우측(파란 말풍선 + 나 아바타),
// system → 가운데 노트 칩.
const props = defineProps<{
  role: 'assistant' | 'user' | 'system'
  content: string
}>()

const avatarLabel = computed(() => (props.role === 'assistant' ? 'AI' : '나'))
</script>

<template>
  <div v-if="role === 'system'" class="bubble-row system">
    <span class="system-note">{{ content }}</span>
  </div>
  <div v-else class="bubble-row" :class="role">
    <div class="avatar" :class="role">{{ avatarLabel }}</div>
    <div class="bubble" :class="role">{{ content }}</div>
  </div>
</template>

<style scoped>
.bubble-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-bottom: 11px;
}
.bubble-row.assistant {
  flex-direction: row;
}
.bubble-row.user {
  flex-direction: row-reverse;
}
.bubble-row.system {
  justify-content: center;
}
.avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  flex: 0 0 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
}
.avatar.assistant {
  background: #e3f2fd;
  color: #1565c0;
}
.avatar.user {
  background: #eceff3;
  color: #5b6b7d;
}
.bubble {
  max-width: 80%;
  padding: 9px 12px;
  font-size: 12.5px;
  line-height: 1.46;
  border-radius: 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.assistant {
  background: #eef2f8;
  color: #25303f;
  border-bottom-left-radius: 4px;
}
.bubble.user {
  background: #1976d2;
  color: #fff;
  border-bottom-right-radius: 4px;
}
.system-note {
  font-size: 11px;
  color: #8a97a8;
  background: #f0f2f5;
  padding: 3px 11px;
  border-radius: 11px;
}
</style>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `npx vitest run components/chat/ChatBubble.spec.ts`
Expected: PASS (3 passed).

- [ ] **Step 5: 커밋**

```bash
git add components/chat/ChatBubble.vue components/chat/ChatBubble.spec.ts
git commit -m "feat(interview): ChatBubble 컴포넌트 (대화 턴 말풍선)"
```

---

## Task 4: `InterviewPanel.vue` 통합 (말풍선 + 타이핑 + 스크롤 + 취소확인 + 종료닫기)

`InterviewPanel.vue`를 자식 컴포넌트/컴포저블로 재배선한다. 기존 spec의 모든 케이스는 그대로 통과해야 하고(텍스트/`data-test` 훅 보존), 신규 케이스(타이핑/취소/닫기)를 추가한다.

> **참고 — 타이핑 표시 조건은 스펙 §5.2를 정밀화한다.** `PLAN_READY`에서 턴이 0개일 때 표시되던 경계 버그를 막기 위해, `AWAITING_INPUT`·`PLAN_READY`를 명시적으로 먼저 제외한다(아래 `waitingForAi` 구현 참조).

> **참고 — 취소 확인은 템플릿 `<q-dialog>`** 로 구현한다(imperative `$q.dialog` 대신, 테스트 용이). q-dialog는 `<body>`로 teleport되므로 테스트에서 `document.querySelector`로 접근하고, describe-scope `afterEach`에서 잔여 노드를 정리한다.

**Files:**
- Modify: `components/InterviewPanel.vue` (전체 교체)
- Modify: `components/InterviewPanel.spec.ts` (import 라인에 `afterEach` 추가 + 신규 describe 3개 추가, 기존 케이스 유지)

- [ ] **Step 1: 신규 실패 테스트 추가**

`components/InterviewPanel.spec.ts`의 vitest import 라인을 수정:

```ts
import { describe, it, expect, vi, afterEach } from 'vitest'
```

그리고 파일 **맨 끝**에 다음 describe 블록 3개를 추가(기존 블록은 그대로 둔다):

```ts
describe('InterviewPanel — typing indicator', () => {
  it('shows the typing indicator while RUNNING and hides it once AWAITING_INPUT', async () => {
    const w = mountPanel()
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(true)

    FakeEventSource.last().emit('question', { seq: 1, content: '범위는?' })
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(false)
    w.unmount()
  })

  it('hides the typing indicator at PLAN_READY and on terminal states', async () => {
    const w = mountPanel()
    FakeEventSource.last().emit('plan_ready', {
      designMarkdown: 'd',
      planMarkdown: 'p',
      planJson: JSON.stringify([]),
    })
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(false)

    FakeEventSource.last().emit('status', 'EXPIRED')
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(false)
    w.unmount()
  })
})

describe('InterviewPanel — cancel flow', () => {
  // q-dialog는 <body>로 teleport되므로 잔여 노드를 정리한다.
  afterEach(() => {
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  it('opens a confirm dialog and only cancels after 취소하기', async () => {
    useApiMock.mockResolvedValueOnce({})
    const w = mountPanel(7)
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()

    await w.find('[data-test="cancel-interview"]').trigger('click')
    await flushPromises()
    const confirm = document.querySelector('[data-test="cancel-confirm"]') as HTMLElement | null
    expect(confirm).toBeTruthy()

    confirm!.click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/7/cancel', { method: 'POST' })
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })

  it('does not cancel when 계속하기 is chosen', async () => {
    const w = mountPanel(7)
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()

    await w.find('[data-test="cancel-interview"]').trigger('click')
    await flushPromises()
    const keep = document.querySelector('[data-test="cancel-keep"]') as HTMLElement | null
    expect(keep).toBeTruthy()

    keep!.click()
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalled()
    expect(w.emitted('close')).toBeFalsy()
    w.unmount()
  })
})

describe('InterviewPanel — terminal close', () => {
  it('shows a 닫기 button on FAILED that emits close', async () => {
    const w = mountPanel()
    FakeEventSource.last().emit('status', 'FAILED')
    await flushPromises()
    const close = w.find('[data-test="close-interview"]')
    expect(close.exists()).toBe(true)
    await close.trigger('click')
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run components/InterviewPanel.spec.ts`
Expected: FAIL — 신규 케이스에서 `[data-test="typing-indicator"]`/`[data-test="cancel-interview"]`/`[data-test="close-interview"]` 미존재로 실패(기존 케이스는 통과).

- [ ] **Step 3: `InterviewPanel.vue` 전체 교체**

Replace the entire contents of `components/InterviewPanel.vue` with:

```vue
<script setup lang="ts">
import { useQuasar } from 'quasar'
import { useInterviewStream } from '~/composables/useInterviewStream'
import type { InterviewStatus } from '~/composables/useInterviewStream'
import { useAutoScroll } from '~/composables/useAutoScroll'
import ChatBubble from '~/components/chat/ChatBubble.vue'
import TypingIndicator from '~/components/chat/TypingIndicator.vue'

const props = defineProps<{ sessionId: number }>()
const emit = defineEmits<{ (e: 'registered', taskId: number): void; (e: 'close'): void }>()

const stream = useInterviewStream()
const { connState, status, turns, designSections, plan, error } = stream

// Narrow-screen tab fallback ('chat' | 'design').
const activeTab = ref<'chat' | 'design'>('chat')

const $q = useQuasar()
const answer = ref('')
const sending = ref(false)

// 마지막 미답변 assistant 질문의 seq (idempotency용 replyToSeq).
const lastQuestionSeq = computed(() => {
  for (let i = turns.value.length - 1; i >= 0; i--) {
    if (turns.value[i].role === 'assistant' && turns.value[i].kind === 'question') {
      return turns.value[i].seq
    }
  }
  return null
})

const canAnswer = computed(
  () => status.value === 'AWAITING_INPUT' && !sending.value && !!answer.value.trim(),
)

// 화면 표시 전용 한글 라벨. 상태 비교/터미널 판정은 영문 enum 이름으로만 한다.
const STATUS_LABELS: Record<InterviewStatus, string> = {
  QUEUED: '대기 중',
  RUNNING: '분석 중',
  AWAITING_INPUT: '입력 대기',
  PLAN_READY: '플랜 완료',
  REGISTERED: '등록됨',
  CANCELLED: '취소됨',
  EXPIRED: '만료됨',
  FAILED: '실패',
}
const statusLabel = computed(() =>
  status.value ? (STATUS_LABELS[status.value] ?? status.value) : '연결 중',
)

const registering = ref(false)

const isTerminal = computed(() =>
  ['REGISTERED', 'CANCELLED', 'EXPIRED', 'FAILED'].includes(status.value as string),
)

// AI 응답을 기다리는 중이면 타이핑 표시. 입력 차례/플랜 완료/종료에는 숨긴다.
// (스펙 §5.2 정밀화: AWAITING_INPUT·PLAN_READY를 먼저 제외해 PLAN_READY+턴0 경계 버그 방지.)
const waitingForAi = computed(() => {
  if (isTerminal.value) return false
  if (status.value === 'AWAITING_INPUT' || status.value === 'PLAN_READY') return false
  if (status.value === 'QUEUED' || status.value === 'RUNNING') return true
  // status가 아직 없음(연결 직후, 첫 질문 전): 스트림이 살아있고 턴이 없으면 준비 중 표시.
  return turns.value.length === 0 && connState.value !== 'closed' && connState.value !== 'idle'
})

// 스마트 자동 스크롤.
const transcriptEl = ref<HTMLElement | null>(null)
const { unread, onScroll, scrollToBottom, notifyNewContent } = useAutoScroll(transcriptEl)

// 새 턴/타이핑 표시가 생기면 스크롤 정책 적용. (내가 보낸 답변은 sendAnswer가 force 스크롤.)
watch([() => turns.value.length, waitingForAi], async () => {
  await nextTick()
  notifyNewContent()
})

async function sendAnswer() {
  const text = answer.value.trim()
  if (!text || status.value !== 'AWAITING_INPUT') return
  const replyToSeq = lastQuestionSeq.value
  sending.value = true
  try {
    await useApi(`/api/interviews/${props.sessionId}/answer`, {
      method: 'POST',
      body: { answer: text, replyToSeq },
    })
    // 낙관적 추가: 서버 재큐 후 다음 질문이 새 seq로 도착한다.
    turns.value = [
      ...turns.value,
      {
        seq: (replyToSeq ?? turns.value.length) + 0.5,
        role: 'user',
        kind: 'answer',
        content: text,
      },
    ]
    answer.value = ''
    status.value = 'QUEUED'
    await nextTick()
    scrollToBottom() // 내가 보낸 답변은 항상 하단으로
  } catch (e: any) {
    const st = e?.statusCode ?? e?.response?.status ?? e?.status
    if (st === 409) {
      $q.notify({ type: 'warning', message: '세션이 만료되었거나 이미 처리된 답변입니다' })
    } else {
      $q.notify({ type: 'negative', message: e?.data?.message ?? '답변 전송 실패' })
    }
  } finally {
    sending.value = false
  }
}

async function register() {
  if (status.value !== 'PLAN_READY' || registering.value) return
  registering.value = true
  try {
    const res = await useApi<{ taskId: number }>(`/api/interviews/${props.sessionId}/register`, {
      method: 'POST',
    })
    $q.notify({ type: 'positive', message: '작업 등록 완료' })
    emit('registered', res.taskId)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '작업 등록 실패' })
  } finally {
    registering.value = false
  }
}

// 대화 취소: 확인 다이얼로그를 거친 뒤에만 실제 취소.
const showCancelConfirm = ref(false)

async function cancelInterview() {
  try {
    await useApi(`/api/interviews/${props.sessionId}/cancel`, { method: 'POST' })
  } catch {
    /* 취소 실패는 무시 — 세션은 어차피 닫는다 */
  }
  status.value = 'CANCELLED' // 닫히기 전 UI 상태 정합 (배너/터미널 판정)
  stream.close()
  emit('close')
}

function confirmCancel() {
  showCancelConfirm.value = false
  cancelInterview()
}

function closePanel() {
  emit('close')
}

onMounted(async () => {
  stream.open(props.sessionId)
  await nextTick()
  scrollToBottom('auto')
})
onUnmounted(() => stream.close())
</script>

<template>
  <div class="interview-panel column no-wrap">
    <div class="status-bar row items-center q-pa-sm q-gutter-sm">
      <q-spinner
        v-if="connState === 'connecting' || connState === 'reconnecting'"
        size="18px"
        color="primary"
      />
      <!-- 색상/터미널 판정은 영문 enum(status), 표시는 한글(statusLabel). -->
      <q-badge :color="status === 'PLAN_READY' ? 'positive' : 'primary'" :label="statusLabel" />
      <q-banner v-if="status === 'EXPIRED'" dense class="bg-orange-1 text-orange-10 col"
        >세션이 만료되었습니다. 다시 인터뷰를 시작해 주세요.</q-banner
      >
      <q-banner v-else-if="status === 'CANCELLED'" dense class="bg-grey-2 text-grey-9 col"
        >인터뷰가 취소되었습니다.</q-banner
      >
      <q-banner v-else-if="status === 'FAILED'" dense class="bg-red-1 text-red-9 col"
        >인터뷰 실패: {{ error ?? '알 수 없는 오류가 발생했습니다' }}</q-banner
      >
      <q-banner v-else-if="error" dense class="bg-red-1 text-red-9 col">{{ error }}</q-banner>
      <q-space />
      <q-btn
        v-if="!isTerminal"
        data-test="cancel-interview"
        outline
        dense
        no-caps
        color="grey-7"
        icon="stop_circle"
        label="대화 취소"
        class="cancel-btn"
        @click="showCancelConfirm = true"
      />
      <q-btn
        v-if="isTerminal && status !== 'REGISTERED'"
        data-test="close-interview"
        flat
        dense
        no-caps
        color="grey-7"
        icon="close"
        label="닫기"
        @click="closePanel"
      />
    </div>

    <!-- 좁은 화면 탭 전환 -->
    <q-tabs v-model="activeTab" class="lt-md text-primary interview-tabs" dense align="justify">
      <q-tab name="chat" icon="forum" label="대화" />
      <q-tab name="design" icon="design_services" label="설계·플랜" />
    </q-tabs>

    <div class="interview-body row no-wrap">
      <!-- 좌: 대화 트랜스크립트 -->
      <section class="chat-col column no-wrap" :class="{ 'mobile-hidden': activeTab !== 'chat' }">
        <div
          ref="transcriptEl"
          class="transcript col scroll q-pa-sm"
          aria-live="polite"
          @scroll="onScroll"
        >
          <ChatBubble v-for="t in turns" :key="t.seq" :role="t.role" :content="t.content" />
          <TypingIndicator v-if="waitingForAi" data-test="typing-indicator" />
          <div
            v-if="turns.length === 0 && !waitingForAi"
            class="text-grey-6 q-pa-md text-center"
          >
            인터뷰를 시작합니다…
          </div>
          <div
            v-if="unread > 0"
            data-test="new-msg-pill"
            class="new-msg-pill"
            @click="scrollToBottom()"
          >
            ↓ 새 메시지 {{ unread }}
          </div>
        </div>
        <div class="answer-bar q-pa-sm">
          <q-input
            v-model="answer"
            type="textarea"
            outlined
            dense
            autogrow
            :disable="status !== 'AWAITING_INPUT' || sending"
            placeholder="답변을 입력하세요…"
            @keydown.enter.exact.prevent="sendAnswer"
          />
          <div class="row justify-end q-mt-xs">
            <q-btn
              data-test="send-answer"
              unelevated
              color="primary"
              icon="send"
              label="전송"
              :loading="sending"
              :disable="!canAnswer"
              @click="sendAnswer"
            />
          </div>
        </div>
      </section>

      <q-separator vertical class="gt-sm" />

      <!-- 우: 설계 섹션 + 플랜 (기능 동일) -->
      <section
        class="design-col column no-wrap"
        :class="{ 'mobile-hidden': activeTab !== 'design' }"
      >
        <div class="col scroll q-pa-sm">
          <div class="text-subtitle2 q-mb-sm">설계 섹션</div>
          <q-list bordered separator>
            <q-item v-for="d in designSections" :key="d.key">
              <q-item-section avatar>
                <q-icon
                  :name="d.approved ? 'check_circle' : 'radio_button_unchecked'"
                  :color="d.approved ? 'positive' : 'grey-5'"
                />
              </q-item-section>
              <q-item-section>
                <q-item-label>{{ d.title }}</q-item-label>
                <q-item-label caption style="white-space: pre-wrap">{{ d.body }}</q-item-label>
              </q-item-section>
            </q-item>
            <q-item v-if="designSections.length === 0">
              <q-item-section class="text-grey-6">아직 설계 섹션이 없습니다.</q-item-section>
            </q-item>
          </q-list>

          <div v-if="plan && plan.designMarkdown" class="q-mt-md">
            <div class="text-subtitle2 q-mb-sm">설계 문서</div>
            <pre class="plan-md">{{ plan.designMarkdown }}</pre>
          </div>

          <div v-if="plan" class="q-mt-md">
            <div class="text-subtitle2 q-mb-sm">구현 플랜</div>
            <pre class="plan-md">{{ plan.planMarkdown }}</pre>
          </div>

          <div class="row justify-end q-mt-md">
            <q-btn
              data-test="register"
              unelevated
              color="positive"
              icon="task_alt"
              label="작업 등록"
              :loading="registering"
              :disable="status !== 'PLAN_READY' || registering"
              @click="register"
            />
          </div>
        </div>
      </section>
    </div>

    <!-- 대화 취소 확인 -->
    <q-dialog v-model="showCancelConfirm">
      <q-card style="min-width: 320px">
        <q-card-section class="text-subtitle1 text-weight-bold">
          진행 중인 분석을 취소할까요?
        </q-card-section>
        <q-card-section class="q-pt-none text-grey-8">
          지금까지의 대화와 분석 진행 상황이 사라집니다. 작업은 등록되지 않습니다.
        </q-card-section>
        <q-card-actions align="right">
          <q-btn
            data-test="cancel-keep"
            flat
            no-caps
            label="계속하기"
            @click="showCancelConfirm = false"
          />
          <q-btn
            data-test="cancel-confirm"
            unelevated
            color="negative"
            no-caps
            label="취소하기"
            @click="confirmCancel"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </div>
</template>

<style scoped>
.interview-panel {
  height: 70vh;
  min-height: 420px;
}
.interview-body {
  flex: 1;
  min-height: 0;
}
.chat-col,
.design-col {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.transcript {
  min-height: 0;
  position: relative;
}
.plan-md {
  white-space: pre-wrap;
  font-family: 'Pretendard', sans-serif;
  font-size: 0.85rem;
}
.cancel-btn:hover {
  color: #c0392b !important;
}
.new-msg-pill {
  position: sticky;
  bottom: 8px;
  width: fit-content;
  margin: 4px auto 0;
  z-index: 5;
  background: var(--q-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  padding: 5px 14px;
  border-radius: 16px;
  cursor: pointer;
  box-shadow: 0 3px 10px rgba(25, 118, 210, 0.4);
}
@media (max-width: 1023px) {
  .mobile-hidden {
    display: none;
  }
}
</style>
```

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `npm run test`
Expected: PASS — 기존 + 신규 전부 통과. 특히 `InterviewPanel.spec.ts`의 기존 8케이스 + 신규 5케이스, `useAutoScroll`/`TypingIndicator`/`ChatBubble` 전부 녹색. 콘솔에 Vue/Quasar 경고가 있어도 실패가 없으면 OK.

> 만약 cancel-flow 테스트에서 teleport된 `[data-test="cancel-confirm"]`를 못 찾으면: q-dialog 전환이 끝나기 전 조회한 것 → `await flushPromises()` 한 번 더 추가하거나 `await new Promise((r) => setTimeout(r, 0))`로 다음 tick 대기.

- [ ] **Step 5: 커밋**

```bash
git add components/InterviewPanel.vue components/InterviewPanel.spec.ts
git commit -m "feat(interview): 팝업 말풍선 리프레시 + 타이핑/스마트스크롤/취소확인/종료닫기 배선"
```

---

## Task 5: 최종 검증 (전체 테스트 + 컴파일 + 수동 스모크)

자동 테스트로 못 잡는 애니메이션/스크롤/teleport 거동은 실제 앱에서 눈으로 확인한다.

**Files:** (코드 변경 없음 — 회귀 발견 시 해당 Task로 돌아가 수정)

- [ ] **Step 1: 전체 단위 테스트**

Run: `npm run test`
Expected: 모든 spec PASS, 실패 0.

- [ ] **Step 2: 프로덕션 빌드(컴파일/타입) 확인**

Run: `npm run build`
Expected: 빌드 성공(타입/SFC 컴파일 에러 없음). 시간이 오래 걸리면 생략 가능하나, 새 import 경로(`~/components/chat/*`)와 SFC 문법 검증용으로 권장.

- [ ] **Step 3: 앱 띄워 수동 스모크**

풀스택 기동(워크스페이스 루트의 `/start` 스킬 또는 수동: PostgreSQL → netis-auth(:9000) → netisMaker API(:8090) → 워커(macOS) → `cd frontend && npm run dev`(:3001)). admin/`password123` 로그인 후 작업 → "대화형 분석 시작" → 인터뷰 진행하며 아래 체크:

- [ ] 첫 질문 도착 전: AI 말풍선 자리에 **타이핑 점 3개**가 통통 튄다.
- [ ] 질문 도착 시: 점이 사라지고 질문 말풍선(좌측 회색)이 뜬다.
- [ ] 답변 전송 시: 내 말풍선(우측 파랑)이 뜨고 **자동으로 하단까지 스크롤**, 곧 타이핑 점 재등장.
- [ ] 대화가 길어진 뒤 **위로 스크롤**하고 새 메시지가 오면: 자동으로 안 내려가고 하단 중앙 **"↓ 새 메시지 N" 칩** 등장 → 클릭하면 하단으로.
- [ ] 상태바 우측 **"대화 취소"** 외곽선 버튼(■ 아이콘, hover 빨강) → 클릭 시 **확인 다이얼로그** → "계속하기"면 유지, "취소하기"면 팝업 닫힘.
- [ ] 세션 만료/실패(EXPIRED/FAILED) 상태에서 **"닫기"** 버튼으로 다이얼로그를 닫을 수 있다(이전엔 닫기 수단 없었음).
- [ ] 좁은 화면(<1024px): 대화/설계 탭 전환 정상.
- [ ] 우측 설계·플랜 컬럼/“작업 등록” 동작은 이전과 동일.

- [ ] **Step 4: (회귀 없으면) 마무리**

코드 변경이 없었다면 커밋 불필요. 회귀 수정이 있었다면 해당 변경을 커밋.

```bash
git log --oneline feat/conversational-analysis..HEAD
```

Expected: Task 1~4의 커밋 4개가 보임.

---

## Self-Review (작성자 점검 결과)

**1. 스펙 커버리지**
- §5.1 말풍선 리프레시 → Task 3(ChatBubble) + Task 4(루프 교체) ✓
- §5.2 타이핑 애니메이션 → Task 2(TypingIndicator) + Task 4(`waitingForAi`) ✓ (PLAN_READY 경계 버그를 명시 제외로 정밀화)
- §5.3 스마트 스크롤 + 칩 → Task 1(useAutoScroll) + Task 4(watch/pill/force) ✓
- §5.4 취소 버튼 + 확인 → Task 4(외곽선 버튼 + `<q-dialog>` + confirmCancel) ✓
- §5.5 종료-닫기 버그 → Task 4(`close-interview` 버튼) ✓
- §6 비주얼 스펙 → Task 2/3/4 스타일에 색·치수 반영 ✓
- §7 접근성 → TypingIndicator `role/aria-label`, transcript `aria-live`, reduced-motion ✓
- §8 테스트 → Task 1~4 TDD + 기존 케이스 보존(`send-answer`/`register`/`textarea`/배너/배지/`check_circle`) + 신규(typing/cancel/close) ✓

**2. 플레이스홀더 스캔:** TBD/TODO/“적절히 처리” 없음 — 모든 코드/명령/기대출력 구체값 ✓

**3. 타입/이름 정합성:** `useAutoScroll`가 노출하는 `{ nearBottom, unread, onScroll, scrollToBottom, notifyNewContent }`를 Task 4가 동일 이름으로 소비 ✓. `ChatBubble` props `{ role, content }`, `TypingIndicator` prop `label` — 사용처와 일치 ✓. `data-test` 훅(`typing-indicator`/`cancel-interview`/`cancel-keep`/`cancel-confirm`/`close-interview`/`new-msg-pill`)이 컴포넌트와 테스트에서 동일 ✓.

**알려진 리스크:** q-dialog teleport 테스트는 환경에 민감 — Step 4에 폴백(추가 tick 대기) 명시. 스크롤 실DOM 거동은 단위 테스트 대상이 아니라 Task 5 수동 스모크로 검증.
