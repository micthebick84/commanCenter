import type { QVueGlobals } from 'quasar'

/**
 * window.confirm 대신 쓰는 Quasar 확인 다이얼로그 — 확인이면 true, 취소면 false.
 *
 * 네이티브 confirm은 내장 브라우저(Claude 앱 브라우저 창 등)가 창을 띄우지 않고 즉시 false를 돌려줘
 * 삭제·취소 같은 동작이 "아무 반응 없음"으로 끝났다(2026-10-08). 배포 중지($q.dialog)처럼
 * 앱 안에서 그리는 다이얼로그는 어디서나 같게 동작한다.
 *
 * 확인 버튼 글자(ok)는 필수 — 하는 일을 이름으로 적는다("확인" 금지, 2026-11-11 검토). 되돌릴 수 없는 병합이
 * "확인"으로 실행되던 것을 막는다. 취소 동작을 확인할 때는 cancel을 "돌아가기"처럼 바꿔 "취소"가 두 뜻이 되지 않게 한다.
 *
 *   if (!(await confirmDialog($q, '삭제하시겠습니까?', { title: '작업 삭제', ok: '삭제' }))) return
 */
export function confirmDialog(
  $q: QVueGlobals,
  message: string,
  opts: { title?: string; ok: string; cancel?: string },
): Promise<boolean> {
  return new Promise((resolve) => {
    $q.dialog({
      title: opts.title ?? opts.ok,
      message,
      // 버튼 props를 객체로 넘기면 Quasar 기본값(flat)이 빠지므로 직접 지정한다.
      ok: { label: opts.ok, flat: true, 'data-test': 'confirm-dialog-ok' },
      cancel: { label: opts.cancel ?? '취소', flat: true, 'data-test': 'confirm-dialog-cancel' },
      persistent: true,
    })
      .onOk(() => resolve(true))
      .onCancel(() => resolve(false))
  })
}
