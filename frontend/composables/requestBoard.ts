// 요청 게시판 공용 타입·상수 (스펙 2026-10-19). 필드 이름은 백엔드 RequestBoardDto와 같다.
import type { RepoCatalogOption } from './refRepos'

export type RequestKind = 'DEV' | 'REPO' | 'FEATURE' | 'MCP'
export type RequestIssueType = 'BUG' | 'DEV' | 'INQUIRY'
export type RequestPriority = 'HIGH' | 'NORMAL' | 'LOW'
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
  issueType: RequestIssueType | null
  issueTypeLabel: string | null
  priority: RequestPriority | null
  priorityLabel: string | null
  /** yyyy-MM-dd */
  dueDate: string | null
  targetRepoAlias: string | null
  attachmentCount: number
  commentCount: number
  taskId: number | null
  taskStatusLabel: string | null
  createdAt: string
  updatedAt: string
}

export interface RequestAttachmentView {
  id: number
  name: string
  sizeBytes: number
  expired: boolean
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
  issueType: RequestIssueType | null
  issueTypeLabel: string | null
  priority: RequestPriority | null
  priorityLabel: string | null
  /** yyyy-MM-dd */
  dueDate: string | null
  targetRepoCatalogId: number | null
  targetRepoAlias: string | null
  taskId: number | null
  taskStatusLabel: string | null
  attachments: RequestAttachmentView[]
  editable: boolean
  cancellable: boolean
  convertible: boolean
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

/** 등록 폼용 종류 — 예전 종류(FEATURE·MCP)는 새로 등록하지 못한다. */
export const KIND_OPTIONS: { value: RequestKind; label: string }[] = [
  { value: 'DEV', label: '개발 요청' },
  { value: 'REPO', label: '사이트 추가' },
]

/** 관리자 목록 필터용 — 예전 종류 글도 걸러 볼 수 있다. */
export const KIND_FILTER_OPTIONS: { value: RequestKind; label: string }[] = [
  ...KIND_OPTIONS,
  { value: 'FEATURE', label: '기능 추가(예전)' },
  { value: 'MCP', label: 'MCP 추가(예전)' },
]

export const ISSUE_TYPE_OPTIONS: { value: RequestIssueType; label: string }[] = [
  { value: 'BUG', label: '버그' },
  { value: 'DEV', label: '개발요청' },
  { value: 'INQUIRY', label: '기능문의' },
]

export const PRIORITY_OPTIONS: { value: RequestPriority; label: string }[] = [
  { value: 'HIGH', label: '▲ 높음' },
  { value: 'NORMAL', label: '● 보통' },
  { value: 'LOW', label: '▼ 낮음' },
]

export const ISSUE_TYPE_COLOR: Record<RequestIssueType, string> = {
  BUG: 'negative',
  DEV: 'primary',
  INQUIRY: 'grey-8',
}

export const PRIORITY_COLOR: Record<RequestPriority, string> = {
  HIGH: 'negative',
  NORMAL: 'grey-8',
  LOW: 'blue-grey-5',
}

/**
 * 요청 → 작업 등록 창에 채울 값 (스펙 2026-11-19 §5.4). 질문용 `TaskPrefill`에는 설명·타입 칸이 없어 따로 둔다.
 * 레포는 지금 카탈로그에 있고 지원하는 호스트(`host !== 'other'`)일 때만 옮긴다. 타입은 버그일 때만 `BUG_FIX`, 그 밖은 비워
 * 요청자가 직접 고르게 한다. `attachmentCount`는 정리 잡이 파일을 지운(expired) 첨부를 뺀 수 — 서버가 복사하는 수와 같다.
 */
export interface RequestTaskPrefill {
  repoCatalogId: number | null
  taskType: 'BUG_FIX' | null
  title: string
  description: string
  attachmentCount: number
}

export function taskPrefillFromRequest(r: RequestDetail, catalog: RepoCatalogOption[]): RequestTaskPrefill {
  const usable =
    r.targetRepoCatalogId != null && catalog.some((c) => c.id === r.targetRepoCatalogId && c.host !== 'other')
  const tail =
    `\n\n— 요청 #${r.id} · ${r.issueTypeLabel ?? ''} · 우선순위 ${r.priorityLabel ?? ''}` +
    (r.dueDate ? ` · 마감 ${r.dueDate}` : '') +
    ` · 요청자 ${r.requesterId}`
  return {
    repoCatalogId: usable ? r.targetRepoCatalogId : null,
    taskType: r.issueType === 'BUG' ? 'BUG_FIX' : null,
    title: r.title,
    description: r.content + tail,
    attachmentCount: r.attachments.filter((a) => !a.expired).length,
  }
}

/** 요청 한 건의 첨부 한도 — 서버 AttachmentStorage.validate와 같은 값(composables/attachmentLimits.ts의 MAX_FILES). */
export const MAX_REQUEST_FILES = 10

/** 개발 요청 multipart 본문: meta(application/json Blob) + 반복 files. pages/tasks/index.vue와 같은 모양. */
export function requestFormData(meta: object, files: File[]): FormData {
  const form = new FormData()
  form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }))
  for (const f of files) form.append('files', f, f.name)
  return form
}

export const STATUS_OPTIONS: { value: RequestStatus; label: string }[] = [
  { value: 'RECEIVED', label: '접수' },
  { value: 'REVIEWING', label: '검토중' },
  { value: 'DONE', label: '처리완료' },
  { value: 'REJECTED', label: '반려' },
]

/** 처리 중인 상태(접수·검토중) — 관리자 목록 기본 필터와 사이트 추가 버튼 노출 기준. */
export const OPEN_STATUSES: RequestStatus[] = ['RECEIVED', 'REVIEWING']

export const STATUS_COLOR: Record<RequestStatus, string> = {
  RECEIVED: 'grey-8',
  REVIEWING: 'blue-9',
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
