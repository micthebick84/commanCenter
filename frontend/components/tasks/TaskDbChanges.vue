<script setup lang="ts">
// 작업 상세 "DB 변경" 카드 (스펙 2026-11-03 §6.5) — GET /api/tasks/{id}/db-changes. 구현 단계에서 개발 DB에 쓴 SQL 기록을
// 연결별·시간순으로 보여 주고, 승인 대기(DEFERRED) 항목은 관리자에게만 실행·실행 안 함 버튼을 보인다. 기록이 하나도 없으면 카드 자체를 그리지 않는다.
// 목록은 로컬 ref에 보관 — 5초 폴링이 task를 갈아끼워도 상태가 유지되고, 다시 읽을 때만 refreshKey를 올린다(FixRequestHistory와 같은 방식).
import { useQuasar } from 'quasar'
import { confirmDialog } from '~/composables/confirmDialog'
import {
  EXECUTE_ADVICE,
  dbChangeLabel,
  deferredCount,
  groupByConnection,
  type DbChangeView,
} from '~/composables/taskDbChanges'
import { formatKoDateTime } from '~/components/tasks/TaskHistoryTimeline.vue'

const props = defineProps<{ taskId: string; isAdmin: boolean; refreshKey: number }>()
// 관리자가 실행·실행 안 함을 처리했다 — 부모가 작업을 다시 읽어 이력 등을 갱신한다
const emit = defineEmits<{ (e: 'changed'): void }>()

const $q = useQuasar()
const items = ref<DbChangeView[]>([])
let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  try {
    const res = await useApi<DbChangeView[]>(`/api/tasks/${props.taskId}/db-changes`)
    // 배열이 아닌 응답(프록시 오류 페이지 등)도 빈 목록으로 — 렌더 중 throw 방지. 더 새로 시작한 조회가 있으면 이 응답은 버린다.
    if (seq === loadSeq) items.value = Array.isArray(res) ? res : []
  } catch {
    // 403/404 등은 조용히 빈 목록 처리 — 카드만 안 보인다
    if (seq === loadSeq) items.value = []
  }
}

onMounted(load)
watch(() => [props.taskId, props.refreshKey], load)

const groups = computed(() => groupByConnection(items.value))
const pending = computed(() => deferredCount(items.value))

const LABEL_COLOR: Record<string, string> = {
  EXECUTED: 'positive',
  DEFERRED: 'orange-8',
  DISMISSED: 'grey-7',
  REJECTED: 'negative',
  FAILED: 'negative',
}

/** 처리 중인 기록 id — 두 번 눌러도 요청이 한 번만 나가게 막고(서버도 409로 막는다) 두 버튼을 함께 잠근다. */
const busyId = ref<number | null>(null)

/** 응답이 갱신된 행이면 그 자리를 바꾸고, 아니면(모양이 다름) 다시 읽는다. */
async function applyUpdated(updated: DbChangeView | null | undefined) {
  if (updated && typeof updated === 'object' && typeof updated.id === 'number' && items.value.some((r) => r.id === updated.id)) {
    items.value = items.value.map((r) => (r.id === updated.id ? updated : r))
  } else {
    await load()
  }
}

