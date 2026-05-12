<script setup lang="ts">
import { useQuasar } from 'quasar'

definePageMeta({ layout: 'default' })

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
}

interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
}

const $q = useQuasar()
const mine = ref(true)
const statusFilter = ref<string | null>(null)

const { data: page, refresh } = useTaskPolling<PageResponse<TaskResponse>>(() =>
  useApi('/api/tasks', {
    params: { mine: String(mine.value), status: statusFilter.value || undefined, size: 50 },
  }),
)

const tasks = computed<TaskResponse[]>(() => page.value?.content ?? [])

// 등록 다이얼로그 상태
const showCreate = ref(false)
const draft = reactive({ githubRepo: '', githubBranch: 'main', title: '', description: '' })
const submitting = ref(false)

async function submit() {
  submitting.value = true
  try {
    await useApi('/api/tasks', {
      method: 'POST',
      body: { ...draft },
    })
    $q.notify({ type: 'positive', message: '작업 등록 완료' })
    showCreate.value = false
    draft.title = ''
    draft.description = ''
    refresh()
  } catch (e: any) {
    const msg = e?.data?.message ?? '등록 실패'
    $q.notify({ type: 'negative', message: msg })
  } finally {
    submitting.value = false
  }
}

async function cancel(t: TaskResponse) {
  if (!confirm(`작업 #${t.id} '${t.title}'을 취소하시겠습니까?`)) return
  try {
    await useApi(`/api/tasks/${t.id}/cancel`, { method: 'POST' })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '취소 실패' })
  }
}

async function remove(t: TaskResponse) {
  if (!confirm(`작업 #${t.id}을 삭제하시겠습니까? (복구 불가)`)) return
  try {
    await useApi(`/api/tasks/${t.id}`, { method: 'DELETE' })
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '삭제 실패' })
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
    <QueueStatsBar />
    <div class="row items-center q-mb-md">
      <div class="text-h5">작업 목록</div>
      <q-space />
      <q-toggle v-model="mine" label="내 작업만" @update:model-value="refresh" />
      <q-select
        v-model="statusFilter"
        :options="[
          { label: '전체', value: null },
          { label: '작업대기', value: 'PENDING' },
          { label: '분석중', value: 'IN_PROGRESS' },
          { label: '분석완료', value: 'COMPLETED' },
          { label: '분석실패', value: 'FAILED' },
          { label: '취소됨', value: 'CANCELLED' },
        ]"
        emit-value
        map-options
        dense
        outlined
        style="min-width: 140px"
        class="q-ml-md"
        @update:model-value="refresh"
      />
      <q-btn class="q-ml-md" color="primary" icon="add" label="작업 등록" @click="showCreate = true" />
    </div>

    <q-table
      :rows="tasks"
      row-key="id"
      flat
      bordered
      :columns="[
        { name: 'id', label: '#', field: 'id', align: 'left' },
        { name: 'title', label: '제목', field: 'title', align: 'left' },
        { name: 'repo', label: '레포', field: 'githubRepo', align: 'left' },
        { name: 'status', label: '상태', field: 'statusLabel', align: 'left' },
        { name: 'retry', label: '재시도', field: (r) => `${r.retryCount}/${r.maxRetry}`, align: 'center' },
        { name: 'createdAt', label: '등록', field: 'createdAt', align: 'left' },
        { name: 'actions', label: '', field: () => '', align: 'right' },
      ]"
      :pagination="{ rowsPerPage: 20 }"
    >
      <template #body-cell-title="props">
        <q-td :props="props">
          <NuxtLink :to="`/tasks/${props.row.id}`">{{ props.row.title }}</NuxtLink>
        </q-td>
      </template>
      <template #body-cell-status="props">
        <q-td :props="props">
          <span :class="statusClass(props.row.status)">{{ props.row.statusLabel }}</span>
        </q-td>
      </template>
      <template #body-cell-actions="props">
        <q-td :props="props">
          <q-btn
            v-if="props.row.status === 'PENDING'"
            flat
            dense
            color="warning"
            icon="block"
            @click="cancel(props.row)"
          />
          <q-btn flat dense color="negative" icon="delete" @click="remove(props.row)" />
        </q-td>
      </template>
    </q-table>

    <!-- 등록 다이얼로그 -->
    <q-dialog v-model="showCreate" persistent>
      <q-card style="min-width: 500px">
        <q-card-section>
          <div class="text-h6">새 작업 등록</div>
        </q-card-section>
        <q-card-section class="q-gutter-sm">
          <q-input
            v-model="draft.githubRepo"
            label="GitHub 레포 (owner/repo)"
            hint="예: hamonsoft/netis-backend"
            outlined
            dense
          />
          <q-input v-model="draft.githubBranch" label="브랜치" outlined dense />
          <q-input v-model="draft.title" label="작업 제목" outlined dense />
          <q-input
            v-model="draft.description"
            label="작업 상세"
            type="textarea"
            outlined
            autogrow
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="취소" @click="showCreate = false" />
          <q-btn unelevated color="primary" label="등록" :loading="submitting" @click="submit" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>
