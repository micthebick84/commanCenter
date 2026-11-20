<script setup lang="ts">
import { failMsg } from '~/composables/failMessage'
// 요청 상세 — 본문(마크다운) · 답글 스레드 · 수정/취소(editable) · 관리자 상태 변경.
import { useQuasar } from 'quasar'
import RepoAddDropdown from '~/components/requests/RepoAddDropdown.vue'
import RequestFormDialog from '~/components/requests/RequestFormDialog.vue'
import RequestThread from '~/components/requests/RequestThread.vue'
import { downloadAttachment } from '~/composables/attachmentDownload'
import { formatSize } from '~/composables/attachmentLimits'
import { confirmDialog } from '~/composables/confirmDialog'
import { EXPIRED_ATTACHMENT_TIP } from '~/composables/questions'
import {
  ISSUE_TYPE_COLOR,
  OPEN_STATUSES,
  PRIORITY_COLOR,
  STATUS_COLOR,
  STATUS_OPTIONS,
  adminTransitions,
  type RequestComment,
  type RequestDetail,
  type RequestAttachmentView,
  type RequestListItem,
  type RequestStatus,
} from '~/composables/requestBoard'
import { useHydrationSafeAuth } from '~/composables/useHydrationSafeAuth'
import { renderMarkdown } from '~/composables/useMarkdown'

definePageMeta({ layout: 'default' })

const $q = useQuasar()
const route = useRoute()
// 템플릿의 관리자 분기는 하이드레이션 안전 값
const { isAdmin, me } = useHydrationSafeAuth()

const id = computed(() => String(route.params.id))
const req = ref<RequestDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const loadError = ref('')

async function load() {
  loading.value = true
  notFound.value = false
  loadError.value = ''
  try {
    req.value = await useApi<RequestDetail>(`/api/requests/${id.value}`)
  } catch (e: any) {
    req.value = null
    const status = e?.statusCode ?? e?.response?.status ?? e?.status
    if (status === 404) notFound.value = true
    else loadError.value = e?.data?.message ?? '요청을 불러오지 못했습니다'
  } finally {
    loading.value = false
  }
}
onMounted(load)

function fmt(iso: string) {
  return new Date(iso).toLocaleString()
}
const statusLabel = (s: RequestStatus) => STATUS_OPTIONS.find((o) => o.value === s)?.label ?? s

// REPO 처리 결과 — 카탈로그에서 지워졌으면 그 사실을 덧붙인다(alias는 카탈로그가 있을 때만 온다)
const repoResult = computed(() => {
  const r = req.value
  if (!r || r.kind !== 'REPO') return null
  let text: string | null = null
  if (r.repoAlias) text = `등록된 레포: ${r.repoAlias}${r.repoGitUrl ? ` (${r.repoGitUrl})` : ''}`
  else if (r.repoGitUrl) text = `등록된 레포: ${r.repoGitUrl}`
  if (text && r.repoCatalogDeleted) text += ' (카탈로그에서 삭제됨)'
  return text
})
const showRepoAddSlot = computed(
  () => isAdmin.value && req.value?.kind === 'REPO' && OPEN_STATUSES.includes(req.value.status),
)

// 연결 작업 링크는 요청자·관리자만(작업 상세는 그 둘만 볼 수 있다)
const isRequester = computed(() => {
  const m = me.value
  const mine = m?.username ?? (m?.user_id != null ? String(m.user_id) : null)
  return !!mine && mine === req.value?.requesterId
})
const canOpenTask = computed(() => isAdmin.value || isRequester.value)

function openConvert() {
  if (req.value) navigateTo('/tasks?fromRequest=' + req.value.id)
}
function openTask() {
  if (req.value?.taskId != null) navigateTo(`/tasks/${req.value.taskId}`)
}

// ── 첨부 내려받기 ──
async function downloadReqAttachment(a: RequestAttachmentView) {
  if (!req.value || a.expired) return
  try {
    await downloadAttachment(`/api/requests/${req.value.id}/attachments/${a.id}`, a.name)
  } catch (e: unknown) {
    $q.notify({ type: 'negative', message: e instanceof Error && e.message ? e.message : failMsg('다운로드') })
  }
}

// ── 답글 ──
function onPosted(c: RequestComment) {
  if (req.value) req.value.comments = [...req.value.comments, c]
}

function onRepoAdded(d: RequestDetail) {
  req.value = d
}

