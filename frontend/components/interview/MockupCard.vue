<script setup lang="ts">
// 질문 말풍선 아래 목업 카드(스펙 2026-11-03 인터뷰 목업 §6, 캔버스 ①).
// 미리보기 html은 카드가 화면에 들어올 때만 받는다 — 패널을 열기만 해도 최대 40 × 200KB를 받지 않게.
import { mockupLabel, mockupSrcdoc, type MockupSummary } from '~/composables/interviewMockups'

// 성공한 html만 담는다(실패는 지워 다시 시도할 수 있게). 키 `${sid}:${id}`.
const htmlCache = new Map<string, string>()

const props = defineProps<{ sessionId: number; mockup: MockupSummary }>()

const root = ref<HTMLElement | null>(null)
const html = ref<string | null>(null)
let observer: IntersectionObserver | null = null
let gone = false

const key = computed(() => `${props.sessionId}:${props.mockup.id}`)
const openHref = computed(() => `/interviews/${props.sessionId}/mockups/${props.mockup.id}`)
const label = computed(() => mockupLabel(props.mockup))
const srcdoc = computed(() => (html.value == null ? '' : mockupSrcdoc(html.value)))

async function load() {
  const k = key.value
  const cached = htmlCache.get(k)
  if (cached != null) {
    html.value = cached
    return
  }
  try {
    const res = await useApi<{ html?: string }>(
      `/api/interviews/${props.sessionId}/mockups/${props.mockup.id}`,
    )
    if (typeof res?.html !== 'string') return
    htmlCache.set(k, res.html)
    if (!gone) html.value = res.html
  } catch {
    htmlCache.delete(k)
  }
}

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') {
    void load()
    return
  }
  observer = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) {
      observer?.disconnect()
      observer = null
      void load()
    }
  })
  if (root.value) observer.observe(root.value)
})
onUnmounted(() => {
  gone = true
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <div ref="root" class="mockup-card" :data-test="`mockup-card-${mockup.id}`">
    <iframe
      v-if="html != null"
      class="mockup-preview"
      sandbox=""
      loading="lazy"
      tabindex="-1"
      :srcdoc="srcdoc"
      :title="label"
      :data-test="`mockup-preview-${mockup.id}`"
    />
    <div class="mockup-card-title">{{ label }}</div>
    <div v-if="mockup.description" class="mockup-card-desc">{{ mockup.description }}</div>
    <a
      class="mockup-card-open"
      :href="openHref"
      target="_blank"
      rel="noopener"
      :data-test="`mockup-open-${mockup.id}`"
    >
      새 탭에서 보기<q-icon name="open_in_new" size="14px" class="q-ml-xs" />
    </a>
  </div>
</template>

<style scoped>
.mockup-card {
  min-width: 0;
  border: 1px solid #d5dbe5;
  border-radius: 8px;
  background: #fff;
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.mockup-preview {
  width: 100%;
  height: 112px;
  border: 0;
  border-radius: 4px;
  background: #f6f8fb;
  pointer-events: none;
}
.mockup-card-title {
  font-size: 12px;
  font-weight: 700;
  color: #25303f;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mockup-card-desc {
  font-size: 11px;
  color: #8a97a8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mockup-card-open {
  font-size: 11.5px;
  color: #1565c0;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
}
</style>
