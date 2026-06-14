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

interface DeploymentView {
  deployUrl: string | null
  deployHostPort: number | null
  deployImage: string | null
  deployedAt: string | null
  deployLog: string | null
}

interface EnvVar {
  key: string
  value: string
  secret: boolean
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
  envVars: EnvVar[]
  createdAt: string
  updatedAt: string
  analysis: AnalysisView | null
  implementation: ImplementationView | null
  deployment: DeploymentView | null
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
    return JSON.parse(task.value.analysis.subtasksJson) as Array<
      Record<string, string>
    >
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
    $q.notify({
      type: 'positive',
      message: '승인 완료 — 구현 큐에 진입했습니다',
    })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '승인 실패' })
  }
}

// 편집용 행: 와이어 포맷(EnvVar)에 UI 전용 상태(reveal) + 안정적 key(id)를 더한다.
// id는 v-for의 stable key로 써서 행 삭제 시 입력/마스킹 상태가 어긋나지 않게 한다.
interface EnvRow extends EnvVar {
  id: number
  reveal: boolean
}

const envDialog = ref(false)
const envMode = ref<'deploy' | 'redeploy'>('deploy')
const envRows = ref<EnvRow[]>([])
let envRowSeq = 0

function openDeployDialog(mode: 'deploy' | 'redeploy') {
  envMode.value = mode
  envRows.value = (task.value?.envVars ?? []).map((e) => ({
    ...e,
    id: envRowSeq++,
    reveal: false,
  }))
  envDialog.value = true
}

function addEnvRow() {
  envRows.value.push({
    key: '',
    value: '',
    secret: false,
    id: envRowSeq++,
    reveal: false,
  })
}

function removeEnvRow(id: number) {
  envRows.value = envRows.value.filter((r) => r.id !== id)
}

async function submitDeploy() {
  const endpoint = envMode.value === 'deploy' ? 'deploy' : 'redeploy'
  // UI 전용 필드(id/reveal)는 제외하고 와이어 포맷만 전송.
  const envVars = envRows.value
    .map((r) => ({ key: r.key.trim(), value: r.value, secret: r.secret }))
    .filter((r) => r.key !== '')
  try {
    await useApi(`/api/tasks/${taskId.value}/${endpoint}`, {
      method: 'POST',
      body: { envVars },
    })
    $q.notify({
      type: 'positive',
      message: envMode.value === 'deploy' ? '배포 큐 등록' : '재배포 큐 등록',
    })
    envDialog.value = false
    refresh()
  } catch (e: any) {
    $q.notify({
      type: 'negative',
      message:
        e?.data?.message ??
        (envMode.value === 'deploy' ? '배포 실패' : '재배포 실패'),
    })
  }
}

