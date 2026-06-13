// SSE client for a conversational-analysis interview session.
// Mirrors the EventSource pattern in pages/tasks/[id].vue (auth via ?access_token=),
// adds reconnect + typed handlers for question/design/plan_ready/status/done.

export type InterviewStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'AWAITING_INPUT'
  | 'PLAN_READY'
  | 'REGISTERED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'FAILED'

export type ConnState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface Turn {
  seq: number
  role: 'assistant' | 'user' | 'system'
  kind: 'question' | 'answer' | 'design' | 'gate' | 'note'
  content: string
}

export interface DesignSection {
  key: string
  title: string
  body: string
  approved: boolean
}

export interface InterviewPlan {
  designMarkdown: string
  planMarkdown: string
  /** planJson arrives as a JSON string inside the plan_ready object; parsed to array on receipt. */
  planJson: unknown
}

const TERMINAL: InterviewStatus[] = ['REGISTERED', 'CANCELLED', 'EXPIRED', 'FAILED']

// Valid English enum names the server may push on the `status` event
// (InterviewStatus.name() — never the Korean dbValue).
const KNOWN_STATUSES: InterviewStatus[] = [
  'QUEUED',
  'RUNNING',
  'AWAITING_INPUT',
  'PLAN_READY',
  'REGISTERED',
  'CANCELLED',
  'EXPIRED',
  'FAILED',
]

export function useInterviewStream() {
  const auth = useAuthStore()

  const connState = ref<ConnState>('idle')
  const status = ref<InterviewStatus | null>(null)
  const turns = ref<Turn[]>([])
  const designSections = ref<DesignSection[]>([])
  const plan = ref<InterviewPlan | null>(null)
  const error = ref<string | null>(null)

  let es: EventSource | null = null
  let sessionId: number | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let attempts = 0

  // Literal `/api` prefix proxied through Nitro devProxy — identical convention to
  // pages/tasks/[id].vue's log stream and the useApi() REST wrapper. We deliberately do
  // NOT read runtimeConfig.public.apiBaseUrl (it may hold an absolute origin, which would
  // bypass the SSE proxy path).
  function streamUrl(id: number): string {
    return `/api/interviews/${id}/stream?access_token=${encodeURIComponent(
      auth.accessToken as string,
    )}`
  }

  function open(id: number) {
    sessionId = id
    error.value = null
    if (!auth.accessToken) {
      error.value = '인증 토큰이 없습니다'
      connState.value = 'closed'
      return
    }
    connect()
  }

  function connect() {
    teardown()
    connState.value = attempts === 0 ? 'connecting' : 'reconnecting'
    es = new EventSource(streamUrl(sessionId as number))
    es.onopen = () => {
      attempts = 0
      connState.value = 'open'
    }
    es.addEventListener('status', (e) => onStatus(e as MessageEvent))
    es.addEventListener('question', (e) => onQuestion(e as MessageEvent))
    es.onerror = () => onError()
  }

  // `status` payload is a BARE English enum-name string (InterviewStatus.name()),
  // e.g. "AWAITING_INPUT" / "PLAN_READY" / "EXPIRED" — NOT a JSON object, NOT the
  // Korean dbValue. Terminal detection keys off this English name.
  function onStatus(e: MessageEvent) {
    const name = (e.data ?? '').trim() as InterviewStatus
    if (!KNOWN_STATUSES.includes(name)) return
    status.value = name
    if (TERMINAL.includes(name)) close()
  }

  function onQuestion(e: MessageEvent) {
    const data = parse(e)
    if (!data) return
    pushTurn({
      seq: data.seq,
      role: 'assistant',
      kind: 'question',
      content: data.content,
    })
    status.value = 'AWAITING_INPUT'
  }

  // Dedup by seq so SSE replay-on-reconnect does not duplicate turns.
  function pushTurn(t: Turn) {
    if (turns.value.some((x) => x.seq === t.seq)) return
    turns.value = [...turns.value, t].sort((a, b) => a.seq - b.seq)
  }

  function onError() {
    if (!es) return
    connState.value = 'reconnecting'
    attempts += 1
    const delay = Math.min(1000 * 2 ** (attempts - 1), 15000)
    reconnectTimer = setTimeout(() => {
      if (sessionId != null) connect()
    }, delay)
  }

  function parse(e: MessageEvent): any | null {
    try {
      return JSON.parse(e.data)
    } catch {
      return null
    }
  }

  function teardown() {
    if (es) {
      es.close()
      es = null
    }
  }

  function close() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    teardown()
    sessionId = null
    connState.value = 'closed'
  }

  onUnmounted(() => close())

  return {
    connState,
    status,
    turns,
    designSections,
    plan,
    error,
    open,
    close,
  }
}
