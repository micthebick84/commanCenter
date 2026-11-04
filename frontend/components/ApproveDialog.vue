<script setup lang="ts">
// 승인 — 인터뷰 시작 다이얼로그 (2026-09-06 UI/UX 개선).
// 위→아래: 작업 요약(어떤 작업인지) → 지난 인터뷰 안내(재승인 맥락) → 인터뷰 설정(모델 라디오 + 추론 단계 세그먼트,
// MCP 칩, DB 연결, 참조 repo, 구독 사용량 strip) → 승인 후 다음 단계 안내 → 액션. 폰(lt.sm)은 전체화면: 상단 고정 바(닫기) /
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
import DbConnectionPicker from '~/components/DbConnectionPicker.vue'
import {
  MAX_DB_SELECTION,
  fetchDbConnections,
  type DbConnectionView,
} from '~/composables/dbConnections'
import { hasWritableSelection } from '~/composables/taskDbChips'
import ModelEffortFields from '~/components/ModelEffortFields.vue'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'
import RefRepoChips from '~/components/RefRepoChips.vue'
import RefRepoAddForm from '~/components/RefRepoAddForm.vue'
import {
  sameRefSet,
  toInputs,
  useRefCatalog,
  useRefDraft,
  type RefRepoView,
} from '~/composables/refRepos'
import ApproveTaskSummary, {
  type SummaryTask,
} from '~/components/tasks/ApproveTaskSummary.vue'
import { useFullscreenDialog } from '~/composables/dialogLayout'
import { TASK_TYPES } from '~/composables/taskType'
import {
  SIMPLE_INTERVIEW_CAPTION,
  SUPERPOWERS_TOGGLE_LABEL,
  defaultSuperpowers,
  lastSessionSuperpowers,
} from '~/composables/interviewMode'

// 지난 인터뷰 목록 행 — 백엔드 InterviewSummaryResponse (InterviewHistoryCard와 동일 계약)
interface SessionSummary {
  id: number
  status: string
  statusName: string
  model: string | null
  effort: string | null
  dbConnectionIds?: number[] | null
  // 인터뷰 방식(스펙 2026-11-03 간단 인터뷰) — 구 API 행에는 칸이 없다(프리필은 boolean일 때만 쓴다)
  superpowersEnabled?: boolean | null
  createdAt: string
}

const props = defineProps<{ task: SummaryTask }>()
const show = defineModel<boolean>({ required: true })
const emit = defineEmits<{ (e: 'approved'): void }>()

const $q = useQuasar()
const model = ref(DEFAULT_MODEL)
const effort = ref(DEFAULT_EFFORT)
// 작업 타입(스펙 2026-11-03 §4.2): 열 때마다 작업의 현재 타입으로 미리 고른다. 바꿨을 때만 승인 바디에 싣는다(서버는 키가 없으면 유지).
// 승인 뒤에는 바꿀 수 없다 — 타입 없음으로 되돌리는 선택지는 없다(null은 "유지"를 뜻한다).
const taskType = ref<string | null>(null)
const taskTypeChanged = computed(() => taskType.value != null && taskType.value !== (props.task.taskType ?? null))
// 인터뷰 방식(스펙 2026-11-03 간단 인터뷰 §6): 켬 = superpowers 인터뷰, 끔 = 간단 인터뷰. 미리 고르는 순서 — 지난 세션 값 → 타입 기본값(DB 작업만 끔).
// 타입 select를 바꾸면 따라가고(onTaskTypeChange) 관리자가 토글을 직접 바꾼 뒤에는 따라가지 않는다. watch가 아니라 핸들러인 이유:
// init이 taskType을 다시 넣을 때 watch가 돌면 지난 세션 값으로 채운 프리필을 덮는다. 열 때마다 init이 둘 다 되돌린다.
const superpowers = ref(true)
let superpowersTouched = false
function onSuperpowersToggle(value: boolean) {
  superpowers.value = value
  superpowersTouched = true
}
function onTaskTypeChange(type: string | null) {
  if (!superpowersTouched) superpowers.value = defaultSuperpowers(type)
}
const mcpCatalogIds = ref<number[]>([])
// DB 연결(스펙 2026-10-14 §7): 레포 공용 활성 연결을 기본 선택, 마지막 인터뷰 선택이 있으면 그것을 우선.
// 기능이 꺼져 있으면(enabled=false) 피커를 숨기고 승인 바디에도 싣지 않는다.
// writeEnabled(스펙 2026-11-03 §6.5)는 그 레포에서 구현 단계 쓰기가 열릴 수 있는지 — 옛 응답에는 없어 false(예전 문구 그대로).
const db = reactive({ enabled: false, writeEnabled: false, items: [] as DbConnectionView[] })
const dbConnectionIds = ref<number[]>([])
// 고른 연결에 쓰기 허용 공용 연결이 있는가 — 쓰기 배지·캡션·"DB 작업" 경고가 이 값을 본다. 작업 단위로 쓰기를 끄는 칸은 없다(스펙 §6.5).
const writableSelected = computed(() =>
  hasWritableSelection(dbConnectionIds.value, { writeEnabled: db.writeEnabled, items: db.items }),
)
const DB_CAPTION_READ_ONLY = 'DB 연결 — 인터뷰·디자인·구현에서 읽기 전용으로 조회합니다'
const DB_CAPTION_WRITABLE =
  'DB 연결 — 인터뷰·디자인에서는 읽기 전용으로 조회하고, 구현 단계에서는 쓰기 허용 연결에 직접 적용할 수 있습니다'
