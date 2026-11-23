import { computed, ref, watch, type Ref } from 'vue'
import { sameRefSet, toInputs, type RefRepoInput, type RefRepoView } from './refRepos'

export interface RefDraftTask {
  status: string
  interviewSessionId: number | null
  refRepos?: RefRepoView[]
}

/** 세션별로 남겨 두는 초안 — 처리함처럼 여러 작업을 번갈아 보는 화면이 쓴다. */
export interface RefDraftEntry {
  picked: RefRepoView[]
  syncedTo: RefRepoInput[]
}

// 인터뷰 중 참조 변경 (작업 스펙 2026-10-24 §7.4) — 질문 대화 화면(1단계)과 같은 원리: "✎ 참조 변경" 메뉴가 고치는 것은
// "다음 답변에 쓸 초안"(pickedRefs)이고, 칩 줄은 서버 값(task.refRepos — 인터뷰 단계면 진행 중 세션의 참조·상태)을 그대로 따른다.
// 초안은 인터뷰 세션마다 시드한다 — 같은 페이지에서 취소·재승인으로 새 세션이 생겼는데 시드를 안 하면 낡은 초안이 재승인 때 뺀
// 참조를 되살린다. 같은 세션의 폴링(5초)에서는 보내지 않은 변경이 없을 때만(초안 = 마지막으로 맞춘 서버 집합) 새 서버 집합을 따라간다 —
// 무조건 서버 값으로 되돌리면 보내기 전의 변경이 지워지고, 한 번 시드하고 끝이면 다른 화면(휴대폰·다른 탭·다른 관리자)에서 바꾼 참조를
// 이 화면의 다음 답변이 낡은 초안으로 되돌린다. 보내지 않은 변경이 있으면(초안 ≠ 맞춘 집합) 그대로 둔다.
// store를 주면 세션이 바뀔 때 떠나는 세션의 초안을 저장하고, 돌아온 세션은 저장값으로 되살린 뒤 같은 규칙으로 서버를 따라간다.
export function useInterviewRefDraft(task: Ref<RefDraftTask | null>, opts?: { store?: Map<number, RefDraftEntry> }) {
  const auth = useAuthStore()
  const store = opts?.store

  const isInterviewPhase = computed(() =>
    ['INTERVIEWING', 'INTERVIEW_INPUT', 'INTERVIEW_REVIEW'].includes(task.value?.status ?? ''),
  )

  const pickedRefs = ref<RefRepoView[]>([])
  const refsSeededFor = ref<number | null>(null)
  let refsSyncedTo: RefRepoInput[] = [] // 초안을 마지막으로 맞춘 서버 집합(카탈로그 id·브랜치) — 화면에 쓰지 않으므로 반응형이 아니다

  // 초안과 서버 집합을 맞춘다 — 같은 세션의 폴링과 저장값을 되살린 직후가 같은 규칙을 쓴다.
  function follow(server: RefRepoView[], serverSet: RefRepoInput[]) {
    const draftSet = toInputs(pickedRefs.value)
    if (sameRefSet(draftSet, serverSet)) {
      refsSyncedTo = serverSet // 초안이 이미 서버와 같다(보낸 변경이 반영됨) — 기준만 옮긴다
    } else if (sameRefSet(draftSet, refsSyncedTo)) {
      pickedRefs.value = [...server] // 보내지 않은 변경 없음 — 다른 화면에서 바뀐 서버 집합을 따라간다
      refsSyncedTo = serverSet
    }
  }

  watch(
    task,
    (t) => {
      if (!t || !isInterviewPhase.value || t.interviewSessionId == null) return
      const server = t.refRepos ?? []
      const serverSet = toInputs(server)
      if (t.interviewSessionId !== refsSeededFor.value) {
        if (store && refsSeededFor.value != null) {
          store.set(refsSeededFor.value, { picked: [...pickedRefs.value], syncedTo: refsSyncedTo })
        }
        const saved = store?.get(t.interviewSessionId)
        refsSeededFor.value = t.interviewSessionId
        if (saved) {
          pickedRefs.value = [...saved.picked]
          refsSyncedTo = saved.syncedTo
          follow(server, serverSet)
          return
        }
        pickedRefs.value = [...server]
        refsSyncedTo = serverSet
        return
      }
      follow(server, serverSet)
    },
    { immediate: true },
  )

  // 답변 바디에 실을 참조 — 시드된 세션에서 초안이 서버 값과 다른 집합일 때만(전부 해제면 []), 아니면 null(키 없음 = 유지).
  // 참조를 안 건드린 답변 바디는 예전과 같다. 두 InterviewPanel(모바일·데스크톱)에 같이 넘긴다.
  const answerRefs = computed<RefRepoInput[] | null>(() => {
    const t = task.value
    if (!t || t.interviewSessionId == null || t.interviewSessionId !== refsSeededFor.value) return null
    const picked = toInputs(pickedRefs.value)
    return sameRefSet(picked, toInputs(t.refRepos ?? [])) ? null : picked
  })

  // 바꿀 수 있는 사람은 관리자뿐(답변 API가 관리자 전용), 인터뷰 단계에서만. 칩 줄은 참조가 있거나 바꿀 수 있을 때만 그린다 —
  // 참조 없는 작업 상세는 지금 그대로이고, 인터뷰 중 관리자는 참조 없이 등록된 작업에도 더할 수 있다("참조 repo 없음" + ✎).
  const canChangeRefs = computed(() => auth.isAdmin && isInterviewPhase.value && !!task.value?.interviewSessionId)
  const showRefRow = computed(() => (task.value?.refRepos?.length ?? 0) > 0 || canChangeRefs.value)

  return { isInterviewPhase, pickedRefs, answerRefs, canChangeRefs, showRefRow }
}
