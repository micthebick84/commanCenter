<script setup lang="ts">
// 배포된 앱 로그 카드 (스펙 2026-11-03 §5) — 열면 최근 로그가 나오고 새 줄이 계속 붙는다. 부모가 펼칠 때만 마운트하고(마운트 = 연결),
// 접거나 떠나면 언마운트로 닫는다(useAppLogStream이 정리). 연결·재연결 규칙은 useAppLogStream, 순수 규칙은 appLog.ts.
// 줄 본문은 로그 앱이 찍은 임의 문자열이라 `{{ }}` 보간으로만 그린다 — HTML을 그대로 넣는 디렉티브는 쓰지 않는다(AppLogCard.spec.ts가 소스를 읽어 가드).
import { useQuasar } from 'quasar'
import { APP_LOG_RENDER_LINES, appLogEndText, appLogFileName, appLogText, formatLogTime } from '~/composables/appLog'
import { saveBlob } from '~/composables/saveBlob'
import { useAppLogStream } from '~/composables/useAppLogStream'

const props = defineProps<{ taskId: number; viewable: boolean }>()
const $q = useQuasar()
const auth = useAuthStore()

const { state, endReason, endMessage, infoMessage, lines, paused, pendingCount, connect, pause, resume, clear } =
  useAppLogStream({
    taskId: () => props.taskId,
    viewable: () => props.viewable,
    // 연결할 때마다 새로 읽는다 — 30분 상한 뒤 다시 붙을 때 예전 토큰을 들고 있으면 401이다(스토어에 갱신 액션이 없다).
    token: () => auth.accessToken,
  })

onMounted(() => connect())

const statusText = computed(() => {
  switch (state.value) {
    case 'connecting':
      return '연결 중'
    case 'open':
      return '연결됨'
    case 'reconnecting':
      return '다시 연결 중'
    case 'ended':
      return `끝남 — ${appLogEndText(endReason.value, endMessage.value)}`
    default:
      return '연결 안 됨'
  }
})

// "오류 줄만" — 걸러 보는 중에도 새 줄은 계속 받는다(끄면 전체가 그대로 있다). 복사·내려받기는 이 걸러 낸 줄 전체(버퍼 5000줄까지)를 쓴다.
const errorsOnly = ref(false)
const visibleLines = computed(() => (errorsOnly.value ? lines.value.filter((l) => l.level === 'error') : lines.value))
// 화면(DOM)에는 걸러 낸 줄의 끝 APP_LOG_RENDER_LINES줄만 그린다 — 5000행을 300ms 묶음마다 다시 그리면 폰에서 끊긴다(판정 T10).
// 그리지 않은 앞 줄은 복사·내려받기에 들어 있고, 맨 위 안내(olderCount)가 그 수를 알린다.
const renderedLines = computed(() => visibleLines.value.slice(-APP_LOG_RENDER_LINES))
const olderCount = computed(() => Math.max(0, visibleLines.value.length - APP_LOG_RENDER_LINES))
const emptyText = computed(() => {
  if (lines.value.length > 0) return '오류 줄이 없습니다'
  return state.value === 'ended' ? '받은 로그가 없습니다' : '로그 대기 중…'
})

// 따라가기 — useAutoScroll의 smooth 스크롤은 쓰지 않는다. 300ms마다 줄이 붙는데 애니메이션 중의 scroll 이벤트가
// "바닥에서 멀다"로 판정돼 따라가기가 꺼진다(composables/useAutoScroll.ts). 그래서 바로 내리고, 끄는 것은 사용자가 위로 올렸을 때뿐이다.
const NEAR_BOTTOM_PX = 80
const follow = ref(true)
const scrollEl = ref<HTMLElement | null>(null)

function scrollToBottom() {
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
}

function setFollow(on: boolean) {
  follow.value = on
  if (on) nextTick(scrollToBottom)
}

function onScroll() {
  const el = scrollEl.value
  if (!el) return
  if (el.scrollHeight - el.scrollTop - el.clientHeight > NEAR_BOTTOM_PX) follow.value = false
}

watch(visibleLines, () => {
  if (follow.value) nextTick(scrollToBottom)
})

const copyUnsupported = () => $q.notify({ type: 'warning', message: '이 브라우저에서는 복사를 지원하지 않습니다' })

// LAN의 http 주소에서는 navigator.clipboard가 없어 경고로 빠진다(MarkdownViewerDialog와 같은 처리).
async function copy() {
  const text = appLogText(visibleLines.value)
  if (!navigator.clipboard?.writeText) {
    copyUnsupported()
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    $q.notify({ type: 'positive', message: '복사했습니다' })
  } catch {
    copyUnsupported()
  }
}

function download() {
  const blob = new Blob([appLogText(visibleLines.value)], { type: 'text/plain;charset=utf-8' })
  saveBlob(blob, appLogFileName(props.taskId, new Date()))
}
</script>

