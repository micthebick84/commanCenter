import { defineStore } from 'pinia'

interface MeResponse {
  user_id: number
  username: string | null
  email: string | null
  authorities: string[]
  expires_at?: string
}

interface TokenResponse {
  access_token: string
  refresh_token?: string
  id_token?: string
  expires_in: number
}

const STORAGE_KEY = 'netis-maker-auth'

/**
 * netis-auth OAuth2 Authorization Code + PKCE 흐름.
 * SPA(public client)이므로 client_secret 없음.
 *
 *   loginRedirect() ─► state, code_verifier 저장 + netis-auth/authorize 리다이렉트
 *   handleCallback() ─► code → access_token 교환 → localStorage 저장
 *   me()             ─► GET /api/me로 user_id, authorities 갱신
 *   logout()         ─► 토큰 폐기 + /login 이동
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: null as string | null,
    refreshToken: null as string | null,
    expiresAt: 0 as number,
    me: null as MeResponse | null,
  }),
  getters: {
    isAuthenticated: (s) => !!s.accessToken && s.expiresAt > Date.now(),
    isAdmin: (s) => !!s.me?.authorities?.includes('ROLE_ADMIN'),
    userId: (s) => s.me?.user_id ?? null,
  },
  actions: {
    restore() {
      if (process.server) return
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return
      try {
        const parsed = JSON.parse(raw)
        this.accessToken = parsed.accessToken ?? null
        this.refreshToken = parsed.refreshToken ?? null
        this.expiresAt = parsed.expiresAt ?? 0
        this.me = parsed.me ?? null
      } catch {
        /* 무시 */
      }
    },
    persist() {
      if (process.server) return
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({
          accessToken: this.accessToken,
          refreshToken: this.refreshToken,
          expiresAt: this.expiresAt,
          me: this.me,
        }),
      )
    },

    async loginRedirect() {
      const config = useRuntimeConfig().public
      const state = randomString(32)
      const codeVerifier = randomString(64)
      const codeChallenge = await sha256Base64Url(codeVerifier)
      sessionStorage.setItem('oauth_state', state)
      sessionStorage.setItem('oauth_verifier', codeVerifier)

      const params = new URLSearchParams({
        response_type: 'code',
        client_id: config.clientId,
        redirect_uri: config.redirectUri,
        scope: 'openid profile email',
        state,
        code_challenge: codeChallenge,
        code_challenge_method: 'S256',
      })
      window.location.href = `${config.authIssuer}/oauth2/authorize?${params}`
    },

    async handleCallback(code: string, state: string) {
      const config = useRuntimeConfig().public
      const savedState = sessionStorage.getItem('oauth_state')
      const verifier = sessionStorage.getItem('oauth_verifier')
      sessionStorage.removeItem('oauth_state')
      sessionStorage.removeItem('oauth_verifier')

      if (!savedState || savedState !== state || !verifier) {
        throw new Error('OAuth state 불일치')
      }

      const params = new URLSearchParams({
        grant_type: 'authorization_code',
        code,
        redirect_uri: config.redirectUri,
        client_id: config.clientId,
        code_verifier: verifier,
      })

      const res = await fetch(`${config.authIssuer}/oauth2/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params,
      })
      if (!res.ok) throw new Error(`토큰 교환 실패: ${res.status}`)
      const json = (await res.json()) as TokenResponse

      this.accessToken = json.access_token
      this.refreshToken = json.refresh_token ?? null
      this.expiresAt = Date.now() + json.expires_in * 1000
      this.persist()
      await this.refreshMe()
    },

    async refreshMe() {
      const me = await $fetch<MeResponse>('/api/me', {
        headers: { Authorization: `Bearer ${this.accessToken}` },
      })
      this.me = me
      this.persist()
    },

    logout() {
      this.accessToken = null
      this.refreshToken = null
      this.expiresAt = 0
      this.me = null
      if (process.client) localStorage.removeItem(STORAGE_KEY)
      navigateTo('/login')
    },
  },
})

function randomString(len: number): string {
  const bytes = new Uint8Array(len)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('').slice(0, len)
}

async function sha256Base64Url(input: string): Promise<string> {
  const data = new TextEncoder().encode(input)
  const buf = await crypto.subtle.digest('SHA-256', data)
  return base64UrlEncode(buf)
}

function base64UrlEncode(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf)
  let str = ''
  for (let i = 0; i < bytes.byteLength; i++) str += String.fromCharCode(bytes[i])
  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