// ── 수정 · 취소 ──
const showEdit = ref(false)
function onSaved(item: RequestListItem | RequestDetail) {
  // 수정(PUT) 응답은 상세다
  req.value = item as RequestDetail
}
const cancelling = ref(false)
async function cancelRequest() {
  if (!req.value || cancelling.value) return
  if (!(await confirmDialog($q, '이 요청을 취소할까요? 되돌릴 수 없습니다.', { title: '요청 취소', ok: '요청 취소', cancel: '돌아가기' }))) return
  cancelling.value = true
  try {
    await useApi(`/api/requests/${req.value.id}`, { method: 'DELETE' })
    await navigateTo('/requests')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? failMsg('요청 취소') })
  } finally {
    cancelling.value = false
  }
}

// ── 관리자 상태 변경 ──
const changing = ref(false)
const showReject = ref(false)
const rejectReason = ref('')

async function changeStatus(status: RequestStatus, reason?: string) {
  if (!req.value || changing.value) return false
  changing.value = true
  try {
    const body: { status: RequestStatus; reason?: string } = { status }
    if (reason !== undefined) body.reason = reason
    req.value = await useApi<RequestDetail>(`/api/admin/requests/${req.value.id}/status`, { method: 'POST', body })
    return true
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? failMsg('상태 변경') })
    return false
  } finally {
    changing.value = false
  }
}
function pickStatus(status: RequestStatus) {
  if (status === 'REJECTED') {
    rejectReason.value = ''
    showReject.value = true
    return
  }
  changeStatus(status)
}
async function confirmReject() {
  const reason = rejectReason.value.trim()
  if (!reason) return
  if (await changeStatus('REJECTED', reason)) showReject.value = false
}
</script>

