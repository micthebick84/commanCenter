<script setup lang="ts">
// 종료 단계 목록 — 작업 상세의 종료 카드와 같은 아이콘·색 규칙.
// 병합하지 않고 종료(스펙 2026-11-23 close-keep-branch)면 상세 종료 카드처럼 칩을 같이 보인다 — 병합·브랜치 삭제는 closeSteps가 '건너뜀'으로 준다.
import { KEEP_BRANCH_CHIP } from '~/composables/taskClose'

defineProps<{ steps: { key: string; label: string; state: string }[]; keepBranch?: boolean }>()
</script>

<template>
  <div v-if="steps.length" class="row items-center" style="gap: 12px" data-test="inbox-close-steps">
    <q-chip v-if="keepBranch" dense color="orange-2" text-color="orange-10" class="q-ma-none" data-test="inbox-close-keep-chip">{{ KEEP_BRANCH_CHIP }}</q-chip>
    <div v-for="s in steps" :key="s.key" class="row items-center" style="gap: 4px" :data-test="`inbox-close-step-${s.key}`">
      <q-icon
        :name="s.state === 'done' ? 'check_circle' : s.state === 'failed' ? 'error' : s.state === 'current' ? 'autorenew' : s.state === 'skipped' ? 'remove_circle_outline' : 'radio_button_unchecked'"
        :color="s.state === 'done' ? 'positive' : s.state === 'failed' ? 'negative' : s.state === 'current' ? 'orange' : s.state === 'skipped' ? 'grey-7' : 'grey-5'"
      />
      <span>{{ s.label }}<template v-if="s.state === 'skipped'"> (건너뜀)</template></span>
    </div>
  </div>
</template>
