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

export interface ActivityLine {
  label: string
  detail?: string
}

/** SSE activity 누적 상태 — 확정 question 도착 전의 transient 진행 미리보기. */
export interface PendingActivity {
  activities: ActivityLine[]
  narration: string
  thinking: string
}

export interface InterviewSnapshot {
  statusName?: string | null
  turns?: Array<{ seq: number; role: string; kind: string; content: string }>
  plan?: { designMarkdown?: string; planMarkdown?: string; planJson?: unknown } | null
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
  const pending = ref<PendingActivity | null>(null)
  const error = ref<string | null>(null)

  let es: EventSource | null = null
  let sessionId: number | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let attempts = 0
  let basePath = '/api/interviews'

  // Literal `/api` prefix proxied through Nitro devProxy — identical convention to
  // pages/tasks/[id].vue's log stream and the useApi() REST wrapper. We deliberately do
  // NOT read runtimeConfig.public.apiBaseUrl (it may hold an absolute origin, which would
  // bypass the SSE proxy path).
  function streamUrl(id: number): string {
    return `${basePath}/${id}/stream?access_token=${encodeURIComponent(
      auth.accessToken as string,
    )}`
  }

  // apiBase: 질문 세션은 '/api/questions' (스펙 2026-08-30 §7). 재연결(onError→connect)에서도 유지되도록 클로저에 보관.
  function open(id: number, apiBase = '/api/interviews') {
    sessionId = id
    basePath = apiBase
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
    es.addEventListener('design', (e) => onDesign(e as MessageEvent))
    es.addEventListener('activity', (e) => onActivity(e as MessageEvent))
    es.addEventListener('plan_ready', (e) => onPlanReady(e as MessageEvent))
    es.addEventListener('done', () => close())
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
    const added = pushTurn({
      seq: data.seq,
      role: 'assistant',
      kind: 'question',
      content: data.content,
    })
    // 새로 도착한 질문일 때만 입력 대기로 전환 + 진행 미리보기 소거(확정 턴이 대체).
    // replay된(이미 있는 seq) 질문은 status도 pending도 건드리지 않는다.
    if (added) {
      status.value = 'AWAITING_INPUT'
      pending.value = null
    }
  }

  function onDesign(e: MessageEvent) {
    const data = parse(e)
    if (!data || !data.key) return
    upsertDesign({
      key: data.key,
      title: data.title ?? data.key,
      body: data.body ?? '',
      approved: !!data.approved,
    })
  }

  // key로 upsert (replay/hydrate 중복 방지). onDesign과 hydrate가 공유.
  function upsertDesign(section: DesignSection) {
    const idx = designSections.value.findIndex((d) => d.key === section.key)
    if (idx >= 0) {
      const next = designSections.value.slice()
      next[idx] = section
      designSections.value = next
    } else {
      designSections.value = [...designSections.value, section]
    }
  }

  const MAX_ACTIVITY_LINES = 30

  // activity 페이로드 = {events:[{seq,type,label,detail,content}]} — transient 진행 미리보기.
  // 확정 question/plan_ready/터미널이 도착하면 pending은 소거되고 확정 턴이 대체한다.
  function onActivity(e: MessageEvent) {
    const data = parse(e)
    if (!data || !Array.isArray(data.events)) return
    const p: PendingActivity = pending.value ?? { activities: [], narration: '', thinking: '' }
    for (const ev of data.events) {
      if (ev?.type === 'tool' && typeof ev.label === 'string') {
        p.activities.push({
          label: ev.label,
          detail: typeof ev.detail === 'string' ? ev.detail : undefined,
        })
        if (p.activities.length > MAX_ACTIVITY_LINES) p.activities.shift()
      } else if (ev?.type === 'text' && typeof ev.content === 'string') {
        p.narration += ev.content
      } else if (ev?.type === 'thinking' && typeof ev.content === 'string') {
        p.thinking += ev.content
      }
    }
    pending.value = { ...p } // 새 객체 할당으로 watch 트리거 보장
  }

  // plan_ready 이벤트와 REST 스냅샷의 plan은 동일 형태({designMarkdown,planMarkdown,planJson})다.
  // 두 경로가 드리프트하지 않도록 파싱을 공유한다. planJson은 JSON 문자열일 수 있다(SDK가 JSON.stringify(array)).
  function toPlan(raw: { designMarkdown?: string; planMarkdown?: string; planJson?: unknown }): InterviewPlan {
    let parsedPlanJson: unknown = null
    try {
      parsedPlanJson =
        typeof raw.planJson === 'string' ? JSON.parse(raw.planJson) : (raw.planJson ?? null)
    } catch {
      parsedPlanJson = null // guard against malformed JSON in planJson
    }
    return {
      designMarkdown: raw.designMarkdown ?? '',
      planMarkdown: raw.planMarkdown ?? '',
      planJson: parsedPlanJson,
    }
  }

  function onPlanReady(e: MessageEvent) {
    // plan_ready 데이터는 {designMarkdown, planMarkdown, planJson} JSON 객체이며 planJson은
    // 그 자체로 JSON 문자열이다. toPlan이 외부 객체+내부 planJson 문자열을 함께 처리한다.
    const obj = parse(e)
    if (!obj) return
    plan.value = toPlan(obj)
    status.value = 'PLAN_READY'
    pending.value = null
  }

  // REST 스냅샷(GET /api/interviews/{id})으로 상태 시드 — 새로고침 후 대화 복원.
  // kind==='design' → designSections(key=design-{seq}, 백엔드 DesignEvent와 동일)
  // role==='user'   → turns(user/answer) / 그 외 → turns(assistant/question)
  // pushTurn이 seq로 dedup하므로 직후 SSE replay와 안전하게 병합된다.
  function hydrate(snapshot: InterviewSnapshot | null | undefined) {
    if (!snapshot) return
    const name = (snapshot.statusName ?? '') as InterviewStatus
    if (KNOWN_STATUSES.includes(name)) status.value = name
    for (const t of snapshot.turns ?? []) {
      if (t.kind === 'design') {
        // 백엔드 SSE replay(InterviewStreamService)와 동일한 DesignEvent 형태로 시드:
        // key=design-{seq}, title='설계', approved=false. 직후 open() replay가 같은 값으로 upsert하므로
        // 정보 손실 없음(백엔드는 design 턴에 별도 title/approved를 저장하지 않는다).
        upsertDesign({ key: `design-${t.seq}`, title: '설계', body: t.content, approved: false })
      } else if (t.role === 'user') {
        pushTurn({ seq: t.seq, role: 'user', kind: 'answer', content: t.content })
      } else {
        pushTurn({ seq: t.seq, role: 'assistant', kind: 'question', content: t.content })
      }
    }
    if (snapshot.plan) {
      plan.value = toPlan(snapshot.plan)
    }
  }

  // Dedup by seq so SSE replay-on-reconnect does not duplicate turns.
  // Returns true if a new turn was appended, false if it was a duplicate (already-present seq).
  function pushTurn(t: Turn): boolean {
    if (turns.value.some((x) => x.seq === t.seq)) return false
    turns.value = [...turns.value, t].sort((a, b) => a.seq - b.seq)
    return true
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
    pending.value = null
    connState.value = 'closed'
  }

  onUnmounted(() => close())

  return {
    connState,
    status,
    turns,
    designSections,
    plan,
    pending,
    error,
    open,
    close,
    hydrate,
  }
}
