<script setup lang="ts">
import { useQuasar } from 'quasar'
import InterviewPanel from '~/components/InterviewPanel.vue'
import { interviewStatusLabel, interviewStatusChip } from '~/composables/interviewLabels'

definePageMeta({ layout: 'default' })

// GET /api/questions/{id} (InterviewResponse) 중 이 화면이 쓰는 부분집합
interface QuestionDetail {
  id: number
  title: string
  githubRepo: string
  githubBranch: string
  statusName: string
  model: string | null
  effort: string | null
  totalCostUsd: number | null
}

const route = useRoute()
const $q = useQuasar()
const sessionId = computed(() => Number(route.params.id))
const detail = ref<QuestionDetail | null>(null)

async function load() {
  try {
    detail.value = await useApi<QuestionDetail>(`/api/questions/${sessionId.value}`)
  } catch (e: any) {
    // 403(타인)/404(없거나 인터뷰 세션) — 목록으로
    $q.notify({ type: 'negative', message: e?.data?.message ?? '질문을 불러오지 못했습니다' })
    await navigateTo('/questions')
  }
}
onMounted(load)

// InterviewPanel의 close = 종료/나중에 — 상태 갱신 후 화면 유지 (읽기 전용 열람 가능)
function onPanelClose() {
  load()
}

// 템플릿에서 navigateTo(자동 임포트)를 직접 부르면 vitest(setup.ts 전역 주입)에서 _ctx.navigateTo가 없다 — 함수로 감싼다.
function goList() {
  navigateTo('/questions')
}
</script>

<template>
  <q-page padding>
    <div class="row items-center q-mb-md">
      <q-btn flat dense round icon="arrow_back" @click="goList" />
      <div class="text-h5 q-ml-sm">{{ detail?.title ?? '질문' }}</div>
      <q-space />
      <template v-if="detail">
        <q-chip
          dense
          size="sm"
          :style="{
            backgroundColor: interviewStatusChip(detail.statusName)[0],
            color: interviewStatusChip(detail.statusName)[1],
          }"
          :label="interviewStatusLabel(detail.statusName, 'QUESTION')"
        />
        <q-chip dense size="sm" outline icon="folder" :label="`${detail.githubRepo} · ${detail.githubBranch}`" />
        <q-chip
          v-if="detail.totalCostUsd != null"
          dense
          size="sm"
          outline
          icon="paid"
          :label="`누적 비용 ${Number(detail.totalCostUsd).toFixed(2)}`"
        />
      </template>
    </div>

    <q-card flat bordered>
      <q-card-section class="q-pa-none">
        <InterviewPanel
          :session-id="sessionId"
          kind="QUESTION"
          :model="detail?.model ?? undefined"
          :effort="detail?.effort ?? undefined"
          @close="onPanelClose"
        />
      </q-card-section>
    </q-card>
  </q-page>
</template>
