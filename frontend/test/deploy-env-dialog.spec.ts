import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import DeployEnvDialog from '../components/tasks/DeployEnvDialog.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

const suggestion = {
  rows: [
    { key: 'DB_URL', value: 'jdbc:prev', secret: false, description: 'DB 접속 URL', required: true, source: 'TEMPLATE', valueFromPrevious: true },
    { key: 'JWT_SECRET', value: '', secret: true, description: '서명 키', required: true, source: 'TEMPLATE', valueFromPrevious: false },
  ],
  templateCount: 2,
  previousTaskId: 41,
}

function mountDialog(props: { mode?: 'deploy' | 'redeploy'; savedEnvVars?: Array<{ key: string; value: string; secret: boolean }> } = {}) {
  return mount(DeployEnvDialog, {
    attachTo: document.body,
    props: { modelValue: true, taskId: '42', mode: 'deploy', savedEnvVars: [], ...props },
  })
}

const body = () => document.body
const keyLabels = () =>
  [...body().querySelectorAll('[data-test="env-key-label"]')].map((e) => e.textContent?.trim())

describe('DeployEnvDialog (스펙 2026-10-07 §8)', () => {
  beforeEach(() => {
    useApiMock.mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('추천을 불러와 KEY는 라벨로, 설명·필수·직전 배포 표식과 상단 안내를 보여 준다', async () => {
    useApiMock.mockResolvedValueOnce(suggestion)
    const w = mountDialog()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/deploy-env')
    expect(keyLabels()).toEqual(['DB_URL', 'JWT_SECRET'])
    expect(body().querySelectorAll('[data-test="env-key-input"]').length).toBe(0)
    expect(body().textContent).toContain('DB 접속 URL')
    expect(body().textContent).toContain('직전 배포 값')
    expect(body().textContent).toContain('배포 — 환경변수')
    expect(body().querySelector('[data-test="deploy-env-caption"]')?.textContent).toContain('task #41')
    w.unmount()
  })

  it('필수 값이 비면 경고와 "그래도 배포", 전송은 빈 값 행을 뺀다', async () => {
    useApiMock.mockResolvedValueOnce(suggestion).mockResolvedValueOnce({})
    const w = mountDialog()
    await flushPromises()
    expect(body().querySelector('[data-test="env-missing-required"]')?.textContent).toContain('JWT_SECRET')
    expect(body().querySelector('[data-test="env-submit"]')?.textContent).toContain('그래도 배포')
    await (w.vm as any).submit()
    expect(useApiMock).toHaveBeenLastCalledWith('/api/tasks/42/deploy', {
      method: 'POST',
      body: { envVars: [{ key: 'DB_URL', value: 'jdbc:prev', secret: false }] },
    })
    expect(w.emitted('submitted')).toBeTruthy()
    expect(w.emitted('update:modelValue')?.[0]).toEqual([false])
    w.unmount()
  })

  it('필수 값을 모두 채우면 경고가 사라지고 버튼은 평소 문구', async () => {
    useApiMock.mockResolvedValueOnce(suggestion)
    const w = mountDialog({ mode: 'redeploy' })
    await flushPromises()
    ;(w.vm as any).rows[1].value = 'sign-key'
    await flushPromises()
    expect(body().querySelector('[data-test="env-missing-required"]')).toBeNull()
    expect(body().querySelector('[data-test="env-submit"]')?.textContent?.trim()).toBe('재배포')
    w.unmount()
  })

  it('추천을 못 받으면 저장된 env로 채우고 비밀 값도 그대로 다시 보낸다', async () => {
    useApiMock.mockRejectedValueOnce(new Error('500')).mockResolvedValueOnce({})
    const w = mountDialog({ mode: 'redeploy', savedEnvVars: [{ key: 'DB_PASSWORD', value: 'pw', secret: true }] })
    await flushPromises()
    expect(body().querySelectorAll('[data-test="env-key-input"]').length).toBe(1) // 대체 경로는 KEY 편집 가능
    await (w.vm as any).submit()
    expect(useApiMock).toHaveBeenLastCalledWith('/api/tasks/42/redeploy', {
      method: 'POST',
      body: { envVars: [{ key: 'DB_PASSWORD', value: 'pw', secret: true }] },
    })
    w.unmount()
  })

  it('엉뚱한 응답(rows 없음)도 대체 경로로', async () => {
    useApiMock.mockResolvedValueOnce({ id: 42, envVars: [] })
    const w = mountDialog({ savedEnvVars: [{ key: 'A', value: '1', secret: false }] })
    await flushPromises()
    expect((w.vm as any).rows.map((r: any) => r.key)).toEqual(['A'])
    w.unmount()
  })

  it('닫았다 다시 열면 추천을 새로 불러온다', async () => {
    useApiMock.mockResolvedValue(suggestion)
    const w = mountDialog()
    await flushPromises()
    ;(w.vm as any).rows[0].value = '고친 값'
    await w.setProps({ modelValue: false })
    await w.setProps({ modelValue: true })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledTimes(2)
    expect((w.vm as any).rows[0].value).toBe('jdbc:prev')
    w.unmount()
  })

  it('늦게 도착한 이전 응답은 새로 연 다이얼로그를 덮지 않는다', async () => {
    let resolveFirst!: (v: unknown) => void
    useApiMock
      .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
      .mockResolvedValueOnce(suggestion)
    const w = mountDialog()
    await w.setProps({ modelValue: false })
    await w.setProps({ modelValue: true })
    await flushPromises()
    resolveFirst({
      rows: [{ key: 'STALE', value: 'x', secret: false, description: '', required: false, source: 'TEMPLATE', valueFromPrevious: false }],
      templateCount: 1,
      previousTaskId: null,
    })
    await flushPromises()
    expect((w.vm as any).rows.map((r: any) => r.key)).toEqual(['DB_URL', 'JWT_SECRET'])
    w.unmount()
  })

  it('390px에서는 행 입력이 세로로 쌓인다', async () => {
    await setViewportWidth(390)
    useApiMock.mockResolvedValueOnce(suggestion)
    const w = mountDialog()
    await flushPromises()
    expect(body().querySelector('[data-test="env-row"] .column')).not.toBeNull()
    w.unmount()
    await setViewportWidth(1024)
  })
})
