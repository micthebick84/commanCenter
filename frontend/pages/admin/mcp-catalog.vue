<script setup lang="ts">
import { useQuasar } from 'quasar'
import AdminSectionTabs from '~/components/tasks/AdminSectionTabs.vue'

definePageMeta({ layout: 'default' })

interface CatalogEntry {
  id: number
  name: string
  displayName: string
  url: string
  transport: string
  description: string | null
  enabled: boolean
  createdBy: string | null
  createdAt: string
  updatedAt: string
  lastCheckAt: string | null
  lastCheckStatus: string | null
  lastCheckError: string | null
}

const checkingIds = ref<Set<number>>(new Set())

async function check(e: CatalogEntry) {
  checkingIds.value.add(e.id)
  try {
    const res = await useApi<{ status: string; error: string; elapsedMs: number }>(
      `/api/admin/mcp-catalog/${e.id}/check`,
      { method: 'POST' },
    )
    const color =
      res.status === 'HEALTHY' ? 'positive' : res.status === 'DEGRADED' ? 'warning' : 'negative'
    $q.notify({
      color,
      textColor: 'white',
      message: `${e.displayName}: ${res.status} (${res.elapsedMs}ms)`,
      caption: res.error || undefined,
      icon: res.status === 'HEALTHY' ? 'check_circle' : 'warning',
      timeout: 4000,
    })
    await load()
  } catch (err: any) {
    $q.notify({ type: 'negative', message: err?.data?.message ?? '체크 호출 실패' })
  } finally {
    checkingIds.value.delete(e.id)
  }
}

