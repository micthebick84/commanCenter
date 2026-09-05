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
