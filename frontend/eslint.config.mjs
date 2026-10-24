// ESLint 9 flat config (2026-10-24). Nuxt 모듈(@nuxt/eslint) 없이 설정 팩만 쓴다 — nuxt.config·빌드는 그대로.
// 스타일 규칙(stylistic)은 끈다: 따옴표·세미콜론·줄바꿈은 손으로 맞춘 기존 스타일과 .prettierrc가 맡는다(CLAUDE.md).
// 도입 시점의 기존 부채는 경고로 둔다(오류 0 기준선) — 새 코드에서 경고를 늘리지 말 것.
import { createConfigForNuxt } from '@nuxt/eslint-config/flat'

export default createConfigForNuxt(
  { features: { stylistic: false } },
  { ignores: ['.nuxt/**', '.output/**', '.output.bak-*/**', 'node_modules/**', 'test-results/**'] },
  {
    rules: {
      // 기존 부채(소스 any 51건, process.server 5건 등) — 경고로 보이게만 한다
      '@typescript-eslint/no-explicit-any': 'warn',
      'nuxt/prefer-import-meta': 'warn',
      'no-useless-assignment': 'warn',
      'preserve-caught-error': 'warn',
      'vue/no-multiple-template-root': 'warn',
      // defineEmits<{ (e: 'a', …): void; (e: 'b', …): void }>() 호출 시그니처가 이 코드베이스의 emits 관례다
      '@typescript-eslint/unified-signatures': 'off',
    },
  },
  {
    // <script>(export)와 <script setup>을 함께 쓰는 SFC에서 setup 블록의 import를 "본문 속 import"로 오판한다
    files: ['**/*.vue'],
    rules: { 'import/first': 'off' },
  },
  {
    // 테스트 더블·목은 any와 this 별칭을 쓴다
    files: ['**/*.spec.ts', 'test/**'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-this-alias': 'off',
    },
  },
)
