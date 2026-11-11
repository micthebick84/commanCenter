<script setup lang="ts">
definePageMeta({ layout: 'default' })

const route = useRoute()
const auth = useAuthStore()
const error = ref<string | null>(null)

onMounted(async () => {
  const code = route.query.code as string | undefined
  const state = route.query.state as string | undefined
  // 화면에는 사용자가 할 일만 보이고, 기술 사유(OAuth 값 누락·토큰 교환 HTTP 상태 등)는 콘솔에만 남긴다(2026-11-11 검토)
  if (!code || !state) {
    console.warn('[oauth] callback without code/state', { hasCode: !!code, hasState: !!state })
    error.value = LOGIN_RETRY
    return
  }
  try {
    await auth.handleCallback(code, state)
    navigateTo('/tasks')
  } catch (e: any) {
    console.warn('[oauth] callback failed', e)
    error.value = LOGIN_RETRY
  }
})
const LOGIN_RETRY = '로그인을 마치지 못했습니다. 아래 "다시 시도"를 눌러 다시 로그인해 주세요.'
</script>

<template>
  <q-page class="flex flex-center column">
    <q-spinner-dots v-if="!error" color="primary" size="3em" />
    <q-banner v-if="error" class="bg-negative text-white" style="max-width: 500px">
      <template #avatar><q-icon name="error" /></template>
      {{ error }}
      <template #action>
        <q-btn flat label="다시 시도" @click="navigateTo('/login')" />
      </template>
    </q-banner>
  </q-page>
</template>
