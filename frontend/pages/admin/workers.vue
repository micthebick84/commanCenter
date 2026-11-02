<script setup lang="ts">
import AdminSectionTabs from '~/components/tasks/AdminSectionTabs.vue'
import AdminCatalogCard from '~/components/AdminCatalogCard.vue'
import AdminInfoBanner from '~/components/AdminInfoBanner.vue'
import ClaudeAuthChip from '~/components/ClaudeAuthChip.vue'
import { claudeAuthAlerts, claudeAuthView } from '~/composables/claudeAuth'
definePageMeta({ layout: 'default' })

interface WorkerHealth {
  workerId: string
  hostname: string | null
  version: string | null
  lastSeenAt: string | null
  alive: boolean
  claudeSessionOk: boolean | null
  claudeAuthMethod?: string | null
  claudeAuthExpiresAt?: string | null
  vpnStatus: string | null
  mcps: string[]
  lostReportCount: number
}

const { data, loading } = useTaskPolling<WorkerHealth[]>(() => useApi('/api/workers/health'))

// claude 로그인이 끊겼거나 3일 안에 만료되는 워커 — 페이지 맨 위 경고 (스펙 2026-11-02 §6)
const authAlerts = computed(() => claudeAuthAlerts(data.value ?? []))
const authAlertBad = computed(() => authAlerts.value.some((a) => a.view.level === 'bad'))

