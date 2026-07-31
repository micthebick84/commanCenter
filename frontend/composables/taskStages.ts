// 작업 목록 카드뷰(1b 여유형)의 단계·상태·전이 정의.
// 디자인 문서: Claude Design 프로젝트 '작업 목록 카드뷰 변환' → `작업 목록 카드뷰.dc.html` 의 1b.
//
// ⚠️ 원본 목업의 STAGES에는 인터뷰 구간 3개(인터뷰중/입력대기/플랜승인대기)와 취소됨이 빠져 있었다.
//    그대로 옮기면 해당 상태의 작업이 화면에서 조용히 사라진다(목록에서 증발 = 데이터 손실급 버그).
//    → 인터뷰 3종은 '분석' 단계에 편입, 취소됨은 terminal 단계로 분리했다.
//    STATUS_STAGE_COVERAGE 테스트가 이 불변식을 지킨다.

export interface StageDef {
  key: string
  name: string
  color: string
  statuses: string[]
  emptyHint: string
  /** 파이프라인이 아닌 종료 상태 — 카드가 있을 때만 렌더한다 */
  terminal?: boolean
}

/** 단계 정의. statuses 순서가 카드뷰 안의 세부 그룹 순서가 된다. */
export const STAGES: StageDef[] = [
  {
    key: 'analysis',
    name: '분석',
    color: '#1565c0',
    statuses: [
      'AWAITING_APPROVAL',
      'INTERVIEWING',
      'INTERVIEW_INPUT',
      'INTERVIEW_REVIEW',
      'PENDING',
      'IN_PROGRESS',
      'COMPLETED',
      'FAILED',
    ],
    emptyHint: '작업 등록 시 이 단계에서 시작합니다',
  },
  {
    key: 'design',
    name: '디자인',
    color: '#4527a0',
    statuses: ['DESIGN_PENDING', 'DESIGNING', 'DESIGN_REVIEW', 'DESIGN_FAILED'],
    emptyHint: '디자인 요청 작업만 이 단계를 거칩니다',
  },
  {
    key: 'impl',
    name: '구현',
    color: '#00695c',
    statuses: ['APPROVED', 'IMPLEMENTING', 'PR_CREATED', 'IMPLEMENTATION_FAILED'],
    emptyHint: '플랜 확정 또는 디자인 승인 후 진입합니다',
  },
  {
    key: 'deploy',
    name: '배포',
    color: '#d84315',
    statuses: [
      'DEPLOY_PENDING',
      'DEPLOYING',
      'DEPLOYED',
      'DEPLOY_FAILED',
      'DEPLOY_LOST',
      'UNDEPLOY_PENDING',
      'UNDEPLOYING',
    ],
    emptyHint: 'PR생성 작업을 배포하면 진입합니다',
  },
  {
    key: 'closed',
    name: '취소',
    color: '#757575',
    statuses: ['CANCELLED'],
    emptyHint: '',
    terminal: true,
  },
]

/** 상태 → [배경, 글자색]. main.css 의 .status-* 토큰과 같은 계열. */
export const CHIP: Record<string, [string, string]> = {
  AWAITING_APPROVAL: ['#e3f2fd', '#1565c0'],
  // 인터뷰 구간: 초록 = 관리자 승인 대기, 앰버 = 사람 입력 대기
  INTERVIEWING: ['#e1f5fe', '#01579b'],
  INTERVIEW_INPUT: ['#fff8e1', '#ef6c00'],
  INTERVIEW_REVIEW: ['#e8f5e9', '#2e7d32'],
  PENDING: ['#e3f2fd', '#1565c0'],
  IN_PROGRESS: ['#fff8e1', '#ef6c00'],
  COMPLETED: ['#e8f5e9', '#2e7d32'],
  FAILED: ['#ffebee', '#c62828'],
  DESIGN_PENDING: ['#ede7f6', '#5e35b1'],
  DESIGNING: ['#fff8e1', '#ef6c00'],
  DESIGN_REVIEW: ['#e8f5e9', '#2e7d32'],
  DESIGN_FAILED: ['#fce4ec', '#ad1457'],
  APPROVED: ['#ede7f6', '#4527a0'],
  IMPLEMENTING: ['#e1f5fe', '#01579b'],
  PR_CREATED: ['#e0f2f1', '#00695c'],
  IMPLEMENTATION_FAILED: ['#fce4ec', '#ad1457'],
  DEPLOY_PENDING: ['#ede7f6', '#5e35b1'],
  DEPLOYING: ['#fff3e0', '#e65100'],
  DEPLOYED: ['#e0f2f1', '#00695c'],
  DEPLOY_FAILED: ['#fce4ec', '#ad1457'],
  DEPLOY_LOST: ['#fbe9e7', '#bf360c'],
  UNDEPLOY_PENDING: ['#fbe9e7', '#bf360c'],
  UNDEPLOYING: ['#fff3e0', '#e65100'],
  CANCELLED: ['#f5f5f5', '#616161'],
}

