<script setup lang="ts">
import { useQuasar } from 'quasar'
import InterviewPanel from '~/components/InterviewPanel.vue'
import QuestionComposer from '~/components/chat/QuestionComposer.vue'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'
import McpPicker from '~/components/McpPicker.vue'
import DbConnectionPicker from '~/components/DbConnectionPicker.vue'
import RefRepoChips from '~/components/RefRepoChips.vue'
import RefRepoAddForm from '~/components/RefRepoAddForm.vue'
import { interviewStatusLabel, interviewStatusChip } from '~/composables/interviewLabels'
import { contextPercent, EXPIRED_ATTACHMENT_TIP, type QuestionDetail, type AttachmentView } from '~/composables/questions'
import { dbTypeLabel, fetchDbConnections, type DbConnectionView } from '~/composables/dbConnections'
import { sameRefSet, toInputs, type RefRepoInput, type RefRepoView, type RepoCatalogOption } from '~/composables/refRepos'
import { usageColor } from '~/composables/claudeUsage'
import { DEFAULT_MODEL, DEFAULT_EFFORT } from '~/composables/modelEffort'
import { formatSize } from '~/composables/attachmentLimits'
import { downloadAttachment } from '~/composables/attachmentDownload'
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
// 대화 중 MCP 변경(스펙 2026-09-13 §2·§7): 같은 원리 — 세션의 mcpCatalogIds로 1회 시드, 이후엔 "다음 질문에 쓸 초안".
// 전송 시 항상 현재 집합을 실어 보내며 서버는 같은 집합이면 검증 없는 no-op, 다르면 갱신 + system note.
const pickedMcp = ref<number[]>([])
// 대화 중 DB 연결 변경(스펙 2026-10-02 §7): MCP와 같은 원리 — 세션 값으로 1회 시드, 기능이 켜져 있을 때만 전송.
const pickedDb = ref<number[]>([])
const dbState = reactive({ enabled: false, items: [] as DbConnectionView[] })
async function loadDb(repoId: number) {
  const r = await fetchDbConnections(repoId)
  dbState.enabled = r.enabled
  dbState.items = r.items
}
function reloadDb() {
  if (detail.value?.repoCatalogId) loadDb(detail.value.repoCatalogId)
}
// 대화 중 참조 repo 변경(스펙 2026-10-22 §7.3): 같은 원리 — 세션 값으로 1회 시드, 이후엔 "다음 질문에 쓸 초안".
// 헤더 아래 칩 줄은 서버 값(커밋·상태)을, "✎ 참조 변경" 메뉴는 이 초안을 보여 준다 — 폴링이 서버 값으로 되돌리면 보내기 전의 변경이 지워진다.
const pickedRefs = ref<RefRepoView[]>([])
const pickedSeeded = ref(false)
watch(detail, (d) => {
  if (pickedSeeded.value || !d?.model) return
  picked.model = d.model
  picked.effort = d.effort ?? DEFAULT_EFFORT
  pickedMcp.value = [...(d.mcpCatalogIds ?? [])]
  pickedDb.value = [...(d.dbConnectionIds ?? [])]
  pickedRefs.value = [...(d.refRepos ?? [])]
  if (d.repoCatalogId) loadDb(d.repoCatalogId)
  pickedSeeded.value = true
})

// 첨부(스펙 2026-09-13 §7): 대기 파일은 페이지가 들고(v-model:files) 전송이 성공했을 때만 비운다 — 거부(400/409)면 유지.
const files = ref<File[]>([])
type SendFn = (extra: {
  model: string
  effort: string
  mcpCatalogIds: number[]
  dbConnectionIds?: number[]
  refRepos?: RefRepoInput[]
  files: File[]
}) => Promise<boolean>
async function onSend(send: SendFn) {
  // 참조 repo는 초안이 서버 값과 다른 집합일 때만 싣는다 — 참조를 안 건드린 질문의 ask 바디는 예전과 똑같다(서버도 같은 집합이면 no-op).
  // 시드 전의 빈 초안(model이 없어 시드 블록을 건너뛴 세션)은 사용자가 비운 것이 아니므로 싣지 않는다 — 서버 참조가 전부 해제되지 않게.
  const refInputs = toInputs(pickedRefs.value)
  const refsChanged = pickedSeeded.value && !sameRefSet(refInputs, toInputs(detail.value?.refRepos ?? []))
  const ok = await send({
    model: picked.model,
    effort: picked.effort,
    mcpCatalogIds: pickedMcp.value,
    ...(dbState.enabled ? { dbConnectionIds: pickedDb.value } : {}),
    ...(refsChanged ? { refRepos: refInputs } : {}),
    files: files.value,
  })
  if (ok) files.value = []
}

