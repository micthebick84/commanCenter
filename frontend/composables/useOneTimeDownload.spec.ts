import { describe, it, expect, vi, afterEach } from 'vitest'
import { useOneTimeDownload, downloadErrorMessage } from './useOneTimeDownload'
import { failMsg } from './failMessage'

afterEach(() => vi.useRealTimers())

describe('useOneTimeDownload', () => {
  it('진행 중·쿨다운 중 재호출은 무시하고 지나면 다시 받는다', async () => {
    vi.useFakeTimers()
    const start = vi.fn().mockResolvedValue(undefined)
    const { busy, run } = useOneTimeDownload(start, 2000)
    await run(1)
    await run(1)
    expect(start).toHaveBeenCalledTimes(1)
    expect(busy.value).toBe(true)
    vi.advanceTimersByTime(2000)
    expect(busy.value).toBe(false)
    await run(1)
    expect(start).toHaveBeenCalledTimes(2)
  })
  it('오류면 바로 풀고 오류를 던진다', async () => {
    const start = vi.fn().mockRejectedValueOnce(new Error('x')).mockResolvedValue(undefined)
    const { busy, run } = useOneTimeDownload(start)
    await expect(run(1)).rejects.toThrow('x')
    expect(busy.value).toBe(false)
  })
})

describe('downloadErrorMessage', () => {
  it('410은 정리 안내, 그 밖은 서버 메시지 또는 기본', () => {
    expect(downloadErrorMessage({ statusCode: 410 })).toBe('보관 파일이 정리되었습니다')
    expect(downloadErrorMessage({ data: { message: 'm' } })).toBe('m')
    expect(downloadErrorMessage(new Error('n'))).toBe(failMsg('내려받기'))
  })
})
