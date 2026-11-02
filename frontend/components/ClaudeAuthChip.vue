<script setup lang="ts">
// 워커 헬스의 "Claude OAuth" 칸 — 칩(OK/N일 남음/만료/NG) + 방식·만료 시각 한 줄, 빨강·주황이면 툴팁에 할 일 (스펙 2026-11-02 §6).
// 표·모바일 카드가 같이 쓴다. 상태 판정은 composables/claudeAuth.ts.
import { claudeAuthView } from '../composables/claudeAuth'

const props = defineProps<{
  row: { claudeSessionOk: boolean | null; claudeAuthMethod?: string | null; claudeAuthExpiresAt?: string | null }
}>()

const view = computed(() => claudeAuthView(props.row))
const caption = computed(() => [view.value.methodLabel, view.value.expiresLabel].filter(Boolean).join(' · '))
</script>

<template>
  <span class="claude-auth" :data-level="view.level" data-test="claude-auth">
    <q-chip
      v-if="view.level !== 'unknown'"
      :color="view.color"
      :text-color="view.textColor"
      size="sm"
      dense
      :label="view.label"
      class="q-ma-none"
      data-test="claude-auth-chip"
    >
      <q-tooltip v-if="view.action" max-width="320px">{{ view.action }}</q-tooltip>
    </q-chip>
    <span v-else>—</span>
    <span v-if="caption" class="claude-auth-caption text-caption text-grey-7" data-test="claude-auth-caption">
      {{ caption }}
    </span>
  </span>
</template>

<style scoped>
.claude-auth {
  display: inline-flex;
  flex-direction: column;
  align-items: inherit;
  gap: 2px;
}
.claude-auth-caption {
  line-height: 1.2;
  white-space: nowrap;
}
</style>
