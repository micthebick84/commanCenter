<script setup lang="ts">
import { useQuasar } from 'quasar'
import InterviewPanel from '~/components/InterviewPanel.vue'
import QuestionComposer from '~/components/chat/QuestionComposer.vue'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'
import { interviewStatusLabel, interviewStatusChip } from '~/composables/interviewLabels'
import { contextPercent, type QuestionDetail } from '~/composables/questions'
import { usageColor } from '~/composables/claudeUsage'
import { DEFAULT_MODEL, DEFAULT_EFFORT } from '~/composables/modelEffort'
import type { InterviewStatus } from '~/composables/useInterviewStream'

definePageMeta({ layout: 'default' })

// 대화 화면 (스펙 2026-09-05 §3 Main). 헤더 데이터(제목·비용·컨텍스트)는 5초 폴링 — 답변 도착 후 최대 5초 내 따라온다.
// 상태 배지는 패널의 SSE emit이 폴링보다 먼저 반영한다. 조회 실패(403 타인/404 kind 불일치)는 목록으로.
const route = useRoute()
const $q = useQuasar()
const sessionId = computed(() => Number(route.params.id))
const openDrawer = inject<() => void>('questions:open-drawer', () => {})
const refreshList = inject<() => void>('questions:refresh', () => {})

const redirected = ref(false)
const { data: detail } = useTaskPolling<QuestionDetail | null>(async () => {
  try {
    return await useApi<QuestionDetail>(`/api/questions/${sessionId.value}`)
  } catch (e: any) {
    const st = e?.statusCode ?? e?.response?.status ?? e?.status
    if (st !== 403 && st !== 404) throw e // 일시적 오류(네트워크/5xx)는 마지막 detail 유지 — useTaskPolling이 error만 기록
    if (!redirected.value) {
      redirected.value = true
      $q.notify({ type: 'negative', message: e?.data?.message ?? '질문을 불러오지 못했습니다' })
      await navigateTo('/questions')
    }
    return null
  }
})

// 대화 중 모델·effort 변경(스펙 2026-09-05 §2 개정): 픽커는 "다음 질문에 쓸 값"의 초안. 첫 조회 때 세션 값으로 시드하고
// 이후엔 사용자가 고른 값을 유지한다 — 전송 시 ask 바디에 실려 세션 값이 되므로 서버와 다시 일치한다.
const picked = reactive({ model: DEFAULT_MODEL, effort: DEFAULT_EFFORT })
const pickedSeeded = ref(false)
watch(detail, (d) => {
  if (pickedSeeded.value || !d?.model) return
  picked.model = d.model
  picked.effort = d.effort ?? DEFAULT_EFFORT
  pickedSeeded.value = true
})

const liveStatus = ref<InterviewStatus | null>(null)
const statusName = computed(() => liveStatus.value ?? detail.value?.statusName ?? null)
const statusLabel = computed(() => interviewStatusLabel(statusName.value, 'QUESTION'))
const statusChip = computed(() => interviewStatusChip(statusName.value))
const isTerminal = computed(() =>
  ['REGISTERED', 'CANCELLED', 'EXPIRED', 'FAILED'].includes(statusName.value ?? ''),
)

const contextPct = computed(() =>
  contextPercent(detail.value?.contextTokens, detail.value?.contextWindow),
)
function fmtTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}
const contextTip = computed(() => {
  const d = detail.value
  return d?.contextTokens != null && d.contextWindow
    ? `${fmtTokens(d.contextTokens)} / ${fmtTokens(d.contextWindow)} 토큰`
    : ''
})

const panel = ref<InstanceType<typeof InterviewPanel> | null>(null)
function requestClose() {
  panel.value?.requestCancel()
}
// 종료/나중에 → 사이드바 캡션(종료됨) 즉시 갱신
function onPanelClose() {
  refreshList()
}
</script>