function statusColor(s: string | null): string {
  if (!s) return 'grey-5'
  return { HEALTHY: 'positive', DEGRADED: 'warning', DOWN: 'negative' }[s] ?? 'grey-5'
}
function statusLabel(s: string | null): string {
  if (!s) return 'UNKNOWN'
  return s
}
function lastCheckAgo(iso: string | null): string {
  if (!iso) return '미확인'
  const diff = Math.floor((Date.now() - new Date(iso).getTime()) / 1000)
  if (diff < 60) return `${diff}초 전`
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`
  return `${Math.floor(diff / 86400)}일 전`
}

const $q = useQuasar()
const entries = ref<CatalogEntry[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    entries.value = await useApi<CatalogEntry[]>('/api/admin/mcp-catalog')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '카탈로그 조회 실패' })
  } finally {
    loading.value = false
  }
}
onMounted(load)

// 등록/수정 다이얼로그
const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  name: '',
  displayName: '',
  url: '',
  transport: 'sse',
  description: '',
  enabled: true,
})
const submitting = ref(false)

function openCreate() {
  editingId.value = null
  form.name = ''
  form.displayName = ''
  form.url = ''
  form.transport = 'sse'
  form.description = ''
  form.enabled = true
  showForm.value = true
}

function openEdit(e: CatalogEntry) {
  editingId.value = e.id
  form.name = e.name
  form.displayName = e.displayName
  form.url = e.url
  form.transport = e.transport
  form.description = e.description ?? ''
  form.enabled = e.enabled
  showForm.value = true
}

async function submit() {
  submitting.value = true
  try {
    const body = {
      name: form.name,
      displayName: form.displayName,
      url: form.url,
      transport: form.transport,
      description: form.description || null,
      enabled: form.enabled,
    }
    if (editingId.value) {
      await useApi(`/api/admin/mcp-catalog/${editingId.value}`, { method: 'PUT', body })
      $q.notify({ type: 'positive', message: '수정 완료' })
    } else {
      await useApi('/api/admin/mcp-catalog', { method: 'POST', body })
      $q.notify({ type: 'positive', message: '등록 완료' })
    }
    showForm.value = false
    await load()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '저장 실패' })
  } finally {
    submitting.value = false
  }
}

async function toggleEnabled(e: CatalogEntry) {
  try {
    await useApi(`/api/admin/mcp-catalog/${e.id}`, {
      method: 'PUT',
      body: {
        name: e.name,
        displayName: e.displayName,
        url: e.url,
        transport: e.transport,
        description: e.description,
        enabled: !e.enabled,
      },
    })
    await load()
  } catch (err: any) {
    $q.notify({ type: 'negative', message: err?.data?.message ?? '활성화 토글 실패' })
  }
}

async function remove(e: CatalogEntry) {
  if (!confirm(`'${e.displayName}' 카탈로그 항목을 삭제하시겠습니까? (이미 등록된 task의 스냅샷은 보존됨)`)) return
  try {
    await useApi(`/api/admin/mcp-catalog/${e.id}`, { method: 'DELETE' })
    $q.notify({ type: 'positive', message: '삭제 완료' })
    await load()
  } catch (err: any) {
    $q.notify({ type: 'negative', message: err?.data?.message ?? '삭제 실패' })
  }
}

const canSubmit = computed(
  () =>
    !submitting.value &&
    !!form.name.trim() &&
    /^[a-z0-9_-]+$/.test(form.name) &&
    !!form.displayName.trim() &&
    !!form.url.trim() &&
    /^https?:\/\/.+/.test(form.url),
)
</script>

<template>
  <q-page padding>
    <AdminSectionTabs />
    <div class="row items-center q-mb-md">
      <div class="text-h5">MCP 카탈로그</div>
      <q-space />
      <q-btn color="primary" icon="add" label="새 MCP 등록" unelevated @click="openCreate" />
    </div>

    <q-banner class="bg-blue-1 text-grey-9 q-mb-md">
      <template #avatar><q-icon name="info" color="primary" /></template>
      관리자가 등록한 SSE MCP 서버. 사용자가 작업 등록 시 활성화(enabled)된 항목 중 선택할 수 있습니다.
      등록된 task의 MCP 설정은 작성 시점 스냅샷으로 박제되어, 카탈로그 변경/삭제 후에도 분석 재현 가능.
    </q-banner>

    <q-table
      :rows="entries"
      :loading="loading"
      row-key="id"
      flat
      bordered
      :pagination="{ rowsPerPage: 50 }"
      :columns="[
        { name: 'enabled', label: '', field: 'enabled', align: 'center', style: 'width:60px' },
        { name: 'name', label: 'name', field: 'name', align: 'left' },
        { name: 'displayName', label: '표시명', field: 'displayName', align: 'left' },
        { name: 'transport', label: 'transport', field: 'transport', align: 'center' },
        { name: 'url', label: 'URL', field: 'url', align: 'left' },
        { name: 'health', label: '헬스', field: 'lastCheckStatus', align: 'center' },
        { name: 'description', label: '설명', field: 'description', align: 'left' },
        { name: 'actions', label: '', field: () => '', align: 'right' },
      ]"
    >
      <template #body-cell-enabled="props">
        <q-td :props="props">
          <q-toggle
            :model-value="props.row.enabled"
            color="positive"
            @update:model-value="toggleEnabled(props.row)"
          />
        </q-td>
      </template>
      <template #body-cell-name="props">
        <q-td :props="props">
          <code>{{ props.row.name }}</code>
          <div class="text-caption text-grey-7">mcp__{{ props.row.name }}</div>
        </q-td>
      </template>
      <template #body-cell-transport="props">
        <q-td :props="props">
          <q-chip size="sm" dense color="grey-3" text-color="grey-9" :label="props.row.transport" />
        </q-td>
      </template>
      <template #body-cell-url="props">
        <q-td :props="props">
          <code style="word-break: break-all">{{ props.row.url }}</code>
        </q-td>
      </template>
      <template #body-cell-health="props">
        <q-td :props="props">
          <q-chip
            dense
            size="sm"
            :color="statusColor(props.row.lastCheckStatus)"
            text-color="white"
            :label="statusLabel(props.row.lastCheckStatus)"
          />
          <div class="text-caption text-grey-7">{{ lastCheckAgo(props.row.lastCheckAt) }}</div>
          <div
            v-if="props.row.lastCheckError"
            class="text-caption text-negative"
            style="max-width: 240px; word-break: break-word"
          >
            {{ props.row.lastCheckError }}
          </div>
        </q-td>
      </template>
      <template #body-cell-actions="props">
        <q-td :props="props">
          <q-btn
            flat
            dense
            icon="health_and_safety"
            color="primary"
            :loading="checkingIds.has(props.row.id)"
            @click="check(props.row)"
          >
            <q-tooltip>헬스 테스트</q-tooltip>
          </q-btn>
          <q-btn flat dense icon="edit" color="primary" @click="openEdit(props.row)" />
          <q-btn flat dense icon="delete" color="negative" @click="remove(props.row)" />
        </q-td>
      </template>
    </q-table>

    <q-dialog v-model="showForm" persistent>
      <q-card style="min-width: 520px">
        <q-card-section>
          <div class="text-h6">{{ editingId ? 'MCP 수정' : '새 MCP 등록' }}</div>
        </q-card-section>
        <q-card-section class="q-gutter-md">
          <q-input
            v-model="form.name"
            label="name (claude 노출용)"
            placeholder="company-docs"
            outlined
            dense
            hint="소문자/숫자/-/_  · 변경 시 mcp__<name> 도 변경됨"
            :rules="[
              (v) => /^[a-z0-9_-]+$/.test(v) || '소문자/숫자/-/_ 만',
            ]"
          />
          <q-input v-model="form.displayName" label="표시명" placeholder="사내 문서 검색" outlined dense />
          <q-input
            v-model="form.url"
            label="URL"
            placeholder="https://mcp.example.com/sse"
            outlined
            dense
            :rules="[(v) => /^https?:\/\/.+/.test(v) || 'http(s)://로 시작']"
          />
          <q-select
            v-model="form.transport"
            :options="['sse', 'http']"
            label="transport"
            outlined
            dense
          />
          <q-input
            v-model="form.description"
            label="설명 (선택)"
            type="textarea"
            outlined
            autogrow
            rows="2"
          />
          <q-toggle v-model="form.enabled" label="활성화 (사용자에게 노출)" color="positive" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="취소" @click="showForm = false" />
          <q-btn
            unelevated
            color="primary"
            :label="editingId ? '수정' : '등록'"
            :loading="submitting"
            :disable="!canSubmit"
            @click="submit"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>
