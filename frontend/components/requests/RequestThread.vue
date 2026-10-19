<script setup lang="ts">
// 요청 답글 스레드. COMMENT는 말풍선(마크다운), SYSTEM은 회색 한 줄. 입력창은 POST /api/requests/{id}/comments.
import { useQuasar } from 'quasar'
import type { RequestComment } from '~/composables/requestBoard'
import { renderMarkdown } from '~/composables/useMarkdown'

const props = defineProps<{
  comments: RequestComment[]
  requestId: number
}>()
const emit = defineEmits<{
  posted: [comment: RequestComment]
}>()

const $q = useQuasar()
const draft = ref('')
const sending = ref(false)
const canSend = computed(() => !sending.value && !!draft.value.trim())

function fmt(iso: string) {
  return new Date(iso).toLocaleString()
}

async function send() {
  if (!canSend.value) return
  sending.value = true
  try {
    const posted = await useApi<RequestComment>(`/api/requests/${props.requestId}/comments`, {
      method: 'POST',
      body: { body: draft.value.trim() },
    })
    draft.value = ''
    emit('posted', posted)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '답글 등록 실패' })
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <div class="request-thread" data-test="request-thread">
    <div v-if="!comments.length" class="text-caption text-grey-7 q-mb-md">아직 답글이 없습니다</div>
    <template v-for="c in comments" :key="c.id">
      <div v-if="c.kind === 'SYSTEM'" class="system-line text-caption text-grey-7 text-center q-my-sm" data-test="system-line">
        {{ c.body }} · {{ fmt(c.createdAt) }}
      </div>
      <div v-else class="bubble-row q-mb-md" :class="{ 'bubble-row--admin': c.authorAdmin }" data-test="comment-bubble">
        <div class="row items-center no-wrap q-gutter-x-xs q-mb-xs">
          <span class="text-caption text-weight-medium">{{ c.authorId }}</span>
          <q-chip v-if="c.authorAdmin" dense size="sm" color="primary" text-color="white" label="관리자" class="q-ma-none" />
          <span class="text-caption text-grey-7">{{ fmt(c.createdAt) }}</span>
        </div>
        <div class="bubble-body md-scroll markdown" v-html="renderMarkdown(c.body)" />
      </div>
    </template>

    <div class="row items-end no-wrap q-gutter-x-sm q-mt-md">
      <q-input
        v-model="draft"
        type="textarea"
        outlined
        dense
        autogrow
        maxlength="5000"
        placeholder="답글을 입력하세요"
        class="col"
        data-test="reply-input"
      />
      <q-btn
        color="primary"
        unelevated
        label="전송"
        :loading="sending"
        :disable="!canSend"
        data-test="reply-send"
        @click="send"
      />
    </div>
  </div>
</template>

<style scoped>
.bubble-row {
  max-width: 100%;
}
.bubble-body {
  display: inline-block;
  max-width: 100%;
  padding: 8px 12px;
  border-radius: 12px;
  background: rgba(127, 127, 127, 0.12);
  overflow-wrap: anywhere;
}
.bubble-row--admin .bubble-body {
  background: rgba(25, 118, 210, 0.12);
}
</style>
