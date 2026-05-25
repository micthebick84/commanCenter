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

interface TaskMcpSpec {
  name: string
  url: string
  transport: string
}

interface ImplementationView {
  prUrl: string | null
  prNumber: number | null
  headBranch: string | null
  headSha: string | null
  implementationLog: string | null
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
  mcpsExtra: TaskMcpSpec[]
  createdAt: string
  updatedAt: string
  analysis: AnalysisView | null
  implementation: ImplementationView | null
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
  if (
    !confirm(
      '이 분석 결과를 승인하시겠습니까?\n승인 즉시 워커가 worktree에서 구현 + Draft PR 생성합니다.',
    )
  )
    return
  try {
    await useApi(`/api/tasks/${taskId.value}/approve`, { method: 'POST' })
    $q.notify({ type: 'positive', message: '승인 완료 — 구현 큐에 진입했습니다' })
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
  return (
    {
      PENDING: 'status-chip status-pending',
      IN_PROGRESS: 'status-chip status-in-progress',
      COMPLETED: 'status-chip status-completed',
      FAILED: 'status-chip status-failed',
      APPROVED: 'status-chip status-approved',
      IMPLEMENTING: 'status-chip status-implementing',
      PR_CREATED: 'status-chip status-pr-created',
      IMPLEMENTATION_FAILED: 'status-chip status-impl-failed',
      CANCELLED: 'status-chip status-cancelled',
    }[status] || 'status-chip'
  )
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
        <q-separator v-if="task.mcpsExtra && task.mcpsExtra.length" />
        <q-card-section v-if="task.mcpsExtra && task.mcpsExtra.length">
          <div class="text-caption q-mb-xs">분석에 주입된 추가 MCP (등록 시점 스냅샷)</div>
          <q-chip
            v-for="m in task.mcpsExtra"
            :key="m.name"
            color="indigo-1"
            text-color="indigo-9"
            icon="extension"
            size="sm"
            dense
            :label="`${m.name} (${m.transport})`"
            class="q-mr-xs q-mb-xs"
          >
            <q-tooltip>{{ m.url }}</q-tooltip>
          </q-chip>
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

      <!-- 구현 결과 카드 (PR 생성 또는 구현 실패 시 노출) -->
      <q-card
        v-if="task.implementation"
        flat
        bordered
        class="q-mb-md"
        :class="task.status === 'PR_CREATED' ? 'bg-green-1' : 'bg-red-1'"
      >
        <q-card-section class="row items-center">
          <div class="text-h6">
            <q-icon
              :name="task.status === 'PR_CREATED' ? 'merge_type' : 'error'"
              :color="task.status === 'PR_CREATED' ? 'positive' : 'negative'"
              class="q-mr-sm"
            />
            구현 결과
          </div>
          <q-space />
          <q-btn
            v-if="task.implementation.prUrl"
            unelevated
            color="primary"
            icon="open_in_new"
            :label="`PR #${task.implementation.prNumber}`"
            :href="task.implementation.prUrl"
            target="_blank"
          />
        </q-card-section>
        <q-separator />
        <q-card-section v-if="task.implementation.prUrl">
          <div class="text-caption">PR URL</div>
          <a :href="task.implementation.prUrl" target="_blank">{{
            task.implementation.prUrl
          }}</a>
          <div class="text-caption q-mt-sm">브랜치</div>
          <code>{{ task.implementation.headBranch }}</code>
          <span v-if="task.implementation.headSha" class="text-caption q-ml-sm">
            @ {{ task.implementation.headSha.slice(0, 7) }}
          </span>
        </q-card-section>
        <q-card-section v-else-if="task.implementation.headBranch">
          <div class="text-caption text-negative">
            구현 실패 — 일부 진행됨 (브랜치까지)
          </div>
          <div>
            브랜치: <code>{{ task.implementation.headBranch }}</code>
            <span v-if="task.implementation.headSha" class="text-caption q-ml-sm">
              @ {{ task.implementation.headSha.slice(0, 7) }}
            </span>
          </div>
        </q-card-section>
        <q-expansion-item
          v-if="task.implementation.implementationLog"
          icon="terminal"
          label="구현 로그 (디버그)"
          header-class="text-grey-9"
          dense
        >
          <q-card-section>
            <pre
              style="
                white-space: pre-wrap;
                max-height: 400px;
                overflow: auto;
                font-size: 11px;
                font-family: 'Menlo', monospace;
              "
              >{{ task.implementation.implementationLog }}</pre
            >
          </q-card-section>
        </q-expansion-item>
      </q-card>

      <q-card v-if="task.analysis" flat bordered>
        <q-card-section class="row items-center q-gutter-sm">
          <div class="text-h6">분석 결과</div>
          <q-space />
          <!-- 승인 상태 chip -->
          <q-chip
            v-if="task.analysis.approved"
            color="positive"
            text-color="white"
            icon="check"
            :label="task.status === 'APPROVED' ? '승인됨 (구현 대기)' : '승인됨'"
            dense
          />
          <!-- 구현 승인/시작 버튼: admin이고 status=COMPLETED일 때 (승인 여부 무관) -->
          <!-- - 미승인: 1차 승인 + 큐잉 → '구현 승인' -->
          <!-- - 이미 승인됨(구 데이터): status만 APPROVED로 전이 → '구현 시작' -->
          <q-btn
            v-if="auth.isAdmin && task.status === 'COMPLETED'"
            unelevated
            color="positive"
            icon="rocket_launch"
            :label="task.analysis.approved ? '구현 시작' : '구현 승인'"
            @click="approve"
          >
            <q-tooltip>
              {{
                task.analysis.approved
                  ? '이미 승인된 작업입니다. 클릭하면 즉시 구현 큐에 진입합니다.'
                  : '승인 시 즉시 워커가 worktree에서 구현 + Draft PR 생성'
              }}
            </q-tooltip>
          </q-btn>
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
