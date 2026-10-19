<script setup lang="ts">
import { useQuasar } from 'quasar'
import RepoAddDropdown from '~/components/requests/RepoAddDropdown.vue'
import RequestFormDialog from '~/components/requests/RequestFormDialog.vue'
import { useHydrationSafeAuth } from '~/composables/useHydrationSafeAuth'
import {
  KIND_OPTIONS,
  OPEN_STATUSES,
  STATUS_COLOR,
  STATUS_OPTIONS,
  type RequestKind,
  type RequestListItem,
  type RequestStatus,
} from '~/composables/requestBoard'

definePageMeta({ layout: 'default' })

const $q = useQuasar()
// 템플릿의 관리자 분기는 하이드레이션 안전 값, 조회 URL 판단은 스토어 그대로
const { isAdmin } = useHydrationSafeAuth()
const auth = useAuthStore()

const rows = ref<RequestListItem[]>([])
const loading = ref(false)

// 관리자 필터 — 기본은 열린 요청(접수·검토중) 전체 + 안 읽은 건 포함
const showAll = ref(true)
const kindFilter = ref<RequestKind | null>(null)
const statusFilter = ref<RequestStatus[]>([...OPEN_STATUSES])
const includeUnread = ref(true)

const kindFilterOptions = [{ value: null, label: '전체 종류' }, ...KIND_OPTIONS]

function buildUrl(): string {
  if (!auth.isAdmin || !showAll.value) return '/api/requests'
  const qs = new URLSearchParams()
  qs.set('all', 'true')
  if (kindFilter.value) qs.set('kind', kindFilter.value)
  // 칩을 모두 끄면 "전체 상태" — 파라미터를 생략하면 백엔드가 열린 상태만 내려 UI와 어긋난다
  const statuses = statusFilter.value.length ? statusFilter.value : STATUS_OPTIONS.map((o) => o.value)
  for (const s of statuses) qs.append('status', s)
  qs.set('includeUnread', String(includeUnread.value))
  return `/api/requests?${qs.toString()}`
}

async function load() {
  loading.value = true
  try {
    rows.value = await useApi<RequestListItem[]>(buildUrl())
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '요청 목록 조회 실패' })
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch([showAll, kindFilter, statusFilter, includeUnread], load)

function fmt(iso: string) {
  return new Date(iso).toLocaleString()
}
function open(r: RequestListItem) {
  navigateTo('/requests/' + r.id)
}
const isOpenRepo = (r: RequestListItem) => r.kind === 'REPO' && OPEN_STATUSES.includes(r.status)

const showCreate = ref(false)
</script>

