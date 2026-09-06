<script setup lang="ts">
// 승인 — 인터뷰 시작 다이얼로그 (2026-09-06 UI/UX 개선).
// 위→아래: 작업 요약(어떤 작업인지) → 지난 인터뷰 안내(재승인 맥락) → 인터뷰 설정(모델 라디오 + 추론 단계 세그먼트,
// MCP 칩, 구독 사용량 strip) → 승인 후 다음 단계 안내 → 액션. 모바일(lt.md)은 전체화면: 상단 고정 바(닫기) /
// 스크롤 본문(.dialog-body, assets/css/main.css) / 하단 액션 바. 데이터는 부모([id].vue)가 가진 task를 그대로 받는다.
import { useQuasar } from 'quasar'
import {
  MODEL_OPTIONS,
  DEFAULT_MODEL,
  DEFAULT_EFFORT,
  coerceEffort,
  shortModelLabel,
} from '~/composables/modelEffort'
import {
  INTERVIEW_TERMINAL_STATUSES,
  interviewStatusLabel,
} from '~/composables/interviewLabels'
import McpPicker from '~/components/McpPicker.vue'
import ModelEffortFields from '~/components/ModelEffortFields.vue'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'
import ApproveTaskSummary, {
  type SummaryTask,
} from '~/components/tasks/ApproveTaskSummary.vue'

// 지난 인터뷰 목록 행 — 백엔드 InterviewSummaryResponse (InterviewHistoryCard와 동일 계약)
interface SessionSummary {
  id: number
  status: string
  statusName: string
  model: string | null
  effort: string | null
  createdAt: string
}

const props = defineProps<{ task: SummaryTask }>()
const show = defineModel<boolean>({ required: true })
const emit = defineEmits<{ (e: 'approved'): void }>()

const $q = useQuasar()
const model = ref(DEFAULT_MODEL)
const effort = ref(DEFAULT_EFFORT)
const mcpCatalogIds = ref<number[]>([])
const approving = ref(false)
/** 이 작업의 지난(터미널) 인터뷰 세션, 최신순. 재승인 맥락 안내 + 초기값 프리필에 쓴다. */
const previous = ref<SessionSummary[]>([])
/** 마지막 세션의 설정을 초기값으로 채웠는지 — 캡션 표시용 */
const prefilled = ref(false)

const lastSession = computed(() => previous.value[0] ?? null)
const previousHint = computed(() => {
  const s = lastSession.value
  if (!s) return ''
  const modelName = s.model ? shortModelLabel(s.model) : ''
  const setting = [modelName, s.effort].filter(Boolean).join(' · ')
  return `이전 인터뷰 ${previous.value.length}건 · 마지막 #${s.id} ${interviewStatusLabel(s.statusName)}${
    setting ? ` · ${setting}` : ''
  }`
})

/** 열릴 때마다 초기화: 기본값으로 되돌린 뒤 지난 인터뷰를 조회해 마지막 세션 설정을 프리필한다(조회 실패는 조용히 기본값). */
async function init() {
  model.value = DEFAULT_MODEL
  effort.value = DEFAULT_EFFORT
  mcpCatalogIds.value = []
  previous.value = []
  prefilled.value = false
  try {
    const res = await useApi<SessionSummary[]>(
      `/api/tasks/${props.task.id}/interviews`,
    )
    const rows = (Array.isArray(res) ? res : [])
      .filter((s) =>
        (INTERVIEW_TERMINAL_STATUSES as string[]).includes(s.statusName),
      )
      .sort((a, b) => b.id - a.id)
    previous.value = rows
    const last = rows[0]
    // 박제된 과거 모델(fable-5 등)은 더는 고를 수 없으므로 안내만 하고 초기값은 기본값으로 둔다.
    if (last?.model && MODEL_OPTIONS.some((m) => m.value === last.model)) {
      model.value = last.model
      effort.value = coerceEffort(last.model, last.effort ?? DEFAULT_EFFORT)
      prefilled.value = true
    }
  } catch {
    // 403/404 등 — 비소유자/구 API. 기본값으로 진행.
  }
}

watch(
  show,
  (open) => {
    if (open) init()
  },
  { immediate: true },
)

