<script setup lang="ts">
// 작업 상세 "인터뷰 목업 — 구현 기준"(스펙 2026-11-03 인터뷰 목업 §6): 확정 때 구현에 넘긴 목업을 링크로만 보여 준다.
// 목록은 마운트 뒤에만 받고(하이드레이션), 목업 HTML은 이 절에서 그리지 않는다(새 탭 페이지가 sandbox로 그린다).
// 응답이 배열이 아니거나 남는 행이 없으면 아무것도 그리지 않는다(InterviewHistoryCard 선례).
import { mockupLabel, type MockupSummary } from '~/composables/interviewMockups'

const props = defineProps<{ sessionId: number; ids: number[] }>()

const list = ref<MockupSummary[]>([])

const rows = computed(() =>
  list.value.filter((m) => props.ids.includes(m.id)).sort((a, b) => a.n - b.n),
)
const href = (m: MockupSummary) => `/interviews/${props.sessionId}/mockups/${m.id}`

onMounted(async () => {
  if (!props.ids.length) return
  try {
    const res = await useApi<MockupSummary[]>(`/api/interviews/${props.sessionId}/mockups`)
    list.value = Array.isArray(res) ? res : []
  } catch {
    // 403/404 등은 조용히 빈 목록 — 절이 없을 뿐 작업 상세는 그대로
    list.value = []
  }
})
</script>

<template>
  <q-card v-if="rows.length > 0" data-test="task-interview-mockups" flat bordered class="q-mb-md">
    <q-card-section class="text-h6">인터뷰 목업 — 구현 기준</q-card-section>
    <q-separator />
    <q-list separator>
      <q-item v-for="m in rows" :key="m.id" :data-test="`task-interview-mockup-${m.id}`">
        <q-item-section>
          <q-item-label>{{ mockupLabel(m) }}</q-item-label>
          <q-item-label v-if="m.description" caption>{{ m.description }}</q-item-label>
        </q-item-section>
        <q-item-section side>
          <a :href="href(m)" target="_blank" rel="noopener">새 탭에서 보기</a>
        </q-item-section>
      </q-item>
    </q-list>
  </q-card>
</template>