function lastSeenAgo(iso: string | null): string {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  const diffSec = Math.floor((Date.now() - t) / 1000)
  if (diffSec < 60) return `${diffSec}초 전`
  const min = Math.floor(diffSec / 60)
  if (min < 60) return `${min}분 전`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr}시간 전`
  return `${Math.floor(hr / 24)}일 전`
}
</script>

<template>
  <q-page padding>
    <AdminSectionTabs />
    <div class="row items-center q-mb-md">
      <div class="text-h5">워커 헬스</div>
      <q-space />
      <q-spinner v-if="loading" size="1.2em" color="primary" />
      <span class="text-caption text-grey-7 q-ml-sm">5초마다 자동 갱신</span>
    </div>

    <q-banner
      v-if="authAlerts.length"
      :class="authAlertBad ? 'bg-red-1 text-red-10' : 'bg-orange-1 text-orange-10'"
      class="q-mb-md"
      data-test="claude-auth-alert"
    >
      <template #avatar><q-icon :name="authAlertBad ? 'error' : 'schedule'" /></template>
      <div class="text-weight-medium">Claude 로그인 확인 필요</div>
      <div v-for="a in authAlerts" :key="a.workerId" class="q-mt-xs" data-test="claude-auth-alert-row">
        <strong>{{ a.workerId }}</strong> — {{ a.view.label }}<template v-if="a.view.methodLabel || a.view.expiresLabel">
          ({{ [a.view.methodLabel, a.view.expiresLabel].filter(Boolean).join(' · ') }})</template>.
        {{ a.view.action }}
      </div>
    </q-banner>

    <q-banner v-if="data && data.length === 0" class="bg-grey-2 q-mb-md">
      <template #avatar><q-icon name="info" /></template>
      등록된 워커가 없습니다. 운영자 macOS에서 worker 프로파일을 실행하세요:
      <code>java -jar netis-maker.jar --spring.profiles.active=worker</code>
    </q-banner>

    <!-- 모바일: 9열 표는 가로 스크롤이 되므로 읽기 전용 카드(토글·액션 없음)로 -->
    <div v-else-if="$q.screen.lt.md" class="catalog-cards" data-test="catalog-cards">
      <div v-if="!data" class="catalog-empty"><q-spinner size="28px" color="primary" /></div>
      <AdminCatalogCard v-for="w in data ?? []" :key="w.workerId" :title="w.workerId">
        <template #badges>
          <q-chip
            dense
            size="sm"
            :color="w.alive ? 'green-2' : 'red-2'"
            :text-color="w.alive ? 'green-9' : 'red-9'"
            :icon="w.alive ? 'check_circle' : 'cancel'"
            :label="w.alive ? '정상' : '응답 없음'"
            class="q-ma-none"
            data-test="card-alive"
          />
        </template>
        <dl class="card-kv q-my-none">
          <dt>마지막 응답</dt>
          <dd :class="w.alive ? '' : 'text-negative text-weight-medium'">{{ lastSeenAgo(w.lastSeenAt) }}</dd>
          <dt>호스트</dt>
          <dd>{{ w.hostname ?? '—' }}</dd>
          <dt>버전</dt>
          <dd>{{ w.version ?? '—' }}</dd>
          <dt>Claude OAuth</dt>
          <dd data-test="card-claude-auth"><ClaudeAuthChip :row="w" /></dd>
          <dt>네트워크</dt>
          <dd>{{ w.vpnStatus ?? '—' }}</dd>
          <dt>유실 보고</dt>
          <dd :class="w.lostReportCount > 0 ? 'text-red-9 text-weight-bold' : 'text-grey-6'" data-test="card-lost">
            {{ w.lostReportCount ?? 0 }}
          </dd>
        </dl>
        <div v-if="w.mcps && w.mcps.length" class="row q-gutter-xs" data-test="card-mcps">
          <q-chip
            v-for="m in w.mcps"
            :key="m"
            color="indigo-1"
            text-color="indigo-9"
            icon="bolt"
            size="sm"
            dense
            :label="m"
          />
        </div>
      </AdminCatalogCard>
    </div>

    <q-table
      v-else
      :rows="data ?? []"
      row-key="workerId"
      flat
      bordered
      :pagination="{ rowsPerPage: 50 }"
      :columns="[
        { name: 'status', label: '', field: () => '', align: 'center', style: 'width:48px' },
        { name: 'workerId', label: 'Worker ID', field: 'workerId', align: 'left' },
        { name: 'hostname', label: 'Hostname', field: 'hostname', align: 'left' },
        { name: 'version', label: 'Version', field: 'version', align: 'left' },
        { name: 'lastSeenAt', label: '마지막 응답', field: 'lastSeenAt', align: 'left' },
        { name: 'claudeSession', label: 'Claude OAuth', field: (r) => claudeAuthView(r).label, align: 'center' },
        { name: 'vpnStatus', label: '네트워크', field: 'vpnStatus', align: 'center' },
        { name: 'mcps', label: 'MCP 도구', field: 'mcps', align: 'left' },
        { name: 'lostReportCount', label: '유실 보고', field: 'lostReportCount', align: 'center' },
      ]"
    >
      <template #body-cell-status="props">
        <q-td :props="props">
          <q-icon
            :name="props.row.alive ? 'check_circle' : 'cancel'"
            :color="props.row.alive ? 'positive' : 'negative'"
            size="1.5em"
          />
        </q-td>
      </template>
      <template #body-cell-workerId="props">
        <q-td :props="props">
          <strong>{{ props.row.workerId }}</strong>
        </q-td>
      </template>
      <template #body-cell-lastSeenAt="props">
        <q-td :props="props">
          <span :class="props.row.alive ? '' : 'text-negative'">
            {{ lastSeenAgo(props.row.lastSeenAt) }}
          </span>
        </q-td>
      </template>
      <template #body-cell-claudeSession="props">
        <q-td :props="props" data-test="cell-claude-auth">
          <ClaudeAuthChip :row="props.row" />
        </q-td>
      </template>
      <template #body-cell-mcps="props">
        <q-td :props="props">
          <template v-if="props.row.mcps && props.row.mcps.length">
            <q-chip
              v-for="m in props.row.mcps"
              :key="m"
              color="indigo-1"
              text-color="indigo-9"
              icon="bolt"
              size="sm"
              dense
              :label="m"
              class="q-mr-xs q-mb-xs"
            />
          </template>
          <span v-else class="text-grey-6">—</span>
        </q-td>
      </template>
      <template #body-cell-lostReportCount="props">
        <q-td :props="props">
          <span :class="props.row.lostReportCount > 0 ? 'text-red-9 text-weight-bold' : 'text-grey-6'">
            {{ props.row.lostReportCount ?? 0 }}
          </span>
        </q-td>
      </template>
    </q-table>

    <AdminInfoBanner summary="alive 기준 · 유실 보고 안내" class="q-mt-md">
      <strong>alive 기준</strong>: 마지막 heartbeat가 60초 이내. 그 이상 응답 없으면
      <strong>Stale 회수 잡</strong>(매 1분)이 자동으로 분석중 작업을 작업대기로 되돌립니다.
      <br /><strong>Claude OAuth</strong>: 워커·인터뷰 서비스가 5분마다 그 PC의 claude 로그인을 확인해 보고합니다.
      <strong>setup-token</strong>(env CLAUDE_CODE_OAUTH_TOKEN, 1년)이면 OK, <strong>CLI 로그인</strong>(claude auth
      login)은 refresh token 만료 시각(로그인 후 약 30일)까지 — 3일 안이면 주황, 지나거나 로그아웃이면 빨강입니다.
      <br /><strong>유실 보고</strong>: 결과 보고가 끝내 백엔드에 닿지 못한 횟수입니다. 워커
      재시작 시 0으로 리셋되며, 유실된 결과 원본은 워커의
      <code>~/netis-maker/dead-letter/</code>에 보존됩니다.
    </AdminInfoBanner>
  </q-page>
</template>