async function approve() {
  approving.value = true
  try {
    await useApi(`/api/tasks/${props.task.id}/approve`, {
      method: 'POST',
      body: {
        model: model.value,
        effort: effort.value,
        mcpCatalogIds: mcpCatalogIds.value,
      },
    })
    $q.notify({
      type: 'positive',
      message: '승인 완료 — 인터뷰가 큐에 들어갔습니다',
    })
    show.value = false
    emit('approved')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '승인 실패' })
  } finally {
    approving.value = false
  }
}

defineExpose({ approve })
</script>

<template>
  <!-- 승인 요청 중엔 ESC/바깥 클릭으로도 닫히지 않게(persistent) — 결과 토스트와 후속 스크롤을 놓치지 않도록 -->
  <q-dialog v-model="show" :maximized="$q.screen.lt.md" :persistent="approving">
    <q-card class="approve-card" style="width: min(560px, 100vw)">
      <!-- 모바일: 상단 고정 바 — 전체화면이라 뒤 페이지가 안 보이므로 닫기와 제목을 항상 노출 -->
      <q-toolbar
        v-if="$q.screen.lt.md"
        class="approve-topbar"
        data-test="approve-topbar"
      >
        <q-btn
          flat
          round
          dense
          icon="close"
          aria-label="닫기"
          data-test="approve-close"
          :disable="approving"
          @click="show = false"
        />
        <q-toolbar-title class="text-subtitle1 text-weight-medium"
          >인터뷰 시작 승인</q-toolbar-title
        >
      </q-toolbar>

      <div class="dialog-body">
        <q-card-section v-if="!$q.screen.lt.md" class="text-h6 q-pb-sm"
          >인터뷰 시작 승인</q-card-section
        >

        <q-card-section :class="$q.screen.lt.md ? '' : 'q-pt-none'">
          <ApproveTaskSummary :task="task" />
        </q-card-section>

        <q-card-section v-if="previousHint" class="q-pt-none">
          <div
            class="previous-hint row items-start no-wrap"
            data-test="previous-hint"
          >
            <q-icon name="history" size="16px" class="q-mr-xs hint-icon" />
            <span>{{ previousHint }}</span>
          </div>
          <div
            v-if="prefilled"
            class="text-caption text-grey-7 q-mt-xs q-ml-lg"
          >
            마지막 인터뷰 설정을 불러왔습니다. 필요하면 아래에서 바꾸세요.
          </div>
        </q-card-section>

        <q-card-section class="q-pt-none">
          <ModelEffortFields
            v-model:model="model"
            v-model:effort="effort"
            :disabled="approving"
          />
          <div class="q-mt-md">
            <McpPicker v-model="mcpCatalogIds" flat />
          </div>
          <!-- 첫 폴링 뒤에 strip이 나타나며 아래 섹션이 밀리지 않도록 높이를 미리 확보한다 -->
          <div class="usage-slot q-mt-sm">
            <ClaudeUsagePanel
              variant="strip"
              hide-when-empty
              prefix="Claude 사용량"
              class="approve-usage"
            />
          </div>
        </q-card-section>

        <q-separator />
        <q-card-section
          class="next-step row no-wrap items-start"
          data-test="next-step"
        >
          <q-icon
            name="info_outline"
            size="18px"
            class="q-mr-sm text-grey-6 next-step-icon"
          />
          <!-- 상태 칩에 실제로 보이는 백엔드 라벨(입력대기)로 쓴다 -->
          <div>
            승인하면 인터뷰가 큐에 들어가고, 첫 질문이 오면 상태가
            <b>입력대기</b>로 바뀝니다. 답변은 이 작업 화면의
            <b>대화형 분석</b> 카드에서 합니다.
          </div>
        </q-card-section>
      </div>

      <q-card-actions align="right">
        <q-btn v-close-popup flat label="취소" :disable="approving" />
        <q-btn
          data-test="approve-submit"
          unelevated
          color="primary"
          icon="forum"
          label="승인하고 인터뷰 시작"
          :loading="approving"
          @click="approve"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.approve-topbar {
  background: #fff;
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
  min-height: 52px;
  padding-top: env(safe-area-inset-top, 0px);
}
.previous-hint {
  font-size: 13px;
  color: #424242;
  line-height: 1.4;
}
.usage-slot {
  min-height: 26px;
}
.hint-icon {
  margin-top: 2px;
  color: #757575;
}
.approve-usage {
  padding-left: 0;
}
.next-step {
  font-size: 13px;
  line-height: 1.45;
  color: #616161;
}
.next-step-icon {
  margin-top: 1px;
}
</style>
