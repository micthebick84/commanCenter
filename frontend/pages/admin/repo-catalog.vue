<script setup lang="ts">
import { useQuasar } from 'quasar'
import AdminSectionTabs from '~/components/tasks/AdminSectionTabs.vue'
import AdminFormDialog from '~/components/AdminFormDialog.vue'
import AdminCatalogCard from '~/components/AdminCatalogCard.vue'
import AdminInfoBanner from '~/components/AdminInfoBanner.vue'

definePageMeta({ layout: 'default' })

interface RepoEntry {
  id: number
  alias: string
  gitUrl: string
  host: string
  ownerRepo: string | null
  defaultBranch: string | null
  description: string | null
  enabled: boolean
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

const $q = useQuasar()
const entries = ref<RepoEntry[]>([])
const loading = ref(false)
const checkingIds = ref<Set<number>>(new Set())

async function load() {
  loading.value = true
  try {
    entries.value = await useApi<RepoEntry[]>('/api/admin/repo-catalog')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '카탈로그 조회 실패' })
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function check(e: RepoEntry) {
  checkingIds.value.add(e.id)
  try {
    const res = await useApi<{
      reachable: boolean
      defaultBranch: string | null
      branchCount: number
      error: string | null
    }>(`/api/admin/repo-catalog/${e.id}/check`, { method: 'POST' })
    if (res.reachable) {
      $q.notify({
        type: 'positive',
        message: `${e.alias}: 연결 성공 (브랜치 ${res.branchCount}개, 기본 ${res.defaultBranch ?? '-'})`,
        timeout: 4000,
      })
    } else {
      $q.notify({ type: 'negative', message: `${e.alias}: 연결 실패`, caption: res.error ?? undefined })
    }
  } catch (err: any) {
    $q.notify({ type: 'negative', message: err?.data?.message ?? '체크 호출 실패' })
  } finally {
    checkingIds.value.delete(e.id)
  }
}

// 등록/수정 다이얼로그
const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  alias: '',
  gitUrl: '',
  defaultBranch: '',
  description: '',
  enabled: true,
})
const submitting = ref(false)

function openCreate() {
  editingId.value = null
  form.alias = ''
  form.gitUrl = ''
  form.defaultBranch = ''
  form.description = ''
  form.enabled = true
  showForm.value = true
}

function openEdit(e: RepoEntry) {
  editingId.value = e.id
  form.alias = e.alias
  form.gitUrl = e.gitUrl
  form.defaultBranch = e.defaultBranch ?? ''
  form.description = e.description ?? ''
  form.enabled = e.enabled
  showForm.value = true
}

