import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
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