/**
 * 드래그로 허용되는 전이. 전부 ROLE_ADMIN 엔드포인트이므로 관리자에게만 드래그를 연다.
 *
 * 여기 없는 전이는 다이얼로그가 필요해서 뺐다:
 *   승인대기 → 인터뷰 시작   (모델/effort/MCP 선택 필요 → 작업 상세의 ApproveDialog)
 *   플랜승인대기 → 구현대기  (디자인 구간 포함 여부 선택 필요 → 인터뷰 패널의 '구현 진행')
 */
export interface MoveDef {
  /** 낙관적 드롭 타깃 판정용 상태 */
  to: string
  /** POST 할 경로 접미사 */
  path: string
  label: string
  /** 되돌리기 비싼 동작(배포 계열)은 확인 후 실행 */
  confirm: boolean
}

export const MOVES: Record<string, MoveDef> = {
  COMPLETED: { to: 'APPROVED', path: 'approve', label: '승인 — 구현 큐 진입', confirm: false },
  DESIGN_REVIEW: { to: 'APPROVED', path: 'design/approve', label: '디자인 승인', confirm: false },
  PR_CREATED: { to: 'DEPLOY_PENDING', path: 'deploy', label: '배포', confirm: true },
  DEPLOYED: { to: 'UNDEPLOY_PENDING', path: 'undeploy', label: '배포 중지', confirm: true },
  DEPLOY_FAILED: { to: 'DEPLOY_PENDING', path: 'redeploy', label: '재배포', confirm: true },
  DEPLOY_LOST: { to: 'DEPLOY_PENDING', path: 'redeploy', label: '재배포', confirm: true },
}

/** 디자인 구간을 요청한 작업의 분석완료 승인은 구현대기가 아니라 디자인대기로 간다. */
export function moveFor(task: { status: string; designRequested?: boolean }): MoveDef | null {
  const m = MOVES[task.status]
  if (!m) return null
  if (task.status === 'COMPLETED' && task.designRequested) {
    return { ...m, to: 'DESIGN_PENDING', label: '승인 — 디자인 큐 진입' }
  }
  return m
}

export function ageHours(iso: string, now = Date.now()): number {
  return (now - new Date(iso).getTime()) / 3600000
}

export function ageOf(iso: string, now = Date.now()): string {
  const h = ageHours(iso, now)
  if (h < 1) return `${Math.max(1, Math.round(h * 60))}분`
  if (h < 24) return `${Math.round(h)}시간`
  return `${Math.round(h / 24)}일`
}

/** 등록 후 경과를 60일 만점으로 환산한 막대 길이(%). */
export function agePct(iso: string, now = Date.now()): number {
  return Math.min(100, Math.round((ageHours(iso, now) / (60 * 24)) * 100))
}

export function ageColor(iso: string, now = Date.now()): string {
  const h = ageHours(iso, now)
  if (h > 24 * 30) return '#c62828'
  if (h > 24 * 7) return '#ef6c00'
  return '#43a047'
}

export interface StageCardTask {
  id: number
  status: string
  statusLabel: string
  createdAt: string
  designRequested?: boolean
}

export interface StageCard<T extends StageCardTask> {
  task: T
  bg: string
  fg: string
  age: string
  agePct: number
  ageColor: string
  /** 카드 안의 4단계 미니 진행바 (취소 단계는 제외) */
  steps: { label: string; bg: string; fg: string; weight: string }[]
  move: MoveDef | null
}

