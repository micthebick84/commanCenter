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
      // 백엔드 사용자 API. nitro가 prefix를 strip 후 target에 append하므로
      // target에 '/api'까지 포함해야 최종 경로가 'http://localhost:8090/api/...'로 도달
      '/api': {
        target: (process.env.NUXT_API_PROXY_TARGET || 'http://localhost:8090') + '/api',
        changeOrigin: true,
      },
      // netis-auth OAuth2 엔드포인트 (브라우저 CORS 우회용)
      // 1) Origin을 same-origin으로 rewrite (netis-auth CorsFilter가 외부 Origin 거부)
      // 2) prefix는 '/oauth2', target에도 '/oauth2' 포함 → nitro가 prefix strip 후
      //    target에 다시 append되어 최종 http://localhost:9000/oauth2/token 도달
      '/oauth2': {
        target: (process.env.NUXT_AUTH_PROXY_TARGET || 'http://localhost:9000') + '/oauth2',
        changeOrigin: true,
        headers: {
          origin: process.env.NUXT_AUTH_PROXY_TARGET || 'http://localhost:9000',
          referer: process.env.NUXT_AUTH_PROXY_TARGET || 'http://localhost:9000',
        },
      },
    },
  },

  typescript: {
    strict: true,
  },

  css: ['~/assets/css/main.css'],
})
