// 모델/effort 선택 옵션 + 모델별 허용 effort(단일 진실원천, 백엔드 ModelEffortPolicy와 동기화).
// claude-fable-5는 2026-09-05 목록에서 제외(스펙 2026-09-05-question-chat-ui-design §5.4).
export const DEFAULT_MODEL = 'claude-opus-5'
export const DEFAULT_EFFORT = 'high'

export const MODEL_OPTIONS = [
  { label: 'Opus 5 (기본)', value: 'claude-opus-5' },
  { label: 'Sonnet 5', value: 'claude-sonnet-5' },
  { label: 'Haiku 4.5', value: 'claude-haiku-4-5' },
]

// ultracode는 의도적으로 없다 — CLI가 인식하는 --effort 별칭이지만 헤드리스에서
// xhigh와 관측 가능한 차이가 없다(사유는 백엔드 ModelEffortPolicy 주석 참고).
const FULL = ['low', 'medium', 'high', 'xhigh', 'max']
const LIMITED = ['low', 'medium', 'high']

const MODEL_EFFORT_MAP: Record<string, string[]> = {
  'claude-opus-5': FULL,
  'claude-sonnet-5': FULL,
  'claude-haiku-4-5': LIMITED,
}

/** 모델이 허용하는 effort 목록. 미지 모델은 FULL(서버가 권위 검증). */
export function effortsForModel(model: string): string[] {
  return MODEL_EFFORT_MAP[model] ?? FULL
}

/** 현재 effort가 모델에서 허용되면 유지, 아니면 기본값(high)으로 강등. */
export function coerceEffort(model: string, effort: string): string {
  return effortsForModel(model).includes(effort) ? effort : DEFAULT_EFFORT
}

// 승인 다이얼로그(2026-09-06)에서 보여주는 한 줄 설명 — 원시값("high")만으로는 속도·깊이·한도 소모를
// 가늠할 수 없다는 사용자 지적. 목록(MODEL_OPTIONS/FULL)과 같이 관리한다.
const MODEL_DESCRIPTIONS: Record<string, string> = {
  'claude-opus-5': '가장 깊은 분석 · 기본값 · 느리고 한도 소모가 큼',
  'claude-sonnet-5': '속도와 품질의 균형 · 대부분의 작업에 충분',
  'claude-haiku-4-5':
    '가장 빠르고 가벼움 · 단순 작업용 · 추론 low/medium/high만',
}

const EFFORT_DESCRIPTIONS: Record<string, string> = {
  low: '빠른 답변 · 얕은 추론',
  medium: '보통 깊이 · 빠른 편',
  high: '기본값 · 깊은 추론',
  xhigh: '더 깊은 추론 · 느림',
  max: '가장 깊은 추론 · 가장 느리고 한도 소모가 큼',
}

/** 모델 한 줄 설명. 목록에 없는 값은 빈 문자열(박제된 과거 모델 등). */
export function describeModel(model: string): string {
  return MODEL_DESCRIPTIONS[model] ?? ''
}

/** effort 한 줄 설명. 목록에 없는 값(ultracode 등)은 빈 문자열. */
export function describeEffort(effort: string): string {
  return EFFORT_DESCRIPTIONS[effort] ?? ''
}

/** 픽커/칩/안내용 짧은 모델 이름 — "Opus 5 (기본)" → "Opus 5". 목록에 없는 값(박제된 과거 모델)은 그대로. */
export function shortModelLabel(model: string): string {
  const label = MODEL_OPTIONS.find((m) => m.value === model)?.label ?? model
  return label.replace(/\s*\(.*\)$/, '')
}
