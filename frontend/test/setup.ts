import { config } from '@vue/test-utils'
import * as QuasarPkg from 'quasar'
import { Quasar, Notify, Dialog, Loading } from 'quasar'
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
  // Nuxt page-only macro — no-op in Vitest (pages are tested without the router layer)
  definePageMeta: vi.fn(),
})

// Register every Quasar component (QInput, QBtn, ...) globally so they resolve to real
// DOM nodes in mounts — the Quasar Vue plugin alone does not auto-register components.
const quasarComponents: Record<string, any> = Object.fromEntries(
  Object.entries(QuasarPkg as Record<string, unknown>).filter(
    ([name, val]) => /^Q[A-Z]/.test(name) && val != null && typeof val === 'object',
  ),
)

// Quasar components (q-input, q-btn, ...) available in mounts, plus the same plugins
// nuxt.config.ts registers (Notify/Dialog/Loading) so $q.notify(...) is callable.
config.global.plugins = [
  [Quasar, { components: quasarComponents, plugins: { Notify, Dialog, Loading } }],
]
config.global.components = quasarComponents

beforeEach(() => {
  resetNuxtMocks()
  FakeEventSource.reset()
})
