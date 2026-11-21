import { failMsg } from '~/composables/failMessage'

export interface RepoBranches {
  defaultBranch: string | null
  branches: { name: string; sha: string }[]
}

interface HttpErrorLike {
  statusCode?: number
  status?: number
  response?: { status?: number }
  data?: { message?: string }
}

/**
 * 레포 브랜치 조회 — 실패는 Error(message)로 던진다. 문구는 직접 고르기 모드(pages/questions/index.vue의 loadBranches)와 같다.
 */
export async function fetchRepoBranches(catalogId: number): Promise<RepoBranches> {
  try {
    const res = await useApi<{ repo?: string; defaultBranch: string | null; branches: { name: string; sha: string }[] }>(
      '/api/repos/branches',
      { params: { catalogId } },
    )
    return { defaultBranch: res?.defaultBranch ?? null, branches: res?.branches ?? [] }
  } catch (e) {
    const err = (e ?? {}) as HttpErrorLike
    const status = err.statusCode ?? err.response?.status ?? err.status
    throw new Error(
      err.data?.message ?? (status === 404 ? '레포를 찾을 수 없거나 접근 권한이 없습니다' : failMsg('브랜치 동기화')),
    )
  }
}

/** 카탈로그 기본 브랜치 → 조회한 기본 브랜치 → 첫 브랜치 → 빈 문자열. */
export function initialBranch(res: RepoBranches, catalogDefault: string | null): string {
  return catalogDefault ?? res.defaultBranch ?? res.branches[0]?.name ?? ''
}
