import type { QVueGlobals } from 'quasar'

// 종료 승인 선택창 (스펙 2026-11-23 §4.5) — 병합하고 종료 / 병합하지 않고 종료(브랜치·PR 유지).
// window.confirm은 내장 브라우저에서 즉시 false라 쓰지 않고 Quasar $q.dialog의 라디오 옵션을 쓴다.

export type CloseMode = 'MERGE' | 'KEEP_BRANCH'

/** 승인 바디 — 병합 종료는 바디 없음(예전과 같다), 브랜치 유지만 { keepBranch: true }. */
export function closeApproveBody(mode: CloseMode): { keepBranch: true } | undefined {
  return mode === 'KEEP_BRANCH' ? { keepBranch: true } : undefined
}

export function chooseCloseMode(
  $q: QVueGlobals,
  opts: { taskId: number; baseBranch: string | null; extraWarning?: string },
): Promise<CloseMode | null> {
  const base = opts.baseBranch?.trim() || '기준 브랜치'
  return new Promise((resolve) => {
    $q.dialog({
      title: `종료 승인 — 작업 #${opts.taskId}`,
      message: `병합·배포 중지·브랜치 삭제는 되돌릴 수 없습니다.${opts.extraWarning ?? ''}`,
      options: {
        type: 'radio',
        model: 'MERGE',
        items: [
          { label: `병합하고 종료 — PR을 ${base}에 병합 · 배포 중지 · 노트 · 브랜치 삭제`, value: 'MERGE' },
          { label: '병합하지 않고 종료 — 브랜치·PR 유지 · 배포 중지 · 노트', value: 'KEEP_BRANCH' },
        ],
      },
      ok: { label: '종료 승인', flat: true, 'data-test': 'confirm-dialog-ok' },
      cancel: { label: '취소', flat: true, 'data-test': 'confirm-dialog-cancel' },
      persistent: true,
    })
      .onOk((value: CloseMode) => resolve(value))
      .onCancel(() => resolve(null))
  })
}
