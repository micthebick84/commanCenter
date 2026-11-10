import { ref, onUnmounted, getCurrentInstance } from 'vue'

/**
 * 1회용 내려받기 주소 토큰은 연타하면 안 된다 — 진행 중은 물론, 성공해 주소를 연 직후에도 잠깐(쿨다운) 잠근다:
 * start는 응답 직후 동기로 이동하므로 빠른 API에서는 진행 중 플래그만으로 두 번째 클릭이 새어 나온다.
 * 오류면 바로 풀어 다시 시도할 수 있게 하고 오류는 그대로 던진다(알림은 호출자 몫). 언마운트 때 타이머를 정리한다.
 */
export function useOneTimeDownload(start: (id: number | string) => Promise<void>, cooldownMs = 2000) {
  const busy = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null
  if (getCurrentInstance()) {
    onUnmounted(() => {
      if (timer) clearTimeout(timer)
      timer = null
    })
  }
  async function run(id: number | string) {
    if (busy.value) return
    busy.value = true
    try {
      await start(id)
    } catch (e) {
      busy.value = false
      throw e
    }
    timer = setTimeout(() => {
      timer = null
      busy.value = false
    }, cooldownMs)
  }
  return { busy, run }
}

/** 내려받기 오류 알림 문구 — 410 = 보관 기간이 지나 서버가 파일을 정리했다(본문 메시지가 없어도 같은 안내). */
export function downloadErrorMessage(e: unknown): string {
  const err = (typeof e === 'object' && e !== null ? e : {}) as {
    statusCode?: number
    status?: number
    response?: { status?: number }
    data?: { message?: string }
  }
  const status = err.statusCode ?? err.response?.status ?? err.status
  return status === 410 ? '보관 파일이 정리되었습니다' : (err.data?.message ?? '내려받기 실패')
}
