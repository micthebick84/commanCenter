<script setup lang="ts">
import { useQuasar } from 'quasar'
import { confirmDialog } from '~/composables/confirmDialog'
import AdminSectionTabs from '~/components/tasks/AdminSectionTabs.vue'
import AdminFormDialog from '~/components/AdminFormDialog.vue'
import AdminCatalogCard from '~/components/AdminCatalogCard.vue'
import AdminInfoBanner from '~/components/AdminInfoBanner.vue'

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
  if (!(await confirmDialog($q, `'${e.displayName}' 카탈로그 항목을 삭제하시겠습니까? (이미 등록된 task의 스냅샷은 보존됨)`, { title: '카탈로그 항목 삭제', ok: '삭제' }))) return
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

    <AdminInfoBanner summary="작업 등록 때 고르는 MCP 서버 목록">
      관리자가 등록한 SSE MCP 서버. 사용자가 작업 등록 시 활성화(enabled)된 항목 중 선택할 수 있습니다.
      등록된 task의 MCP 설정은 작성 시점 스냅샷으로 박제되어, 카탈로그 변경/삭제 후에도 분석 재현 가능.
    </AdminInfoBanner>

    <!-- 모바일: 8열 표는 가로 스크롤이 되므로 카드 목록으로 -->
    <div v-if="$q.screen.lt.md" class="catalog-cards" data-test="catalog-cards">
      <div v-if="loading && !entries.length" class="catalog-empty"><q-spinner size="28px" color="primary" /></div>
      <div v-else-if="!entries.length" class="catalog-empty">등록된 MCP가 없습니다</div>
      <AdminCatalogCard
        v-for="e in entries"
        :key="e.id"
        :title="e.displayName"
        :enabled="e.enabled"
        @toggle="toggleEnabled(e)"
      >
        <template #badges>
          <q-chip size="sm" dense color="grey-3" text-color="grey-9" :label="e.transport" class="q-ma-none" />
        </template>
        <div><code class="text-grey-9">mcp__{{ e.name }}</code></div>
        <code class="text-grey-6 card-url">{{ e.url }}</code>
        <div class="row no-wrap items-center q-gutter-x-sm" data-test="card-health">
          <q-chip
            dense
            size="sm"
            :color="statusColor(e.lastCheckStatus)"
            text-color="white"
            :label="statusLabel(e.lastCheckStatus)"
            class="q-ma-none"
          />
          <span>{{ lastCheckAgo(e.lastCheckAt) }}</span>
        </div>
        <div v-if="e.lastCheckError" class="text-negative" style="word-break: break-word">{{ e.lastCheckError }}</div>
        <div v-if="e.description" class="card-desc">{{ e.description }}</div>
        <template #actions>
          <q-btn
            flat
            no-caps
            icon="health_and_safety"
            label="헬스 테스트"
            color="primary"
            :loading="checkingIds.has(e.id)"
            data-test="card-check"
            @click="check(e)"
          />
          <q-btn flat no-caps icon="edit" label="수정" color="primary" data-test="card-edit" @click="openEdit(e)" />
          <q-btn flat no-caps icon="delete" label="삭제" color="negative" data-test="card-delete" @click="remove(e)" />
        </template>
      </AdminCatalogCard>
    </div>

    <q-table
      v-else
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

    <AdminFormDialog
      v-model="showForm"
      :title="editingId ? 'MCP 수정' : '새 MCP 등록'"
      :submit-label="editingId ? '수정' : '등록'"
      :submitting="submitting"
      :can-submit="canSubmit"
      @submit="submit"
    >
      <!-- 모바일: 규칙이 있는 필드는 hide-bottom-space — 기본(20px 예약 + 절대배치)이면 두 줄로 꺾인 힌트가 다음 필드를 덮는다 -->
      <template #default="{ mobile }">
        <q-input
          v-model="form.name"
          label="name (claude 노출용)"
          placeholder="company-docs"
          outlined
          :dense="!mobile"
          :hide-bottom-space="mobile"
          autocapitalize="off"
          autocorrect="off"
          spellcheck="false"
          hint="소문자/숫자/-/_  · 변경 시 mcp__<name> 도 변경됨"
          :rules="[
            (v) => /^[a-z0-9_-]+$/.test(v) || '소문자/숫자/-/_ 만',
          ]"
          data-test="form-name"
        />
        <q-input
          v-model="form.displayName"
          label="표시명"
          placeholder="사내 문서 검색"
          outlined
          :dense="!mobile"
          data-test="form-display-name"
        />
        <q-input
          v-model="form.url"
          label="URL"
          placeholder="https://mcp.example.com/sse"
          outlined
          :dense="!mobile"
          :hide-bottom-space="mobile"
          inputmode="url"
          autocapitalize="off"
          autocorrect="off"
          spellcheck="false"
          :rules="[(v) => /^https?:\/\/.+/.test(v) || 'http(s)://로 시작']"
          data-test="form-url"
        />
        <!-- 선택지가 2개뿐이라 모바일은 드롭다운 대신 한 번에 누르는 세그먼트 -->
        <div v-if="mobile">
          <div class="text-caption text-grey-7 q-mb-xs">transport</div>
          <q-btn-toggle
            v-model="form.transport"
            spread
            no-caps
            unelevated
            toggle-color="primary"
            color="grey-2"
            text-color="grey-9"
            :options="[
              { label: 'sse', value: 'sse' },
              { label: 'http', value: 'http' },
            ]"
            data-test="form-transport-toggle"
          />
        </div>
        <q-select
          v-else
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
      </template>
    </AdminFormDialog>
  </q-page>
</template>
