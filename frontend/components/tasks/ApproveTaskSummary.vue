<script setup lang="ts">
// 승인 다이얼로그 상단의 작업 요약(2026-09-06 UI/UX 개선): 팝업이 "어떤 작업을 승인하는지" 스스로 설명한다.
// 모바일은 전체화면 다이얼로그라 뒤 페이지가 안 보이므로 특히 필요. 표시 전용 — 데이터는 부모가 준다.
import { ageOf } from '~/composables/taskStages'

// [id].vue의 TaskResponse 중 이 컴포넌트가 읽는 부분집합
export interface SummaryTask {
  id: number
  title: string
  repoAlias: string | null
  githubRepo: string
  githubBranch: string
  requesterId: string
  createdAt: string
  description: string
  attachments: { id: number }[]
}

const props = defineProps<{ task: SummaryTask }>()

const expanded = ref(false)
// 3줄 클램프에 맞춘 "긴 글" 근사 판정 — 줄 수 3 초과 또는 160자 초과면 더 보기 토글을 보여준다.
const isLong = computed(() => {
  const d = props.task.description ?? ''
  return d.split('\n').length > 3 || d.length > 160
})
const clamped = computed(() => isLong.value && !expanded.value)
</script>

<template>
  <div class="approve-summary" data-test="approve-summary">
    <div class="title-row" data-test="summary-title">
      <span class="task-id">#{{ task.id }}</span>
      <span class="task-title">{{ task.title }}</span>
    </div>
    <div class="row items-center no-wrap repo-row" data-test="summary-repo">
      <q-icon name="folder" size="15px" class="q-mr-xs text-grey-7" />
      <span class="repo-name">{{ task.repoAlias ?? task.githubRepo }}</span>
      <span class="branch">{{ task.githubBranch }}</span>
    </div>
    <div class="text-caption text-grey-7 meta-row" data-test="summary-meta">
      요청자 {{ task.requesterId }} · {{ ageOf(task.createdAt) }} 전 등록<span
        v-if="task.attachments?.length"
      >
        · 첨부 {{ task.attachments.length }}</span
      >
    </div>
    <pre
      v-if="task.description"
      class="desc"
      :class="{ clamped }"
      data-test="summary-desc"
      >{{ task.description }}</pre
    >
    <q-btn
      v-if="isLong"
      flat
      dense
      no-caps
      size="sm"
      color="primary"
      class="more-btn"
      :label="expanded ? '접기' : '더 보기'"
      :icon-right="expanded ? 'expand_less' : 'expand_more'"
      data-test="summary-more"
      @click="expanded = !expanded"
    />
  </div>
</template>

<style scoped>
.approve-summary {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 6px;
  background: #fafafa;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.task-id {
  font-size: 12px;
  font-weight: 700;
  color: #9e9e9e;
}
.task-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.35;
  color: #212121;
}
.repo-row {
  font-size: 13px;
  color: #424242;
  min-width: 0;
}
.repo-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.branch {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: #eeeeee;
  font-size: 12px;
  color: #616161;
}
.meta-row {
  line-height: 1.4;
}
.desc {
  margin: 4px 0 0;
  font-family: inherit;
  font-size: 13.5px;
  line-height: 1.45;
  color: #424242;
  white-space: pre-wrap;
  word-break: break-word;
}
.desc.clamped {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.more-btn {
  align-self: flex-start;
  margin-left: -6px;
}
</style>