<template>
  <q-page padding>
    <div class="row items-center q-mb-md q-gutter-y-sm">
      <div class="text-h5">요청 게시판</div>
      <q-space />
      <q-btn
        color="primary"
        icon="add"
        label="새 요청"
        unelevated
        data-test="open-create"
        @click="showCreate = true"
      />
    </div>

    <!-- 관리자 필터 -->
    <div v-if="isAdmin" class="row items-center q-gutter-sm q-mb-md" data-test="admin-filters">
      <q-toggle v-model="showAll" label="전체 보기" data-test="filter-all" />
      <template v-if="showAll">
        <q-select
          v-model="kindFilter"
          :options="kindFilterOptions"
          emit-value
          map-options
          dense
          outlined
          style="min-width: 140px"
          data-test="filter-kind"
        />
        <q-select
          v-model="statusFilter"
          :options="STATUS_OPTIONS"
          emit-value
          map-options
          multiple
          use-chips
          dense
          outlined
          label="상태"
          style="min-width: 220px"
          data-test="filter-status"
        />
        <q-toggle v-model="includeUnread" label="안 읽은 건 포함" data-test="filter-unread" />
      </template>
    </div>

    <!-- 모바일: 카드 목록 -->
    <div v-if="$q.screen.lt.md" class="request-cards" data-test="request-cards">
      <div v-if="loading && !rows.length" class="text-center q-pa-lg"><q-spinner size="28px" color="primary" /></div>
      <div v-else-if="!rows.length" class="text-center text-grey-7 q-pa-lg">요청이 없습니다</div>
      <q-card v-for="r in rows" :key="r.id" flat bordered class="q-mb-sm cursor-pointer" @click="open(r)">
        <q-card-section class="q-pb-sm" :data-test="`req-row-${r.id}`">
          <div class="row items-center no-wrap q-gutter-x-xs q-mb-xs">
            <q-chip dense size="sm" color="blue-grey-1" text-color="grey-9" :label="r.kindLabel" class="q-ma-none" />
            <q-chip dense size="sm" :color="STATUS_COLOR[r.status]" text-color="white" :label="r.statusLabel" class="q-ma-none" />
            <q-space />
            <span v-if="r.unread" class="unread-dot" data-test="unread-dot" aria-label="새 활동" />
          </div>
          <div class="text-subtitle2 text-weight-medium">{{ r.title }}</div>
          <div class="text-caption text-grey-7">
            <span v-if="isAdmin">{{ r.requesterId }} · </span>{{ fmt(r.updatedAt) }}
          </div>
        </q-card-section>
        <q-card-actions v-if="isAdmin && isOpenRepo(r)" align="right" @click.stop>
          <span data-test="repo-add-slot"><RepoAddDropdown :request-id="r.id" @added="load" /></span>
        </q-card-actions>
      </q-card>
    </div>

    <q-table
      v-else
      :rows="rows"
      :loading="loading"
      row-key="id"
      flat
      bordered
      no-data-label="요청이 없습니다"
      :pagination="{ rowsPerPage: 50 }"
      :columns="[
        { name: 'kind', label: '종류', field: 'kindLabel', align: 'left', style: 'width:110px' },
        { name: 'title', label: '제목', field: 'title', align: 'left' },
        { name: 'status', label: '상태', field: 'statusLabel', align: 'center', style: 'width:100px' },
        ...(isAdmin ? [{ name: 'requesterId', label: '요청자', field: 'requesterId', align: 'left' as const }] : []),
        { name: 'updatedAt', label: '최근 활동', field: 'updatedAt', align: 'left' },
        { name: 'actions', label: '', field: () => '', align: 'right' },
      ]"
      @row-click="(_evt: Event, r: RequestListItem) => open(r)"
    >
      <template #body-cell-kind="props">
        <q-td :props="props" class="cursor-pointer">
          <q-chip dense size="sm" color="blue-grey-1" text-color="grey-9" :label="props.row.kindLabel" class="q-ma-none" />
        </q-td>
      </template>
      <template #body-cell-title="props">
        <q-td :props="props" class="cursor-pointer">
          <span class="text-weight-medium" :data-test="`req-row-${props.row.id}`">{{ props.row.title }}</span>
          <span v-if="props.row.unread" class="unread-dot q-ml-sm" data-test="unread-dot" aria-label="새 활동" />
        </q-td>
      </template>
      <template #body-cell-status="props">
        <q-td :props="props" class="cursor-pointer">
          <q-chip dense size="sm" :color="STATUS_COLOR[props.row.status as RequestStatus]" text-color="white" :label="props.row.statusLabel" class="q-ma-none" />
        </q-td>
      </template>
      <template #body-cell-updatedAt="props">
        <q-td :props="props" class="cursor-pointer">{{ fmt(props.row.updatedAt) }}</q-td>
      </template>
      <template #body-cell-actions="props">
        <q-td :props="props" @click.stop>
          <span v-if="isAdmin && isOpenRepo(props.row)" data-test="repo-add-slot">
            <RepoAddDropdown :request-id="props.row.id" @added="load" />
          </span>
        </q-td>
      </template>
    </q-table>

    <RequestFormDialog v-model="showCreate" @saved="load" />
  </q-page>
</template>

<style scoped>
.unread-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--q-negative, #c10015);
  vertical-align: middle;
}
</style>
