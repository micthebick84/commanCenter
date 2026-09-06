<script setup lang="ts">
// 전체화면 마크다운 뷰어 (스펙 2026-09-06 §4.2) — 긴 분석/디자인 문서를 상세 페이지 인라인 대신 여기서 읽는다.
import { renderMarkdown } from '~/composables/useMarkdown'
const props = defineProps<{ title: string; markdown: string | null }>()
const show = defineModel<boolean>({ default: false })
const html = computed(() => renderMarkdown(props.markdown))
</script>

<template>
  <q-dialog v-model="show" maximized>
    <q-card class="viewer">
      <div class="row items-center no-wrap viewer-head">
        <q-btn v-close-popup flat round icon="close" aria-label="닫기" />
        <div class="text-subtitle1 text-weight-medium ellipsis">{{ title }}</div>
      </div>
      <q-separator />
      <q-card-section class="viewer-body"><div class="md-scroll markdown" data-test="markdown-viewer" v-html="html" /></q-card-section>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.viewer { display: flex; flex-direction: column; }
.viewer-head { min-height: 56px; padding: 0 8px; gap: 4px; }
.viewer-body { flex: 1; overflow: auto; }
</style>