// "✎ 참조 변경" 메뉴(스펙 2026-10-22 §7.3). 후보 카탈로그는 메뉴를 처음 열 때 한 번만 불러온다 — 페이지를 여는 시점의 API 호출은 늘리지 않는다.
// 실패하면 알리고 다음에 열 때 다시 시도한다. 지원하지 않는 호스트(other)는 추가 폼이 스스로 뺀다.
const repoCatalog = ref<RepoCatalogOption[]>([])
let catalogLoaded = false
let catalogLoading = false
async function loadRepoCatalogOnce() {
  if (catalogLoaded || catalogLoading) return
  catalogLoading = true
  try {
    const res = await useApi<RepoCatalogOption[]>('/api/repo-catalog')
    repoCatalog.value = Array.isArray(res) ? res : []
    catalogLoaded = true
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '레포 목록을 불러오지 못했습니다' })
  } finally {
    catalogLoading = false
  }
}

// 메뉴가 고치는 것은 서버 값이 아니라 초안(pickedRefs)이다. 추가 폼 후보에서는 대상 레포(헤더 📁)와 이미 고른 참조를 뺀다 —
// 서버도 같은 규칙으로 검증한다. 새 질문 화면(pages/questions/index.vue)의 인라인 폼과 같은 토글·접힘 동작.
// 메뉴에 그릴 칩 = 초안의 구성(어느 레포·브랜치)에 서버가 아는 상태(커밋·받은 시각·사유)를 입힌 것. 초안은 처음 한 번만 시드해서 그 안의 상태는
// 시드 때 그대로 굳는다 — 첫 답변이 참조를 고정한 뒤에도 메뉴에 "다음 답변 때 받음"이 남지 않게 한다(바로 위 칩 줄은 서버 값을 따라간다).
// 서버에 없는 짝(새로 더했거나 브랜치를 바꾼 참조)은 초안 그대로라 대기로 보인다. 입히는 것은 표시뿐이고 초안(pickedRefs)은 건드리지 않는다.
const draftChips = computed<RefRepoView[]>(() =>
  pickedRefs.value.map(
    (p) => detail.value?.refRepos?.find((s) => s.catalogId === p.catalogId && s.branch === p.branch) ?? p,
  ),
)
const refAddOpen = ref(false)
const refExcludeIds = computed(() => {
  const targetId = detail.value?.repoCatalogId
  return [...(targetId != null ? [targetId] : []), ...pickedRefs.value.map((r) => r.catalogId)]
})
function toggleRefAdd() {
  refAddOpen.value = !refAddOpen.value
}
function addPickedRef(added: RefRepoView) {
  pickedRefs.value.push(added)
  refAddOpen.value = false // 추가하면 접는다
}
function removePickedRef(catalogId: number) {
  pickedRefs.value = pickedRefs.value.filter((r) => r.catalogId !== catalogId)
}

// 등록 시 첨부(헤더 칩 줄) 다운로드 — 말풍선 칩(InterviewPanel)과 같은 blob 헬퍼·경로.
async function downloadSessionAttachment(a: AttachmentView) {
  try {
    await downloadAttachment(`/api/questions/${sessionId.value}/attachments/${a.id}`, a.fileName)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.message ?? '다운로드 실패' })
  }
}

