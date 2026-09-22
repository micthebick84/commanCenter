import { describe, it, expect } from 'vitest'
import {
  MAX_FILES,
  MAX_FILE_MB,
  MAX_TOTAL_MB,
  validateFiles,
  formatSize,
} from './attachmentLimits'

const MB = 1024 * 1024

// Blob.size는 프로토타입 getter라 own data property로 가리면 실제 버퍼 없이 경계값을 만들 수 있다
// (test/tasks-form-attachments.spec.ts의 sizedFile과 동일).
function sizedFile(name: string, size: number): File {
  const f = new File([], name)
  Object.defineProperty(f, 'size', { value: size, configurable: true })
  return f
}

describe('attachmentLimits — 작업 등록·질문 채팅 공용 첨부 한도 (스펙 2026-09-13 §7)', () => {
  it('상수는 서버 AttachmentStorage.validate와 같은 값이다 (10개 · 20MB · 합계 50MB)', () => {
    expect(MAX_FILES).toBe(10)
    expect(MAX_FILE_MB).toBe(20)
    expect(MAX_TOTAL_MB).toBe(50)
  })

  it('개수 초과 · 파일당 초과 · 0바이트 · 합계 초과를 각각 거부한다', () => {
    expect(
      validateFiles(
        Array.from({ length: 11 }, (_, i) => sizedFile(`f${i}.txt`, 1)),
      ),
    ).toBe('첨부는 최대 10개까지 가능합니다')
    expect(validateFiles([sizedFile('big.bin', 20 * MB + 1)])).toBe(
      '파일당 20MB 이하만 첨부할 수 있습니다: big.bin',
    )
    expect(validateFiles([sizedFile('empty.txt', 0)])).toBe(
      '빈 파일(0바이트)은 첨부할 수 없습니다',
    )
    expect(
      validateFiles([
        sizedFile('a.bin', 19 * MB),
        sizedFile('b.bin', 19 * MB),
        sizedFile('c.bin', 19 * MB),
      ]),
    ).toBe('첨부 합계는 50MB 이하여야 합니다')
  })

  it('경계값(정확히 10개 · 파일당 20MB · 합계 50MB)과 빈 배열은 통과한다', () => {
    expect(validateFiles([])).toBeNull()
    expect(
      validateFiles(
        Array.from({ length: 10 }, (_, i) => sizedFile(`f${i}.bin`, 5 * MB)),
      ),
    ).toBeNull()
    expect(validateFiles([sizedFile('exact20.bin', 20 * MB)])).toBeNull()
  })

  it('formatSize는 B/KB/MB 세 분기로 표기한다 (pages/tasks/[id].vue와 동일 규칙)', () => {
    expect(formatSize(512)).toBe('512B')
    expect(formatSize(1536)).toBe('2KB')
    expect(formatSize(5 * MB)).toBe('5.0MB')
  })
})
