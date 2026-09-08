// 질문 탭 음성 입력 — Web Speech API(SpeechRecognition) 얇은 래퍼.
// 받아쓰기만 한다: 인식 텍스트는 콜백으로 넘기고, 전송은 사용자가 확인·수정한 뒤 직접 한다(자동 전송 없음).
// - 지원 여부는 마운트 후 판정한다. SSR HTML과 첫 클라이언트 렌더가 같아야 하이드레이션 불일치가 없다.
// - Chrome/Android는 SpeechRecognition, Safari(iOS 14.5+)는 webkitSpeechRecognition 프리픽스. Firefox·iOS 홈화면 PWA는 미지원.
// - continuous+interimResults: 사용자가 버튼으로 멈출 때까지 듣고 중간 결과를 실시간으로 넘긴다. 브라우저가 침묵
//   타임아웃으로 스스로 끝내면 onEnd만 부르고 자동 재시작하지 않는다(iOS Safari의 continuous 불안정 대비).
// - TS lib.dom에는 SpeechRecognition 타입이 없어 쓰는 멤버만 여기서 선언한다.

export interface SpeechTranscript {
  /** 이번 듣기 세션에서 확정된 텍스트 전체(결과 사이는 공백) */
  final: string
  /** 아직 확정되지 않은 중간 텍스트 */
  interim: string
}

export interface UseSpeechInputOptions {
  /** 기본 ko-KR */
  lang?: string
  /** result 이벤트마다 누적 목록 전체를 다시 계산해 넘긴다 */
  onTranscript: (t: SpeechTranscript) => void
  /** 인식기가 끝났을 때 — 사용자 stop 후, 브라우저 침묵 타임아웃, 오류 뒤 */
  onEnd?: () => void
  /** SpeechRecognitionErrorEvent.error 코드 — not-allowed · no-speech · network · aborted · audio-capture … */
  onError?: (code: string) => void
}

interface RecognitionAlternativeLike {
  transcript: string
}
interface RecognitionResultLike {
  isFinal: boolean
  length: number
  [index: number]: RecognitionAlternativeLike
}
interface RecognitionResultListLike {
  length: number
  [index: number]: RecognitionResultLike
}
interface RecognitionResultEventLike {
  results: RecognitionResultListLike
}
interface RecognitionErrorEventLike {
  error: string
}
export interface SpeechRecognitionLike {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((ev: RecognitionResultEventLike) => void) | null
  onerror: ((ev: RecognitionErrorEventLike) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}
type SpeechRecognitionCtor = new () => SpeechRecognitionLike

function resolveCtor(): SpeechRecognitionCtor | null {
  const g = globalThis as unknown as {
    SpeechRecognition?: SpeechRecognitionCtor
    webkitSpeechRecognition?: SpeechRecognitionCtor
  }
  return g.SpeechRecognition ?? g.webkitSpeechRecognition ?? null
}

/** 누적 결과 목록에서 확정/중간 텍스트를 분리한다. Chrome은 후속 결과에 선행 공백을 붙이므로 각 조각을 trim한다. */
function collect(results: RecognitionResultListLike): SpeechTranscript {
  const finals: string[] = []
  const interims: string[] = []
  for (let i = 0; i < results.length; i++) {
    const r = results[i]
    const t = (r?.[0]?.transcript ?? '').trim()
    if (!t) continue
    ;(r.isFinal ? finals : interims).push(t)
  }
  return { final: finals.join(' '), interim: interims.join(' ') }
}

export function useSpeechInput(opts: UseSpeechInputOptions) {
  const supported = ref(false)
  const listening = ref(false)
  let rec: SpeechRecognitionLike | null = null

  onMounted(() => {
    supported.value = resolveCtor() != null
  })

  /** 핸들러를 떼고 인스턴스를 버린다 — 뒤늦게 오는 end/result가 새 상태를 건드리지 않게 */
  function detach() {
    if (!rec) return
    rec.onresult = null
    rec.onerror = null
    rec.onend = null
    rec = null
  }

  function start() {
    if (listening.value) return
    const Ctor = resolveCtor()
    if (!Ctor) return
    const r = new Ctor()
    r.lang = opts.lang ?? 'ko-KR'
    r.continuous = true
    r.interimResults = true
    r.onresult = (ev) => opts.onTranscript(collect(ev.results))
    r.onerror = (ev) => {
      listening.value = false
      opts.onError?.(ev.error)
    }
    r.onend = () => {
      listening.value = false
      detach()
      opts.onEnd?.()
    }
    rec = r
    listening.value = true
    try {
      r.start()
    } catch {
      // 이미 시작된 인식기 등 InvalidStateError — 듣기 상태로 남기지 않는다
      listening.value = false
      detach()
    }
  }

  /** 사용자 중지 — 브라우저가 마지막 결과를 확정한 뒤 end 이벤트를 낸다 */
  function stop() {
    rec?.stop()
  }

  /** 즉시 중단(입력 잠김·언마운트) — 이후 이벤트는 무시 */
  function abort() {
    const r = rec
    detach()
    listening.value = false
    r?.abort()
  }

  onUnmounted(abort)

  return { supported, listening, start, stop, abort }
}