function undeploy() {
  $q.dialog({
    title: '배포 중지',
    message: '실행 중인 컨테이너를 중지하고 제거합니다. 계속할까요?',
    cancel: true,
    persistent: true,
  }).onOk(async () => {
    try {
      await useApi(`/api/tasks/${taskId.value}/undeploy`, { method: 'POST' })
      $q.notify({ type: 'positive', message: '배포 중지 요청 등록' })
      refresh()
    } catch (e: any) {
      $q.notify({
        type: 'negative',
        message: e?.data?.message ?? '배포 중지 실패',
      })
    }
  })
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

const liveLog = ref('')
let logSource: EventSource | null = null

function closeLog() {
  if (logSource) {
    logSource.close()
    logSource = null
  }
}

function openLog() {
  closeLog()
  liveLog.value = ''
  const token = auth.accessToken
  if (!token) return
  const url = `/api/tasks/${taskId.value}/logs/stream?access_token=${encodeURIComponent(token)}`
  logSource = new EventSource(url)
  logSource.addEventListener('log', (e) => {
    liveLog.value += (e as MessageEvent).data + '\n'
  })
  logSource.addEventListener('done', () => closeLog())
  logSource.onerror = () => closeLog()
}

// in-flight 진입 시 SSE 열고, 벗어나면 닫는다.
watch(
  () => task.value?.status,
  (s) => {
    if (s === 'DEPLOYING' || s === 'UNDEPLOYING') openLog()
    else closeLog()
  },
)

onUnmounted(() => closeLog())

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
      DEPLOY_PENDING: 'status-chip status-deploy-pending',
      DEPLOYING: 'status-chip status-deploying',
      DEPLOYED: 'status-chip status-deployed',
      DEPLOY_FAILED: 'status-chip status-deploy-failed',
      UNDEPLOY_PENDING: 'status-chip status-deploying',
      UNDEPLOYING: 'status-chip status-deploying',
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
          <div>
            {{ task.githubRepo }}
            <q-chip dense size="sm" :label="task.githubBranch" />
          </div>
        </q-card-section>
        <q-separator />
        <q-card-section>
          <div class="text-caption">요청 상세</div>
          <pre style="white-space: pre-wrap">{{ task.description }}</pre>
        </q-card-section>
        <q-separator v-if="task.mcpsExtra && task.mcpsExtra.length" />
        <q-card-section v-if="task.mcpsExtra && task.mcpsExtra.length">
          <div class="text-caption q-mb-xs">
            분석에 주입된 추가 MCP (등록 시점 스냅샷)
          </div>
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
          <pre style="white-space: pre-wrap; color: #c62828">{{
            task.failureReason
          }}</pre>
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
            <span
              v-if="task.implementation.headSha"
              class="text-caption q-ml-sm"
            >
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

      <!-- 배포 카드 -->
      <q-card
        v-if="task.status === 'PR_CREATED' || task.deployment"
        flat
        bordered
        class="q-mb-md"
      >
        <q-card-section class="row items-center q-gutter-sm">
          <q-icon name="rocket_launch" color="primary" size="sm" />
          <div class="text-h6">배포</div>
          <q-space />
          <q-btn
            v-if="auth.isAdmin && task.status === 'PR_CREATED'"
            unelevated
            color="primary"
            icon="rocket_launch"
            label="배포"
            @click="openDeployDialog('deploy')"
          />
          <q-chip
            v-if="
              task.status === 'DEPLOYING' ||
              task.status === 'DEPLOY_PENDING' ||
              task.status === 'UNDEPLOY_PENDING' ||
              task.status === 'UNDEPLOYING'
            "
            color="orange"
            text-color="white"
            >{{ task.statusLabel }}</q-chip
          >
          <template
            v-if="
              auth.isAdmin &&
              (task.status === 'DEPLOYED' || task.status === 'DEPLOY_FAILED')
            "
          >
            <q-btn
              unelevated
              color="primary"
              icon="refresh"
              label="재배포"
              @click="openDeployDialog('redeploy')"
            />
            <q-btn
              v-if="task.status === 'DEPLOYED'"
              color="negative"
              icon="stop"
              label="중지"
              outline
              @click="undeploy"
            />
          </template>
        </q-card-section>

        <q-separator v-if="task.deployment && task.deployment.deployUrl" />
        <q-card-section v-if="task.deployment && task.deployment.deployUrl">
          <div class="text-caption text-grey-7">접속 URL</div>
          <a :href="task.deployment.deployUrl" target="_blank" rel="noopener">{{
            task.deployment.deployUrl
          }}</a>
          <div class="text-caption q-mt-xs">
            포트 <code>{{ task.deployment.deployHostPort }}</code> · 이미지
            <code>{{ task.deployment.deployImage }}</code>
            <span v-if="task.deployment.deployedAt">
              ·
              {{ new Date(task.deployment.deployedAt).toLocaleString() }}</span
            >
          </div>
        </q-card-section>

        <q-separator v-if="task.envVars && task.envVars.length" />
        <q-card-section v-if="task.envVars && task.envVars.length">
          <div class="text-caption text-grey-7 q-mb-xs">환경변수</div>
          <div v-for="(e, i) in task.envVars" :key="i" class="text-body2">
            <code>{{ e.key }}</code> =
            <span v-if="e.secret" class="text-grey">••••••</span>
            <code v-else>{{ e.value }}</code>
          </div>
        </q-card-section>

        <q-separator v-if="task.status === 'DEPLOYING' || task.status === 'UNDEPLOYING'" />
        <q-card-section v-if="task.status === 'DEPLOYING' || task.status === 'UNDEPLOYING'">
          <div class="text-caption text-grey-7 q-mb-xs">실시간 로그</div>
          <pre class="deploy-log">{{ liveLog || '로그 대기 중…' }}</pre>
        </q-card-section>

        <q-separator v-if="task.status === 'DEPLOY_FAILED'" />
        <q-card-section v-if="task.status === 'DEPLOY_FAILED'">
          <q-banner class="bg-red-1 text-red-9">배포 실패: {{ task.failureReason }}</q-banner>
          <pre v-if="task.deployment && task.deployment.deployLog" class="deploy-log">{{ task.deployment.deployLog }}</pre>
        </q-card-section>

        <q-separator v-if="task.status === 'DEPLOYED' && task.deployment && task.deployment.deployLog" />
        <q-card-section v-if="task.status === 'DEPLOYED' && task.deployment && task.deployment.deployLog">
          <q-expansion-item dense label="배포 로그" icon="article">
            <pre class="deploy-log">{{ task.deployment.deployLog }}</pre>
          </q-expansion-item>
        </q-card-section>
      </q-card>

      <q-dialog v-model="envDialog">
        <q-card style="min-width: 480px; max-width: 90vw">
          <q-card-section class="row items-center">
            <div class="text-h6">
              {{ envMode === 'deploy' ? '배포' : '재배포' }} — 환경변수
            </div>
            <q-space />
            <q-btn v-close-popup flat round dense icon="close" />
          </q-card-section>
          <q-card-section class="text-caption text-grey-7">
            컨테이너에 <code>-e KEY=VALUE</code>로 주입됩니다. DB 접속
            정보·시크릿을 여기에 입력하세요. (예:
            <code>SPRING_DATASOURCE_URL</code>, <code>JWT_SECRET</code>) 비밀
            값은 마스킹 표시되지만 평문 저장됩니다.
          </q-card-section>
          <q-card-section class="q-gutter-sm">
            <div
              v-for="row in envRows"
              :key="row.id"
              class="row items-center q-gutter-xs no-wrap"
            >
              <q-input
                v-model="row.key"
                dense
                outlined
                placeholder="KEY"
                style="flex: 1"
              />
              <q-input
                v-model="row.value"
                dense
                outlined
                placeholder="value"
                style="flex: 2"
                :type="row.secret && !row.reveal ? 'password' : 'text'"
              >
                <template v-if="row.secret" #append>
                  <q-icon
                    :name="row.reveal ? 'visibility_off' : 'visibility'"
                    class="cursor-pointer"
                    @click="row.reveal = !row.reveal"
                  />
                </template>
              </q-input>
              <q-toggle v-model="row.secret" label="비밀" dense />
              <q-btn
                flat
                round
                dense
                icon="delete"
                color="grey"
                @click="removeEnvRow(row.id)"
              />
            </div>
            <q-btn flat dense icon="add" label="변수 추가" @click="addEnvRow" />
          </q-card-section>
          <q-card-actions align="right">
            <q-btn v-close-popup flat label="취소" />
            <q-btn
              unelevated
              color="primary"
              :label="envMode === 'deploy' ? '배포' : '재배포'"
              @click="submitDeploy"
            />
          </q-card-actions>
        </q-card>
      </q-dialog>

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
            :label="
              task.status === 'APPROVED' ? '승인됨 (구현 대기)' : '승인됨'
            "
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
          <div class="text-caption">
            소요 시간: {{ task.analysis.durationMs }} ms
          </div>
          <pre
            style="white-space: pre-wrap; font-family: 'Pretendard', sans-serif"
            >{{ task.analysis.markdownResult }}</pre
          >
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
                  파일: <code>{{ s.files }}</code> · LoC: {{ s.estimatedLoc }}
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

<style scoped>
.deploy-log {
  max-height: 240px;
  overflow: auto;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 8px;
  border-radius: 4px;
  font-size: 0.75rem;
  white-space: pre-wrap;
}
</style>