async function process(row: DbChangeView, action: 'execute' | 'dismiss') {
  if (busyId.value != null) return
  const name = row.connectionName ?? '삭제된 연결'
  const ok =
    action === 'execute'
      ? await confirmDialog($q, `'${name}'에서 이 SQL을 실행합니다. ${EXECUTE_ADVICE}.`, { title: '승인 대기 SQL 실행', ok: '실행' })
      : await confirmDialog($q, '이 SQL을 실행하지 않습니다. 스크립트 파일의 "병합 뒤 실행" 절에서도 빠집니다.', { title: '실행 안 함', ok: '실행 안 함' })
  if (!ok) return
  if (busyId.value != null) return // 확인창을 기다리는 동안 다른 항목을 처리했을 수 있다
  busyId.value = row.id
  try {
    const updated = await useApi<DbChangeView>(`/api/tasks/${props.taskId}/db-changes/${row.id}/${action}`, { method: 'POST' })
    $q.notify({ type: 'positive', message: action === 'execute' ? 'SQL을 실행했습니다' : '실행 안 함으로 정리했습니다' })
    await applyUpdated(updated)
    emit('changed')
  } catch (e) {
    const serverMessage = (e as { data?: { message?: string } } | null)?.data?.message
    $q.notify({ type: 'negative', message: serverMessage ?? (action === 'execute' ? '실행 실패' : '처리 실패') })
    // 이미 다른 관리자가 처리했거나 실행이 실패해 되돌려졌을 수 있다 — 지금 상태로 다시 그린다
    await load()
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <q-card v-if="items.length" flat bordered class="q-mb-md" data-test="db-changes">
    <q-card-section class="row items-center q-gutter-sm">
      <q-icon name="storage" color="primary" size="sm" />
      <div class="text-h6">DB 변경</div>
      <q-chip dense size="sm" outline :label="`${items.length}건`" />
      <q-chip v-if="pending" dense size="sm" square color="orange-8" text-color="white" :label="`승인 대기 ${pending}건`" data-test="db-changes-pending" />
    </q-card-section>
    <q-separator />
    <div v-for="g in groups" :key="g.key" data-test="db-change-group">
      <q-card-section class="row items-center no-wrap q-pb-none" style="gap: 6px">
        <q-icon name="dns" size="xs" color="grey-7" />
        <span class="text-subtitle2" data-test="db-change-group-name">{{ g.name }}</span>
      </q-card-section>
      <div v-for="(r, i) in g.items" :key="r.id" :data-test="`db-change-row-${r.id}`">
        <q-separator v-if="i > 0" inset />
        <q-card-section>
          <div class="row items-center" style="gap: 6px">
            <q-chip
              dense
              size="sm"
              square
              :color="LABEL_COLOR[r.classification] ?? 'grey-7'"
              text-color="white"
              :label="dbChangeLabel(r.classification)"
              :data-test="`db-change-label-${r.id}`"
            />
            <span v-if="r.attempt >= 2" class="text-caption text-grey-7">{{ r.attempt }}회째 시도</span>
            <span v-if="r.fixRequestId != null" class="text-caption text-grey-7">수정 요청 #{{ r.fixRequestId }}</span>
            <span v-if="r.affectedRows != null" class="text-caption text-grey-7">{{ r.affectedRows }}행</span>
            <q-space />
            <span class="text-caption text-grey-7">{{ formatKoDateTime(r.createdAt) }}</span>
          </div>
          <div class="md-scroll q-mt-xs">
            <pre class="db-sql" :data-test="`db-change-sql-${r.id}`">{{ r.sqlText }}</pre>
          </div>
          <div v-if="r.reason" class="q-mt-xs text-caption text-grey-8" :data-test="`db-change-reason-${r.id}`">{{ r.reason }}</div>
          <div v-if="r.error" class="q-mt-xs text-caption text-negative" :data-test="`db-change-error-${r.id}`">{{ r.error }}</div>
          <!-- 세션이 실행한 행(처리자 없음)에 시각이 없으면 실행 중이거나 마무리 저장이 안 된 것 — 실행됐다고 단정하지 않는다 -->
          <div
            v-if="r.classification === 'EXECUTED' && !r.executedAt"
            class="q-mt-xs text-caption text-deep-orange-10"
            :data-test="`db-change-unconfirmed-${r.id}`"
          >
            실행 시각이 기록되지 않았습니다 — 실행 중이거나 마무리 저장이 안 된 것일 수 있으니 DB에서 확인하세요
          </div>
          <div v-else-if="r.executedBy && r.executedAt" class="q-mt-xs text-caption text-grey-7">
            {{ dbChangeLabel(r.classification) }} · 관리자 {{ r.executedBy }} · {{ formatKoDateTime(r.executedAt) }}
          </div>
          <div v-if="r.classification === 'DEFERRED' && isAdmin" class="row q-mt-sm" style="gap: 8px">
            <q-btn
              dense
              unelevated
              no-caps
              color="primary"
              icon="play_arrow"
              label="실행"
              :loading="busyId === r.id"
              :disable="busyId != null && busyId !== r.id"
              :data-test="`db-change-execute-${r.id}`"
              @click="process(r, 'execute')"
            />
            <q-btn
              dense
              flat
              no-caps
              color="grey-8"
              icon="block"
              label="실행 안 함"
              :disable="busyId != null"
              :data-test="`db-change-dismiss-${r.id}`"
              @click="process(r, 'dismiss')"
            />
          </div>
        </q-card-section>
      </div>
    </div>
  </q-card>
</template>

<style scoped>
.db-sql {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-size: 12px;
  font-family: 'Menlo', 'Consolas', monospace;
}
</style>
