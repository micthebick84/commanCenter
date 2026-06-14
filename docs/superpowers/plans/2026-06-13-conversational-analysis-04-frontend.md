# Conversational Analysis (Frontend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:test-driven-development` — every task below is structured as write-failing-test → see-it-fail → minimal-impl → see-it-pass → commit. Do not skip the red step; do not batch steps. Also consult `superpowers:verification-before-completion` before claiming any task done.

**Goal:** Extend the existing `frontend/pages/tasks/index.vue` "작업 등록" dialog into a 2-phase conversational-analysis flow. Phase 1 keeps the current create form but its submit button becomes **"인터뷰 시작"** (calls `POST /api/interviews`). Phase 2 is a split view: left = chat transcript + answer input + send (`POST /api/interviews/{id}/answer`), right = design sections (with approved-checks) + plan preview + a **"작업 등록"** button enabled at `PLAN_READY` (`POST /api/interviews/{id}/register`). A new `useInterviewStream` composable mirrors the EventSource/SSE pattern in `tasks/[id].vue` (`?access_token=`, reconnect, handlers for `question`/`design`/`plan_ready`/`status`/`done`). Narrow screens fall back to a tab toggle. Loading / expired / error / cancel states are handled throughout.

**Architecture:** Pure additive frontend change. One new composable (`composables/useInterviewStream.ts`), one new presentational/stateful component (`components/InterviewPanel.vue`) mounted inside the existing dialog, and edits to `pages/tasks/index.vue` to add Phase 1↔Phase 2 switching and the interview-start call. SSE wire contract (matches backend plan 02 `InterviewStreamService`): server emits named events `question`, `design`, `plan_ready`, `status`, `done`; client subscribes via native `EventSource` to `/api/interviews/{id}/stream?access_token=<jwt>`. **Locked payload shapes (must conform exactly):** the `status` event carries a bare **ENGLISH enum name** string from `InterviewStatus.name()` — one of `QUEUED`/`RUNNING`/`AWAITING_INPUT`/`PLAN_READY`/`REGISTERED`/`CANCELLED`/`EXPIRED`/`FAILED` (never the Korean `dbValue`, never a JSON object); the `plan_ready` event carries a JSON **object** `{designMarkdown, planMarkdown, planJson}` (serialized server-side; the client `JSON.parse`s the outer object — it is never a bare string; `planJson` inside that object is itself a **JSON string** that the composable must parse into an array with a try/catch guard). Terminal-state detection and the status badge therefore key off the English enum name, and Korean labels are applied **for display only** via a `STATUS_LABELS` map in the component. The `question` and `design` events carry structured JSON payloads (`question`: `{seq, content}`; `design`: `{key, title, body, approved}`) that the composable `JSON.parse`s; `done` carries a sentinel. The stream URL and all REST calls use the literal `/api` prefix proxied through Nitro `devProxy` (same convention as `tasks/[id].vue` log stream and `useApi`); the composable does **not** read `runtimeConfig.public.apiBaseUrl` (that var may hold an absolute origin and would break the SSE proxy path). REST calls go through the existing `useApi` Bearer-JWT wrapper. State for the interview lives in the composable (reactive refs), not Pinia — it is dialog-scoped and disposed on dialog close.

**Tech Stack:** Nuxt 3 (`^3.14`, SSR on, auto-imports), Quasar via `nuxt-quasar-ui`, Vue `^3.5` `<script setup lang="ts">`, Pinia for auth only, native `EventSource` for SSE, `useApi` (`$fetch` wrapper) for REST. Tests: **Vitest + @vue/test-utils + happy-dom + @nuxt/test-utils** (no FE test harness exists today — Task 1 adds a minimal one). Code style: single quotes, semicolons-off-by-prettier (match existing files — they use **no semicolons**, single quotes, 2-space indent, trailing commas), Korean UI strings.

---

## File Structure

**Created:**
- `frontend/vitest.config.ts` — Vitest config: happy-dom env, Vue plugin, `~`/`#imports` alias, globals.
- `frontend/test/setup.ts` — global test setup: stub Nuxt auto-imports (`ref`/`computed`/`watch`/lifecycle), install a fake `EventSource`, register Quasar for component mounts.
- `frontend/test/mocks/eventsource.ts` — `FakeEventSource` test double: records URL, exposes `emit(event, data)`, `triggerError()`, `triggerOpen()`, tracks `close()`.
- `frontend/test/mocks/nuxt.ts` — mock factories for `useApi`, `useAuthStore`, `useRuntimeConfig`.
- `frontend/composables/useInterviewStream.ts` — SSE client composable: opens EventSource with `?access_token=` against the literal `/api` prefix, parses `question` (`{seq, content}`), `design` (`{key, title, body, approved}`), `status` (bare **English** enum-name string), `plan_ready` (`JSON.parse` outer object → `{designMarkdown,planMarkdown,planJson}` then `JSON.parse` inner `planJson` string → array, guarded), and `done`; exposes reactive `turns`/`designSections`/`plan`/`status`/`connState`, terminal-detection on the English enum name, auto-reconnect with backoff, `close()`.
- `frontend/composables/useInterviewStream.spec.ts` — unit tests for the composable (event handling, reconnect, dedup, cleanup).
- `frontend/components/InterviewPanel.vue` — Phase-2 split-view UI (transcript + answer input on left, design sections + plan + register on right; narrow-screen tab fallback; expired/error/cancel banners).
- `frontend/components/InterviewPanel.spec.ts` — component tests for answer-send and register flows + state rendering.

**Modified:**
- `frontend/package.json` — add devDeps (`vitest`, `@vue/test-utils`, `happy-dom`, `@nuxt/test-utils`, `@vitejs/plugin-vue`) and a `"test"` script.
- `frontend/pages/tasks/index.vue` — Phase 1↔2 dialog state, rename submit button to "인터뷰 시작", `startInterview()` calling `POST /api/interviews`, mount `<InterviewPanel>` in Phase 2, dialog widen/maximize during interview, close/reset wiring.

---

### Task 1: Add a minimal Vitest + Vue Test Utils harness

**Files:**
- Modify: `frontend/package.json` (devDependencies + scripts)
- Create: `frontend/vitest.config.ts`
- Create: `frontend/test/setup.ts`
- Create: `frontend/test/mocks/eventsource.ts`
- Create: `frontend/test/mocks/nuxt.ts`
- Create: `frontend/test/smoke.spec.ts` (temporary, deleted at end of task)

