// 배포된 앱 로그 SSE 상태 기계 (스펙 2026-11-03 §5 재연결). 표시는 AppLogCard, 순수 규칙은 appLog.ts.
// shallowRef·onBeforeUnmount는 vitest 전역(test/setup.ts)에 없어 'vue'에서 직접 가져온다.
import { ref, shallowRef, onMounted, onBeforeUnmount, type Ref, type ShallowRef } from 'vue'
import {
  APP_LOG_MAX_LINES,
  appendLines,
  appLogStreamUrl,
  type AppLogViewLine,
  type AppLogWireLine,
} from './appLog'

export type AppLogConnState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'ended'

/** `container-stopped` 뒤 다시 연결 — 이번 마운트에서 이 횟수까지, 이 간격 뒤(스펙 §5 "몇 초 뒤 한두 번만"). */
const CONTAINER_RESTART_MAX = 2
const CONTAINER_RESTART_DELAY_MS = 3_000
/** 이벤트 없이 끊긴 onerror 뒤 다시 연결 — 이 횟수까지 지수 백오프(useInterviewStream 선례), 다음 실패는 끝남(스펙 §5 "몇 번까지만"). */
const ERROR_RETRY_MAX = 4
const ERROR_RETRY_CAP_MS = 15_000

export interface AppLogStream {
  state: Ref<AppLogConnState>
  /** `ended`일 때의 사유 — 서버 reason 또는 클라이언트 전용 `connection-lost`·`auth`. */
  endReason: Ref<string | null>
  /** `ended`일 때 서버 `message`. 클라이언트 전용 사유면 null(문구는 appLogEndText). */
  endMessage: Ref<string | null>
  /** 지금 연결이 보낸 마지막 `info` 문장. 줄 목록에는 넣지 않는다. */
  infoMessage: Ref<string | null>
  lines: ShallowRef<AppLogViewLine[]>
  paused: Ref<boolean>
  /** 멈춘 동안 모아 둔 줄 수(상한 APP_LOG_MAX_LINES). */
  pendingCount: Ref<number>
  /** 사용자 연결(카드 마운트·"다시 연결") — 오류 재시도 횟수를 처음으로 되돌린다. */
  connect(): void
  /** 연결과 대기 중인 재연결을 끊고 `idle`. */
  close(): void
  pause(): void
  resume(): void
  /** 줄과 대기 버퍼만 비운다 — 다시 연결 때 걸러 낼 기준(cutoff)은 그대로라 지운 줄이 다시 나오지 않는다. */
  clear(): void
}

/**
 * 앱 로그 스트림 하나(카드 하나)의 연결 상태 기계. `taskId`·`viewable`·`token`은 연결할 때마다 새로 읽는다 —
 * JWT는 갱신되지 않아(스토어에 갱신 액션이 없다) 30분 상한 뒤 예전 토큰으로 다시 붙으면 401이 나기 때문이다.
 * 컴포넌트 setup 안에서 불러야 한다(visibilitychange 리스너를 onMounted에서 붙이고 onBeforeUnmount에서 모두 정리).
 */
