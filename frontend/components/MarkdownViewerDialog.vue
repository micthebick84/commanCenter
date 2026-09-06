<script setup lang="ts">
// 전체화면 마크다운 뷰어 (스펙 2026-09-06 §4.2) — 긴 분석/디자인 문서를 상세 페이지 인라인 대신 여기서 읽는다.
import { useQuasar } from 'quasar'
import { renderMarkdown } from '~/composables/useMarkdown'
const props = defineProps<{ title: string; markdown: string | null }>()
const show = defineModel<boolean>({ default: false })
const $q = useQuasar()
const html = computed(() => renderMarkdown(props.markdown))

// 리뷰 파인딩 10: 뷰어에서 바로 원문을 복사할 수 있게 한다. 클립보드 API 미지원(비-HTTPS 등)
// 환경은 조용히 실패하지 않고 경고로 알린다.
async function copy() {
  if (!navigator.clipboard?.writeText) {
    $q.notify({ type: 'warning', message: '이 브라우저에서는 복사를 지원하지 않습니다' })
    return
  }
  try {
    await navigator.clipboard.writeText(props.markdown ?? '')
    $q.notify({ type: 'positive', message: '복사했습니다' })
  } catch {
    $q.notify({ type: 'warning', message: '이 브라우저에서는 복사를 지원하지 않습니다' })
  }
}
</script>

<template>
  <q-dialog v-model="show" maximized>
    <q-card class="viewer">
      <div class="row items-center no-wrap viewer-head">
        <q-btn v-close-popup flat round icon="close" aria-label="닫기" />
        <div class="text-subtitle1 text-weight-medium ellipsis col">{{ title }}</div>
        <q-btn flat round icon="content_copy" aria-label="복사" class="copy-btn" data-test="viewer-copy" @click="copy" />
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
.copy-btn { min-width: 44px; min-height: 44px; }
</style>
