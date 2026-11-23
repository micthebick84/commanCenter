<script setup lang="ts">
// 종료 단계 목록 — 작업 상세의 종료 카드와 같은 아이콘·색 규칙.
defineProps<{ steps: { key: string; label: string; state: string }[] }>()
</script>

<template>
  <div v-if="steps.length" class="row" style="gap: 12px" data-test="inbox-close-steps">
    <div v-for="s in steps" :key="s.key" class="row items-center" style="gap: 4px" :data-test="`inbox-close-step-${s.key}`">
      <q-icon
        :name="s.state === 'done' ? 'check_circle' : s.state === 'failed' ? 'error' : s.state === 'current' ? 'autorenew' : s.state === 'skipped' ? 'remove_circle_outline' : 'radio_button_unchecked'"
        :color="s.state === 'done' ? 'positive' : s.state === 'failed' ? 'negative' : s.state === 'current' ? 'orange' : s.state === 'skipped' ? 'grey-7' : 'grey-5'"
      />
      <span>{{ s.label }}<template v-if="s.state === 'skipped'"> (건너뜀)</template></span>
    </div>
  </div>
</template>