const liveStatus = ref<InterviewStatus | null>(null)
const statusName = computed(() => liveStatus.value ?? detail.value?.statusName ?? null)
// "참조 변경"은 입력창 슬롯 밖(헤더 아래 칩 줄)이라 슬롯의 awaiting을 못 쓴다 — InterviewPanel이 상태를 즉시·변경마다 emit하므로
// (`@status`, immediate) 이 값은 슬롯의 awaiting(status === 'AWAITING_INPUT')과 같다.
const awaiting = computed(() => statusName.value === 'AWAITING_INPUT')
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

// "이 대화로 작업 등록"(작업 스펙 2026-10-24 §7.6) — 작업 등록 창이 이 질문을 읽어 대상·브랜치·참조·제목을 채운다.
// navigateTo는 템플릿에서 직접 부르지 않는다(pages/tasks/index.vue openTaskDetail 주석 — `_ctx.navigateTo`로 컴파일된다).
function goToTask() {
  navigateTo(`/tasks?fromQuestion=${sessionId.value}`)
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
      <!-- 헤더 오른쪽 버튼들은 좁은 화면(lt.md)에서 라벨이 두 줄로 꺾여 헤더 밖으로 넘치므로 아이콘만 남기고 이름은 aria-label로 유지 (2026-09-06 모바일 QA) -->
      <!-- 이 대화로 작업 등록 (작업 스펙 2026-10-24 §7.6) — 끝난 질문에도 보인다. 답변이 끝나지 않았거나(서버도 409) 대상 레포가 없으면 막는다. -->
      <q-btn
        outline
        dense
        no-caps
        color="primary"
        icon="add_task"
        :label="$q.screen.lt.md ? undefined : '이 대화로 작업 등록'"
        aria-label="이 대화로 작업 등록"
        :disable="!detail?.repoCatalogId || ['QUEUED', 'RUNNING'].includes(statusName ?? '')"
        data-test="to-task"
        @click="goToTask"
      />
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
    <!-- 참조 repo 줄 (스펙 2026-10-22 §7.3) — 서버 값(커밋·상태)을 읽기 전용으로 보인다. 질문 대화 화면에는 항상 그리고, 비면 "참조 repo 없음".
         "✎ 참조 변경"은 입력 대기일 때만 열리고, 메뉴는 "다음 질문에 쓸 초안"(pickedRefs)을 고친다 — 보낼 때 ask 바디에 실린다. -->
    <div class="row items-end no-wrap session-refs" data-test="session-ref-chips">
      <RefRepoChips :items="detail?.refRepos ?? []" class="session-refs-chips" />
      <q-btn
        flat
        dense
        no-caps
        size="sm"
        color="deep-purple-7"
        label="✎ 참조 변경"
        aria-label="참조 변경"
        class="session-refs-change"
        :disable="!awaiting"
        data-test="ref-change"
      >
        <q-tooltip>대화 중 변경 — 다음 질문부터 적용</q-tooltip>
        <q-menu @show="loadRepoCatalogOnce" @hide="refAddOpen = false">
          <div class="ref-menu">
            <div class="ref-menu-chips">
              <RefRepoChips :items="draftChips" editable @remove="removePickedRef" @add="toggleRefAdd" />
            </div>
            <RefRepoAddForm v-if="refAddOpen" :catalog="repoCatalog" :exclude-ids="refExcludeIds" @add="addPickedRef" />
          </div>
        </q-menu>
      </q-btn>
    </div>
    <div
      v-if="detail?.dbConnections?.length"
      class="row items-center session-attachments"
      data-test="session-db-chips"
    >
      <q-chip
        v-for="c in detail.dbConnections"
        :key="c.id"
        dense
        size="sm"
        icon="storage"
        color="teal-1"
        text-color="teal-10"
        :label="`${c.name} · ${dbTypeLabel(c.dbType)}`"
      />
    </div>
    <!-- 등록 시 첨부 — 첫 질문은 말풍선이 없으므로 헤더 아래 칩 줄로 (스펙 2026-09-13 §2). 비면 숨김. -->
    <div
      v-if="detail?.attachments?.length"
      class="row items-center session-attachments"
      data-test="session-attachments"
    >
      <q-chip
        v-for="a in detail.attachments"
        :key="a.id"
        :clickable="!a.expired"
        dense
        size="sm"
        :icon="a.expired ? 'history' : 'attach_file'"
        :color="a.expired ? 'grey-3' : 'blue-grey-1'"
        :text-color="a.expired ? 'grey-6' : 'blue-grey-9'"
        :label="`${a.fileName} (${formatSize(a.sizeBytes)})`"
        :data-test="a.expired ? 'attachment-expired' : undefined"
        @click="!a.expired && downloadSessionAttachment(a)"
      >
        <q-tooltip>{{ a.expired ? EXPIRED_ATTACHMENT_TIP : '클릭하여 다운로드' }}</q-tooltip>
      </q-chip>
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
            v-model:files="files"
            :model-value="answer"
            :can-send="canSend"
            :sending="sending"
            :disabled="!awaiting"
            :hint="!$q.screen.lt.md"
            @update:model-value="setAnswer"
            @send="onSend(send)"
          >
            <template #tools>
              <!-- pages/questions/index.vue와 같은 q-btn + q-badge + q-menu + McpPicker. xs(<600px)에서는 라벨이 두 줄로 꺾이므로
                   아이콘만 — 이름은 aria-label로 유지 (2026-09-06 모바일 QA). 입력 대기가 아니면 잠근다. -->
              <q-btn
                flat
                dense
                no-caps
                icon="extension"
                :label="$q.screen.xs ? undefined : 'MCP 도구'"
                aria-label="MCP 도구"
                :disable="!awaiting"
                data-test="mcp-button"
              >
                <q-badge v-if="pickedMcp.length" color="primary" floating>
                  {{ pickedMcp.length }}
                </q-badge>
                <q-tooltip>대화 중 변경 — 다음 질문부터 적용</q-tooltip>
                <q-menu>
                  <div class="mcp-menu">
                    <McpPicker v-model="pickedMcp" />
                  </div>
                </q-menu>
              </q-btn>
              <q-btn
                v-if="dbState.enabled"
                flat
                dense
                no-caps
                icon="storage"
                :label="$q.screen.xs ? undefined : 'DB'"
                aria-label="DB 연결"
                :disable="!awaiting"
                data-test="db-button"
              >
                <q-badge v-if="pickedDb.length" color="primary" floating>{{ pickedDb.length }}</q-badge>
                <q-tooltip>대화 중 변경 — 다음 질문부터 적용</q-tooltip>
                <q-menu>
                  <DbConnectionPicker
                    v-model="pickedDb"
                    :items="dbState.items"
                    :known-chips="detail?.dbConnections"
                    :repo-catalog-id="detail?.repoCatalogId ?? 0"
                    @reload="reloadDb"
                  />
                </q-menu>
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
.session-attachments {
  gap: 2px;
  padding: 6px 20px 0;
}
.mcp-menu {
  min-width: 360px;
  max-width: 480px;
}
/* 참조 repo 줄: 칩 줄은 내용 폭만큼만 차지하고(넓은 화면에서 "참조 변경"이 칩 바로 옆에 붙는다), 좁으면 남는 폭까지 줄어 칩이 줄바꿈한다 */
.session-refs {
  gap: 8px;
  padding: 6px 20px 0;
}
.session-refs-chips {
  flex: 0 1 auto;
  min-width: 0;
}
/* 버튼은 줄어들지 않는다 — 좁은 화면에서 줄어드는 쪽은 칩 줄뿐이고, 라벨이 세로로 꺾이지 않는다 */
.session-refs-change {
  flex: none;
  white-space: nowrap;
}
/* 참조 변경 메뉴 폭 — 추가 폼 루트(RefRepoAddForm)와 같은 값. 390px 화면에서도 좌우 16px 여백 안에 든다 */
.ref-menu {
  box-sizing: border-box;
  width: min(340px, calc(100vw - 32px));
}
.ref-menu-chips {
  padding: 12px 16px 4px;
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
