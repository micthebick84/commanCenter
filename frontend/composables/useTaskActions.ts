// 작업 동작 공용 모듈 — 작업 상세(pages/tasks/[id].vue)와 처리함이 같은 확인창·알림·API 호출·버튼 조건을 쓴다.
// 각 동작은 서버가 받아들였을 때만 true — 확인창 취소·서버 오류·중복 클릭 무시는 false.
import { computed, type ComputedRef, type Ref } from 'vue'
import { useQuasar } from 'quasar'
import { failMsg } from '~/composables/failMessage'
import { confirmDialog } from '~/composables/confirmDialog'
import { chooseCloseMode, closeApproveBody } from '~/composables/closeApprove'
import { fetchDeferredWarning } from '~/composables/taskDbChanges'
import { isDbWorkPrCreated, DB_WORK_CLOSE_REQUEST_CONFIRM } from '~/composables/taskStages'
import { BASE_SYNC_ABORT_CONFIRM, SYNC_BASE_CONFIRM, canAbortFix, canSyncBase, isBaseSyncRound, type FixView } from '~/composables/taskFix'
import { canSkipArtifact, canSkipPatch, isKeepBranchClose, MAIL_STATUS_LABEL, type CloseView } from '~/composables/taskClose'

export interface ActionTask {
  id: number
  status: string
  githubBranch: string
  close?: CloseView | null
  fix?: FixView | null
  taskType?: string | null
}

export interface TaskActions {
  approveImpl(): Promise<boolean>
  retry(): Promise<boolean>
  cancelTask(): Promise<boolean>
  undeploy(): Promise<boolean>
  requestClose(): Promise<boolean>
  approveClose(): Promise<boolean>
  retryClose(): Promise<boolean>
  cancelClose(): Promise<boolean>
  syncBase(): Promise<boolean>
  rejectClose(): Promise<boolean>
  skipPatch(): Promise<boolean>
  skipArtifact(): Promise<boolean>
  resendMail(): Promise<boolean>
  approveFix(): Promise<boolean>
  rejectFix(): Promise<boolean>
  withdrawFix(): Promise<boolean>
  abortFix(): Promise<boolean>
  /** 확인창 없이 종료·수정 동작 하나를 부른다(종료 요청 철회 등 화면이 직접 쓰는 길). */
  closeAction(path: string, ok: string | ((res: { status?: string } | null) => CloseNotice), body?: Record<string, unknown>): Promise<boolean>
  canCancelClose: ComputedRef<boolean>
  canSyncBaseNow: ComputedRef<boolean>
  canSkipArtifactClose: ComputedRef<boolean>
  canSkipPatchClose: ComputedRef<boolean>
  canAbort: ComputedRef<boolean>
  canAdminRequestCloseLost: ComputedRef<boolean>
  baseSyncOpen: ComputedRef<boolean>
  abortLabel: ComputedRef<string>
}

// 작업 종료 (스펙 2026-10-13) — 응답에 사용량이 없으므로 액션 뒤에는 항상 refresh()로 다시 읽는다.
// 성공 알림은 문자열(항상 positive)이거나, 서버 응답으로 정하는 함수다 — 메일 다시 보내기는 재시도가 또 실패해도 200이다.
export type CloseNotice = { type: 'positive' | 'warning'; message: string }
const CLOSE_ACTION_NAMES: Record<string, string> = {
  'close-request': '종료 요청',
  'close-request/withdraw': '종료 요청 철회',
  'close/approve': '종료 승인',
  'close/retry': '종료 재시도',
  'close/cancel': '종료 취소',
  'close/sync-base': '기준 브랜치 반영',
  'close/reject': '종료 반려',
  'close/skip-patch': '패치 없이 마무리',
  'close/skip-artifact': '산출물 없이 마무리',
  'close/mail/resend': '메일 다시 보내기',
  'fix-requests/approve': '수정 요청 승인',
  'fix-requests/reject': '수정 요청 반려',
  'fix-requests/withdraw': '수정 요청 철회',
  'fix-requests/abort': '수정 중단',
}