<template>
  <div class="app-log-card" data-test="app-log-card">
    <div class="app-log-bar">
      <div class="app-log-status" role="status" data-test="app-log-status">
        <span class="app-log-dot" :class="`app-log-dot--${state}`" aria-hidden="true" />
        <span>{{ statusText }}</span>
      </div>
      <q-btn
        v-if="state === 'ended'"
        class="app-log-btn"
        data-test="app-log-reconnect"
        dense
        no-caps
        size="sm"
        color="primary"
        icon="refresh"
        label="다시 연결"
        @click="connect()"
      />
    </div>

    <div class="app-log-tools">
      <q-btn
        class="app-log-btn"
        data-test="app-log-follow"
        dense
        no-caps
        size="sm"
        icon="vertical_align_bottom"
        label="따라가기"
        :color="follow ? 'primary' : 'grey-8'"
        :outline="!follow"
        :unelevated="follow"
        :aria-pressed="follow ? 'true' : 'false'"
        @click="setFollow(!follow)"
      />
      <q-btn
        class="app-log-btn"
        data-test="app-log-pause"
        dense
        no-caps
        size="sm"
        :icon="paused ? 'play_arrow' : 'pause'"
        :label="paused ? (pendingCount > 0 ? `재개 (${pendingCount})` : '재개') : '멈춤'"
        :color="paused ? 'primary' : 'grey-8'"
        :outline="!paused"
        :unelevated="paused"
        :aria-pressed="paused ? 'true' : 'false'"
        @click="paused ? resume() : pause()"
      />
      <q-btn
        class="app-log-btn"
        data-test="app-log-errors-only"
        dense
        no-caps
        size="sm"
        icon="error_outline"
        label="오류 줄만"
        :color="errorsOnly ? 'negative' : 'grey-8'"
        :outline="!errorsOnly"
        :unelevated="errorsOnly"
        :aria-pressed="errorsOnly ? 'true' : 'false'"
        @click="errorsOnly = !errorsOnly"
      />
      <q-btn
        class="app-log-btn"
        data-test="app-log-clear"
        dense
        no-caps
        size="sm"
        outline
        color="grey-8"
        icon="delete_sweep"
        label="지우기"
        @click="clear()"
      />
      <q-btn
        class="app-log-btn"
        data-test="app-log-copy"
        dense
        no-caps
        size="sm"
        outline
        color="grey-8"
        icon="content_copy"
        label="복사"
        :disable="visibleLines.length === 0"
        @click="copy()"
      />
      <q-btn
        class="app-log-btn"
        data-test="app-log-download"
        dense
        no-caps
        size="sm"
        outline
        color="grey-8"
        icon="download"
        label="내려받기"
        :disable="visibleLines.length === 0"
        @click="download()"
      />
    </div>

    <div v-if="infoMessage" class="app-log-info" data-test="app-log-info">{{ infoMessage }}</div>

    <div ref="scrollEl" class="app-log-lines" tabindex="0" data-test="app-log-lines" @scroll="onScroll">
      <div v-if="olderCount > 0" class="app-log-older" data-test="app-log-older">
        앞의 {{ olderCount }}줄은 화면에 그리지 않습니다 — 복사·내려받기에는 들어 있습니다
      </div>
      <div v-if="visibleLines.length === 0" class="app-log-empty">{{ emptyText }}</div>
      <div
        v-for="l in renderedLines"
        :key="l.seq"
        class="app-log-line"
        :class="{
          'app-log-line--error': l.level === 'error',
          'app-log-line--warn': l.level === 'warn',
          'app-log-line--err': l.stream === 'err',
        }"
        :title="l.stream === 'err' ? 'stderr' : undefined"
        data-test="app-log-line"
      >
        <span class="app-log-line__time">[{{ formatLogTime(l.ts) }}]</span> <span class="app-log-line__text">{{ l.text }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-log-card {
  min-width: 0;
}
.app-log-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 12px;
  margin-bottom: 6px;
}
.app-log-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 0.875rem;
  overflow-wrap: anywhere;
}
.app-log-dot {
  flex: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #9e9e9e;
}
.app-log-dot--open {
  background: #43a047;
}
.app-log-dot--connecting,
.app-log-dot--reconnecting {
  background: #fb8c00;
}
.app-log-dot--ended {
  background: #e53935;
}
/* 폰(390px)에서는 버튼 여섯 개가 한 줄에 안 들어가 줄바꿈한다 */
.app-log-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.app-log-btn {
  min-height: 36px;
}
.app-log-info {
  margin-bottom: 6px;
  color: #bf360c;
  font-size: 0.8125rem;
  overflow-wrap: anywhere;
}
/* 로그 영역 — 기존 빌드 로그(.deploy-log)와 같은 어두운 바탕, 안쪽 스크롤 */
.app-log-lines {
  max-height: 360px;
  overflow: auto;
  padding: 8px;
  border-radius: 4px;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace;
  font-size: 0.75rem;
  line-height: 1.45;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.app-log-empty {
  color: #8a8a8a;
}
/* 화면에 그리지 않은 앞 줄 안내 — 줄이 아니라 영역 맨 위의 한 줄 */
.app-log-older {
  margin-bottom: 4px;
  padding-bottom: 4px;
  border-bottom: 1px dashed #4a4a4a;
  color: #8a8a8a;
}
.app-log-line {
  padding-left: 6px;
  border-left: 3px solid transparent;
}
/* stderr 표식 — 왼쪽 띠 */
.app-log-line--err {
  border-left-color: #ef9a9a;
}
.app-log-line--warn {
  color: #ffcc80;
}
.app-log-line--error {
  background: rgba(244, 67, 54, 0.14);
  color: #ff8a80;
}
.app-log-line__time {
  color: #8a8a8a;
}
</style>
