<script setup lang="ts">
// 처리함 본문 — 실패 계열 (스펙 2026-11-23 §5.3): 실패·디자인실패·구현실패·배포실패·배포중단됨·종료실패.
// 실패 사유는 처리 영역 머리글이 보인다. 창이 필요한 동작(구현 재시도·재배포·수정 요청)은 open으로 부모에게 열어 달라고 한다.
import { computed } from 'vue'
import { mrRef } from '~/composables/mergeRequestLabel'
import { closeSteps } from '~/composables/taskClose'
import { canRequestFix } from '~/composables/taskFix'
import InboxCloseSteps from '~/components/inbox/InboxCloseSteps.vue'
// defineProps 타입이므로 상대 경로
import type { TaskResponse } from '../../composables/taskDetailTypes'
import type { TaskActions } from '../../composables/useTaskActions'

const props = defineProps<{ task: TaskResponse; actions: TaskActions }>()
const emit = defineEmits<{
  (e: 'done'): void
  (e: 'open', kind: 'implRetry' | 'deployEnv' | 'fixRequest'): void
}>()

const auth = useAuthStore()

async function run(act: () => Promise<boolean>) {
  if (await act()) emit('done')
}

// 처리함은 관리자 화면이라 소유자 판정은 필요 없다(상세와 같은 함수)
const canFixRequest = computed(() => canRequestFix(props.task, auth.isAdmin, false))
const steps = computed(() => (props.task.close ? closeSteps(props.task.close, props.task.status) : []))
const pr = computed(() => props.task.implementation)
const deployUrl = computed(() => props.task.deployment?.deployUrl ?? null)
</script>

<template>
  <div class="failure column q-gutter-y-sm">
    <template v-if="task.status === 'FAILED' || task.status === 'DESIGN_FAILED'">
      <div class="row q-gutter-sm">
        <q-btn unelevated no-caps color="primary" icon="refresh" label="재시도" data-test="inbox-act-retry" @click="run(actions.retry)" />
      </div>
    </template>

    <template v-else-if="task.status === 'IMPLEMENTATION_FAILED'">
      <div v-if="pr?.prUrl" class="text-body2" data-test="inbox-pr-link">
        <a :href="pr.prUrl" target="_blank" rel="noopener">{{ mrRef(pr.prUrl, pr.prNumber!) }} 열기</a>
      </div>
      <div v-if="pr?.headBranch" class="text-body2" data-test="inbox-branch">
        브랜치: <code>{{ pr.headBranch }}</code>
        <span v-if="pr.headSha" class="text-caption q-ml-sm">@ {{ pr.headSha.slice(0, 7) }}</span>
      </div>
      <div class="row q-gutter-sm">
        <q-btn unelevated no-caps color="primary" icon="refresh" label="구현 재시도" data-test="inbox-act-impl-retry" @click="emit('open', 'implRetry')" />
        <q-btn v-if="actions.canAbort.value" outline no-caps color="grey-8" icon="stop_circle" :label="actions.abortLabel.value" data-test="inbox-act-abort-fix" @click="run(actions.abortFix)" />
      </div>
    </template>

    <template v-else-if="task.status === 'DEPLOY_FAILED' || task.status === 'DEPLOY_LOST'">
      <div v-if="task.status === 'DEPLOY_LOST' && deployUrl" class="text-body2" data-test="inbox-deploy-url">
        <span class="text-caption text-grey-7">접속 URL </span>
        <a :href="deployUrl" target="_blank" rel="noopener">{{ deployUrl }}</a>
      </div>
      <div class="row q-gutter-sm">
        <q-btn unelevated no-caps color="primary" icon="refresh" label="재배포" data-test="inbox-act-redeploy" @click="emit('open', 'deployEnv')" />
        <q-btn v-if="actions.canAdminRequestCloseLost.value" outline no-caps color="primary" icon="task_alt" label="종료 요청" data-test="inbox-act-request-close" @click="run(actions.requestClose)" />
        <q-btn v-if="task.status === 'DEPLOY_LOST'" outline no-caps color="negative" icon="stop" label="배포 중지" data-test="inbox-act-undeploy" @click="run(actions.undeploy)" />
        <q-btn v-if="canFixRequest" outline no-caps color="primary" icon="build" label="수정 요청" data-test="inbox-act-fix-request" @click="emit('open', 'fixRequest')" />
      </div>
    </template>

    <template v-else-if="task.status === 'CLOSE_FAILED'">
      <InboxCloseSteps :steps="steps" />
      <div class="row q-gutter-sm">
        <q-btn unelevated no-caps color="primary" icon="refresh" label="종료 재시도" data-test="inbox-act-retry-close" @click="run(actions.retryClose)" />
        <q-btn v-if="actions.canSyncBaseNow.value" outline no-caps color="primary" icon="merge" label="기준 브랜치 반영" data-test="inbox-act-sync-base" @click="run(actions.syncBase)" />
        <q-btn v-if="actions.canCancelClose.value" outline no-caps color="grey-8" icon="undo" label="종료 취소" data-test="inbox-act-cancel-close" @click="run(actions.cancelClose)" />
        <q-btn v-if="actions.canSkipArtifactClose.value" outline no-caps color="grey-8" icon="skip_next" label="산출물 없이 마무리" data-test="inbox-act-skip-artifact" @click="run(actions.skipArtifact)" />
        <q-btn v-if="actions.canSkipPatchClose.value" outline no-caps color="grey-8" icon="skip_next" label="패치 없이 마무리" data-test="inbox-act-skip-patch" @click="run(actions.skipPatch)" />
      </div>
    </template>
  </div>
</template>
