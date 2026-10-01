import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import { VITEST_ESBUILD_OPTIONS } from './test/esbuildTsconfig'

export default defineConfig({
  plugins: [vue()],
  // Vite는 .ts/<script lang="ts">를 esbuild로 트랜스폼할 때마다 가장 가까운 tsconfig.json을 읽는데,
  // 그 파일이 생성물 .nuxt/tsconfig.json을 extends한다 — nuxi prepare를 안 돈 새 clone/worktree/캐시 복원
  // 환경에서는 테스트를 한 건도 수집하기 전에 전부 `TSConfckParseError`로 죽었다.
  // tsconfigRaw를 문자열로 주면 Vite가 tsconfig.json 조회 자체를 건너뛰므로(@vitejs/plugin-vue도 이 옵션을
  // 물려받음) 트랜스폼이 .nuxt 유무와 무관해진다. 타입체크·빌드·IDE는 그대로 tsconfig.json → .nuxt를 쓴다.
  // 남는 차이(nuxi prepare로 사라짐): .nuxt가 없으면 @vue/compiler-sfc가 SFC 매크로 타입의 `~/` import를
  // 못 푼다 — prop 하나의 타입이면 그 prop의 런타임 타입 검사만 빠지고(type: null), defineProps<Imported>()처럼
  // props 전체를 `~/`에서 가져오면 컴파일 에러다(현재 그런 컴포넌트 없음, 상대 경로 import는 무관).
  // 설정 로드 때 esbuild가 내는 "Cannot find base config file" 경고도 같은 원인이며 무해하다.
  esbuild: VITEST_ESBUILD_OPTIONS,
  resolve: {
    alias: {
      // Force Quasar's browser/client build under Vitest. The package's `node`
      // export condition points at the SSR build (quasar.server.prod.js) whose
      // install() throws under happy-dom; alias straight to the client entry.
      quasar: fileURLToPath(
        new URL('./node_modules/quasar/dist/quasar.client.js', import.meta.url),
      ),
      '~': fileURLToPath(new URL('./', import.meta.url)),
      '@': fileURLToPath(new URL('./', import.meta.url)),
    },
  },
  test: {
    globals: true,
    environment: 'happy-dom',
    setupFiles: ['./test/setup.ts'],
    include: ['**/*.spec.ts'],
    exclude: ['node_modules', '.nuxt', '.output', 'dist'],
  },
})
