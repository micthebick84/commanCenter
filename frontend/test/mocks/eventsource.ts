// Minimal EventSource double for SSE composable tests.
// Mirrors the subset the composable uses: addEventListener('<name>'), onerror, onopen, close().
export const OPEN = 1
export const CLOSED = 2

export class FakeEventSource {
  static instances: FakeEventSource[] = []
  url: string
  readyState = 0
  onerror: ((e: Event) => void) | null = null
  onopen: ((e: Event) => void) | null = null
  private listeners: Record<string, ((e: MessageEvent) => void)[]> = {}

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, cb: (e: MessageEvent) => void) {
    ;(this.listeners[type] ??= []).push(cb)
  }

  // Test helpers
  emit(type: string, data: unknown) {
    const payload = typeof data === 'string' ? data : JSON.stringify(data)
    const ev = { data: payload } as MessageEvent
    for (const cb of this.listeners[type] ?? []) cb(ev)
  }
  triggerOpen() {
    this.readyState = OPEN
    this.onopen?.(new Event('open'))
  }
  triggerError() {
    this.onerror?.(new Event('error'))
  }
  close() {
    this.readyState = CLOSED
  }

  static reset() {
    FakeEventSource.instances = []
  }
  static last(): FakeEventSource {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1]
  }
}
