// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2026-05-01',
  devtools: { enabled: true },

  ssr: true,

  devServer: {
    port: 3001,
  },

  modules: ['@pinia/nuxt', 'nuxt-quasar-ui'],

  quasar: {
    plugins: ['Dialog', 'Notify', 'Loading'],
    extras: {
      fontIcons: ['material-icons'],
    },
  },

  runtimeConfig: {
    public: {
      apiBaseUrl: process.env.NUXT_PUBLIC_API_BASE_URL || '/api',
      authIssuer: process.env.NUXT_PUBLIC_AUTH_ISSUER || 'http://localhost:9000',
      clientId: process.env.NUXT_PUBLIC_CLIENT_ID || 'netis-maker-spa',
      redirectUri:
        process.env.NUXT_PUBLIC_REDIRECT_URI || 'http://localhost:3001/oauth/callback',
      pollIntervalMs: 5000,
    },
  },

  nitro: {
    devProxy: {
      // 백엔드 사용자 API
      '/api': {
        target: process.env.NUXT_API_PROXY_TARGET || 'http://localhost:8090',
        changeOrigin: true,
      },
    },
  },

  typescript: {
    strict: true,
  },

  css: ['~/assets/css/main.css'],
})
