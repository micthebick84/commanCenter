import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { FakeSpeechRecognition } from '~/test/mocks/speechRecognition'
import {
  useSpeechInput,
  type SpeechTranscript,
  type UseSpeechInputOptions,
} from './useSpeechInput'

// 컴포저블은 onMounted/onUnmounted를 쓰므로 빈 컴포넌트 안에서 세팅한다.
function mountWith(opts: Partial<UseSpeechInputOptions> = {}) {
  const onTranscript = vi.fn<(t: SpeechTranscript) => void>()
  const onEnd = vi.fn()
  const onError = vi.fn<(code: string) => void>()
  let api!: ReturnType<typeof useSpeechInput>
  const w = mount(
    defineComponent({
      setup() {
        api = useSpeechInput({ onTranscript, onEnd, onError, ...opts })
        return () => h('div')
      },
    }),
  )
  return { w, api, onTranscript, onEnd, onError }
}

describe('useSpeechInput (질문 탭 음성 입력)', () => {
  beforeEach(() => {
    FakeSpeechRecognition.reset()
    FakeSpeechRecognition.install()
  })
  afterEach(() => {
    FakeSpeechRecognition.uninstall()
  })

  it('SpeechRecognition 전역이 없으면 supported=false이고 start는 아무것도 하지 않는다', () => {
    FakeSpeechRecognition.uninstall()
    const { w, api } = mountWith()
    expect(api.supported.value).toBe(false)
    api.start()
    expect(api.listening.value).toBe(false)
    expect(FakeSpeechRecognition.instances).toHaveLength(0)
    w.unmount()
  })

  it('webkit 프리픽스 전역(Safari)도 지원으로 인식한다', () => {
    FakeSpeechRecognition.uninstall()
    FakeSpeechRecognition.install('webkitSpeechRecognition')
    const { w, api } = mountWith()
    expect(api.supported.value).toBe(true)
    w.unmount()
  })

  it('start는 ko-KR·continuous·interimResults로 인식기를 만들어 시작하고 listening을 켠다', () => {
    const { w, api } = mountWith()
    expect(api.supported.value).toBe(true)
    api.start()
    const rec = FakeSpeechRecognition.last()
    expect(rec.lang).toBe('ko-KR')
    expect(rec.continuous).toBe(true)
    expect(rec.interimResults).toBe(true)
    expect(rec.started).toBe(true)
    expect(api.listening.value).toBe(true)
    w.unmount()
  })

  it('listening 중 start를 다시 불러도 두 번째 인식기를 만들지 않는다', () => {
    const { w, api } = mountWith()
    api.start()
    api.start()
    expect(FakeSpeechRecognition.instances).toHaveLength(1)
    w.unmount()
  })

  it('result 이벤트에서 확정 텍스트와 중간 텍스트를 분리해 onTranscript로 넘긴다', () => {
    const { w, api, onTranscript } = mountWith()
    api.start()
    FakeSpeechRecognition.last().emitResults([
      { text: '로그인은', final: true },
      { text: ' 어디서', final: false }, // Chrome은 후속 결과에 선행 공백을 붙인다
    ])
    expect(onTranscript).toHaveBeenLastCalledWith({
      final: '로그인은',
      interim: '어디서',
    })
    w.unmount()
  })

  it('후속 result 이벤트는 누적 목록 전체에서 다시 계산한다(확정 결과는 공백으로 이어 붙임)', () => {
    const { w, api, onTranscript } = mountWith()
    api.start()
    const rec = FakeSpeechRecognition.last()
    rec.emitResults([{ text: '로그인은', final: false }])
    rec.emitResults([
      { text: '로그인은', final: true },
      { text: '어디서 처리하나요', final: true },
    ])
    expect(onTranscript).toHaveBeenLastCalledWith({
      final: '로그인은 어디서 처리하나요',
      interim: '',
    })
    w.unmount()
  })

  it('stop은 인식기 stop을 부르고, end 이벤트가 와야 listening이 풀리며 onEnd가 불린다', () => {
    const { w, api, onEnd } = mountWith()
    api.start()
    const rec = FakeSpeechRecognition.last()
    api.stop()
    expect(rec.stopCalls).toBe(1)
    expect(api.listening.value).toBe(true) // 브라우저가 end를 낼 때까지는 듣는 중
    rec.triggerEnd()
    expect(api.listening.value).toBe(false)
    expect(onEnd).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('브라우저가 스스로 인식을 끝내면(침묵 타임아웃) listening만 풀리고 onEnd가 불린다', () => {
    const { w, api, onEnd } = mountWith()
    api.start()
    FakeSpeechRecognition.last().triggerEnd()
    expect(api.listening.value).toBe(false)
    expect(onEnd).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('error 이벤트는 onError(code)로 전달하고 listening을 푼다', () => {
    const { w, api, onError } = mountWith()
    api.start()
    FakeSpeechRecognition.last().triggerError('not-allowed')
    expect(onError).toHaveBeenCalledWith('not-allowed')
    expect(api.listening.value).toBe(false)
    w.unmount()
  })

  it('abort는 인식기를 즉시 버리고 listening을 푼다 — 뒤늦은 end 이벤트는 무시', () => {
    const { w, api, onEnd } = mountWith()
    api.start()
    const rec = FakeSpeechRecognition.last()
    api.abort()
    expect(rec.abortCalls).toBe(1)
    expect(api.listening.value).toBe(false)
    rec.triggerEnd()
    expect(onEnd).not.toHaveBeenCalled()
    w.unmount()
  })

  it('언마운트 시 진행 중인 인식을 abort한다', () => {
    const { w, api } = mountWith()
    api.start()
    const rec = FakeSpeechRecognition.last()
    w.unmount()
    expect(rec.abortCalls).toBe(1)
  })
})