- [ ] **Step 1: Add test deps and script to package.json.**
  Edit `frontend/package.json`. Add to `scripts` (after `"lint-prettier"`):
  ```json
    "test": "vitest run",
    "test:watch": "vitest"
  ```
  Add to `devDependencies` (keep alphabetical-ish, match existing `^` style):
  ```json
    "@nuxt/test-utils": "^3.15.1",
    "@vitejs/plugin-vue": "^5.2.1",
    "@vue/test-utils": "^2.4.6",
    "happy-dom": "^15.11.7",
    "vitest": "^2.1.8",
  ```
  Then install:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm install
  ```
  Expected: `npm install` completes, `node_modules/.bin/vitest` exists. Verify:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && ls node_modules/.bin/vitest
  ```

- [ ] **Step 2: Write the FakeEventSource test double.**
  Create `frontend/test/mocks/eventsource.ts`:
  ```ts
  // Minimal EventSource double for SSE composable tests.
  // Mirrors the subset the composable uses: addEventListener('<name>'), onerror, onopen, close().
  export const OPEN = 1
  export const CLOSED = 2

  export class FakeEventSource {
    static instances: FakeEventSource[] = []
    url: string
    readyState = 0
    onerror: ((e: Event) => void) | null = null
    onopen: ((e: Event) => void) | null = null
    private listeners: Record<string, ((e: MessageEvent) => void)[]> = {}

    constructor(url: string) {
      this.url = url
      FakeEventSource.instances.push(this)
    }

    addEventListener(type: string, cb: (e: MessageEvent) => void) {
      ;(this.listeners[type] ??= []).push(cb)
    }

    // Test helpers
    emit(type: string, data: unknown) {
      const payload = typeof data === 'string' ? data : JSON.stringify(data)
      const ev = { data: payload } as MessageEvent
      for (const cb of this.listeners[type] ?? []) cb(ev)
    }
    triggerOpen() {
      this.readyState = OPEN
      this.onopen?.(new Event('open'))
    }
    triggerError() {
      this.onerror?.(new Event('error'))
    }
    close() {
      this.readyState = CLOSED
    }

    static reset() {
      FakeEventSource.instances = []
    }
    static last(): FakeEventSource {
      return FakeEventSource.instances[FakeEventSource.instances.length - 1]
    }
  }
  ```

- [ ] **Step 3: Write Nuxt auto-import mocks.**
  Create `frontend/test/mocks/nuxt.ts`:
  ```ts
  import { vi } from 'vitest'

  // useApi mock: tests override .mockResolvedValueOnce / .mockRejectedValueOnce per case.
  export const useApiMock = vi.fn()

  // auth store stub: composable reads auth.accessToken to build the SSE URL.
  export const authStub = { accessToken: 'test-token-abc' }
  export const useAuthStoreMock = vi.fn(() => authStub)

  // runtime config stub: composable reads public.apiBaseUrl for URL construction.
  export const useRuntimeConfigMock = vi.fn(() => ({
    public: { apiBaseUrl: '/api', pollIntervalMs: 5000 },
  }))

  export function resetNuxtMocks() {
    useApiMock.mockReset()
    authStub.accessToken = 'test-token-abc'
    useAuthStoreMock.mockClear()
  }
  ```

- [ ] **Step 4: Write the global test setup.**
  Create `frontend/test/setup.ts`:
  ```ts
  import { config } from '@vue/test-utils'
  import { Quasar } from 'quasar'
  import { vi, beforeEach } from 'vitest'
  import * as vue from 'vue'
  import { FakeEventSource } from './mocks/eventsource'
  import {
    useApiMock,
    useAuthStoreMock,
    useRuntimeConfigMock,
    resetNuxtMocks,
  } from './mocks/nuxt'

  // Install fake EventSource globally so the composable's `new EventSource(...)` resolves to it.
  ;(globalThis as any).EventSource = FakeEventSource

  // Nuxt auto-imports are compiler magic at runtime; under Vitest we expose them as globals.
  Object.assign(globalThis as any, {
    ref: vue.ref,
    computed: vue.computed,
    reactive: vue.reactive,
    watch: vue.watch,
    onMounted: vue.onMounted,
    onUnmounted: vue.onUnmounted,
    nextTick: vue.nextTick,
    useApi: useApiMock,
    useAuthStore: useAuthStoreMock,
    useRuntimeConfig: useRuntimeConfigMock,
  })

  // Quasar components (q-input, q-btn, ...) available in mounts.
  config.global.plugins = [[Quasar, {}]]

  beforeEach(() => {
    resetNuxtMocks()
    FakeEventSource.reset()
  })
  ```

- [ ] **Step 5: Write the Vitest config.**
  Create `frontend/vitest.config.ts`:
  ```ts
  import vue from '@vitejs/plugin-vue'
  import { fileURLToPath } from 'node:url'
  import { defineConfig } from 'vitest/config'

  export default defineConfig({
    plugins: [vue()],
    resolve: {
      alias: {
        '~': fileURLToPath(new URL('./', import.meta.url)),
        '@': fileURLToPath(new URL('./', import.meta.url)),
      },
    },
    test: {
      globals: true,
      environment: 'happy-dom',
      setupFiles: ['./test/setup.ts'],
      include: ['**/*.spec.ts'],
      exclude: ['node_modules', '.nuxt', '.output', 'dist'],
    },
  })
  ```

- [ ] **Step 6: Add a smoke test and see the harness run green.**
  Create `frontend/test/smoke.spec.ts`:
  ```ts
  import { mount } from '@vue/test-utils'
  import { describe, it, expect } from 'vitest'
  import { defineComponent, h } from 'vue'
  import { FakeEventSource } from './mocks/eventsource'

  describe('test harness', () => {
    it('mounts a trivial component', () => {
      const C = defineComponent({ setup: () => () => h('div', '안녕') })
      const w = mount(C)
      expect(w.text()).toBe('안녕')
    })

    it('exposes a fake EventSource', () => {
      const es = new (globalThis as any).EventSource('/x') as FakeEventSource
      expect(FakeEventSource.last()).toBe(es)
      expect(es.url).toBe('/x')
    })
  })
  ```
  Run:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm test
  ```
  Expected: `2 passed` across `test/smoke.spec.ts`. (If `Quasar` import fails, confirm `nuxt-quasar-ui` pulls `quasar` into `node_modules` — it does as a peer; `npm install quasar@^2` only if the import errors.)

- [ ] **Step 7: Remove the smoke test and commit the harness.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && rm test/smoke.spec.ts && npm test
  ```
  Expected: `No test files found` (acceptable — harness is wired, no specs yet). Then commit:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git checkout -b feat/interview-frontend && git add frontend/package.json frontend/package-lock.json frontend/vitest.config.ts frontend/test && git commit -m "test(frontend): add minimal Vitest + Vue Test Utils harness

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 2: `useInterviewStream` — connect, parse `question`, and `status` events

