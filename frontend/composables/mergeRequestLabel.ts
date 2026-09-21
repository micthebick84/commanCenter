// PR(GitHub) / MR(GitLab) 표기. 작업 DTO에 호스트 필드를 두지 않고 URL 모양으로 판정한다 —
// GitLab MR URL은 항상 '/-/merge_requests/<iid>' (스펙 2026-09-21 §2.1-5).
const GITLAB_MR = /\/-\/merge_requests\/\d+/

export function mrNoun(prUrl?: string | null): 'PR' | 'MR' {
  return prUrl && GITLAB_MR.test(prUrl) ? 'MR' : 'PR'
}

/** 'PR #13' | 'MR !12' */
export function mrRef(prUrl: string | null | undefined, n: number): string {
  return mrNoun(prUrl) === 'MR' ? `MR !${n}` : `PR #${n}`
}
