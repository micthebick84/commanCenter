// 작업 종료 카드 표시 규칙 (스펙 2026-10-13 §6, 2026-10-14 §9). 단계 이름은 서버 CloseStep과 같다.

export interface CloseArtifactView {
  name: string
  sizeBytes: number
  sha256: string
  uploadedAt: string | null
}

export interface CloseMailView {
  status: 'SENT' | 'FAILED' | 'NO_ADDRESS' | 'DISABLED'
  at: string | null
}

export interface CloseView {
  progress: string[]
  mergeCommitSha: string | null
  notePath: string | null
  artifactMode?: 'REQUIRED' | 'SKIPPED' | null
  artifact?: CloseArtifactView | null
  mail?: CloseMailView | null
}

export type CloseStepState = 'done' | 'current' | 'failed' | 'future' | 'skipped'

export const CLOSE_STEPS = [
  { key: 'MERGED', label: 'PR 병합' },
  { key: 'UNDEPLOYED', label: '배포 중지' },
  { key: 'ARTIFACT_UPLOADED', label: '산출물 업로드' },
  { key: 'NOTE_WRITTEN', label: '옵시디언 노트' },
  { key: 'BRANCH_DELETED', label: '브랜치 삭제' },
]

/** 산출물 단계는 모드가 있을 때만 보이고, SKIPPED면 '건너뜀'이다. */
export function closeSteps(close: CloseView | null, status: string) {
  const mode = close?.artifactMode ?? null
  const steps = CLOSE_STEPS.filter((s) => s.key !== 'ARTIFACT_UPLOADED' || mode !== null)
  const done = new Set(close?.progress ?? [])
  const skipped = (key: string) => key === 'ARTIFACT_UPLOADED' && mode === 'SKIPPED'
  const nextIdx = steps.findIndex((s) => !done.has(s.key) && !skipped(s.key))
  return steps.map((s, i) => {
    let state: CloseStepState = 'future'
    if (done.has(s.key)) state = 'done'
    else if (skipped(s.key)) state = 'skipped'
    else if (i === nextIdx && status === 'CLOSING') state = 'current'
    else if (i === nextIdx && status === 'CLOSE_FAILED') state = 'failed'
    return { ...s, state }
  })
}

/** 산출물 단계에서 멈춘 종료실패인가 — 서버 TaskCloseService.skipArtifact와 같은 조건. */
export function canSkipArtifact(close: CloseView | null, status: string): boolean {
  if (!close || status !== 'CLOSE_FAILED' || close.artifactMode !== 'REQUIRED') return false
  const done = new Set(close.progress)
  return done.has('MERGED') && done.has('UNDEPLOYED') && !done.has('ARTIFACT_UPLOADED')
}

export const MAIL_STATUS_LABEL: Record<string, string> = {
  SENT: '메일 보냄',
  FAILED: '메일 실패',
  NO_ADDRESS: '수신 주소 없음',
  DISABLED: '메일 설정 없음',
}

export function formatMb(bytes: number): string {
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 1회용 내려받기 주소를 받아 브라우저가 바로 저장하게 한다 — 254MB를 blob으로 메모리에 올리지 않는다(스펙 2026-10-14 §6).
 * 서버가 주는 '/api/…' 상대 경로를 그대로 쓴다(useInterviewStream과 같은 /api 관례). go는 테스트 seam.
 */
export async function startArtifactDownload(
  taskId: number | string,
  api: (url: string, opts: { method: 'POST' }) => Promise<{ url: string }>,
  go: (url: string) => void = (u) => window.location.assign(u),
) {
  const res = await api(`/api/tasks/${taskId}/artifact/download-link`, { method: 'POST' })
  go(res.url)
}

/** obsidian://open 은 확장자 없는 볼트 상대 경로를 받는다. 볼트 이름 미설정이면 링크를 만들지 않는다. */
export function obsidianLink(vault: string, notePath: string | null): string | null {
  if (!vault || !notePath) return null
  const file = notePath.replace(/\.md$/, '')
  return `obsidian://open?vault=${encodeURIComponent(vault)}&file=${encodeURIComponent(file)}`
}