**Files:**
- Create: `frontend/composables/useInterviewStream.ts`
- Create: `frontend/composables/useInterviewStream.spec.ts`

- [ ] **Step 1: Write a failing test for URL construction + connect.**
  Create `frontend/composables/useInterviewStream.spec.ts`:
  ```ts
  import { describe, it, expect } from 'vitest'
  import { useInterviewStream } from './useInterviewStream'
  import { FakeEventSource } from '../test/mocks/eventsource'
  import { authStub } from '../test/mocks/nuxt'

  describe('useInterviewStream — connect', () => {
    it('opens EventSource at /stream with access_token query', () => {
      authStub.accessToken = 'jwt-xyz'
      const s = useInterviewStream()
      s.open(42)
      const es = FakeEventSource.last()
      expect(es.url).toBe('/api/interviews/42/stream?access_token=jwt-xyz')
      s.close()
    })

    it('does not open when there is no access token', () => {
      authStub.accessToken = null as any
      const s = useInterviewStream()
      s.open(7)
      expect(FakeEventSource.instances.length).toBe(0)
      expect(s.error.value).toBe('인증 토큰이 없습니다')
    })
  })
  ```
  Run:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm test -- useInterviewStream
  ```
  Expected: fails — `Cannot find module './useInterviewStream'`.

- [ ] **Step 2: Minimal composable to make connect tests pass.**
  Create `frontend/composables/useInterviewStream.ts`:
  ```ts
  // SSE client for a conversational-analysis interview session.
  // Mirrors the EventSource pattern in pages/tasks/[id].vue (auth via ?access_token=),
  // adds reconnect + typed handlers for question/design/plan_ready/status/done.

  export type InterviewStatus =
    | 'QUEUED'
    | 'RUNNING'
    | 'AWAITING_INPUT'
    | 'PLAN_READY'
    | 'REGISTERED'
    | 'CANCELLED'
    | 'EXPIRED'
    | 'FAILED'

  export type ConnState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

  export interface Turn {
    seq: number
    role: 'assistant' | 'user' | 'system'
    kind: 'question' | 'answer' | 'design' | 'gate' | 'note'
    content: string
  }

  export interface DesignSection {
    key: string
    title: string
    body: string
    approved: boolean
  }

  export interface InterviewPlan {
    designMarkdown: string
    planMarkdown: string
    /** planJson arrives as a JSON string inside the plan_ready object; parsed to array on receipt. */
    planJson: unknown
  }

  const TERMINAL: InterviewStatus[] = ['REGISTERED', 'CANCELLED', 'EXPIRED', 'FAILED']

  // Valid English enum names the server may push on the `status` event
  // (InterviewStatus.name() — never the Korean dbValue).
  const KNOWN_STATUSES: InterviewStatus[] = [
    'QUEUED',
    'RUNNING',
    'AWAITING_INPUT',
    'PLAN_READY',
    'REGISTERED',
    'CANCELLED',
    'EXPIRED',
    'FAILED',
  ]

  export function useInterviewStream() {
    const auth = useAuthStore()

    const connState = ref<ConnState>('idle')
    const status = ref<InterviewStatus | null>(null)
    const turns = ref<Turn[]>([])
    const designSections = ref<DesignSection[]>([])
    const plan = ref<InterviewPlan | null>(null)
    const error = ref<string | null>(null)

    let es: EventSource | null = null
    let sessionId: number | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    let attempts = 0

    // Literal `/api` prefix proxied through Nitro devProxy — identical convention to
    // pages/tasks/[id].vue's log stream and the useApi() REST wrapper. We deliberately do
    // NOT read runtimeConfig.public.apiBaseUrl (it may hold an absolute origin, which would
    // bypass the SSE proxy path).
    function streamUrl(id: number): string {
      return `/api/interviews/${id}/stream?access_token=${encodeURIComponent(
        auth.accessToken as string,
      )}`
    }

    function open(id: number) {
      sessionId = id
      error.value = null
      if (!auth.accessToken) {
        error.value = '인증 토큰이 없습니다'
        connState.value = 'closed'
        return
      }
      connect()
    }

    function connect() {
      teardown()
      connState.value = attempts === 0 ? 'connecting' : 'reconnecting'
      es = new EventSource(streamUrl(sessionId as number))
      es.onopen = () => {
        attempts = 0
        connState.value = 'open'
      }
      es.addEventListener('status', (e) => onStatus(e as MessageEvent))
      es.addEventListener('question', (e) => onQuestion(e as MessageEvent))
      es.onerror = () => onError()
    }

    // `status` payload is a BARE English enum-name string (InterviewStatus.name()),
    // e.g. "AWAITING_INPUT" / "PLAN_READY" / "EXPIRED" — NOT a JSON object, NOT the
    // Korean dbValue. Terminal detection keys off this English name.
    function onStatus(e: MessageEvent) {
      const name = (e.data ?? '').trim() as InterviewStatus
      if (!KNOWN_STATUSES.includes(name)) return
      status.value = name
      if (TERMINAL.includes(name)) close()
    }

    function onQuestion(e: MessageEvent) {
      const data = parse(e)
      if (!data) return
      pushTurn({
        seq: data.seq,
        role: 'assistant',
        kind: 'question',
        content: data.content,
      })
      status.value = 'AWAITING_INPUT'
    }

    // Dedup by seq so SSE replay-on-reconnect does not duplicate turns.
    function pushTurn(t: Turn) {
      if (turns.value.some((x) => x.seq === t.seq)) return
      turns.value = [...turns.value, t].sort((a, b) => a.seq - b.seq)
    }

    function onError() {
      if (!es) return
      connState.value = 'reconnecting'
      attempts += 1
      const delay = Math.min(1000 * 2 ** (attempts - 1), 15000)
      reconnectTimer = setTimeout(() => {
        if (sessionId != null) connect()
      }, delay)
    }

    function parse(e: MessageEvent): any | null {
      try {
        return JSON.parse(e.data)
      } catch {
        return null
      }
    }

    function teardown() {
      if (es) {
        es.close()
        es = null
      }
    }

    function close() {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
      teardown()
      sessionId = null
      connState.value = 'closed'
    }

    onUnmounted(() => close())

    return {
      connState,
      status,
      turns,
      designSections,
      plan,
      error,
      open,
      close,
    }
  }
  ```
  Run:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm test -- useInterviewStream
  ```
  Expected: 2 passed. (`onUnmounted` outside a component is a no-op warning under Vitest — harmless; if it throws, the next step's test still drives behavior. It does not throw in Vue 3.5 when called outside setup — it warns and returns.)

- [ ] **Step 3: Failing test for `question` handling + AWAITING_INPUT + dedup.**
  Append to `frontend/composables/useInterviewStream.spec.ts`:
  ```ts
  describe('useInterviewStream — question events', () => {
    it('appends an assistant question turn and flips to AWAITING_INPUT', () => {
      authStub.accessToken = 'jwt'
      const s = useInterviewStream()
      s.open(1)
      FakeEventSource.last().emit('question', {
        seq: 1,
        content: '이 기능의 트리거는 무엇인가요?',
      })
      expect(s.turns.value).toHaveLength(1)
      expect(s.turns.value[0]).toMatchObject({
        seq: 1,
        role: 'assistant',
        kind: 'question',
        content: '이 기능의 트리거는 무엇인가요?',
      })
      expect(s.status.value).toBe('AWAITING_INPUT')
      s.close()
    })

    it('dedups questions by seq across reconnect replay', () => {
      authStub.accessToken = 'jwt'
      const s = useInterviewStream()
      s.open(1)
      const es = FakeEventSource.last()
      es.emit('question', { seq: 1, content: 'Q1' })
      es.emit('question', { seq: 1, content: 'Q1' })
      expect(s.turns.value).toHaveLength(1)
      s.close()
    })
  })
  ```
  Run: `npm test -- useInterviewStream`. Expected: passes (impl from Step 2 already covers it — this locks the contract). If `pushTurn` were wrong, it would fail.

- [ ] **Step 4: Failing test for terminal `status` auto-close.**
  Append:
  ```ts
  describe('useInterviewStream — status events', () => {
    it('reads the bare English enum-name status and auto-closes on terminal status', () => {
      authStub.accessToken = 'jwt'
      const s = useInterviewStream()
      s.open(1)
      const es = FakeEventSource.last()
      // Canonical wire payload: bare InterviewStatus.name() string, NOT a JSON object,
      // NOT the Korean dbValue.
      es.emit('status', 'RUNNING')
      expect(s.status.value).toBe('RUNNING')
      expect(s.connState.value).not.toBe('closed')
      es.emit('status', 'EXPIRED')
      expect(s.status.value).toBe('EXPIRED')
      expect(s.connState.value).toBe('closed')
    })

    it('ignores an unknown / non-enum status payload', () => {
      authStub.accessToken = 'jwt'
      const s = useInterviewStream()
      s.open(1)
      const es = FakeEventSource.last()
      es.emit('status', 'RUNNING')
      es.emit('status', '없는상태') // a stray Korean label must NOT clobber state
      expect(s.status.value).toBe('RUNNING')
      s.close()
    })
  })
  ```
  Run: `npm test -- useInterviewStream`. Expected: passes (locks terminal behavior).

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add frontend/composables/useInterviewStream.ts frontend/composables/useInterviewStream.spec.ts && git commit -m "feat(frontend): useInterviewStream connect + question/status SSE handling

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 3: `useInterviewStream` — `design`, `plan_ready`, `done`, and reconnect

**Files:**
- Modify: `frontend/composables/useInterviewStream.ts`
- Modify: `frontend/composables/useInterviewStream.spec.ts`

- [ ] **Step 1: Failing test for `design` events (upsert section by key, approved flag).**
  Append to `frontend/composables/useInterviewStream.spec.ts`:
  ```ts
  describe('useInterviewStream — design events', () => {
    it('upserts design sections by key and tracks approved', () => {
      authStub.accessToken = 'jwt'
      const s = useInterviewStream()
      s.open(1)
      const es = FakeEventSource.last()
      es.emit('design', { key: 'overview', title: '개요', body: '초안', approved: false })
      expect(s.designSections.value).toHaveLength(1)
      es.emit('design', { key: 'overview', title: '개요', body: '확정', approved: true })
      expect(s.designSections.value).toHaveLength(1)
      expect(s.designSections.value[0]).toMatchObject({ body: '확정', approved: true })
      es.emit('design', { key: 'data', title: '데이터', body: 'x', approved: false })
      expect(s.designSections.value).toHaveLength(2)
      s.close()
    })
  })
  ```
  Run: `npm test -- useInterviewStream`. Expected: fails — `design` has no listener yet, `designSections` stays empty.

- [ ] **Step 2: Add the `design` handler.**
  In `useInterviewStream.ts`, inside `connect()` after the `question` listener line, add:
  ```ts
      es.addEventListener('design', (e) => onDesign(e as MessageEvent))
  ```
  Add the handler after `onQuestion`:
  ```ts
    function onDesign(e: MessageEvent) {
      const data = parse(e)
      if (!data || !data.key) return
      const idx = designSections.value.findIndex((d) => d.key === data.key)
      const section: DesignSection = {
        key: data.key,
        title: data.title ?? data.key,
        body: data.body ?? '',
        approved: !!data.approved,
      }
      if (idx >= 0) {
        const next = designSections.value.slice()
        next[idx] = section
        designSections.value = next
      } else {
        designSections.value = [...designSections.value, section]
      }
    }
  ```
  Run: `npm test -- useInterviewStream`. Expected: design test passes.

- [ ] **Step 3: Failing test for `plan_ready` + `done`.**
  Append:
  ```ts
  describe('useInterviewStream — plan_ready/done', () => {
    it('captures the plan OBJECT and sets PLAN_READY on plan_ready event', () => {
      authStub.accessToken = 'jwt'
      const s = useInterviewStream()
      s.open(1)
      // Canonical wire payload: a JSON OBJECT { designMarkdown, planMarkdown, planJson }
      // where planJson is itself a JSON STRING (the SDK service sends JSON.stringify(array)).
      // The composable parses the outer object, then JSON.parses the inner planJson string.
      FakeEventSource.last().emit('plan_ready', {
        designMarkdown: '# 설계',
        planMarkdown: '# 플랜',
        planJson: JSON.stringify([{ title: 'T1' }]),  // planJson arrives as a JSON STRING
      })
      expect(s.status.value).toBe('PLAN_READY')
      expect(s.plan.value).toMatchObject({
        designMarkdown: '# 설계',
        planMarkdown: '# 플랜',
      })
      // planJson is parsed from the string into an array on receipt
      expect(Array.isArray(s.plan.value!.planJson)).toBe(true)
      expect(s.plan.value!.planJson).toEqual([{ title: 'T1' }])
      s.close()
    })

    it('closes the stream on done', () => {
      authStub.accessToken = 'jwt'
      const s = useInterviewStream()
      s.open(1)
      const es = FakeEventSource.last()
      es.emit('done', {})
      expect(s.connState.value).toBe('closed')
    })
  })
  ```
  Run: `npm test -- useInterviewStream`. Expected: fails — no `plan_ready`/`done` listeners.

- [ ] **Step 4: Add `plan_ready` and `done` handlers.**
  In `connect()`, after the `design` listener line, add:
  ```ts
      es.addEventListener('plan_ready', (e) => onPlanReady(e as MessageEvent))
      es.addEventListener('done', () => close())
  ```
  After `onDesign`, add:
  ```ts
    function onPlanReady(e: MessageEvent) {
      // The plan_ready event data is a JSON object {designMarkdown, planMarkdown, planJson}
      // where planJson is itself a JSON STRING (the SDK service sends JSON.stringify(array)).
      // Parse the outer object first, then parse the inner planJson string into an array.
      const obj = parse(e)
      if (!obj) return
      let parsedPlanJson: unknown = null
      try {
        parsedPlanJson = typeof obj.planJson === 'string' ? JSON.parse(obj.planJson) : (obj.planJson ?? null)
      } catch {
        parsedPlanJson = null // guard against malformed JSON in planJson
      }
      plan.value = {
        designMarkdown: obj.designMarkdown ?? '',
        planMarkdown: obj.planMarkdown ?? '',
        planJson: parsedPlanJson,
      }
      status.value = 'PLAN_READY'
    }
  ```
  Run: `npm test -- useInterviewStream`. Expected: plan_ready + done tests pass.

- [ ] **Step 5: Failing test for reconnect-with-backoff after error.**
  Append:
  ```ts
  import { vi } from 'vitest'

  describe('useInterviewStream — reconnect', () => {
    it('reconnects after a transient error', () => {
      vi.useFakeTimers()
      authStub.accessToken = 'jwt'
      const s = useInterviewStream()
      s.open(1)
      expect(FakeEventSource.instances).toHaveLength(1)
      FakeEventSource.last().triggerError()
      expect(s.connState.value).toBe('reconnecting')
      vi.advanceTimersByTime(1000)
      expect(FakeEventSource.instances).toHaveLength(2)
      s.close()
      vi.useRealTimers()
    })

    it('stops reconnecting after close()', () => {
      vi.useFakeTimers()
      authStub.accessToken = 'jwt'
      const s = useInterviewStream()
      s.open(1)
      FakeEventSource.last().triggerError()
      s.close()
      vi.advanceTimersByTime(30000)
      expect(FakeEventSource.instances).toHaveLength(1)
      vi.useRealTimers()
    })
  })
  ```
  Run: `npm test -- useInterviewStream`. Expected: first reconnect test passes (impl exists); the `stops reconnecting` test passes only because `close()` clears `reconnectTimer` and nulls `sessionId` — confirm green. If `connect()` re-opens after close, fix `connect()` guard.

- [ ] **Step 6: Harden `connect()` against post-close reconnect (if Step 5 second test is red).**
  Ensure the reconnect timer callback already guards on `sessionId != null` (it does in Task 2 Step 2). If the second test is red, change `onError()`'s timer body to also check `connState.value !== 'closed'`:
  ```ts
      reconnectTimer = setTimeout(() => {
        if (sessionId != null && connState.value !== 'closed') connect()
      }, delay)
  ```
  Run: `npm test -- useInterviewStream`. Expected: all `useInterviewStream` tests green.

- [ ] **Step 7: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add frontend/composables/useInterviewStream.ts frontend/composables/useInterviewStream.spec.ts && git commit -m "feat(frontend): useInterviewStream design/plan_ready/done + reconnect backoff

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 4: `InterviewPanel.vue` — split view scaffold + transcript rendering

**Files:**
- Create: `frontend/components/InterviewPanel.vue`
- Create: `frontend/components/InterviewPanel.spec.ts`

- [ ] **Step 1: Failing test for rendering transcript turns.**
  Create `frontend/components/InterviewPanel.spec.ts`:
  ```ts
  import { mount, flushPromises } from '@vue/test-utils'
  import { describe, it, expect, vi } from 'vitest'
  import InterviewPanel from './InterviewPanel.vue'
  import { FakeEventSource } from '../test/mocks/eventsource'
  import { authStub } from '../test/mocks/nuxt'

  function mountPanel(sessionId = 5) {
    authStub.accessToken = 'jwt'
    return mount(InterviewPanel, { props: { sessionId } })
  }

  describe('InterviewPanel — transcript', () => {
    it('renders assistant question turns from the stream', async () => {
      const w = mountPanel()
      FakeEventSource.last().emit('question', { seq: 1, content: '트리거가 뭔가요?' })
      await flushPromises()
      expect(w.text()).toContain('트리거가 뭔가요?')
      w.unmount()
    })
  })
  ```
  Run:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm test -- InterviewPanel
  ```
  Expected: fails — `Cannot find module './InterviewPanel.vue'`.

- [ ] **Step 2: Minimal `InterviewPanel.vue` — open stream on mount, render transcript.**
  Create `frontend/components/InterviewPanel.vue`:
  ```vue
  <script setup lang="ts">
  import { useInterviewStream } from '~/composables/useInterviewStream'

  const props = defineProps<{ sessionId: number }>()
  const emit = defineEmits<{ (e: 'registered', taskId: number): void; (e: 'close'): void }>()

  const stream = useInterviewStream()
  const { connState, status, turns, designSections, plan, error } = stream

  // Narrow-screen tab fallback ('chat' | 'design').
  const activeTab = ref<'chat' | 'design'>('chat')

  onMounted(() => stream.open(props.sessionId))
  onUnmounted(() => stream.close())
  </script>

  <template>
    <div class="interview-panel column no-wrap">
      <!-- 좁은 화면 탭 전환 -->
      <q-tabs
        v-model="activeTab"
        class="lt-md text-primary interview-tabs"
        dense
        align="justify"
      >
        <q-tab name="chat" icon="forum" label="대화" />
        <q-tab name="design" icon="design_services" label="설계·플랜" />
      </q-tabs>

      <div class="interview-body row no-wrap">
        <!-- 좌: 대화 트랜스크립트 -->
        <section
          class="chat-col column no-wrap"
          :class="{ 'mobile-hidden': activeTab !== 'chat' }"
        >
          <div class="transcript col scroll q-pa-sm">
            <div
              v-for="t in turns"
              :key="t.seq"
              class="turn q-mb-sm"
              :class="`turn-${t.role}`"
            >
              <div class="turn-role text-caption text-grey-7">
                {{ t.role === 'assistant' ? 'AI' : t.role === 'user' ? '나' : '시스템' }}
              </div>
              <div class="turn-content" style="white-space: pre-wrap">{{ t.content }}</div>
            </div>
            <div v-if="turns.length === 0" class="text-grey-6 q-pa-md text-center">
              인터뷰를 시작합니다… 첫 질문을 준비 중입니다.
            </div>
          </div>
        </section>

        <q-separator vertical class="gt-sm" />

        <!-- 우: 설계 섹션 + 플랜 -->
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

            <div v-if="plan" class="q-mt-md">
              <div class="text-subtitle2 q-mb-sm">구현 플랜</div>
              <pre class="plan-md">{{ plan.planMarkdown }}</pre>
            </div>
          </div>
        </section>
      </div>
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
  }
  .plan-md {
    white-space: pre-wrap;
    font-family: 'Pretendard', sans-serif;
    font-size: 0.85rem;
  }
  .turn-assistant {
    border-left: 3px solid var(--q-primary);
    padding-left: 8px;
  }
  .turn-user {
    border-left: 3px solid #bdbdbd;
    padding-left: 8px;
  }
  @media (max-width: 1023px) {
    .mobile-hidden {
      display: none;
    }
  }
  </style>
  ```
  Run: `npm test -- InterviewPanel`. Expected: transcript test passes.

- [ ] **Step 3: Failing test for design section approved-check rendering.**
  Append to `frontend/components/InterviewPanel.spec.ts`:
  ```ts
  describe('InterviewPanel — design sections', () => {
    it('shows a check icon for approved sections', async () => {
      const w = mountPanel()
      FakeEventSource.last().emit('design', {
        key: 'overview',
        title: '개요',
        body: '확정안',
        approved: true,
      })
      await flushPromises()
      expect(w.text()).toContain('개요')
      expect(w.findAll('.q-icon').some((i) => i.text() === 'check_circle')).toBe(true)
      w.unmount()
    })
  })
  ```
  Run: `npm test -- InterviewPanel`. Expected: passes (icon name renders as ligature text under material-icons; locks behavior). If `.text()` does not surface the ligature in happy-dom, assert on the rendered icon class instead:
  ```ts
      expect(w.html()).toContain('check_circle')
  ```

- [ ] **Step 4: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add frontend/components/InterviewPanel.vue frontend/components/InterviewPanel.spec.ts && git commit -m "feat(frontend): InterviewPanel split view + transcript/design rendering

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 5: `InterviewPanel.vue` — answer input + send flow

**Files:**
- Modify: `frontend/components/InterviewPanel.vue`
- Modify: `frontend/components/InterviewPanel.spec.ts`

- [ ] **Step 1: Failing test for the answer-send flow.**
  Append to `frontend/components/InterviewPanel.spec.ts`:
  ```ts
  import { useApiMock } from '../test/mocks/nuxt'

  describe('InterviewPanel — answer flow', () => {
    it('POSTs the answer with replyToSeq, optimistically appends a user turn, clears input', async () => {
      useApiMock.mockResolvedValueOnce({})
      const w = mountPanel(9)
      FakeEventSource.last().emit('question', { seq: 3, content: '범위는?' })
      await flushPromises()

      await w.find('textarea').setValue('대시보드만 추가합니다')
      await w.find('[data-test="send-answer"]').trigger('click')
      await flushPromises()

      expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/answer', {
        method: 'POST',
        body: { answer: '대시보드만 추가합니다', replyToSeq: 3 },
      })
      expect(w.text()).toContain('대시보드만 추가합니다')
      expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('')
      w.unmount()
    })

    it('disables send when not AWAITING_INPUT', async () => {
      const w = mountPanel(9)
      // Canonical wire payload: bare English enum name.
      FakeEventSource.last().emit('status', 'RUNNING')
      await flushPromises()
      const btn = w.find('[data-test="send-answer"]')
      expect(btn.attributes('disabled')).toBeDefined()
      w.unmount()
    })
  })
  ```
  Run: `npm test -- InterviewPanel`. Expected: fails — no answer input / send button / `sendAnswer` yet.

- [ ] **Step 2: Add answer state + `sendAnswer()` + input UI.**
  In `InterviewPanel.vue` `<script setup>`, after the `activeTab` line, add:
  ```ts
  import { useQuasar } from 'quasar'

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
  ```
  In the template, inside the `chat-col` section, after the closing `</div>` of `.transcript`, add the input row:
  ```vue
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
  ```
  Run: `npm test -- InterviewPanel`. Expected: both answer-flow tests pass. (Quasar binds `:disable` to the underlying `<button disabled>`, so `attributes('disabled')` is defined when `canAnswer` is false.)

- [ ] **Step 3: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add frontend/components/InterviewPanel.vue frontend/components/InterviewPanel.spec.ts && git commit -m "feat(frontend): InterviewPanel answer input + send with idempotent replyToSeq

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 6: `InterviewPanel.vue` — register flow + status/error/expired/cancel states

**Files:**
- Modify: `frontend/components/InterviewPanel.vue`
- Modify: `frontend/components/InterviewPanel.spec.ts`

- [ ] **Step 1: Failing test for the register flow.**
  Append to `frontend/components/InterviewPanel.spec.ts`:
  ```ts
  describe('InterviewPanel — register flow', () => {
    it('enables 작업 등록 only at PLAN_READY and emits registered with taskId', async () => {
      useApiMock.mockResolvedValueOnce({ taskId: 123 })
      const w = mountPanel(9)
      const es = FakeEventSource.last()

      // Before plan_ready: button is disabled.
      es.emit('design', { key: 'a', title: 'A', body: 'b', approved: true })
      await flushPromises()
      let reg = w.find('[data-test="register"]')
      expect(reg.attributes('disabled')).toBeDefined()

      es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
      await flushPromises()
      reg = w.find('[data-test="register"]')
      expect(reg.attributes('disabled')).toBeUndefined()

      await reg.trigger('click')
      await flushPromises()

      expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/register', { method: 'POST' })
      expect(w.emitted('registered')?.[0]).toEqual([123])
      w.unmount()
    })
  })

  describe('InterviewPanel — terminal states', () => {
    it('shows an expired banner from the bare EXPIRED status', async () => {
      const w = mountPanel(9)
      // Canonical wire payload: bare English enum name.
      FakeEventSource.last().emit('status', 'EXPIRED')
      await flushPromises()
      expect(w.text()).toContain('만료')
      w.unmount()
    })

    it('renders the Korean badge label (not the raw English enum) for AWAITING_INPUT', async () => {
      const w = mountPanel(9)
      FakeEventSource.last().emit('status', 'AWAITING_INPUT')
      await flushPromises()
      const txt = w.text()
      expect(txt).toContain('입력 대기') // Korean label, display-only
      expect(txt).not.toContain('AWAITING_INPUT') // raw enum name must not surface
      w.unmount()
    })

    it('shows a failed banner from the bare FAILED status', async () => {
      const w = mountPanel(9)
      FakeEventSource.last().emit('status', 'FAILED')
      await flushPromises()
      expect(w.text()).toContain('인터뷰 실패')
      w.unmount()
    })
  })
  ```
  Run: `npm test -- InterviewPanel`. Expected: fails — no register button / banners / Korean status-label map yet.

- [ ] **Step 2: (no composable change needed.)**
  The `status` event already lands as a bare English enum name in `useInterviewStream` (Task 2 Step 2 / its terminal-status tests). There is **no** `failureReason` on the SSE wire — the backend `status` event carries only `InterviewStatus.name()`. The FAILED banner therefore shows a generic Korean message (next step); do not add a `failureReason` ref. Confirm the composable suite is still green:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm test -- useInterviewStream
  ```
  Expected: all `useInterviewStream` tests pass (no changes).

