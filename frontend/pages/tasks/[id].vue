<script setup lang="ts">
import { useQuasar } from 'quasar'

definePageMeta({ layout: 'default' })

interface AnalysisView {
  markdownResult: string
  subtasksJson: string
  durationMs: number | null
  approved: boolean
  approvedBy: number | null
  approvedAt: string | null
  completedAt: string
}

interface TaskResponse {
  id: number
  githubRepo: string
  githubBranch: string
  title: string
  description: string
  status: string
  statusLabel: string
  requesterId: number
  retryCount: number
  maxRetry: number
  failureReason: string | null
  createdAt: string
  updatedAt: string
  analysis: AnalysisView | null
}

const route = useRoute()
const $q = useQuasar()
const auth = useAuthStore()
const taskId = computed(() => route.params.id as string)

const { data: task, refresh } = useTaskPolling<TaskResponse>(() =>
  useApi(`/api/tasks/${taskId.value}`),
)

const subtasks = computed(() => {
  if (!task.value?.analysis?.subtasksJson) return []
  try {
    return JSON.parse(task.value.analysis.subtasksJson) as Array<Record<string, string>>
  } catch {
    return []
  }
})

async function approve() {
  if (!confirm('이 분석 결과를 승인하시겠습니까?')) return
  try {
    await useApi(`/api/tasks/${taskId.value}/approve`, { method: 'POST' })
    $q.notify({ type: 'positive', message: '승인 완료' })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '승인 실패' })
  }
}

async function retry() {
  try {
    await useApi(`/api/tasks/${taskId.value}/retry`, { method: 'POST' })
    $q.notify({ type: 'positive', message: '재시도 큐 등록' })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '재시도 실패' })
  }
}

function statusClass(status: string) {
  return {
    PENDING: 'status-chip status-pending',
    IN_PROGRESS: 'status-chip status-in-progress',
    COMPLETED: 'status-chip status-completed',
    FAILED: 'status-chip status-failed',
    CANCELLED: 'status-chip status-cancelled',
  }[status] || 'status-chip'
}
</script>

<template>
  <q-page padding>
    <div v-if="!task" class="flex flex-center q-pa-xl">
      <q-spinner-dots color="primary" size="3em" />
    </div>
    <template v-else>
      <q-breadcrumbs class="q-mb-md">
        <q-breadcrumbs-el to="/tasks" label="작업 목록" />
        <q-breadcrumbs-el :label="`#${task.id}`" />
      </q-breadcrumbs>

      <div class="row items-center q-mb-md">
        <div class="text-h5">{{ task.title }}</div>
        <q-space />
        <span :class="statusClass(task.status)">{{ task.statusLabel }}</span>
      </div>

      <q-card flat bordered class="q-mb-md">
        <q-card-section>
          <div class="text-caption">레포</div>
          <div>{{ task.githubRepo }} <q-chip dense size="sm" :label="task.githubBranch" /></div>
        </q-card-section>
        <q-separator />
        <q-card-section>
          <div class="text-caption">요청 상세</div>
          <pre style="white-space: pre-wrap">{{ task.description }}</pre>
        </q-card-section>
        <q-separator />
        <q-card-section v-if="task.failureReason">
          <div class="text-caption text-negative">실패 사유</div>
          <pre style="white-space: pre-wrap; color: #c62828">{{ task.failureReason }}</pre>
          <q-btn
            unelevated
            color="warning"
            icon="refresh"
            :label="`재시도 (${task.retryCount}/${task.maxRetry})`"
            :disable="task.retryCount >= task.maxRetry"
            @click="retry"
          />
        </q-card-section>
      </q-card>

      <q-card v-if="task.analysis" flat bordered>
        <q-card-section class="row items-center">
          <div class="text-h6">분석 결과</div>
          <q-space />
          <q-chip
            v-if="task.analysis.approved"
            color="positive"
            text-color="white"
            label="승인됨"
            dense
          />
          <q-btn
            v-else-if="auth.isAdmin"
            unelevated
            color="positive"
            icon="check"
            label="승인"
            @click="approve"
          />
        </q-card-section>
        <q-separator />
        <q-card-section>
          <div class="text-caption">소요 시간: {{ task.analysis.durationMs }} ms</div>
          <pre style="white-space: pre-wrap; font-family: 'Pretendard', sans-serif">{{
            task.analysis.markdownResult
          }}</pre>
        </q-card-section>
        <q-separator />
        <q-card-section v-if="subtasks.length">
          <div class="text-subtitle1 q-mb-sm">Subtask 분해</div>
          <q-list dense bordered>
            <q-item v-for="(s, idx) in subtasks" :key="idx">
              <q-item-section>
                <q-item-label>{{ idx + 1 }}. {{ s.title }}</q-item-label>
                <q-item-label caption>{{ s.summary }}</q-item-label>
                <q-item-label caption>
                  파일: <code>{{ s.files }}</code>
                  · LoC: {{ s.estimatedLoc }}
                  · 위험도:
                  <q-chip dense size="sm" :label="s.risk" />
                </q-item-label>
              </q-item-section>
            </q-item>
          </q-list>
        </q-card-section>
      </q-card>
    </template>
  </q-page>
</template>