export function useAppLogStream(opts: {
  taskId: () => number | string
  viewable: () => boolean
  token: () => string | null
}): AppLogStream {
  const state = ref<AppLogConnState>('idle')
  const endReason = ref<string | null>(null)
  const endMessage = ref<string | null>(null)
  const infoMessage = ref<string | null>(null)
  const lines = shallowRef<AppLogViewLine[]>([])
  const paused = ref(false)
  const pendingCount = ref(0)

  let es: EventSource | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  /** 숨은 탭에서 `time-limit`을 맞아 보이게 되기를 기다리는 중. */
  let waitingVisible = false
  let errorAttempts = 0
  let containerRestarts = 0
  let nextSeq = 0
  /** 지금까지 받은 ts의 최댓값 — 다음 연결의 cutoff가 된다. */
  let maxSeenTs: string | null = null
  /** 지금 연결의 cutoff — 연결할 때 고정하고 연결 중에는 바꾸지 않는다. */
  let cutoff: string | null = null
  /** 멈춘 동안 받은 줄 — 받을 때 그 연결의 cutoff로 이미 거른 것. */
  let pending: AppLogWireLine[] = []

  function stop() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    waitingVisible = false
    if (es) {
      es.close()
      es = null
    }
  }

  function end(reason: string | null, message: string | null) {
    stop()
    endReason.value = reason
    endMessage.value = message
    state.value = 'ended'
  }

  function schedule(delayMs: number) {
    state.value = 'reconnecting'
    timer = setTimeout(() => {
      timer = null
      open(true)
    }, delayMs)
  }

  /**
   * 내부 연결. 이전 연결·타이머를 먼저 끊는다. 허용 상태가 아니면 `idle`, 토큰이 없으면 `ended(auth)`.
   * `reconnect`면 열릴 때까지 `reconnecting`으로 둔다(사용자 연결은 `connecting`).
   */
  function open(reconnect: boolean) {
    stop()
    endReason.value = null
    endMessage.value = null
    if (!opts.viewable()) {
      state.value = 'idle'
      return
    }
    const token = opts.token()
    if (!token) {
      end('auth', null)
      return
    }
    // stdout·stderr를 두 스레드가 읽어 받은 순서가 ts 순서가 아니다 — 마지막 ts가 아니라 최댓값을, 연결 동안 고정해서 쓴다.
    cutoff = maxSeenTs
    infoMessage.value = null
    state.value = reconnect ? 'reconnecting' : 'connecting'
    const source = new EventSource(appLogStreamUrl(opts.taskId(), token))
    es = source
    // 오류 재시도 횟수는 여기서 되돌리지 않는다 — 200을 받자마자 끊기는 연결이 백오프 상한 없이 되풀이되지 않게(판정 [T9]).
    source.onopen = () => {
      if (source === es) state.value = 'open'
    }
    source.addEventListener('lines', (e) => {
      if (source !== es) return
      const incoming = parseLines((e as MessageEvent).data)
      if (incoming == null) return
      errorAttempts = 0
      receive(incoming)
    })
    source.addEventListener('info', (e) => {
      if (source !== es) return
      const message = str(parseObject((e as MessageEvent).data)?.message)
      if (message != null) infoMessage.value = message
    })
    source.addEventListener('end', (e) => {
      source.close()
      if (source !== es) return
      es = null
      const data = parseObject((e as MessageEvent).data)
      onEnd(str(data?.reason), str(data?.message))
    })
    source.onerror = () => {
      source.close()
      if (source !== es) return
      es = null
      errorAttempts += 1
      if (errorAttempts > ERROR_RETRY_MAX) {
        end('connection-lost', null)
        return
      }
      schedule(Math.min(1000 * 2 ** (errorAttempts - 1), ERROR_RETRY_CAP_MS))
    }
  }

  function onEnd(reason: string | null, message: string | null) {
    if (reason === 'time-limit') {
      // 잊고 열어 둔 숨은 탭이 docker·ssh를 끝없이 붙잡지 않게, 보일 때만 다시 연결한다.
      if (document.visibilityState === 'visible') {
        open(true)
      } else {
        state.value = 'reconnecting'
        waitingVisible = true
      }
      return
    }
    if (reason === 'container-stopped' && opts.viewable() && containerRestarts < CONTAINER_RESTART_MAX) {
      // 컨테이너는 --restart unless-stopped라 기동하다 죽기를 되풀이할 수 있다 — tail에 남은 기동 오류를 다시 보여 준다.
      containerRestarts += 1
      schedule(CONTAINER_RESTART_DELAY_MS)
      return
    }
    // too-many를 포함한 그 밖의 end는 멈추고 사유를 보인다(판정 [T9] — 설계 §5 "그 밖의 end").
    end(reason, message)
  }

  function receive(incoming: AppLogWireLine[]) {
    for (const l of incoming) {
      if (maxSeenTs == null || l.ts > maxSeenTs) maxSeenTs = l.ts
    }
    if (paused.value) {
      const fresh = cutoff == null ? incoming : incoming.filter((l) => l.ts > (cutoff as string))
      if (fresh.length === 0) return
      pending = pending.concat(fresh)
      if (pending.length > APP_LOG_MAX_LINES) pending = pending.slice(-APP_LOG_MAX_LINES)
      pendingCount.value = pending.length
      return
    }
    append(incoming, cutoff)
  }

  function append(incoming: AppLogWireLine[], cut: string | null) {
    const r = appendLines(lines.value, incoming, cut, nextSeq)
    nextSeq = r.nextSeq
    if (r.lines !== lines.value) lines.value = r.lines
  }

  function onVisibilityChange() {
    if (!waitingVisible || document.visibilityState !== 'visible') return
    waitingVisible = false
    open(true)
  }

  onMounted(() => document.addEventListener('visibilitychange', onVisibilityChange))
  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', onVisibilityChange)
    stop()
  })

  return {
    state,
    endReason,
    endMessage,
    infoMessage,
    lines,
    paused,
    pendingCount,
    connect() {
      errorAttempts = 0
      open(false)
    },
    close() {
      stop()
      endReason.value = null
      endMessage.value = null
      state.value = 'idle'
    },
    pause() {
      paused.value = true
    },
    resume() {
      paused.value = false
      // 대기 버퍼는 받을 때 이미 걸렀다 — 그 사이 다시 연결돼 cutoff가 올라갔어도 다시 거르지 않는다.
      if (pending.length > 0) append(pending, null)
      pending = []
      pendingCount.value = 0
    },
    clear() {
      lines.value = []
      pending = []
      pendingCount.value = 0
    },
  }
}

function parseObject(data: unknown): Record<string, unknown> | null {
  if (typeof data !== 'string') return null
  try {
    const v: unknown = JSON.parse(data)
    return v != null && typeof v === 'object' ? (v as Record<string, unknown>) : null
  } catch {
    return null
  }
}

const str = (v: unknown): string | null => (typeof v === 'string' ? v : null)

/** `lines` 이벤트 본문 `{lines: [{ts, stream, text}]}` — 모양이 아니면 null(이벤트를 버린다). */
function parseLines(data: unknown): AppLogWireLine[] | null {
  const raw = parseObject(data)?.lines
  return Array.isArray(raw) ? (raw as AppLogWireLine[]) : null
}
