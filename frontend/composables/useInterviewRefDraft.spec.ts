import { describe, it, expect, beforeEach } from 'vitest'
import { effectScope, nextTick, ref, type EffectScope } from 'vue'
import { authStub } from '../test/mocks/nuxt'
import { useInterviewRefDraft, type RefDraftEntry, type RefDraftTask } from './useInterviewRefDraft'
import type { RefRepoView } from './refRepos'

const widgets: RefRepoView = { catalogId: 1, alias: 'acme/widgets', branch: 'main' }
const gadgets: RefRepoView = { catalogId: 2, alias: 'acme/gadgets', branch: 'main' }

function mk(sessionId: number, refRepos: RefRepoView[] = [widgets]): RefDraftTask {
  return { status: 'INTERVIEW_INPUT', interviewSessionId: sessionId, refRepos }
}

let scope: EffectScope
beforeEach(() => {
  authStub.isAdmin = true
  scope?.stop()
  scope = effectScope()
})

function setup(initial: RefDraftTask, store?: Map<number, RefDraftEntry>) {
  const task = ref<RefDraftTask | null>(initial)
  const draft = scope.run(() => useInterviewRefDraft(task, store ? { store } : undefined))!
  return { task, draft }
}

describe('useInterviewRefDraft', () => {
  it('store_없이_세션이_바뀌면_서버_값으로_시드', async () => {
    const { task, draft } = setup(mk(7))
    expect(draft.pickedRefs.value).toEqual([widgets])
    draft.pickedRefs.value = [widgets, gadgets]
    expect(draft.answerRefs.value).toEqual([
      { repoCatalogId: 1, branch: 'main' },
      { repoCatalogId: 2, branch: 'main' },
    ])
    task.value = mk(8, [gadgets])
    await nextTick()
    expect(draft.pickedRefs.value).toEqual([gadgets])
    expect(draft.answerRefs.value).toBeNull()
    task.value = mk(7)
    await nextTick()
    expect(draft.pickedRefs.value).toEqual([widgets]) // 저장소가 없으면 초안은 되살아나지 않는다
  })

  it('store가_있으면_돌아온_세션의_보내지_않은_초안을_되살린다', async () => {
    const store = new Map<number, RefDraftEntry>()
    const { task, draft } = setup(mk(7), store)
    draft.pickedRefs.value = [widgets, gadgets]
    task.value = mk(8, [gadgets])
    await nextTick()
    expect(draft.pickedRefs.value).toEqual([gadgets])
    task.value = mk(7)
    await nextTick()
    expect(draft.pickedRefs.value).toEqual([widgets, gadgets])
    expect(draft.answerRefs.value).toEqual([
      { repoCatalogId: 1, branch: 'main' },
      { repoCatalogId: 2, branch: 'main' },
    ])
  })

  it('되살린_세션에서도_서버가_바뀌었고_보낸_변경이면_서버를_따른다', async () => {
    const store = new Map<number, RefDraftEntry>()
    const { task, draft } = setup(mk(7), store)
    draft.pickedRefs.value = [widgets, gadgets]
    task.value = mk(8, [gadgets])
    await nextTick()
    // 세션 7로 돌아왔을 때 서버가 이미 초안과 같다(보낸 변경 반영) → 초안 유지, 기준만 이동
    task.value = mk(7, [widgets, gadgets])
    await nextTick()
    expect(draft.pickedRefs.value).toEqual([widgets, gadgets])
    expect(draft.answerRefs.value).toBeNull()
    // 이어서 다른 화면이 서버를 바꾸면(보내지 않은 변경 없음) 서버를 따른다
    task.value = mk(7, [gadgets])
    await nextTick()
    expect(draft.pickedRefs.value).toEqual([gadgets])
  })

  it('저장된_초안이_기준과_같으면_돌아왔을_때_바뀐_서버를_따른다', async () => {
    const store = new Map<number, RefDraftEntry>()
    const { task, draft } = setup(mk(7), store)
    task.value = mk(8, [gadgets])
    await nextTick()
    task.value = mk(7, [gadgets])
    await nextTick()
    expect(draft.pickedRefs.value).toEqual([gadgets])
  })
})
