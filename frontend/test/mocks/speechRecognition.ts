// Web Speech API SpeechRecognition 최소 더블 (FakeEventSource 선례).
// 컴포저블이 쓰는 멤버만 흉내낸다: lang/continuous/interimResults, start/stop/abort, onresult/onerror/onend.
// 실제 브라우저처럼 stop()/abort()는 end 이벤트를 동기로 내지 않는다 — 테스트가 triggerEnd()로 명시한다.

interface FakeAlternative {
  transcript: string
  confidence: number
}

type FakeResult = FakeAlternative[] & { isFinal: boolean }

export class FakeSpeechRecognition {
  static instances: FakeSpeechRecognition[] = []
  lang = ''
  continuous = false
  interimResults = false
  maxAlternatives = 1
  started = false
  stopCalls = 0
  abortCalls = 0
  onresult:
    | ((ev: { resultIndex: number; results: FakeResult[] }) => void)
    | null = null
  onerror: ((ev: { error: string; message: string }) => void) | null = null
  onend: (() => void) | null = null

  constructor() {
    FakeSpeechRecognition.instances.push(this)
  }

  start() {
    if (this.started)
      throw new DOMException('recognition already started', 'InvalidStateError')
    this.started = true
  }
  stop() {
    this.stopCalls++
  }
  abort() {
    this.abortCalls++
  }

  // ── 테스트 헬퍼 ──
  /** 세션 누적 결과 목록을 통째로 전달한다 (Chrome continuous 모드처럼 results는 누적본). */
  emitResults(items: Array<{ text: string; final: boolean }>) {
    const results = items.map((it) =>
      Object.assign([{ transcript: it.text, confidence: 0.9 }], {
        isFinal: it.final,
      }),
    ) as FakeResult[]
    this.onresult?.({ resultIndex: 0, results })
  }
  triggerError(code: string) {
    this.onerror?.({ error: code, message: '' })
  }
  triggerEnd() {
    this.started = false
    this.onend?.()
  }

  static install(
    name: 'SpeechRecognition' | 'webkitSpeechRecognition' = 'SpeechRecognition',
  ) {
    ;(globalThis as Record<string, unknown>)[name] = FakeSpeechRecognition
  }
  static uninstall() {
    delete (globalThis as Record<string, unknown>).SpeechRecognition
    delete (globalThis as Record<string, unknown>).webkitSpeechRecognition
  }
  static reset() {
    FakeSpeechRecognition.instances = []
  }
  static last(): FakeSpeechRecognition {
    return FakeSpeechRecognition.instances[
      FakeSpeechRecognition.instances.length - 1
    ]
  }
}