- [ ] **Step 3: Add register + cancel actions, the Korean status-label map, and terminal banners to `InterviewPanel.vue`.**
  In `<script setup>`, keep the existing destructure (no `failureReason`):
  ```ts
  const { connState, status, turns, designSections, plan, error } = stream
  ```
  Add the display-only Korean label map + register/cancel logic after `sendAnswer`:
  ```ts
  import type { InterviewStatus } from '~/composables/useInterviewStream'

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

  async function register() {
    if (status.value !== 'PLAN_READY' || registering.value) return
    registering.value = true
    try {
      const res = await useApi<{ taskId: number }>(
        `/api/interviews/${props.sessionId}/register`,
        { method: 'POST' },
      )
      $q.notify({ type: 'positive', message: '작업 등록 완료' })
      emit('registered', res.taskId)
    } catch (e: any) {
      $q.notify({ type: 'negative', message: e?.data?.message ?? '작업 등록 실패' })
    } finally {
      registering.value = false
    }
  }

  async function cancelInterview() {
    try {
      await useApi(`/api/interviews/${props.sessionId}/cancel`, { method: 'POST' })
    } catch {
      /* 취소 실패는 무시 — 세션은 어차피 닫는다 */
    }
    stream.close()
    emit('close')
  }
  </script>
  ```
  In the template, add a status/banner header at the top of `.interview-panel` (before `q-tabs`):
  ```vue
      <div class="status-bar row items-center q-pa-sm q-gutter-sm">
        <q-spinner v-if="connState === 'connecting' || connState === 'reconnecting'" size="18px" color="primary" />
        <!-- 색상/터미널 판정은 영문 enum(status), 표시는 한글(statusLabel). -->
        <q-badge :color="status === 'PLAN_READY' ? 'positive' : 'primary'" :label="statusLabel" />
        <q-banner
          v-if="status === 'EXPIRED'"
          dense
          class="bg-orange-1 text-orange-10 col"
        >세션이 만료되었습니다. 다시 인터뷰를 시작해 주세요.</q-banner>
        <q-banner
          v-else-if="status === 'CANCELLED'"
          dense
          class="bg-grey-2 text-grey-9 col"
        >인터뷰가 취소되었습니다.</q-banner>
        <q-banner
          v-else-if="status === 'FAILED'"
          dense
          class="bg-red-1 text-red-9 col"
        >인터뷰 실패: {{ error ?? '알 수 없는 오류가 발생했습니다' }}</q-banner>
        <q-banner
          v-else-if="error"
          dense
          class="bg-red-1 text-red-9 col"
        >{{ error }}</q-banner>
        <q-space />
        <q-btn
          v-if="!isTerminal"
          flat
          dense
          color="grey-7"
          label="취소"
          @click="cancelInterview"
        />
      </div>
  ```
  In the `design-col`, after the `plan` block's closing `</div>`, add the register button:
  ```vue
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
  ```
  Run: `npm test -- InterviewPanel`. Expected: register-flow + expired + failed + Korean-badge-label tests all pass.

