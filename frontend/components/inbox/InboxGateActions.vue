<script setup lang="ts">
// 처리함 본문 — 승인 계열 (스펙 2026-11-23 §5.3): 레거시 구현 승인·디자인 검토·수정 요청 승인·종료 승인.
// 버튼과 확인창은 작업 상세와 같은 공용 동작(useTaskActions)을 쓴다. 동작이 서버에 받아들여졌을 때만 done을 올린다.
import { computed } from 'vue'
import { mrRef } from '~/composables/mergeRequestLabel'
import { closeSteps, isKeepBranchClose } from '~/composables/taskClose'
import DesignReviewCard from '~/components/DesignReviewCard.vue'
import FixRequestHistory from '~/components/tasks/FixRequestHistory.vue'
import InboxCloseSteps from '~/components/inbox/InboxCloseSteps.vue'
// defineProps 타입이므로 상대 경로 — vitest는 .nuxt 없이 돌아 SFC 매크로 타입의 `~/` import를 못 푼다.
import type { TaskResponse } from '../../composables/taskDetailTypes'
import type { TaskActions } from '../../composables/useTaskActions'

const props = defineProps<{ task: TaskResponse; actions: TaskActions }>()
const emit = defineEmits<{ (e: 'done'): void }>()

async function run(act: () => Promise<boolean>) {
  if (await act()) emit('done')
}

const steps = computed(() => (props.task.close ? closeSteps(props.task.close, props.task.status) : []))
const pr = computed(() => props.task.implementation)
const deployUrl = computed(() => props.task.deployment?.deployUrl ?? null)
</script>

<template>
  <div class="gate column q-gutter-y-sm">
    <template v-if="task.status === 'COMPLETED'">
      <!-- 문구는 작업 상세와 같다 — 이미 승인된 분석(구 데이터)은 상태만 넘기므로 '구현 시작' -->
      <div class="row q-gutter-sm">
        <q-btn
          unelevated
          no-caps
          color="primary"
          icon="check"
          :label="task.analysis?.approved ? '구현 시작' : '구현 승인'"
          data-test="inbox-act-approve-impl"
          @click="run(actions.approveImpl)"
        />
      </div>
    </template>

    <template v-else-if="task.status === 'DESIGN_REVIEW'">
      <!-- 카드의 refresh는 승인·반려가 성공했을 때만 나온다 -->
      <DesignReviewCard
        v-if="task.design"
        :task-id="task.id"
        :status="task.status"
        :design="task.design"
        :is-admin="true"
        @refresh="emit('done')"
      />
    </template>

    <template v-else-if="task.status === 'FIX_REVIEW'">
      <FixRequestHistory :task-id="String(task.id)" :refresh-key="0" />
      <div v-if="deployUrl" class="text-body2" data-test="inbox-deploy-url">
        <span class="text-caption text-grey-7">접속 URL </span>
        <a :href="deployUrl" target="_blank" rel="noopener">{{ deployUrl }}</a>
      </div>
      <div class="row q-gutter-sm">
        <q-btn unelevated no-caps color="primary" icon="build" label="수정 승인" data-test="inbox-act-approve-fix" @click="run(actions.approveFix)" />
        <q-btn outline no-caps color="negative" icon="block" label="반려" data-test="inbox-act-reject-fix" @click="run(actions.rejectFix)" />
      </div>
    </template>

    <template v-else-if="task.status === 'CLOSE_REVIEW'">
      <div v-if="pr?.prUrl" class="text-body2" data-test="inbox-pr-link">
        <a :href="pr.prUrl" target="_blank" rel="noopener">{{ mrRef(pr.prUrl, pr.prNumber!) }} 열기</a>
      </div>
      <div v-if="deployUrl" class="text-body2" data-test="inbox-deploy-url">
        <span class="text-caption text-grey-7">접속 URL </span>
        <a :href="deployUrl" target="_blank" rel="noopener">{{ deployUrl }}</a>
      </div>
      <InboxCloseSteps :steps="steps" :keep-branch="isKeepBranchClose(task.close)" />
      <div class="row q-gutter-sm">
        <q-btn unelevated no-caps color="primary" icon="task_alt" label="종료 승인" data-test="inbox-act-approve-close" @click="run(actions.approveClose)" />
        <q-btn outline no-caps color="negative" icon="block" label="반려" data-test="inbox-act-reject-close" @click="run(actions.rejectClose)" />
      </div>
    </template>
  </div>
</template>