export function useTaskActions<T extends ActionTask>(task: Ref<T | null>, refresh: () => unknown): TaskActions {
  const $q = useQuasar()
  const auth = useAuthStore()
  const taskId = () => String(task.value?.id)

  // 단순 POST → 알림 → 재조회 (확인창은 호출하는 쪽이 이미 통과시켰다)
  async function post(path: string, okMessage: string, failName: string): Promise<boolean> {
    try {
      await useApi(`/api/tasks/${taskId()}/${path}`, { method: 'POST' })
      $q.notify({ type: 'positive', message: okMessage })
      refresh()
      return true
    } catch (e: any) {
      $q.notify({ type: 'negative', message: e?.data?.message ?? failMsg(failName) })
      return false
    }
  }

  // $q.dialog().onOk 기반 동작을 Promise로 — 취소·닫기는 false. onOk 뒤에도 dismiss가 오므로 한 번만 정한다.
  function dialogThen(opts: Record<string, unknown>, onOk: (value: any) => Promise<boolean>): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      let okCalled = false
      let settled = false
      const settle = (v: boolean) => {
        if (settled) return
        settled = true
        resolve(v)
      }
      $q.dialog(opts as any)
        .onOk((value: any) => {
          okCalled = true
          onOk(value).then(settle, () => settle(false))
        })
        .onCancel(() => settle(false))
        .onDismiss(() => {
          if (!okCalled) settle(false)
        })
    })
  }

  let closeInFlight = false
  async function closeAction(
    path: string,
    ok: string | ((res: { status?: string } | null) => CloseNotice),
    body?: Record<string, unknown>,
  ): Promise<boolean> {
    if (closeInFlight) return false // 요청이 끝나기 전의 중복 클릭은 무시한다
    closeInFlight = true
    try {
      const res = await useApi<{ status?: string } | null>(`/api/tasks/${taskId()}/${path}`, { method: 'POST', body })
      $q.notify(typeof ok === 'function' ? ok(res) : { type: 'positive', message: ok })
      refresh()
      return true
    } catch (e: any) {
      // 어느 동작이 실패했는지 말한다 — 예전엔 종료·수정 동작 여럿이 '요청 실패' 하나를 같이 썼다(2026-11-11 검토)
      $q.notify({ type: 'negative', message: e?.data?.message ?? failMsg(CLOSE_ACTION_NAMES[path] ?? '요청') })
      return false
    } finally {
      closeInFlight = false
    }
  }

  async function approveImpl(): Promise<boolean> {
    if (
      !(await confirmDialog(
        $q,
        '승인 즉시 워커가 worktree에서 구현 + Draft PR/MR을 생성합니다.',
        { title: '이 분석 결과를 승인하시겠습니까?', ok: '승인' },
      ))
    )
      return false
    return post('approve', '승인 완료 — 구현 큐에 진입했습니다', '승인')
  }

  // 승인대기 취소: 요청자 본인 또는 admin — getForView가 이미 조회 시점에 두 경우만
  // 통과시키므로(그 외 403) 별도 소유권 가드 없이 재시도 버튼과 동일한 패턴을 따른다.
  async function cancelTask(): Promise<boolean> {
    if (!(await confirmDialog($q, '이 작업을 취소하시겠습니까?', { title: '작업 취소', ok: '작업 취소', cancel: '돌아가기' }))) return false
    return post('cancel', '작업이 취소되었습니다', '취소')
  }

  function undeploy(): Promise<boolean> {
    return dialogThen(
      {
        title: '배포 중지',
        message: '실행 중인 컨테이너를 중지하고 제거합니다. 계속할까요?',
        ok: { label: '배포 중지', flat: true },
        cancel: { label: '취소', flat: true },
        persistent: true,
      },
      () => post('undeploy', '배포 중지 요청 등록', '배포 중지'),
    )
  }

  function retry(): Promise<boolean> {
    return post('retry', '재시도 큐 등록', '재시도')
  }

  async function requestClose(): Promise<boolean> {
    // DB 작업 PR생성(스펙 2026-11-03 §6.9)은 띄운 앱이 없으니 배포 결과가 아니라 개발 DB 반영 결과를 묻는다
    const message = task.value && isDbWorkPrCreated(task.value)
      ? DB_WORK_CLOSE_REQUEST_CONFIRM
      : `배포 결과를 확인했나요? 관리자가 승인하면 PR을 기준 브랜치(${task.value?.githubBranch ?? '기준 브랜치'})에 병합하고, 배포를 중지하고, 브랜치를 삭제합니다(관리자가 '병합하지 않고 종료'를 고르면 브랜치·PR은 그대로 둡니다). 병합은 되돌릴 수 없습니다.`
    if (!(await confirmDialog($q, message, { title: '종료 요청', ok: '종료 요청' }))) return false
    return closeAction('close-request', '종료 요청 등록')
  }

  // 종료 승인 선택창(스펙 2026-11-23 close-keep-branch §4.5) — 병합하고 종료 / 병합하지 않고 종료(브랜치·PR 유지). 취소면 요청하지 않는다.
  async function approveClose(): Promise<boolean> {
    // 승인 대기 SQL이 남아 있으면 확인창에 건수 한 줄을 덧붙인다(스펙 2026-11-03 §6.5) — 조회가 실패하면 경고 없이 그대로 승인할 수 있다
    const warning = await fetchDeferredWarning(taskId())
    const mode = await chooseCloseMode($q, { taskId: taskId(), baseBranch: task.value?.githubBranch ?? null, extraWarning: warning })
    if (mode === null) return false
    return closeAction('close/approve', '종료 큐 등록', closeApproveBody(mode))
  }

  async function retryClose(): Promise<boolean> {
    const retryMsg = isKeepBranchClose(task.value?.close) ? '남은 종료 단계(배포 중지·노트)를 다시 실행합니다. 병합하지 않습니다.' : '남은 종료 단계(병합 포함 가능)를 다시 실행합니다. 병합은 되돌릴 수 없습니다.'
    if (!(await confirmDialog($q, retryMsg, { title: '종료 재시도', ok: '종료 재시도' }))) return false
    return closeAction('close/retry', '종료 재시도 큐 등록')
  }

  // 배포중단됨에서 관리자의 주 행동은 재배포라 종료 요청은 보조 버튼으로 — 관리자가 직접 등록한 작업은 이것 말고 종료할 길이 없다(API는 허용)
  const canAdminRequestCloseLost = computed(() => auth.isAdmin && task.value?.status === 'DEPLOY_LOST')

  // 병합 전(MERGED 없음) 종료실패만 취소할 수 있다 — 서버도 같은 규칙으로 409를 낸다
  // 브랜치 유지 종료는 병합이 없어 기준이 다르다 — 진행 단계가 하나라도 있으면(배포 중지 등 되돌릴 수 없는 일) 취소할 수 없다
  const canCancelClose = computed(() => {
    if (!auth.isAdmin || task.value?.status !== 'CLOSE_FAILED') return false
    const progress = task.value.close?.progress ?? []
    return isKeepBranchClose(task.value.close) ? progress.length === 0 : !progress.includes('MERGED')
  })

  async function cancelClose(): Promise<boolean> {
    if (!(await confirmDialog($q, '병합 전 단계에서 실패한 종료를 취소하고 배포 상태로 되돌립니다.', { title: '종료 취소', ok: '종료 취소', cancel: '돌아가기' }))) return false
    return closeAction('close/cancel', '종료 취소')
  }

  // 기준 브랜치 반영 — 병합 전 종료실패에서 관리자가 연다(충돌 등). 종료를 취소하고 작업 브랜치에 기준 브랜치를 합친 뒤 자동 재배포한다.
  const canSyncBaseNow = computed(() => !!task.value && canSyncBase(task.value, auth.isAdmin))

  async function syncBase(): Promise<boolean> {
    if (!task.value) return false
    if (!(await confirmDialog($q, SYNC_BASE_CONFIRM(task.value.githubBranch), { title: '기준 브랜치 반영', ok: '기준 브랜치 반영', cancel: '돌아가기' }))) return false
    return closeAction('close/sync-base', '기준 브랜치 반영을 시작했습니다')
  }

  function rejectClose(): Promise<boolean> {
    return dialogThen(
      {
        title: '종료 반려',
        message: '반려 사유를 입력하세요 (등록자에게 보입니다)',
        prompt: { model: '', type: 'text', isValid: (v: string) => v.trim().length > 0 },
        ok: { label: '반려', flat: true },
        cancel: { label: '취소', flat: true },
        persistent: true,
      },
      (reason: string) => closeAction('close/reject', '종료 반려', { reason: reason.trim() }),
    )
  }

  function approveFix(): Promise<boolean> {
    return dialogThen(
      {
        title: '수정 요청 승인',
        message: '구현 방식을 고르세요. 승인하면 같은 브랜치에서 이어 고친 뒤 자동으로 다시 배포합니다.',
        options: {
          type: 'radio',
          model: 'REVIEW',
          items: [
            { label: '리뷰 — 구현 뒤 Opus 리뷰 1회 (기본)', value: 'REVIEW' },
            { label: '직접 — 리뷰 없이 바로 구현', value: 'DIRECT' },
          ],
        },
        ok: { label: '승인', flat: true, 'data-test': 'fix-approve-ok' },
        cancel: { label: '취소', flat: true },
        persistent: true,
      },
      (mode: string) => closeAction('fix-requests/approve', '수정 요청 승인 — 구현 큐에 넣었습니다', { implementationMode: mode }),
    )
  }

  function rejectFix(): Promise<boolean> {
    return dialogThen(
      {
        title: '수정 요청 반려',
        message: '반려 사유를 입력하세요 (등록자에게 보입니다)',
        prompt: { model: '', type: 'text', isValid: (v: string) => v.trim().length > 0 },
        ok: { label: '반려', flat: true, 'data-test': 'fix-reject-ok' },
        cancel: { label: '취소', flat: true },
        persistent: true,
      },
      (reason: string) => closeAction('fix-requests/reject', '수정 요청 반려', { reason: reason.trim() }),
    )
  }

  async function withdrawFix(): Promise<boolean> {
    if (!(await confirmDialog($q, '수정 요청을 철회할까요? 직전 상태로 돌아가고, 필요하면 다시 요청할 수 있습니다.', { title: '수정 요청 철회', ok: '철회' }))) return false
    return closeAction('fix-requests/withdraw', '수정 요청 철회')
  }

  const canAbort = computed(() => !!task.value && canAbortFix(task.value, auth.isAdmin))

  // 기준 브랜치 반영 회차면 중단 버튼·메뉴 라벨이 `반영 중단`이고 종료 요청 전 상태로 돌아간다(서버가 반영 직전 상태로 되돌림)
  const baseSyncOpen = computed(() => !!task.value && isBaseSyncRound(task.value))
  const abortLabel = computed(() => (baseSyncOpen.value ? '반영 중단' : '수정 중단'))

  async function abortFix(): Promise<boolean> {
    if (baseSyncOpen.value) {
      if (!(await confirmDialog($q, BASE_SYNC_ABORT_CONFIRM, { title: '반영 중단', ok: '반영 중단' }))) return false
      return closeAction('fix-requests/abort', '기준 브랜치 반영 중단 — 종료 요청 전 상태로 되돌렸습니다')
    }
    if (!(await confirmDialog(
      $q,
      '수정 회차를 닫고 PR생성 상태로 되돌립니다. 이전 배포가 아직 떠 있으면 먼저 그 배포를 중지합니다. 이 브랜치에는 배포되지 않은 커밋이 남아 있을 수 있어요 — 이어서 배포하려면 PR생성에서 다시 배포하세요. 이 회차는 실패한 회차로 세어집니다.',
      { title: '수정 중단', ok: '수정 중단' },
    ))) return false
    return closeAction('fix-requests/abort', '수정 중단 — 회차를 닫았습니다')
  }

  const canSkipArtifactClose = computed(
    () => auth.isAdmin && !!task.value && canSkipArtifact(task.value.close ?? null, task.value.status),
  )
  const canSkipPatchClose = computed(
    () => auth.isAdmin && !!task.value && canSkipPatch(task.value.close ?? null, task.value.status),
  )

  async function skipPatch(): Promise<boolean> {
    if (!(await confirmDialog($q, '부분 패치 없이 배포 중지·노트·브랜치 정리를 이어 갑니다.', { title: '패치 없이 마무리', ok: '패치 없이 마무리' }))) return false
    return closeAction('close/skip-patch', '패치 없이 마무리 — 종료 큐 등록')
  }

  // 서버는 재시도 결과 상태를 200으로 돌려준다(스펙 2026-10-14 §7) — SENT만 성공이고 나머지는 경고로 결과를 알린다.
  function resendMail(): Promise<boolean> {
    return closeAction('close/mail/resend', (res) =>
      res?.status === 'SENT'
        ? { type: 'positive', message: '메일 다시 보내기 완료' }
        : { type: 'warning', message: `메일 다시 보내기 결과: ${MAIL_STATUS_LABEL[res?.status ?? ''] ?? res?.status ?? '알 수 없음'}` },
    )
  }

  async function skipArtifact(): Promise<boolean> {
    if (!(await confirmDialog($q, '산출물 없이 노트·브랜치 정리를 이어 갑니다. 메일은 보내지 않습니다.', { title: '산출물 없이 마무리', ok: '산출물 없이 마무리' }))) return false
    return closeAction('close/skip-artifact', '산출물 없이 마무리 — 종료 큐 등록')
  }

  return {
    approveImpl, retry, cancelTask, undeploy,
    requestClose, approveClose, retryClose, cancelClose, syncBase, rejectClose, skipPatch, skipArtifact, resendMail,
    approveFix, rejectFix, withdrawFix, abortFix, closeAction,
    canCancelClose, canSyncBaseNow, canSkipArtifactClose, canSkipPatchClose, canAbort, canAdminRequestCloseLost,
    baseSyncOpen, abortLabel,
  }
}