- [ ] **Step 4: Full suite green + commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm test
  ```
  Expected: all `useInterviewStream.spec.ts` and `InterviewPanel.spec.ts` tests pass, 0 failures. Then:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add frontend/composables/useInterviewStream.ts frontend/components/InterviewPanel.vue frontend/components/InterviewPanel.spec.ts && git commit -m "feat(frontend): InterviewPanel register flow + expired/failed/cancel states

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 7: Wire Phase 1 → Phase 2 into `pages/tasks/index.vue`

**Files:**
- Modify: `frontend/pages/tasks/index.vue` (script: lines 48-51 draft state, 259-281 `submit`, 232-242 `openCreate`; template: lines 410-595 dialog)

- [ ] **Step 1: Add interview phase state + `startInterview()`.**
  In `pages/tasks/index.vue` `<script setup>`, replace the create-dialog state block (lines 48-51) — keep `showCreate`, `draft`, `submitting` — and add phase + session state directly after the `submitting` line:
  ```ts
  // 다이얼로그 단계: 'form' = 입력(Phase 1), 'interview' = 분할 뷰(Phase 2)
  const dialogPhase = ref<'form' | 'interview'>('form')
  const interviewSessionId = ref<number | null>(null)
  const starting = ref(false)
  ```
  Add the start handler immediately after the existing `submit()` function (ends line 281):
  ```ts
  // Phase 1 제출: 작업이 아니라 인터뷰 세션을 생성하고 Phase 2(분할 뷰)로 전환한다.
  // task는 인터뷰 완료(PLAN_READY) 후 '작업 등록'에서만 생성된다.
  async function startInterview() {
    starting.value = true
    try {
      const res = await useApi<{ sessionId: number }>('/api/interviews', {
        method: 'POST',
        body: {
          githubRepo: normalizeRepo(draft.githubRepo),
          githubBranch: draft.githubBranch,
          title: draft.title,
          description: draft.description,
          mcpCatalogIds: selectedCatalogIds.value,
        },
      })
      interviewSessionId.value = res.sessionId
      dialogPhase.value = 'interview'
    } catch (e: any) {
      $q.notify({ type: 'negative', message: e?.data?.message ?? '인터뷰 시작 실패' })
    } finally {
      starting.value = false
    }
  }

  // Phase 2에서 '작업 등록' 성공 시: 다이얼로그 닫고 목록 갱신.
  function onRegistered(_taskId: number) {
    $q.notify({ type: 'positive', message: '작업이 등록되었습니다' })
    closeDialog()
    refresh()
  }

  function closeDialog() {
    showCreate.value = false
    dialogPhase.value = 'form'
    interviewSessionId.value = null
  }
  ```

- [ ] **Step 2: Reset phase in `openCreate`.**
  In `openCreate()` (lines 232-242), add before `showCreate.value = true`:
  ```ts
    dialogPhase.value = 'form'
    interviewSessionId.value = null
  ```

- [ ] **Step 3: Rename the submit button and switch the dialog body by phase.**
  In the template, change the dialog `<q-card>` opening (line 411) so it widens during the interview:
  ```vue
      <q-card :style="dialogPhase === 'interview' ? 'min-width: 90vw; max-width: 1200px' : 'min-width: 520px'">
  ```
  Change the title (lines 412-414):
  ```vue
          <div class="text-h6">{{ dialogPhase === 'form' ? '대화형 분석 시작' : '대화형 분석' }}</div>
  ```
  Wrap the existing form `q-card-section` (lines 415-582) and the actions (lines 583-593) in a `v-if="dialogPhase === 'form'"` by adding to the form section's opening tag:
  ```vue
        <q-card-section v-if="dialogPhase === 'form'" class="q-gutter-md">
  ```
  Replace the action button (lines 585-592 — the "등록" button) with the interview-start button, and guard the actions block:
  ```vue
        <q-card-actions v-if="dialogPhase === 'form'" align="right">
          <q-btn flat label="취소" @click="closeDialog" />
          <q-btn
            unelevated
            color="primary"
            icon="forum"
            label="인터뷰 시작"
            :loading="starting"
            :disable="!canSubmit"
            @click="startInterview"
          />
        </q-card-actions>
  ```
  Add the Phase-2 section after the actions block, before the closing `</q-card>` (line 594):
  ```vue
        <q-card-section v-else class="q-pa-none">
          <InterviewPanel
            v-if="interviewSessionId"
            :session-id="interviewSessionId"
            @registered="onRegistered"
            @close="closeDialog"
          />
        </q-card-section>
  ```
  Note: `canSubmit` (lines 250-257) already gates repo/branch/title/description — reuse it unchanged. `InterviewPanel` is auto-imported (Nuxt `components/`).

- [ ] **Step 4: Update the cancel button in the form to use `closeDialog`.**
  The form's flat "취소" button now calls `closeDialog` (done in Step 3). Verify no remaining `showCreate = false` direct assignments leak phase state: search and confirm the only setter is `closeDialog`/`openCreate`.
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && grep -n "showCreate" pages/tasks/index.vue
  ```
  Expected: `showCreate` referenced in `v-model="showCreate"`, `openCreate` (`= true`), and `closeDialog` (`= false`) only.