export interface StageView<T extends StageCardTask> {
  key: string
  name: string
  color: string
  count: number
  oldest: string
  summary: string
  pipeline: { bg: string }[]
  bottleneck: boolean
  isEmpty: boolean
  emptyHint: string
  terminal: boolean
  groups: { status: string; label: string; count: number; bg: string; fg: string; cards: StageCard<T>[] }[]
}

export interface BuildOptions {
  /** 드래그 가능 여부 — 전이 엔드포인트가 전부 관리자 전용 */
  isAdmin?: boolean
  /** 비어 있는 파이프라인 단계도 표시 */
  showEmptyStages?: boolean
  now?: number
}

/** 작업 목록을 단계 → 세부상태 그룹 → 카드로 접는다. */
export function buildStages<T extends StageCardTask>(
  tasks: T[],
  opts: BuildOptions = {},
): StageView<T>[] {
  const now = opts.now ?? Date.now()
  const showEmpty = opts.showEmptyStages ?? true
  const pipelineStages = STAGES.filter((s) => !s.terminal)

  const counts: Record<string, number> = {}
  tasks.forEach((t) => {
    counts[t.status] = (counts[t.status] ?? 0) + 1
  })
  const stageTotal = (s: StageDef) => s.statuses.reduce((n, st) => n + (counts[st] ?? 0), 0)
  const maxCount = Math.max(0, ...pipelineStages.map(stageTotal))

  return STAGES.filter((s) => {
    if (s.terminal) return stageTotal(s) > 0 // 종료 단계는 있을 때만
    return showEmpty || stageTotal(s) > 0
  }).map((s) => {
    const inStage = tasks
      .filter((t) => s.statuses.includes(t.status))
      .sort((a, b) => ageHours(b.createdAt, now) - ageHours(a.createdAt, now)) // 오래된 순

    const toCard = (t: T): StageCard<T> => {
      const chip = CHIP[t.status] ?? ['#f5f5f5', '#616161']
      const stageIdx = pipelineStages.findIndex((x) => x.statuses.includes(t.status))
      return {
        task: t,
        bg: chip[0],
        fg: chip[1],
        age: ageOf(t.createdAt, now),
        agePct: agePct(t.createdAt, now),
        ageColor: ageColor(t.createdAt, now),
        steps: pipelineStages.map((x, i) => ({
          label: x.name,
          bg: i < stageIdx ? '#a5d6a7' : i === stageIdx ? x.color : '#eeeeee',
          fg: i === stageIdx ? x.color : '#bdbdbd',
          weight: i === stageIdx ? '700' : '400',
        })),
        move: opts.isAdmin ? moveFor(t) : null,
      }
    }

    const groups = s.statuses
      .filter((st) => inStage.some((t) => t.status === st))
      .map((st) => {
        const chip = CHIP[st] ?? ['#f5f5f5', '#616161']
        const cards = inStage.filter((t) => t.status === st).map(toCard)
        return {
          status: st,
          label: cards[0]?.task.statusLabel ?? st,
          count: cards.length,
          bg: chip[0],
          fg: chip[1],
          cards,
        }
      })

    const count = inStage.length
    const oldest = count
      ? ageOf(
          inStage.reduce((a, b) => (ageHours(a.createdAt, now) > ageHours(b.createdAt, now) ? a : b))
            .createdAt,
          now,
        )
      : '—'

    return {
      key: s.key,
      name: s.name,
      color: s.color,
      count,
      oldest,
      summary: groups.length
        ? groups.map((g) => `${g.label} ${g.count}`).join(' · ')
        : '진행 중인 작업 없음',
      pipeline: pipelineStages.map((x) => ({ bg: x.key === s.key ? s.color : '#e0e0e0' })),
      bottleneck: !s.terminal && count >= 4 && count === maxCount,
      isEmpty: count === 0,
      emptyHint: s.emptyHint,
      terminal: !!s.terminal,
      groups,
    }
  })
}

/** 드롭 대상 단계가 이 전이를 받을 수 있는가. */
export function stageAccepts(stageKey: string, move: MoveDef | null): boolean {
  if (!move) return false
  const s = STAGES.find((x) => x.key === stageKey)
  return !!s && s.statuses.includes(move.to)
}
