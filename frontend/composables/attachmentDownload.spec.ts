import { describe, it, expect, vi, afterEach } from 'vitest'
import { downloadAttachment } from './attachmentDownload'
import { useApiMock } from '../test/mocks/nuxt'

// URL.createObjectURL/revokeObjectURL은 대입으로 갈아끼우므로 원본으로 되돌린다
// (test/task-detail-attachments.spec.ts와 동일 규약 — happy-dom에선 원본이 undefined일 수 있다).
const origCreateObjectURL = URL.createObjectURL
const origRevokeObjectURL = URL.revokeObjectURL
let clickSpy: ReturnType<typeof vi.spyOn> | null = null

afterEach(() => {
  clickSpy?.mockRestore()
  clickSpy = null
  Object.assign(URL, {
    createObjectURL: origCreateObjectURL,
    revokeObjectURL: origRevokeObjectURL,
  })
})

describe('downloadAttachment — useApi blob → objectURL → <a download> (스펙 2026-09-13 §7)', () => {
  it('responseType blob으로 받아 메타 파일명으로 저장하고, 다음 틱에 objectURL을 해제한다', async () => {
    const createObjectURL = vi.fn(() => 'blob:mock')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    let anchor: HTMLAnchorElement | null = null
    let connectedAtClick = false
    clickSpy = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function (this: HTMLAnchorElement) {
        anchor = this
        connectedAtClick = this.isConnected
      })
    const blob = new Blob(['docx'])
    useApiMock.mockResolvedValue(blob)

    await downloadAttachment('/api/questions/3/attachments/7', '요구사항.docx')

    expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/attachments/7', {
      responseType: 'blob',
    })
    expect(createObjectURL).toHaveBeenCalledWith(blob)
    expect(anchor!.download).toBe('요구사항.docx')
    expect(anchor!.href).toContain('blob:mock')
    expect(connectedAtClick).toBe(true) // Firefox는 DOM에 붙지 않은 앵커의 click을 무시한다
    expect(revokeObjectURL).not.toHaveBeenCalled() // click과 같은 틱에 해제하면 다운로드가 중단될 수 있다
    await new Promise((r) => setTimeout(r, 0))
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock')
    expect(anchor!.isConnected).toBe(false)
  })

  it('실패 시 Blob 에러 본문의 message를 꺼내 Error로 던진다 (responseType blob이면 e.data도 Blob)', async () => {
    const createObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL })
    useApiMock.mockRejectedValue({
      statusCode: 403,
      data: new Blob([JSON.stringify({ message: '권한이 없습니다' })], {
        type: 'application/json',
      }),
    })
    await expect(
      downloadAttachment('/api/questions/3/attachments/7', 'x.txt'),
    ).rejects.toThrow('권한이 없습니다')
    expect(createObjectURL).not.toHaveBeenCalled()
  })

  it('에러 본문이 JSON이 아니면 "다운로드 실패", 일반 객체면 그 message로 던진다', async () => {
    useApiMock.mockRejectedValue({
      statusCode: 500,
      data: new Blob(['<html>'], { type: 'text/html' }),
    })
    await expect(downloadAttachment('/u', 'x')).rejects.toThrow('다운로드 실패')
    useApiMock.mockRejectedValue({
      statusCode: 500,
      data: { message: '서버 오류' },
    })
    await expect(downloadAttachment('/u', 'x')).rejects.toThrow('서버 오류')
  })
})
