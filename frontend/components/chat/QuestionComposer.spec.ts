import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { QFile } from 'quasar'
import QuestionComposer from './QuestionComposer.vue'
import ModelEffortPicker from './ModelEffortPicker.vue'
import { FakeSpeechRecognition } from '~/test/mocks/speechRecognition'

function mountComposer(props: Record<string, unknown> = {}) {
  return mount(QuestionComposer, {
    props: {
      canSend: true, model: 'claude-opus-5', effort: 'high', modelValue: '질문', ...props,
    },
  })
}

describe('QuestionComposer (스펙 2026-09-05 §3·§6)', () => {
  it('Enter는 send, Shift+Enter는 줄바꿈(전송 아님), IME 조합 중 Enter는 무시', async () => {
    const w = mountComposer()
    const ta = w.find('textarea')
    await ta.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('send')).toHaveLength(1)
    await ta.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(w.emitted('send')).toHaveLength(1)
    await ta.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(w.emitted('send')).toHaveLength(1)
    // Safari: IME 조합 완료 Enter가 isComposing=false로 오지만 keyCode=229를 남기는 경우가 있다
    await ta.trigger('keydown', { key: 'Enter', keyCode: 229 })
    expect(w.emitted('send')).toHaveLength(1)
    w.unmount()
  })

  it('canSend=false면 Enter도 버튼도 전송하지 않는다', async () => {
    const w = mountComposer({ canSend: false })
    await w.find('textarea').trigger('keydown', { key: 'Enter' })
    expect(w.emitted('send')).toBeUndefined()
    expect(w.find('[data-test="composer-send"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('픽커는 대화 중에도 활성이며 모델을 바꾸면 update:model(+강등된 update:effort)로 올라간다', async () => {
    // 스펙 2026-09-05 §2 개정: 모델·effort는 세션 생성 시 고정이 아니라 다음 질문에 쓸 값이다.
    const w = mountComposer({ effort: 'max' })
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeUndefined()
    ;(w.findComponent(ModelEffortPicker).vm as any).pickModel('claude-haiku-4-5')
    await w.vm.$nextTick()
    expect(w.emitted('update:model')![0]).toEqual(['claude-haiku-4-5'])
    expect(w.emitted('update:effort')![0]).toEqual(['high']) // Haiku는 max 미지원 → 강등
    w.unmount()
  })

  it('disabled(입력 대기 아님)면 입력과 픽커를 함께 막고, sending 중에도 픽커를 잠근다', () => {
    const w = mountComposer({ disabled: true })
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined()
    expect(w.find('textarea').attributes('disabled')).toBeDefined()
    w.unmount()
    const w2 = mountComposer({ sending: true })
    expect(w2.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined()
    w2.unmount()
  })

  it('입력은 v-model로 올라가고 hint 문구를 보여준다', async () => {
    const w = mountComposer({ modelValue: '' })
    await w.find('textarea').setValue('새 질문')
    expect(w.emitted('update:modelValue')![0]).toEqual(['새 질문'])
    expect(w.text()).toContain('Enter 전송 · Shift+Enter 줄바꿈')
    w.unmount()
  })
})

describe('QuestionComposer 음성 입력 (받아쓰기만 — 자동 전송 없음, 사용자가 확인·수정 후 전송)', () => {
  beforeEach(() => {
    FakeSpeechRecognition.reset()
    FakeSpeechRecognition.install()
  })
  afterEach(() => {
    FakeSpeechRecognition.uninstall()
  })

  async function mountListening(props: Record<string, unknown> = {}) {
    const w = mountComposer({ modelValue: '', ...props })
    await w.vm.$nextTick() // supported는 마운트 후 판정
    await w.find('[data-test="composer-mic"]').trigger('click')
    return { w, rec: FakeSpeechRecognition.last() }
  }

  it('브라우저가 SpeechRecognition을 지원하지 않으면 마이크 버튼을 그리지 않는다', async () => {
    FakeSpeechRecognition.uninstall()
    const w = mountComposer()
    await w.vm.$nextTick()
    expect(w.find('[data-test="composer-mic"]').exists()).toBe(false)
    w.unmount()
  })

  it('지원하면 마이크 버튼이 마운트 뒤 나타나고, 탭하면 듣기 시작 — 입력창 읽기 전용·전송 잠금·aria 전환', async () => {
    const w = mountComposer({ modelValue: '' })
    await w.vm.$nextTick()
    const mic = w.find('[data-test="composer-mic"]')
    expect(mic.exists()).toBe(true)
    // 마이크는 툴바가 아니라 입력창(q-input) append 슬롯에 둔다 — 390px에서 툴바(픽커+MCP+전송)가 이미 꽉 차 있어
    // 버튼을 더 넣으면 전송 버튼이 밀려 나간다(2026-09-08 프리뷰 빌드 실측: 내용 361px > 바 322px).
    expect(w.find('.q-field__append [data-test="composer-mic"]').exists()).toBe(true)
    expect(mic.attributes('aria-label')).toBe('음성 입력')
    expect(mic.attributes('aria-pressed')).toBe('false')
    await mic.trigger('click')
    expect(FakeSpeechRecognition.instances).toHaveLength(1)
    expect(w.find('[data-test="composer-mic"]').attributes('aria-label')).toBe('음성 입력 중지')
    expect(w.find('[data-test="composer-mic"]').attributes('aria-pressed')).toBe('true')
    expect(w.find('textarea').attributes('readonly')).toBeDefined()
    expect(w.find('textarea').attributes('placeholder')).toBe('듣는 중…')
    expect(w.find('[data-test="composer-send"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('인식 결과는 기존 텍스트 뒤에 공백으로 이어 붙어 v-model로 올라가고, 중간 결과는 확정 결과로 교체된다', async () => {
    const { w, rec } = await mountListening({ modelValue: '기존 질문' })
    rec.emitResults([{ text: '로그인은', final: false }])
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual(['기존 질문 로그인은'])
    rec.emitResults([
      { text: '로그인은 어디서', final: true },
      { text: '처리하나요', final: false },
    ])
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual(['기존 질문 로그인은 어디서 처리하나요'])
    w.unmount()
  })

  it('두 번째 탭은 stop을 부르고, end 이벤트 뒤 입력·전송이 다시 열린다 — 받아쓴 텍스트는 그대로 남는다', async () => {
    const { w, rec } = await mountListening()
    rec.emitResults([{ text: '토큰은 어디서 검증하나요', final: true }])
    await w.find('[data-test="composer-mic"]').trigger('click')
    expect(rec.stopCalls).toBe(1)
    rec.triggerEnd()
    await w.vm.$nextTick()
    expect(w.find('textarea').attributes('readonly')).toBeUndefined()
    expect(w.find('[data-test="composer-send"]').attributes('disabled')).toBeUndefined()
    expect(w.find('[data-test="composer-mic"]').attributes('aria-label')).toBe('음성 입력')
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual(['토큰은 어디서 검증하나요'])
    expect(w.emitted('send')).toBeUndefined() // 자동 전송 없음
    w.unmount()
  })

  it('듣는 중에는 Enter로도 전송되지 않는다', async () => {
    const { w } = await mountListening({ modelValue: '질문' })
    await w.find('textarea').trigger('keydown', { key: 'Enter' })
    expect(w.emitted('send')).toBeUndefined()
    w.unmount()
  })

  it('마이크 권한이 거부되면 경고 알림을 띄우고 대기 상태로 돌아간다', async () => {
    const { w, rec } = await mountListening()
    const notifySpy = vi.spyOn((w.vm as any).$q, 'notify')
    rec.triggerError('not-allowed')
    rec.triggerEnd() // 브라우저는 error 뒤 end를 낸다
    await w.vm.$nextTick()
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'warning', message: '마이크 권한이 필요합니다' }),
    )
    expect(w.find('[data-test="composer-mic"]').attributes('aria-label')).toBe('음성 입력')
    expect(w.find('textarea').attributes('readonly')).toBeUndefined()
    w.unmount()
  })

  it('no-speech(아무 말 없음)는 알림 없이 조용히 대기로 돌아간다', async () => {
    const { w, rec } = await mountListening()
    const notifySpy = vi.spyOn((w.vm as any).$q, 'notify')
    rec.triggerError('no-speech')
    rec.triggerEnd()
    await w.vm.$nextTick()
    expect(notifySpy).not.toHaveBeenCalled()
    expect(w.find('[data-test="composer-mic"]').attributes('aria-label')).toBe('음성 입력')
    w.unmount()
  })

  it('입력이 막히면(disabled) 진행 중인 인식을 즉시 중단한다', async () => {
    const { w, rec } = await mountListening()
    await w.setProps({ disabled: true })
    expect(rec.abortCalls).toBe(1)
    expect(w.find('[data-test="composer-mic"]').attributes('aria-label')).toBe('음성 입력')
    w.unmount()
  })

  it('듣는 중 언마운트하면 인식을 중단한다', async () => {
    const { w, rec } = await mountListening()
    w.unmount()
    expect(rec.abortCalls).toBe(1)
  })
})

describe('QuestionComposer 첨부 (스펙 2026-09-13 §7 — 클립은 prepend 슬롯, 대기 칩 줄, 한도 검증, 잠금)', () => {
  // q-file은 숨겨져 있어 실제 파일 대화상자를 못 연다 — 선택 결과는 update:modelValue emit으로 흉내낸다
  // (test/tasks-form-attachments.spec.ts와 동일).
  function pick(w: ReturnType<typeof mountComposer>, files: File[]) {
    w.findComponent(QFile).vm.$emit('update:modelValue', files)
    return w.vm.$nextTick()
  }
  const fileA = () => new File(['abc'], 'a.txt', { type: 'text/plain' })
  const fileB = () => new File(['defg'], 'b.png', { type: 'image/png' })

  it('클립 버튼은 입력창 prepend 슬롯에 있고, 탭하면 숨은 q-file의 네이티브 파일 입력을 연다', async () => {
    const w = mountComposer()
    // 마이크(append)와 같은 이유로 툴바가 아니라 입력창 안(prepend) — 390px에서 툴바(픽커+MCP+전송)는 이미 꽉 차 있다.
    const clip = w.find('.q-field__prepend [data-test="composer-attach"]')
    expect(clip.exists()).toBe(true)
    expect(clip.attributes('aria-label')).toBe('파일 첨부')
    // q-file은 inheritAttrs:false로 attrs를 네이티브 <input type=file>에 얹는다 — data-test도 거기 붙는다
    const input = w.find('input[type="file"][data-test="composer-file-input"]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('multiple')).toBeDefined()
    const nativeClick = vi.spyOn(input.element as HTMLInputElement, 'click')
    await clip.trigger('click')
    expect(nativeClick).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('파일을 고르면 #top 슬롯과 입력창 사이의 칩 줄에 이름·크기가 뜨고 update:files로 올라가며, 다시 고르면 누적된다', async () => {
    const w = mount(QuestionComposer, {
      props: { canSend: true, model: 'claude-opus-5', effort: 'high', modelValue: '질문' },
      slots: { top: '<div data-test="top-marker" />' },
    })
    expect(w.find('[data-test="composer-files"]').exists()).toBe(false) // 대기 파일 없으면 줄 자체를 그리지 않는다
    const a = fileA()
    await pick(w, [a])
    const row = w.find('[data-test="composer-files"]')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('a.txt')
    expect(row.text()).toContain('3B')
    expect(w.emitted('update:files')!.at(-1)).toEqual([[a]])
    // 순서: #top → 칩 줄 → 입력창
    const top = w.find('[data-test="top-marker"]').element
    const ta = w.find('textarea').element
    expect(top.compareDocumentPosition(row.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(row.element.compareDocumentPosition(ta) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const b = fileB()
    await pick(w, [b])
    expect(w.emitted('update:files')!.at(-1)).toEqual([[a, b]])
    expect(w.findAll('[data-test="composer-files"] .q-chip')).toHaveLength(2)
    w.unmount()
  })

  it('칩의 제거 아이콘으로 파일을 하나씩 뺀다', async () => {
    const w = mountComposer()
    const a = fileA()
    const b = fileB()
    await pick(w, [a, b])
    const chips = w.findAll('[data-test="composer-files"] .q-chip')
    expect(chips).toHaveLength(2)
    await chips[0]!.find('.q-chip__icon--remove').trigger('click')
    expect(w.emitted('update:files')!.at(-1)).toEqual([[b]])
    expect(w.findAll('[data-test="composer-files"] .q-chip')).toHaveLength(1)
    expect(w.find('[data-test="composer-files"]').text()).not.toContain('a.txt')
    w.unmount()
  })

  it('한도를 넘는 추가는 통째로 거부하고 경고한다 (기존 9개 + 새 2개 = 11개)', async () => {
    const w = mountComposer()
    const nine = Array.from({ length: 9 }, (_, i) => new File(['x'], `f${i}.txt`))
    await pick(w, nine)
    const notifySpy = vi.spyOn((w.vm as any).$q, 'notify')
    await pick(w, [new File(['y'], 'g1.txt'), new File(['y'], 'g2.txt')])
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'warning', message: '첨부는 최대 10개까지 가능합니다' }),
    )
    expect(w.emitted('update:files')!.at(-1)).toEqual([nine]) // 새 2개 중 하나도 들어가지 않는다
    expect(w.findAll('[data-test="composer-files"] .q-chip')).toHaveLength(9)
    w.unmount()
  })

  it('0바이트 파일은 경고하고 칩 줄을 만들지 않는다', async () => {
    const w = mountComposer()
    const notifySpy = vi.spyOn((w.vm as any).$q, 'notify')
    await pick(w, [new File([], 'empty.txt')])
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'warning', message: '빈 파일(0바이트)은 첨부할 수 없습니다' }),
    )
    expect(w.emitted('update:files')).toBeUndefined()
    expect(w.find('[data-test="composer-files"]').exists()).toBe(false)
    w.unmount()
  })

  it('disabled(입력 대기 아님)·sending 중에는 클립 버튼이 잠긴다', () => {
    const w = mountComposer({ disabled: true })
    expect(w.find('[data-test="composer-attach"]').attributes('disabled')).toBeDefined()
    w.unmount()
    const w2 = mountComposer({ sending: true })
    expect(w2.find('[data-test="composer-attach"]').attributes('disabled')).toBeDefined()
    w2.unmount()
    const w3 = mountComposer()
    expect(w3.find('[data-test="composer-attach"]').attributes('disabled')).toBeUndefined()
    w3.unmount()
  })

  it('부모가 v-model:files로 넘긴 대기 파일도 칩으로 그린다 (전송 성공 후 부모가 비우면 줄이 사라진다)', async () => {
    const w = mountComposer({ files: [fileA()] })
    expect(w.find('[data-test="composer-files"]').text()).toContain('a.txt')
    await w.setProps({ files: [] })
    expect(w.find('[data-test="composer-files"]').exists()).toBe(false)
    w.unmount()
  })
})