// 타입이 DB 작업인데 쓰기 허용 연결을 고르지 않았으면 경고한다 — 승인은 막지 않는다. DB 목록을 읽는 중에는 선택이 정해지기 전이라 내지 않는다.
const dbWorkWarning = computed(() => taskType.value === 'DB_WORK' && !dbLoading.value && !writableSelected.value)
// 승인은 관리자만 하지만 서버는 선택을 작업 등록자 기준으로 검증한다(스펙 §7). 목록은 보는 사람 기준이라
// 승인자 ≠ 등록자면 승인자 본인의 USER 연결은 쓸 수 없고 등록자의 USER 연결은 안 보인다 →
// 공용(REPO)만 보이고 "새 접속 추가"를 숨기며, 지난 인터뷰의 안 보이는 id(등록자 개인 연결)는 버리지 않고 유지한다.
// requesterId는 서버 AuthContext.requireUserId와 같은 값(JWT username → sub → user_id) — pages/tasks/[id].vue isOwner와 같은 비교.
const auth = useAuthStore()
const isRegistrant = computed(() => {
  const me = auth.me?.username ?? (auth.me?.user_id != null ? String(auth.me.user_id) : null)
  return me != null && props.task.requesterId === me
})
/** 목록에 안 보이지만 유지하는 등록자 개인 연결 id(승인자 ≠ 등록자일 때만) */
const registrantDbIds = ref<number[]>([])

// 참조 repo(작업 스펙 2026-10-24 §7.3): 열 때마다 작업의 참조로 채운 편집 초안. 재승인 때도 작업의 값(마지막 승인 때 값)으로
// 채우고 지난 인터뷰 세션의 참조로 프리필하지 않는다. 추가 폼 후보에서는 대상 레포와 이미 고른 참조를 뺀다(useRefDraft).
const refs = ref<RefRepoView[]>([])
const {
  addOpen: refAddOpen,
  excludeIds: refExcludeIds,
  toggleAdd: toggleRefAdd,
  add: addRef,
  remove: removeRef,
  reset: resetRefs,
} = useRefDraft(refs, () => props.task.repoCatalogId)
// 후보 카탈로그는 "＋ 참조 추가"를 처음 펼칠 때 받는다 — 다이얼로그를 여는 시점의 API 호출을 늘리지 않는다.
// 실패하면 알리고 다음에 펼칠 때 다시 시도한다.
const { catalog: refCatalog, loadOnce: loadRefCatalogOnce } = useRefCatalog((message) =>
  $q.notify({ type: 'negative', message }),
)
function onRefAdd() {
  toggleRefAdd()
  if (refAddOpen.value) loadRefCatalogOnce()
}
/** 초안이 작업의 참조와 다른 집합인가 — 같으면 승인 바디에서 빼 서버가 등록 값을 그대로 쓴다(전부 빼면 []를 싣는다). */
const refsChanged = computed(
  () => !sameRefSet(toInputs(refs.value), toInputs(props.task.refRepos ?? [])),
)

/** 로드 순번 — init/reload마다 증가. 늦게 도착한 이전 응답이 선택·목록을 덮어쓰지 않게 한다. */
let loadSeq = 0
/** DB 목록 로드 중이면 승인 버튼을 막는다(기본 선택이 반영되기 전 승인 방지). */
const dbLoading = ref(false)

