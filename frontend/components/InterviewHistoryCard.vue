<script setup lang="ts">
// 지난 인터뷰(터미널 세션) 목록 카드. 목록은 로컬 ref에 보관 —
// 5초 폴링이 task ref를 갈아끼워도 카드/다이얼로그 상태가 유지된다.
// 터미널 세션이 하나도 없으면 카드 자체를 렌더하지 않는다.
import InterviewHistoryDialog from '~/components/InterviewHistoryDialog.vue'
import {
  INTERVIEW_TERMINAL_STATUSES,
  interviewStatusLabel,
  interviewStatusChip,
} from '~/composables/interviewLabels'

// GET /api/tasks/{id}/interviews (InterviewSummaryResponse) 계약 미러
interface SessionSummary {
  id: number
  status: string // 한글 dbValue (표시용)
  statusName: string // 영문 enum name (로직용)
  currentPhase: string | null
  model: string | null
  effort: string | null
  createdAt: string
  updatedAt: string
}

const props = defineProps<{ taskId: number; taskStatus?: string }>()

const sessions = ref<SessionSummary[]>([])

// 표시 대상 = 터미널 세션만. 진행 중 세션(QUEUED/RUNNING/…)은 대화형 분석 카드가 담당.
const pastSessions = computed(() =>
  sessions.value.filter((s) => (INTERVIEW_TERMINAL_STATUSES as string[]).includes(s.statusName)),
)

async function load() {
  try {
    const res = await useApi<SessionSummary[]>(`/api/tasks/${props.taskId}/interviews`)
    // 배열이 아닌 응답(프록시 오류 페이지 등)도 빈 목록으로 — 렌더 중 throw 방지
    sessions.value = Array.isArray(res) ? res : []
  } catch {
    // 403/404 등은 조용히 빈 목록 처리 — 비소유자 열람 시 콘솔 오류 스팸 방지
    sessions.value = []
  }
}

onMounted(load)
// 취소/만료 직후 task 상태가 바뀌면(예: 인터뷰중 → 승인대기) 방금 종료된 세션을 바로 반영.
watch(
  () => props.taskStatus,
  () => {
    load()
  },
)

const showDialog = ref(false)
const selectedId = ref<number | null>(null)

function openTranscript(id: number) {
  selectedId.value = id
  showDialog.value = true
}
</script>

<template>
  <q-card v-if="pastSessions.length > 0" data-test="history-card" flat bordered class="q-mb-md">
    <q-card-section class="text-h6">지난 인터뷰</q-card-section>
    <q-separator />
    <q-card-section>
      <q-list dense bordered separator>
        <q-item v-for="s in pastSessions" :key="s.id" data-test="history-row">
          <q-item-section>
            <q-item-label>
              <span class="text-weight-medium">#{{ s.id }}</span>
              <q-chip
                dense
                size="sm"
                class="q-ml-sm"
                :style="{
                  backgroundColor: interviewStatusChip(s.statusName)[0],
                  color: interviewStatusChip(s.statusName)[1],
                }"
                :label="interviewStatusLabel(s.statusName)"
              />
            </q-item-label>
            <q-item-label caption>{{ new Date(s.createdAt).toLocaleString() }}</q-item-label>
          </q-item-section>
          <q-item-section side>
            <q-btn
              data-test="view-transcript"
              flat
              dense
              no-caps
              color="primary"
              icon="forum"
              label="대화 보기"
              @click="openTranscript(s.id)"
            />
          </q-item-section>
        </q-item>
      </q-list>
    </q-card-section>
    <InterviewHistoryDialog v-model="showDialog" :session-id="selectedId" />
  </q-card>
</template>
