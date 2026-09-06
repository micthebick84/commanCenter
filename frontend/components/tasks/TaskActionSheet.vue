<script setup lang="ts">
// 카드 ⋮ 하단 시트 (스펙 2026-09-06 §4.1, 캔버스 ActionSheet). 데스크톱 드래그 전이(MOVES)를 터치용으로 복원.
// 전이 실행·확인(confirm:true)·취소·삭제 API 호출은 부모(pages/tasks/index.vue)가 한다 — 여기선 emit만.
import {
  CHIP,
  moveFor,
  cancelable,
  DEPLOY_ACTIVE_STATUSES,
  type MoveDef,
} from '~/composables/taskStages'
import type { CardTask } from '~/components/tasks/TaskCardCompact.vue'

const props = defineProps<{ task: CardTask | null; isAdmin: boolean }>()
const show = defineModel<boolean>({ default: false })
const emit = defineEmits<{
  (e: 'detail'): void
  (e: 'move', move: MoveDef): void
  (e: 'cancel'): void
  (e: 'remove'): void
}>()

const chip = computed(() =>
  props.task
    ? (CHIP[props.task.status] ?? ['#f5f5f5', '#616161'])
    : ['#f5f5f5', '#616161'],
)
const move = computed(() =>
  props.task && props.isAdmin ? moveFor(props.task) : null,
)
const deployActive = computed(
  () => !!props.task && DEPLOY_ACTIVE_STATUSES.includes(props.task.status),
)
const moveIcon = computed(() =>
  move.value?.path.startsWith('deploy') || move.value?.path === 'redeploy'
    ? 'rocket_launch'
    : move.value?.path === 'undeploy'
      ? 'stop'
      : 'check_circle',
)
</script>

<template>
  <q-dialog v-model="show" position="bottom">
    <q-card v-if="task" class="sheet" data-test="task-action-sheet">
      <div class="handle" />
      <div class="sheet-head">
        <div class="row items-baseline no-wrap" style="gap: 6px">
          <span class="text-caption text-weight-bold text-grey-6"
            >#{{ task.id }}</span
          >
          <span class="text-subtitle1 text-weight-medium ellipsis">{{
            task.title
          }}</span>
        </div>
        <div class="row items-center" style="gap: 8px">
          <span class="chip" :style="{ background: chip[0], color: chip[1] }">{{
            task.statusLabel
          }}</span>
          <span class="text-caption text-grey-7"
            >{{ task.repoAlias ?? task.githubRepo }} ·
            {{ task.githubBranch }}</span
          >
        </div>
      </div>
      <q-list>
        <q-item
          v-close-popup
          clickable
          class="sheet-item"
          data-test="sheet-detail"
          @click="emit('detail')"
        >
          <q-item-section avatar
            ><q-icon name="info" color="grey-8"
          /></q-item-section>
          <q-item-section>상세 보기</q-item-section>
          <q-item-section side
            ><q-icon name="chevron_right" color="grey-5"
          /></q-item-section>
        </q-item>
        <q-item
          v-if="task.implementation?.prUrl"
          v-close-popup
          clickable
          tag="a"
          :href="task.implementation.prUrl"
          target="_blank"
          rel="noopener"
          class="sheet-item"
          data-test="sheet-pr"
        >
          <q-item-section avatar
            ><q-icon name="open_in_new" color="grey-8"
          /></q-item-section>
          <q-item-section
            >PR #{{ task.implementation.prNumber }} 열기</q-item-section
          >
        </q-item>
        <q-item
          v-if="move"
          v-close-popup
          clickable
          class="sheet-item text-primary"
          data-test="sheet-move"
          @click="emit('move', move)"
        >
          <q-item-section avatar
            ><q-icon :name="moveIcon" color="primary"
          /></q-item-section>
          <q-item-section>
            <q-item-label class="text-weight-medium">{{
              move.label
            }}</q-item-label>
            <q-item-label v-if="move.confirm" caption
              >확인 후 실행됩니다 (관리자)</q-item-label
            >
          </q-item-section>
        </q-item>
        <q-separator spaced />
        <q-item
          v-if="cancelable(task)"
          v-close-popup
          clickable
          class="sheet-item text-warning"
          data-test="sheet-cancel"
          @click="emit('cancel')"
        >
          <q-item-section avatar
            ><q-icon name="block" color="warning"
          /></q-item-section>
          <q-item-section>취소</q-item-section>
        </q-item>
        <q-item
          v-close-popup="!deployActive"
          :clickable="!deployActive"
          :disable="deployActive"
          :aria-disabled="deployActive ? 'true' : 'false'"
          class="sheet-item text-negative"
          data-test="sheet-remove"
          @click="!deployActive && emit('remove')"
        >
          <q-item-section avatar
            ><q-icon
              name="delete"
              :color="deployActive ? 'grey-4' : 'negative'"
          /></q-item-section>
          <q-item-section>
            <q-item-label>삭제</q-item-label>
            <q-item-label v-if="deployActive" caption
              >배포 이력이 활성인 작업은 먼저 중지 후 삭제</q-item-label
            >
          </q-item-section>
        </q-item>
        <q-item v-close-popup clickable class="sheet-item sheet-close">
          <q-item-section class="text-center text-grey-8 text-weight-medium"
            >닫기</q-item-section
          >
        </q-item>
      </q-list>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.sheet {
  width: 100%;
  border-radius: 16px 16px 0 0;
  padding: 8px 0 12px;
}
.handle {
  width: 36px;
  height: 4px;
  border-radius: 2px;
  background: #e0e0e0;
  margin: 0 auto 10px;
}
.sheet-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 4px 20px 12px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
}
.chip {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 4px;
}
.sheet-item {
  min-height: 48px;
  font-size: 15px;
}
.sheet-close {
  margin: 4px 8px 0;
  border-radius: 6px;
  background: #f5f5f5;
}
</style>