async function loadDb(seq: number, mode: 'seed' | 'keep', preferred: number[] = []) {
  const repoId = props.task.repoCatalogId
  if (!repoId) {
    db.enabled = false
    db.writeEnabled = false
    db.items = []
    return
  }
  const r = await fetchDbConnections(repoId)
  if (seq !== loadSeq || !show.value) return
  db.enabled = r.enabled
  db.writeEnabled = r.writeEnabled === true
  const registrant = isRegistrant.value
  db.items = registrant ? r.items : r.items.filter((i) => i.scope === 'REPO')
  if (mode === 'seed') {
    registrantDbIds.value = registrant ? [] : preferred.filter((id) => !r.items.some((i) => i.id === id))
  }
  const usable = new Set(db.items.filter((i) => i.enabled).map((i) => i.id))
  const keepable = (id: number) => usable.has(id) || registrantDbIds.value.includes(id)
  if (mode === 'keep') {
    dbConnectionIds.value = dbConnectionIds.value.filter(keepable)
  } else if (preferred.length) {
    dbConnectionIds.value = preferred.filter(keepable).slice(0, MAX_DB_SELECTION)
  } else {
    dbConnectionIds.value = r.items
      .filter((i) => i.scope === 'REPO' && i.enabled)
      .slice(0, MAX_DB_SELECTION)
      .map((i) => i.id)
  }
}
async function reloadDb() {
  const seq = ++loadSeq
  dbLoading.value = true
  try {
    await loadDb(seq, 'keep')
  } finally {
    if (seq === loadSeq) dbLoading.value = false
  }
}
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
  const seq = ++loadSeq
  db.enabled = false
  db.writeEnabled = false
  db.items = []
  dbLoading.value = !!props.task.repoCatalogId
  model.value = DEFAULT_MODEL
  effort.value = DEFAULT_EFFORT
  taskType.value = props.task.taskType ?? null
  superpowers.value = defaultSuperpowers(taskType.value)
  superpowersTouched = false
  mcpCatalogIds.value = []
  dbConnectionIds.value = []
  registrantDbIds.value = []
  resetRefs(props.task.refRepos ?? [])
  let lastDbIds: number[] = []
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
    if (seq !== loadSeq) return
    previous.value = rows
    const last = rows[0]
    lastDbIds = Array.isArray(last?.dbConnectionIds) ? last.dbConnectionIds : []
    // 지난 세션의 인터뷰 방식 — 아래 모델 프리필 if 밖이다(박제된 모델이어도 방식은 이어 간다). 칸이 없으면(구 API) 타입 기본값 그대로,
    // 조회가 오기 전에 관리자가 토글을 직접 바꿨으면 덮어쓰지 않는다.
    const lastSuperpowers = lastSessionSuperpowers(last)
    if (lastSuperpowers != null && !superpowersTouched) superpowers.value = lastSuperpowers
    // 박제된 과거 모델(fable-5 등)은 더는 고를 수 없으므로 안내만 하고 초기값은 기본값으로 둔다.
    if (last?.model && MODEL_OPTIONS.some((m) => m.value === last.model)) {
      model.value = last.model
      effort.value = coerceEffort(last.model, last.effort ?? DEFAULT_EFFORT)
      prefilled.value = true
    }
  } catch {
    // 403/404 등 — 비소유자/구 API. 기본값으로 진행.
  }
  try {
    await loadDb(seq, 'seed', lastDbIds)
  } finally {
    if (seq === loadSeq) dbLoading.value = false
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
  if (dbLoading.value) return
  approving.value = true
  try {
    await useApi(`/api/tasks/${props.task.id}/approve`, {
      method: 'POST',
      body: {
        model: model.value,
        effort: effort.value,
        mcpCatalogIds: mcpCatalogIds.value,
        ...(taskTypeChanged.value ? { taskType: taskType.value } : {}),
        // 끔일 때만 싣는다 — 서버는 키가 없거나 null이면 켬이라 뜻이 같고, 켬 바디는 예전과 바이트까지 같다
        ...(superpowers.value ? {} : { superpowersEnabled: false }),
        ...(db.enabled ? { dbConnectionIds: dbConnectionIds.value } : {}),
        ...(refsChanged.value ? { refRepos: toInputs(refs.value) } : {}),
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
// 폰에서만 전체화면 — 태블릿은 일반 너비 (composables/dialogLayout.ts)
const fullscreen = useFullscreenDialog()
</script>

<template>
  <!-- 승인 요청 중엔 ESC/바깥 클릭으로도 닫히지 않게(persistent) — 결과 토스트와 후속 스크롤을 놓치지 않도록 -->
  <q-dialog v-model="show" :maximized="fullscreen" :persistent="approving">
    <q-card class="approve-card" style="width: min(560px, 100vw)">
      <!-- 모바일: 상단 고정 바 — 전체화면이라 뒤 페이지가 안 보이므로 닫기와 제목을 항상 노출 -->
      <q-toolbar
        v-if="fullscreen"
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
        <q-card-section v-if="!fullscreen" class="text-h6 q-pb-sm"
          >인터뷰 시작 승인</q-card-section
        >

        <q-card-section :class="fullscreen ? '' : 'q-pt-none'">
          <ApproveTaskSummary :task="task" />
        </q-card-section>

        <!-- 작업 타입 (스펙 2026-11-03 §4.2) — 요약 바로 아래. 현재 타입을 미리 고르고, 바꿨을 때만 승인 바디에 실린다 -->
        <q-card-section class="q-pt-none">
          <q-select
            v-model="taskType"
            :options="TASK_TYPES"
            label="작업 타입"
            outlined
            dense
            emit-value
            map-options
            :disable="approving"
            data-test="approve-task-type"
            @update:model-value="onTaskTypeChange"
          />
          <!-- DB 작업인데 쓰기 허용 연결을 고르지 않았을 때 (스펙 2026-11-03 §6.5) — 승인은 막지 않는다. 타입 칸 안에 둔다(요약 → 타입 → 안내 순서 유지) -->
          <div
            v-if="dbWorkWarning"
            class="db-work-warning row items-start no-wrap q-mt-sm"
            data-test="approve-db-work-warning"
          >
            <q-icon name="warning_amber" size="16px" class="q-mr-xs db-work-warning-icon" />
            <span>구현 때 개발 DB에 바로 적용할 수 없습니다 — SQL은 스크립트 파일로만 남습니다</span>
          </div>
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
          <!-- 인터뷰 방식 (스펙 2026-11-03 간단 인터뷰 §6) — 모델·추론 아래, MCP 위. 끔일 때만 보조 문구 -->
          <div class="q-mt-md">
            <q-toggle
              :model-value="superpowers"
              :label="SUPERPOWERS_TOGGLE_LABEL"
              dense
              :disable="approving"
              data-test="approve-superpowers"
              @update:model-value="onSuperpowersToggle"
            />
            <div
              v-if="!superpowers"
              class="text-caption text-grey-7 q-mt-xs"
              data-test="approve-superpowers-caption"
            >
              {{ SIMPLE_INTERVIEW_CAPTION }}
            </div>
          </div>
          <div class="q-mt-md">
            <McpPicker v-model="mcpCatalogIds" flat />
          </div>
          <div v-if="db.enabled && task.repoCatalogId" class="q-mt-md" data-test="approve-db">
            <!-- 쓰기 허용 연결을 고른 상태일 때만 문구를 바꾼다 — 인터뷰·디자인은 늘 읽기 전용이고 구현 단계에서만 쓰기가 열린다 -->
            <div class="text-caption text-grey-7 q-mb-xs" data-test="approve-db-caption">{{ writableSelected ? DB_CAPTION_WRITABLE : DB_CAPTION_READ_ONLY }}</div>
            <DbConnectionPicker
              v-model="dbConnectionIds"
              :items="db.items"
              :repo-catalog-id="task.repoCatalogId"
              :allow-add="isRegistrant"
              :registrant-ids="registrantDbIds"
              :show-write="db.writeEnabled"
              @reload="reloadDb"
            />
          </div>
          <!-- 참조 repo (스펙 2026-10-24 §7.3) — DB 연결 아래·사용량 위. 인터뷰가 아직 받지 않았으므로 칩은 "인터뷰 때 받음"
               (task-setup 맥락). "＋ 참조 추가"를 누르면 추가 폼이 칩 줄 아래에 펼쳐진다. 레거시 작업(카탈로그 없음)은 그리지 않는다. -->
          <div v-if="task.repoCatalogId" class="q-mt-md" data-test="approve-refs">
            <RefRepoChips
              :items="refs"
              editable
              context="task-setup"
              @remove="removeRef"
              @add="onRefAdd"
            />
            <div v-if="refAddOpen" class="ref-add-panel">
              <RefRepoAddForm :catalog="refCatalog" :exclude-ids="refExcludeIds" @add="addRef" />
            </div>
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
          :disable="dbLoading"
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
.db-work-warning {
  font-size: 13px;
  color: #bf360c;
  line-height: 1.4;
}
.db-work-warning-icon {
  margin-top: 2px;
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
/* 참조 추가 폼 — 칩 줄 아래 윤곽선 패널 (작업 등록 창 pages/tasks/index.vue와 같은 모양) */
.ref-add-panel {
  box-sizing: border-box;
  max-width: 360px;
  margin-top: 8px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 8px;
}
.ref-add-panel :deep(.ref-add-form) {
  width: auto;
  padding: 12px;
}
</style>