<template>
  <q-page padding>
    <div class="q-mb-sm">
      <q-btn flat dense no-caps icon="arrow_back" label="요청 목록" @click="navigateTo('/requests')" />
    </div>

    <div v-if="loading" class="text-center q-pa-lg"><q-spinner size="28px" color="primary" /></div>
    <div v-else-if="notFound" class="text-center text-grey-7 q-pa-xl" data-test="request-not-found">
      <q-icon name="search_off" size="40px" class="q-mb-sm" />
      <div>요청을 찾을 수 없습니다</div>
    </div>
    <div v-else-if="!req" class="text-center text-grey-7 q-pa-xl" data-test="request-load-error">
      <div>{{ loadError }}</div>
      <q-btn flat no-caps color="primary" label="다시 시도" class="q-mt-sm" @click="load" />
    </div>

    <template v-else>
      <!-- 헤더 -->
      <div class="row items-center q-gutter-sm q-mb-xs">
        <q-chip dense size="sm" color="blue-grey-1" text-color="grey-9" :label="req.kindLabel" class="q-ma-none" />
        <q-chip
          dense
          size="sm"
          :color="STATUS_COLOR[req.status]"
          text-color="white"
          :label="req.statusLabel || statusLabel(req.status)"
          class="q-ma-none"
          data-test="request-status"
        />
        <q-space />
        <q-btn
          v-if="req.convertible"
          unelevated
          dense
          no-caps
          color="primary"
          icon="assignment_turned_in"
          label="작업으로 등록"
          data-test="request-convert"
          @click="openConvert"
        />
        <q-btn
          v-if="req.editable"
          flat
          dense
          no-caps
          icon="edit"
          label="수정"
          data-test="req-edit"
          @click="showEdit = true"
        />
        <q-btn
          v-if="req.cancellable"
          flat
          dense
          no-caps
          color="negative"
          icon="close"
          label="취소"
          :loading="cancelling"
          data-test="req-cancel"
          @click="cancelRequest"
        />
        <q-btn
          v-if="isAdmin"
          outline
          dense
          no-caps
          color="primary"
          icon-right="arrow_drop_down"
          label="상태 변경"
          :loading="changing"
          data-test="status-menu"
        >
          <q-menu auto-close>
            <q-list dense style="min-width: 140px">
              <q-item
                v-for="s in adminTransitions(req.status)"
                :key="s"
                clickable
                :data-test="`status-${s}`"
                @click="pickStatus(s)"
              >
                <q-item-section>{{ statusLabel(s) }}</q-item-section>
              </q-item>
            </q-list>
          </q-menu>
        </q-btn>
      </div>
      <div class="text-h5 q-mb-xs" data-test="request-title">{{ req.title }}</div>
      <div class="text-caption text-grey-7 q-mb-md">
        <span v-if="isAdmin">{{ req.requesterId }} · </span>{{ fmt(req.createdAt) }}
        <span v-if="req.updatedAt !== req.createdAt" data-test="request-activity"> · 최근 활동 {{ fmt(req.updatedAt) }}</span>
      </div>

      <!-- 개발 요청 머리 -->
      <div v-if="req.kind === 'DEV'" class="row items-center q-gutter-sm q-mb-md" data-test="request-dev-meta">
        <q-chip
          v-if="req.issueType"
          dense
          size="sm"
          :color="ISSUE_TYPE_COLOR[req.issueType]"
          text-color="white"
          :label="req.issueTypeLabel ?? req.issueType"
          class="q-ma-none"
        />
        <q-chip
          v-if="req.priority"
          dense
          size="sm"
          outline
          :color="PRIORITY_COLOR[req.priority]"
          :label="req.priorityLabel ?? req.priority"
          class="q-ma-none"
        />
        <span v-if="req.dueDate" class="text-body2">마감 {{ req.dueDate }}</span>
        <span v-if="req.targetRepoAlias" class="text-body2">대상 레포 {{ req.targetRepoAlias }}</span>
        <span class="text-body2 text-grey-7">요청자 {{ req.requesterId }}</span>
      </div>

      <!-- 본문 -->
      <q-card flat bordered class="q-mb-md">
        <q-card-section>
          <div class="md-scroll markdown" data-test="request-content" v-html="renderMarkdown(req.content)" />
          <div v-if="req.kind === 'REPO' && req.repoHint" class="text-caption text-grey-7 q-mt-sm" data-test="request-hint">
            레포 힌트: {{ req.repoHint }}
          </div>
        </q-card-section>
      </q-card>

      <!-- 첨부 -->
      <div v-if="req.attachments?.length" class="q-mb-md" data-test="request-attachments">
        <div class="text-subtitle2 q-mb-xs">첨부</div>
        <div class="column items-start q-gutter-y-xs">
          <q-btn
            v-for="a in req.attachments"
            :key="a.id"
            flat
            dense
            no-caps
            align="left"
            :icon="a.expired ? 'history' : 'attach_file'"
            :disable="a.expired"
            :label="`${a.name} (${formatSize(a.sizeBytes)})`"
            :data-test="`request-attachment-${a.id}`"
            @click="downloadReqAttachment(a)"
          >
            <q-tooltip v-if="a.expired">{{ EXPIRED_ATTACHMENT_TIP }}</q-tooltip>
          </q-btn>
        </div>
      </div>

      <!-- 연결 작업 -->
      <div v-if="req.taskId != null" class="q-mb-md text-body2">
        <a v-if="canOpenTask" :href="`/tasks/${req.taskId}`" data-test="request-task-link" @click.prevent="openTask">→ 작업 #{{ req.taskId }} · {{ req.taskStatusLabel }}</a>
        <span v-else data-test="request-task-link">→ 작업 #{{ req.taskId }} · {{ req.taskStatusLabel }}</span>
      </div>

      <!-- REPO 처리 결과 -->
      <div v-if="repoResult" class="q-mb-md text-body2" data-test="repo-result">{{ repoResult }}</div>
      <!-- 사이트 추가 처리 — 등록되면 응답 상세로 화면을 바꾼다(상태·답글·등록된 레포 갱신) -->
      <div v-if="showRepoAddSlot" class="q-mb-md" data-test="repo-add-slot">
        <RepoAddDropdown :request-id="req.id" @added="onRepoAdded" />
      </div>

      <!-- 답글 스레드 -->
      <div class="text-subtitle1 text-weight-medium q-mb-sm">답글</div>
      <RequestThread :comments="req.comments" :request-id="req.id" :requester-id="req.requesterId" @posted="onPosted" />

      <RequestFormDialog v-model="showEdit" :initial="req" @saved="onSaved" />

      <!-- 반려 사유 -->
      <q-dialog v-model="showReject" persistent>
        <q-card style="width: min(420px, 100vw)">
          <q-card-section>
            <div class="text-h6">요청 반려</div>
            <div class="text-caption text-grey-7">사유는 요청자에게 답글로 남습니다.</div>
          </q-card-section>
          <q-card-section class="q-pt-none">
            <q-input
              v-model="rejectReason"
              type="textarea"
              outlined
              autogrow
              autofocus
              label="반려 사유"
              maxlength="2000"
              data-test="reject-reason"
            />
          </q-card-section>
          <q-card-actions align="right">
            <q-btn flat no-caps label="취소" :disable="changing" data-test="reject-cancel" @click="showReject = false" />
            <q-btn
              unelevated
              no-caps
              color="negative"
              label="반려"
              :loading="changing"
              :disable="!rejectReason.trim()"
              data-test="reject-confirm"
              @click="confirmReject"
            />
          </q-card-actions>
        </q-card>
      </q-dialog>
    </template>
  </q-page>
</template>
