// vitest 트랜스폼(esbuild)이 쓰는 TS 컴파일 옵션.
// .nuxt/tsconfig.json(nuxi prepare 생성물)에서 Vite가 esbuild로 넘기는 필드만 같은 값으로 옮겨 둔 것이라,
// .nuxt가 있을 때와 트랜스폼 결과가 같다. Nuxt 업그레이드로 생성물 값이 바뀌면
// test/esbuild-tsconfig.spec.ts가 깨진다 → 여기를 생성물에 맞출 것.
export const ESBUILD_COMPILER_OPTIONS = {
  target: 'ESNext',
  verbatimModuleSyntax: true,
  jsx: 'preserve',
  jsxImportSource: 'vue',
  useDefineForClassFields: true,
}

// vitest.config.ts의 `esbuild` 옵션. tsconfigRaw가 **문자열**이어야 Vite가 tsconfig.json 조회를 건너뛴다
// (객체면 여전히 tsconfig.json을 읽어 병합하므로 .nuxt가 없을 때 그대로 죽는다).
export const VITEST_ESBUILD_OPTIONS = {
  tsconfigRaw: JSON.stringify({ compilerOptions: ESBUILD_COMPILER_OPTIONS }),
}
