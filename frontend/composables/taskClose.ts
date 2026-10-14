// 작업 종료 카드 표시 규칙 (스펙 2026-10-13 §6). 단계 이름은 서버 CloseStep과 같다.

export interface CloseView {
  progress: string[]
  mergeCommitSha: string | null
  notePath: string | null
}

export type CloseStepState = 'done' | 'current' | 'failed' | 'future'

export const CLOSE_STEPS = [
  { key: 'MERGED', label: 'PR 병합' },
  { key: 'UNDEPLOYED', label: '배포 중지' },
  { key: 'NOTE_WRITTEN', label: '옵시디언 노트' },
  { key: 'BRANCH_DELETED', label: '브랜치 삭제' },
]

export function closeSteps(close: CloseView | null, status: string) {
  const done = new Set(close?.progress ?? [])
  const nextIdx = CLOSE_STEPS.findIndex((s) => !done.has(s.key))
  return CLOSE_STEPS.map((s, i) => {
    let state: CloseStepState = 'future'
    if (done.has(s.key)) state = 'done'
    else if (i === nextIdx && status === 'CLOSING') state = 'current'
    else if (i === nextIdx && status === 'CLOSE_FAILED') state = 'failed'
    return { ...s, state }
  })
}

/** obsidian://open 은 확장자 없는 볼트 상대 경로를 받는다. 볼트 이름 미설정이면 링크를 만들지 않는다. */
export function obsidianLink(vault: string, notePath: string | null): string | null {
  if (!vault || !notePath) return null
  const file = notePath.replace(/\.md$/, '')
  return `obsidian://open?vault=${encodeURIComponent(vault)}&file=${encodeURIComponent(file)}`
}
