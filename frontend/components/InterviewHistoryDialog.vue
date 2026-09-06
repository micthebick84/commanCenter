<script setup lang="ts">
// 과거 인터뷰 읽기 전용 열람 다이얼로그 (Shape A: REST 1회 전용).
// EventSource(SSE)는 절대 열지 않는다 — SSE+OSIV가 HikariCP 풀을 잠식한 전례가 있어
// 터미널 세션 열람은 스냅샷 GET 한 번으로 끝낸다.
import { useQuasar } from 'quasar'
import ChatBubble from '~/components/chat/ChatBubble.vue'
import { interviewStatusLabel, interviewStatusChip } from '~/composables/interviewLabels'

interface HistoryTurn {
  seq: number
  role: string
  kind: string
  content: string
}
interface HistoryPlan {
  designMarkdown?: string | null
  planMarkdown?: string | null
}
// GET /api/interviews/{id} (InterviewResponse) 중 이 화면이 쓰는 부분집합
interface HistoryDetail {
  id: number
  title: string
  status: string // 한글 dbValue (표시용)
  statusName: string // 영문 enum name (로직용)
  turns: HistoryTurn[]
  plan: HistoryPlan | null
}

const props = defineProps<{ sessionId: number | null }>()
const show = defineModel<boolean>({ required: true })

const $q = useQuasar()
const loading = ref(false)
const detail = ref<HistoryDetail | null>(null)

async function load() {
  if (props.sessionId == null) return
  loading.value = true
  detail.value = null
  try {
    detail.value = await useApi<HistoryDetail>(`/api/interviews/${props.sessionId}`)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '인터뷰 기록을 불러오지 못했습니다' })
    show.value = false
  } finally {
    loading.value = false
  }
}

// 열릴 때마다 1회 조회. immediate: 이미 열린 채로 마운트되는 경우(테스트/직접 open) 커버.
watch(
  show,
  (v) => {
    if (v) load()
  },
  { immediate: true },
)

const statusLabel = computed(() => interviewStatusLabel(detail.value?.statusName))
const chip = computed(() => interviewStatusChip(detail.value?.statusName))

// hydrate()와 동일한 role 매핑: user → user, 그 외(assistant/system) → assistant.
function bubbleRole(role: string): 'assistant' | 'user' {
  return role === 'user' ? 'user' : 'assistant'
}
</script>

<template>
  <q-dialog v-model="show" :maximized="$q.screen.lt.md">
    <q-card data-test="history-dialog" class="history-dialog-card">
      <q-card-section class="row items-center q-pb-sm">
        <div class="text-h6" data-test="history-title">
          #{{ detail?.id ?? sessionId }}<template v-if="detail"> · {{ detail.title }}</template>
        </div>
        <q-chip
          v-if="detail"
          data-test="history-status"
          dense
          size="sm"
          class="q-ml-sm"
          :style="{ backgroundColor: chip[0], color: chip[1] }"
          :label="statusLabel"
        />
        <q-space />
        <q-btn v-close-popup data-test="history-close" flat round dense icon="close" />
      </q-card-section>
      <q-separator />

      <q-card-section v-if="loading" class="flex flex-center q-pa-lg">
        <q-spinner-dots color="primary" size="2em" />
      </q-card-section>

      <q-card-section v-else-if="detail" class="history-body scroll">
        <q-banner data-test="history-banner" dense class="bg-grey-2 text-grey-9 q-mb-md">
          '{{ statusLabel }}' 상태로 종료된 인터뷰입니다 — 읽기 전용 기록입니다.
        </q-banner>

        <ChatBubble
          v-for="t in detail.turns"
          :key="t.seq"
          data-test="history-turn"
          :role="bubbleRole(t.role)"
          :content="t.content"
        />
        <div v-if="detail.turns.length === 0" class="text-grey-6 q-pa-md text-center">
          기록된 대화가 없습니다.
        </div>

        <div v-if="detail.plan && detail.plan.designMarkdown" class="q-mt-md">
          <div class="text-subtitle2 q-mb-sm">설계 문서</div>
          <pre class="plan-md">{{ detail.plan.designMarkdown }}</pre>
        </div>
        <div v-if="detail.plan && detail.plan.planMarkdown" class="q-mt-md">
          <div class="text-subtitle2 q-mb-sm">구현 플랜</div>
          <pre class="plan-md">{{ detail.plan.planMarkdown }}</pre>
        </div>
      </q-card-section>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.history-dialog-card {
  width: min(720px, 100vw);
  max-width: 100vw;
}
.history-body {
  max-height: 65vh;
}
/* InterviewPanel의 plan-md 스타일 복사 */
.plan-md {
  white-space: pre-wrap;
  font-family: 'Pretendard', sans-serif;
  font-size: 0.85rem;
}
@media (max-width: 767px) {
  .history-dialog-card {
    min-width: unset;
    width: 100%;
  }
}
</style>