async function submit() {
  submitting.value = true
  try {
    const body = {
      alias: form.alias,
      gitUrl: form.gitUrl,
      defaultBranch: form.defaultBranch || null,
      description: form.description || null,
      enabled: form.enabled,
    }
    if (editingId.value) {
      await useApi(`/api/admin/repo-catalog/${editingId.value}`, { method: 'PUT', body })
      $q.notify({ type: 'positive', message: '수정 완료' })
    } else {
      await useApi('/api/admin/repo-catalog', { method: 'POST', body })
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

async function toggleEnabled(e: RepoEntry) {
  try {
    await useApi(`/api/admin/repo-catalog/${e.id}`, {
      method: 'PUT',
      body: {
        alias: e.alias,
        gitUrl: e.gitUrl,
        defaultBranch: e.defaultBranch,
        description: e.description,
        enabled: !e.enabled,
      },
    })
    await load()
  } catch (err: any) {
    $q.notify({ type: 'negative', message: err?.data?.message ?? '활성화 토글 실패' })
  }
}

async function remove(e: RepoEntry) {
  if (!confirm(`'${e.alias}' 레포 카탈로그 항목을 삭제하시겠습니까? (이미 등록된 작업의 스냅샷은 보존됨)`)) return
  try {
    await useApi(`/api/admin/repo-catalog/${e.id}`, { method: 'DELETE' })
    $q.notify({ type: 'positive', message: '삭제 완료' })
    await load()
  } catch (err: any) {
    $q.notify({ type: 'negative', message: err?.data?.message ?? '삭제 실패' })
  }
}

const canSubmit = computed(
  () => !submitting.value && !!form.alias.trim() && !!form.gitUrl.trim(),
)
</script>

<template>
  <q-page padding>
    <AdminSectionTabs />
    <div class="row items-center q-mb-md">
      <div class="text-h5">레포 카탈로그</div>
      <q-space />
      <q-btn
        color="primary"
        icon="add"
        label="새 레포 등록"
        unelevated
        data-test="open-create"
        @click="openCreate"
      />
    </div>

    <AdminInfoBanner summary="작업 등록 때 고르는 레포 목록">
      관리자가 등록한 레포. 사용자가 작업 등록 시 활성(enabled)된 항목을 한글 별칭으로 선택합니다.
      등록된 작업의 레포 정보는 작성 시점 스냅샷으로 박제되어, 카탈로그 변경/삭제 후에도 이력이 보존됩니다.
      (GitHub와 사내 GitLab 레포를 등록할 수 있습니다. 그 밖의 호스트는 저장은 되지만 작업 등록은 불가)
    </AdminInfoBanner>

    <!-- 모바일: 7열 표는 가로 스크롤이 되므로 카드 목록으로 -->
    <div v-if="$q.screen.lt.md" class="catalog-cards" data-test="catalog-cards">
      <div v-if="loading && !entries.length" class="catalog-empty"><q-spinner size="28px" color="primary" /></div>
      <div v-else-if="!entries.length" class="catalog-empty">등록된 레포가 없습니다</div>
      <AdminCatalogCard
        v-for="e in entries"
        :key="e.id"
        :title="e.alias"
        :enabled="e.enabled"
        @toggle="toggleEnabled(e)"
      >
        <template #badges>
          <q-chip v-if="e.host === 'gitlab'" size="sm" dense color="blue-grey-2" text-color="grey-9" label="gitlab" class="q-ma-none" />
          <q-chip
            v-else-if="e.host !== 'github'"
            size="sm"
            dense
            color="orange-3"
            text-color="grey-9"
            :label="`${e.host} · 등록 불가`"
            class="q-ma-none"
          />
        </template>
        <div class="row no-wrap items-center q-gutter-x-xs">
          <q-icon name="folder" size="14px" color="grey-6" />
          <code class="text-grey-9">{{ e.ownerRepo ?? '-' }}</code>
        </div>
        <div class="row no-wrap items-center q-gutter-x-xs">
          <q-icon name="call_split" size="14px" color="grey-6" />
          <span>{{ e.defaultBranch ?? '기본 브랜치 자동 감지' }}</span>
        </div>
        <code class="text-grey-6 card-url">{{ e.gitUrl }}</code>
        <div v-if="e.description" class="card-desc">{{ e.description }}</div>
        <template #actions>
          <q-btn
            flat
            no-caps
            icon="cable"
            label="연결 확인"
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
        { name: 'alias', label: '별칭', field: 'alias', align: 'left' },
        { name: 'ownerRepo', label: '경로', field: 'ownerRepo', align: 'left' },
        { name: 'gitUrl', label: 'Git URL', field: 'gitUrl', align: 'left' },
        { name: 'defaultBranch', label: '기본 브랜치', field: 'defaultBranch', align: 'center' },
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
      <template #body-cell-alias="props">
        <q-td :props="props">
          <span class="text-weight-medium">{{ props.row.alias }}</span>
          <q-chip
            v-if="props.row.host === 'gitlab'"
            size="sm"
            dense
            color="blue-grey-2"
            text-color="grey-9"
            label="gitlab"
          />
          <q-chip
            v-else-if="props.row.host !== 'github'"
            size="sm"
            dense
            color="orange-3"
            text-color="grey-9"
            :label="`${props.row.host} · 등록 불가`"
          />
        </q-td>
      </template>
      <template #body-cell-ownerRepo="props">
        <q-td :props="props"><code>{{ props.row.ownerRepo ?? '-' }}</code></q-td>
      </template>
      <template #body-cell-gitUrl="props">
        <q-td :props="props">
          <code style="word-break: break-all">{{ props.row.gitUrl }}</code>
        </q-td>
      </template>
      <template #body-cell-actions="props">
        <q-td :props="props">
          <q-btn
            flat
            dense
            icon="cable"
            color="primary"
            :loading="checkingIds.has(props.row.id)"
            data-test="check"
            @click="check(props.row)"
          >
            <q-tooltip>연결 확인</q-tooltip>
          </q-btn>
          <q-btn flat dense icon="edit" color="primary" @click="openEdit(props.row)" />
          <q-btn flat dense icon="delete" color="negative" @click="remove(props.row)" />
        </q-td>
      </template>
    </q-table>

    <AdminFormDialog
      v-model="showForm"
      :title="editingId ? '레포 수정' : '새 레포 등록'"
      :submit-label="editingId ? '수정' : '등록'"
      :submitting="submitting"
      :can-submit="canSubmit"
      @submit="submit"
    >
      <template #default="{ mobile }">
        <q-input
          v-model="form.alias"
          label="별칭 (사용자에게 보일 한글명)"
          placeholder="Netis7.0"
          outlined
          :dense="!mobile"
          data-test="form-alias"
        />
        <!-- 모바일은 긴 placeholder가 잘리므로 GitHub 예시만 — GitLab 형식은 힌트가 안내.
             hide-bottom-space: 기본(20px 예약 + 절대배치)이면 두 줄로 꺾인 힌트가 다음 필드를 덮는다 -->
        <q-input
          v-model="form.gitUrl"
          label="Git URL"
          :placeholder="mobile ? 'https://github.com/owner/repo.git' : 'https://github.com/owner/repo.git 또는 https://gitlab.hamon.vip/group/sub/project.git'"
          outlined
          :dense="!mobile"
          :hide-bottom-space="mobile"
          inputmode="url"
          autocapitalize="off"
          autocorrect="off"
          spellcheck="false"
          hint="GitHub: 전체 URL · owner/repo · git@… / 사내 GitLab: 전체 URL 필수 (서버가 정규화)"
          data-test="form-giturl"
        />
        <q-input
          v-model="form.defaultBranch"
          label="기본 브랜치 (선택)"
          placeholder="비우면 자동 감지"
          outlined
          :dense="!mobile"
          autocapitalize="off"
          autocorrect="off"
          spellcheck="false"
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
