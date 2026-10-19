// 요청 게시판 공용 타입·상수 (스펙 2026-10-19). 필드 이름은 백엔드 RequestBoardDto와 같다.

export type RequestKind = 'FEATURE' | 'MCP' | 'REPO'
export type RequestStatus = 'RECEIVED' | 'REVIEWING' | 'DONE' | 'REJECTED'
export type RequestCommentKind = 'COMMENT' | 'SYSTEM'

export interface RequestListItem {
  id: number
  kind: RequestKind
  kindLabel: string
  title: string
  status: RequestStatus
  statusLabel: string
  requesterId: string
  unread: boolean
  createdAt: string
  updatedAt: string
}

export interface RequestComment {
  id: number
  kind: RequestCommentKind
  authorId: string
  authorAdmin: boolean
  body: string
  createdAt: string
}

export interface RequestDetail {
  id: number
  kind: RequestKind
  kindLabel: string
  title: string
  content: string
  repoHint: string | null
  status: RequestStatus
  statusLabel: string
  requesterId: string
  repoCatalogId: number | null
  repoAlias: string | null
  repoGitUrl: string | null
  repoCatalogDeleted: boolean
  editable: boolean
  createdAt: string
  updatedAt: string
  comments: RequestComment[]
}

export interface RepoCandidate {
  id: number
  name: string
  pathWithNamespace: string
  defaultBranch: string | null
  description: string | null
  registered: boolean
}

export const KIND_OPTIONS: { value: RequestKind; label: string }[] = [
  { value: 'FEATURE', label: '기능 추가' },
  { value: 'MCP', label: 'MCP 추가' },
  { value: 'REPO', label: 'REPO 추가' },
]

export const STATUS_OPTIONS: { value: RequestStatus; label: string }[] = [
  { value: 'RECEIVED', label: '접수' },
  { value: 'REVIEWING', label: '검토중' },
  { value: 'DONE', label: '처리완료' },
  { value: 'REJECTED', label: '반려' },
]

/** 처리 중인 상태(접수·검토중) — 관리자 목록 기본 필터와 REPO 추가 버튼 노출 기준. */
export const OPEN_STATUSES: RequestStatus[] = ['RECEIVED', 'REVIEWING']

export const STATUS_COLOR: Record<RequestStatus, string> = {
  RECEIVED: 'grey',
  REVIEWING: 'blue',
  DONE: 'positive',
  REJECTED: 'negative',
}

/** 관리자가 현재 상태에서 옮길 수 있는 상태(백엔드 RequestStatus.canTransitionTo와 같은 표). */
export function adminTransitions(s: RequestStatus): RequestStatus[] {
  switch (s) {
    case 'RECEIVED':
      return ['REVIEWING', 'DONE', 'REJECTED']
    case 'REVIEWING':
      return ['DONE', 'REJECTED', 'RECEIVED']
    case 'DONE':
    case 'REJECTED':
      return ['REVIEWING']
  }
}