<template>
  <div class="conversation column no-wrap">
    <div class="row items-center no-wrap conv-header">
      <q-btn
        v-if="$q.screen.lt.md"
        flat
        dense
        round
        icon="menu"
        data-test="open-drawer"
        @click="openDrawer"
      />
      <div class="text-subtitle1 text-weight-medium ellipsis conv-title">{{ detail?.title ?? '질문' }}</div>
      <!-- 진행 상태는 primary 배지, 터미널 상태는 interviewStatusChip 색(기존 상세 헤더와 동일 팔레트) -->
      <q-badge v-if="statusName && !isTerminal" color="primary" :label="statusLabel" data-test="status-badge" />
      <q-chip
        v-else-if="statusName"
        dense
        size="sm"
        :style="{ backgroundColor: statusChip[0], color: statusChip[1] }"
        :label="statusLabel"
        data-test="status-badge"
      />
      <q-chip
        v-if="detail"
        dense
        size="sm"
        outline
        icon="folder"
        :label="`${detail.githubRepo} · ${detail.githubBranch}`"
        class="gt-xs"
      />
      <q-chip
        v-if="detail?.totalCostUsd != null"
        dense
        size="sm"
        outline
        icon="paid"
        :label="`누적 비용 ${Number(detail.totalCostUsd).toFixed(2)}`"
        class="gt-xs"
      />
      <q-chip v-if="contextPct != null" dense size="sm" outline data-test="context-chip">
        <q-circular-progress
          :value="contextPct"
          size="14px"
          :thickness="0.35"
          :color="usageColor(contextPct)"
          track-color="grey-4"
          class="q-mr-xs"
        />
        컨텍스트 {{ contextPct }}%
        <q-tooltip>{{ contextTip }}</q-tooltip>
      </q-chip>
      <q-space />
      <!-- 좁은 화면(lt.md)에서는 라벨이 두 줄로 꺾여 헤더 밖으로 넘치므로 아이콘만 남기고 이름은 aria-label로 유지 (2026-09-06 모바일 QA) -->
      <q-btn
        v-if="!isTerminal"
        outline
        dense
        no-caps
        color="grey-7"
        icon="stop_circle"
        :label="$q.screen.lt.md ? undefined : '세션 종료'"
        aria-label="세션 종료"
        data-test="close-session"
        @click="requestClose"
      />
    </div>

    <InterviewPanel
      ref="panel"
      :session-id="sessionId"
      kind="QUESTION"
      hide-status-bar
      fill
      :model="detail?.model ?? undefined"
      :effort="detail?.effort ?? undefined"
      class="col conv-panel"
      @status="liveStatus = $event"
      @close="onPanelClose"
    >
      <template #composer="{ answer, setAnswer, canSend, sending, send, awaiting }">
        <div class="composer-wrap">
          <QuestionComposer
            v-model:model="picked.model"
            v-model:effort="picked.effort"
            :model-value="answer"
            :can-send="canSend"
            :sending="sending"
            :disabled="!awaiting"
            :hint="!$q.screen.lt.md"
            @update:model-value="setAnswer"
            @send="send({ model: picked.model, effort: picked.effort })"
          >
            <template #tools>
              <q-btn
                flat
                dense
                no-caps
                icon="extension"
                :label="$q.screen.xs ? undefined : 'MCP 도구'"
                aria-label="MCP 도구"
                data-test="mcp-button"
                disable
              >
                <q-tooltip>세션 생성 시 고정 — 바꾸려면 새 질문</q-tooltip>
              </q-btn>
            </template>
          </QuestionComposer>
          <ClaudeUsagePanel v-if="$q.screen.lt.md" variant="strip" :context-pct="contextPct" class="q-mt-xs" />
        </div>
      </template>
    </InterviewPanel>
  </div>
</template>

<style scoped>
.conversation {
  height: 100%;
}
.conv-header {
  gap: 8px;
  min-height: 57px;
  padding: 10px 20px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
}
.conv-title {
  min-width: 0;
}
.conv-panel {
  min-height: 0;
}
.composer-wrap {
  width: 100%;
  max-width: 820px;
  margin: 0 auto;
}
/* 트랜스크립트 가운데 정렬 최대 820px (캔버스 Main) — 패널 내부 스크롤 영역에만 적용 */
.conv-panel :deep(.transcript) {
  max-width: 820px;
  width: 100%;
  margin: 0 auto;
}
</style>
