<script setup lang="ts">
// REPO 추가 요청 처리 — 사내 GitLab 후보를 골라 한 번에 카탈로그에 등록한다(확인 창 없음).
// 열면 요청 힌트로 추측한 후보를 읽고, 검색창(300ms 디바운스·2자 이상)으로 GitLab 전체를 찾을 수 있다.
import { useQuasar } from 'quasar'
import type { RepoCandidate, RequestDetail } from '~/composables/requestBoard'

const props = defineProps<{ requestId: number }>()
const emit = defineEmits<{ added: [detail: RequestDetail] }>()

const $q = useQuasar()

const dropdown = ref<{ hide: () => void } | null>(null)
const searchInput = ref<{ focus: () => void } | null>(null)

const loading = ref(false)
// GitLab 미설정(503)일 때의 서버 메시지 — 있으면 검색창도 숨기고 이것만 보여 준다
const blockedMessage = ref('')
// 그 밖의 일시 오류(502 등) — 메시지는 보여 주되 검색창은 그대로 둔다
const loadError = ref('')
const candidates = ref<RepoCandidate[]>([])

const query = ref<string | null>('')
const searching = ref(false)
// null이면 검색 중이 아니다(후보 영역을 보여 준다)
const results = ref<RepoCandidate[] | null>(null)
const adding = ref(false)

const SEARCH_DEBOUNCE_MS = 300
const MIN_QUERY = 2
let timer: ReturnType<typeof setTimeout> | null = null
// 늦게 도착한 이전 응답이 최신 상태를 덮지 않게 하는 순번
let loadSeq = 0
let searchSeq = 0

const shown = computed(() => results.value ?? candidates.value)

function clearTimer() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

async function onBeforeShow() {
  clearTimer()
  searchSeq++
  query.value = ''
  results.value = null
  searching.value = false
  blockedMessage.value = ''
  loadError.value = ''
  candidates.value = []
  const seq = ++loadSeq
  loading.value = true
  try {
    const list = await useApi<RepoCandidate[]>(`/api/admin/requests/${props.requestId}/repo-candidates`)
    if (seq !== loadSeq) return
    candidates.value = list
  } catch (e: any) {
    if (seq !== loadSeq) return
    const status = e?.statusCode ?? e?.response?.status ?? e?.status
    if (status === 503) blockedMessage.value = e?.data?.message ?? '사내 GitLab이 설정되지 않았습니다'
    else loadError.value = e?.data?.message ?? '후보를 불러오지 못했습니다'
  } finally {
    if (seq === loadSeq) loading.value = false
  }
  if (seq === loadSeq && !blockedMessage.value && !candidates.value.length) {
    await nextTick()
    searchInput.value?.focus()
  }
}

watch(query, (v) => {
  clearTimer()
  // q-input의 지우기 아이콘은 null을 내보낸다
  const q = (v ?? '').trim()
  if (q.length < MIN_QUERY) {
    // 검색어를 지우면 후보 영역으로 돌아간다
    searchSeq++
    results.value = null
    searching.value = false
    return
  }
  timer = setTimeout(() => runSearch(q), SEARCH_DEBOUNCE_MS)
})

async function runSearch(q: string) {
  timer = null
  const seq = ++searchSeq
  searching.value = true
  try {
    const list = await useApi<RepoCandidate[]>(`/api/admin/gitlab-projects?search=${encodeURIComponent(q)}`)
    if (seq !== searchSeq) return
    results.value = list
  } catch (e: any) {
    if (seq !== searchSeq) return
    $q.notify({ type: 'negative', message: e?.data?.message ?? 'GitLab 검색 실패' })
  } finally {
    if (seq === searchSeq) searching.value = false
  }
}

async function pick(c: RepoCandidate) {
  if (adding.value) return
  adding.value = true
  try {
    const detail = await useApi<RequestDetail>(`/api/admin/requests/${props.requestId}/add-repo`, {
      method: 'POST',
      body: { gitlabProjectId: c.id },
    })
    $q.notify({ type: 'positive', message: `${detail?.repoAlias ?? c.pathWithNamespace} 등록됨` })
    dropdown.value?.hide()
    emit('added', detail)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '레포 등록 실패' })
  } finally {
    adding.value = false
  }
}

onUnmounted(clearTimer)
</script>

<template>
  <q-btn-dropdown
    ref="dropdown"
    dense
    no-caps
    outline
    color="primary"
    label="추가"
    data-test="repo-add-btn"
    @click.stop
    @before-show="onBeforeShow"
  >
    <div style="min-width: 300px; max-width: min(440px, 92vw)" data-test="repo-add-panel">
      <div v-if="loading" class="text-center q-pa-md"><q-spinner size="24px" color="primary" /></div>
      <div v-else-if="blockedMessage" class="q-pa-md text-body2 text-grey-8" data-test="cand-blocked">
        {{ blockedMessage }}
      </div>
      <template v-else>
        <div class="q-pa-sm">
          <q-input
            ref="searchInput"
            v-model="query"
            dense
            outlined
            clearable
            placeholder="GitLab 프로젝트 검색 (2자 이상)"
            data-test="cand-search"
          />
        </div>
        <div v-if="searching" class="text-center q-pa-sm"><q-spinner size="20px" color="primary" /></div>
        <div v-else-if="!shown.length" class="q-px-md q-pb-md text-body2 text-grey-7" data-test="cand-empty">
          {{ results ? '검색 결과가 없어요.' : loadError || '추측한 후보가 없어요. 검색해 주세요.' }}
        </div>
        <q-list v-else separator style="max-height: 320px; overflow: auto">
          <q-item v-for="c in shown" :key="c.id" clickable :disable="adding" :data-test="`cand-${c.id}`" @click="pick(c)">
            <q-item-section>
              <q-item-label class="text-weight-bold">{{ c.pathWithNamespace }}</q-item-label>
              <q-item-label caption>{{ c.defaultBranch ?? '-' }}</q-item-label>
              <q-item-label v-if="c.description" caption lines="1">{{ c.description }}</q-item-label>
            </q-item-section>
            <q-item-section v-if="c.registered" side>
              <q-chip dense size="sm" color="blue-grey-1" text-color="grey-9" label="등록됨" class="q-ma-none" />
            </q-item-section>
          </q-item>
        </q-list>
      </template>
    </div>
  </q-btn-dropdown>
</template>
