<script setup lang="ts">
// 좌측 세션 목록 (스펙 2026-09-05 §3). 5초 폴링(기존 useTaskPolling), updatedAt 그룹, 활성 행 강조,
// 관리자 전체 보기 토글(구 목록 페이지에서 이동), 하단 Claude Code 사용량 패널.
import { interviewStatusLabel } from '~/composables/interviewLabels'
import { groupByRecency, type QuestionSummary } from '~/composables/questions'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'

const props = defineProps<{ activeId: number | null }>()
const emit = defineEmits<{ (e: 'select', id: number): void; (e: 'new'): void }>()

const auth = useAuthStore()
const all = ref(false)
const { data, refresh } = useTaskPolling<QuestionSummary[]>(() =>
  useApi('/api/questions', { params: { all: String(all.value) } }),
)
// 배열이 아닌 응답(프록시 오류 페이지 등)도 빈 목록으로 — 렌더 중 throw 방지
const rows = computed(() => (Array.isArray(data.value) ? data.value : []))
const groups = computed(() => groupByRecency(rows.value))

function statusCaption(q: QuestionSummary) {
  return interviewStatusLabel(q.statusName, 'QUESTION')
}
function isBusy(q: QuestionSummary) {
  return q.statusName === 'RUNNING' || q.statusName === 'QUEUED'
}

// 새 질문 등록 직후 셸이 즉시 갱신할 수 있게 노출 (폴링 5초를 기다리지 않는다)
defineExpose({ refresh })
</script>

<template>
  <aside class="question-sidebar column no-wrap">
    <q-btn
      unelevated
      color="primary"
      icon="add"
      label="새 질문"
      class="full-width"
      data-test="new-question"
      @click="emit('new')"
    />
    <div class="col scroll q-mt-sm">
      <div v-if="rows.length === 0" class="text-caption text-grey-7 q-pa-sm">
        아직 질문이 없습니다. 레포에 대해 궁금한 점을 물어보세요.
      </div>
      <template v-for="g in groups" :key="g.key">
        <div class="group-label text-caption text-grey-7">{{ g.label }}</div>
        <div
          v-for="q in g.items"
          :key="q.id"
          class="session-item"
          :class="{ active: q.id === props.activeId }"
          data-test="question-row"
          @click="emit('select', q.id)"
        >
          <div class="session-title ellipsis">{{ q.title }}</div>
          <div class="session-caption ellipsis text-caption text-grey-7">
            <span :class="{ 'text-primary text-weight-medium': isBusy(q) }">{{ statusCaption(q) }}</span>
            · {{ q.repoAlias ?? q.githubRepo }} · {{ q.githubBranch }}<span v-if="all"> · {{ q.requesterId }}</span>
          </div>
        </div>
      </template>
    </div>
    <ClaudeUsagePanel variant="panel" />
    <q-toggle
      v-if="auth.isAdmin"
      v-model="all"
      dense
      label="전체 보기"
      data-test="all-toggle"
      class="q-mt-sm"
      @update:model-value="refresh"
    />
  </aside>
</template>

<style scoped>
.question-sidebar {
  width: 280px;
  flex: 0 0 280px;
  height: 100%;
  background: #fafafa;
  border-right: 1px solid rgba(0, 0, 0, 0.12);
  padding: 12px;
}
.group-label {
  padding: 14px 8px 6px;
  font-weight: 500;
}
.session-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}
.session-item:hover {
  background: rgba(0, 0, 0, 0.04);
}
.session-item.active {
  background: #e3f2fd;
}
.session-item.active .session-title {
  color: #1976d2;
}
.session-title {
  font-size: 14px;
  line-height: 1.2em;
}
.session-caption {
  margin-top: 4px;
  line-height: 1.2em;
}
</style>
