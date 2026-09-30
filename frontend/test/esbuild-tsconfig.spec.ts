import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
import { transformWithEsbuild } from 'vite'
import { describe, expect, it } from 'vitest'
import { ESBUILD_COMPILER_OPTIONS, VITEST_ESBUILD_OPTIONS } from './esbuildTsconfig'

// vitest.config.ts는 tsconfig.json(→ 생성물 .nuxt/tsconfig.json) 대신 esbuildTsconfig.ts의 고정값으로
// 트랜스폼한다 — .nuxt 없는 새 clone/worktree에서도 테스트가 돌게. CI는 npm ci의 postinstall(nuxi prepare)로
// 항상 .nuxt가 있어 그 조건을 직접 겪지 않으므로 여기서 두 가지를 고정한다.

// Vite(transformWithEsbuild)가 tsconfig에서 골라 esbuild로 넘기는 필드 — vite 소스의 meaningfulFields와 같은 목록.
const ESBUILD_FIELDS = [
  'alwaysStrict',
  'experimentalDecorators',
  'importsNotUsedAsValues',
  'jsx',
  'jsxFactory',
  'jsxFragmentFactory',
  'jsxImportSource',
  'preserveValueImports',
  'target',
  'useDefineForClassFields',
  'verbatimModuleSyntax',
]

// happy-dom의 URL은 file: 기준 상대 경로를 location(http://localhost:3000) 기준으로 풀어 버려
// `new URL('..', import.meta.url)`를 못 쓴다 — 문자열로 경로를 만든다.
const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const rootTsconfig = join(frontendRoot, 'tsconfig.json')
const nuxtTsconfig = join(frontendRoot, '.nuxt', 'tsconfig.json')

function readTsconfig(file: string): { extends?: string; compilerOptions?: Record<string, unknown> } {
  const { config, error } = ts.readConfigFile(file, ts.sys.readFile)
  if (error) throw new Error(ts.flattenDiagnosticMessageText(error.messageText, '\n'))
  return config
}

describe('vitest 트랜스폼 tsconfig (.nuxt 비의존)', () => {
  it('tsconfig.json의 extends 대상이 없어도 vitest의 esbuild 옵션으로 트랜스폼된다', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'vitest-tsconfig-'))
    try {
      writeFileSync(join(dir, 'tsconfig.json'), JSON.stringify({ extends: './.nuxt/tsconfig.json' }))
      const { code } = await transformWithEsbuild(
        'const n: number = 1\nexport { n }\n',
        join(dir, 'a.ts'),
        VITEST_ESBUILD_OPTIONS,
      )
      expect(code).toContain('const n = 1')
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  // 고정값이 실제 tsconfig 체인과 어긋나면 테스트만 빌드와 다르게 트랜스폼된다(예: verbatimModuleSyntax가
  // 바뀌면 `type` 없이 타입으로만 쓰는 import의 제거 여부가 갈림) — nuxi prepare가 돈 환경(CI 포함)에서 잡는다.
  it.skipIf(!existsSync(nuxtTsconfig))('고정 옵션이 tsconfig.json → .nuxt/tsconfig.json 체인의 값과 같다', () => {
    const root = readTsconfig(rootTsconfig)
    // 체인 구조가 바뀌면(예: Nuxt 4의 references 구성) 이 가드와 esbuildTsconfig.ts를 함께 다시 볼 것.
    expect(root.extends).toBe('./.nuxt/tsconfig.json')
    const effective = { ...readTsconfig(nuxtTsconfig).compilerOptions, ...root.compilerOptions }
    const expected = Object.fromEntries(
      ESBUILD_FIELDS.filter((field) => field in effective).map((field) => [field, effective[field]]),
    )
    expect(ESBUILD_COMPILER_OPTIONS).toEqual(expected)
  })
})
