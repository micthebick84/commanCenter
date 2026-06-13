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