- [ ] **Step 5: Typecheck + lint the page.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npx nuxi prepare && npx vue-tsc --noEmit && npm run lint -- pages/tasks/index.vue components/InterviewPanel.vue composables/useInterviewStream.ts
  ```
  Expected: `vue-tsc` exits 0 (no type errors); ESLint auto-fixes formatting and exits 0. If `vue-tsc` reports the `EventSource` global type missing in the composable, it is provided by lib.dom — confirm `tsconfig` extends `.nuxt/tsconfig.json` (it does) and `nuxi prepare` regenerated it.

- [ ] **Step 6: Full test suite + commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm test
  ```
  Expected: all specs green. Commit:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add frontend/pages/tasks/index.vue && git commit -m "feat(frontend): 2-phase 대화형 분석 dialog — 인터뷰 시작 + 분할 뷰 패널

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 8: Manual smoke verification against a running stack (optional gate)

**Files:** none (verification only).

- [ ] **Step 1: Build to confirm production bundle compiles.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm run build
  ```
  Expected: `nuxi build` completes with no type/compile errors; `.output/` produced.

- [ ] **Step 2: Dev-server smoke (requires API on :8090 with interview endpoints from the backend plan).**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npm run dev
  ```
  Then in a browser at `http://localhost:3001/tasks`: click **작업 등록**, fill repo/branch/title/description, click **인터뷰 시작** → confirm the dialog widens and the split view renders with a "연결 중" badge and the EventSource request appears in the network tab at `/api/interviews/{id}/stream?access_token=...`. This step is gated on the backend interview endpoints existing; if they are not yet deployed, mark this task blocked and rely on the Vitest suite (Tasks 2-6) as the verification of record. Stop the dev server (Ctrl-C) when done.

- [ ] **Step 3: Finalize the branch.**
  Confirm `git status` is clean and all 7 implementation commits are present:
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git log --oneline -8 && git status
  ```
  Expected: commits for harness, composable (x2), panel (x3), and page-wiring; working tree clean. Hand off per `superpowers:finishing-a-development-branch` (PR or merge per team convention).
