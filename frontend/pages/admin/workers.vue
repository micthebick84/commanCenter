<script setup lang="ts">
import AdminSectionTabs from '~/components/tasks/AdminSectionTabs.vue'
definePageMeta({ layout: 'default' })

interface WorkerHealth {
  workerId: string
  hostname: string | null
  version: string | null
  lastSeenAt: string | null
  alive: boolean
  claudeSessionOk: boolean | null
  vpnStatus: string | null
  mcps: string[]
  lostReportCount: number
}

const { data, loading } = useTaskPolling<WorkerHealth[]>(() => useApi('/api/workers/health'))

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

function bool(b: boolean | null): string {
  if (b === null || b === undefined) return '—'
  return b ? 'OK' : 'NG'
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

    <q-banner v-if="data && data.length === 0" class="bg-grey-2 q-mb-md">
      <template #avatar><q-icon name="info" /></template>
      등록된 워커가 없습니다. 운영자 macOS에서 worker 프로파일을 실행하세요:
      <code>java -jar netis-maker.jar --spring.profiles.active=worker</code>
    </q-banner>

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
        { name: 'claudeSession', label: 'Claude OAuth', field: (r) => bool(r.claudeSessionOk), align: 'center' },
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
        <q-td :props="props">
          <q-chip
            v-if="props.row.claudeSessionOk !== null"
            :color="props.row.claudeSessionOk ? 'green-2' : 'red-2'"
            :text-color="props.row.claudeSessionOk ? 'green-9' : 'red-9'"
            size="sm"
            dense
            :label="bool(props.row.claudeSessionOk)"
          />
          <span v-else>—</span>
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

    <q-banner class="bg-blue-1 text-grey-9 q-mt-md">
      <template #avatar><q-icon name="info" color="primary" /></template>
      <strong>alive 기준</strong>: 마지막 heartbeat가 60초 이내. 그 이상 응답 없으면
      <strong>Stale 회수 잡</strong>(매 1분)이 자동으로 분석중 작업을 작업대기로 되돌립니다.
      <br /><strong>유실 보고</strong>: 결과 보고가 끝내 백엔드에 닿지 못한 횟수입니다. 워커
      재시작 시 0으로 리셋되며, 유실된 결과 원본은 워커의
      <code>~/netis-maker/dead-letter/</code>에 보존됩니다.
    </q-banner>
  </q-page>
</template>
