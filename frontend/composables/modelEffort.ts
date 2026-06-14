// 모델/effort 선택 옵션 + 모델별 허용 effort(단일 진실원천, 백엔드 ModelEffortPolicy와 동기화).
export const DEFAULT_MODEL = 'claude-opus-4-8'
export const DEFAULT_EFFORT = 'high'

export const MODEL_OPTIONS = [
  { label: 'Opus 4.8', value: 'claude-opus-4-8' },
  { label: 'Opus 4.7', value: 'claude-opus-4-7' },
  { label: 'Sonnet 4.6', value: 'claude-sonnet-4-6' },
  { label: 'Haiku 4.5', value: 'claude-haiku-4-5' },
]

const FULL = ['low', 'medium', 'high', 'xhigh', 'max']
const LIMITED = ['low', 'medium', 'high']

const MODEL_EFFORT_MAP: Record<string, string[]> = {
  'claude-opus-4-8': FULL,
  'claude-opus-4-7': FULL,
  'claude-sonnet-4-6': FULL,
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
