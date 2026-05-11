<script setup lang="ts">
definePageMeta({ layout: 'default' })

const route = useRoute()
const auth = useAuthStore()
const error = ref<string | null>(null)

onMounted(async () => {
  const code = route.query.code as string | undefined
  const state = route.query.state as string | undefined
  if (!code || !state) {
    error.value = 'OAuth 응답에 code 또는 state가 없음'
    return
  }
  try {
    await auth.handleCallback(code, state)
    navigateTo('/tasks')
  } catch (e: any) {
    error.value = e?.message ?? '로그인 처리 실패'
  }
})
</script>

<template>
  <q-page class="flex flex-center column">
    <q-spinner-dots v-if="!error" color="primary" size="3em" />
    <q-banner v-if="error" class="bg-negative text-white" style="max-width: 500px">
      <template #avatar><q-icon name="error" /></template>
      로그인 실패: {{ error }}
      <template #action>
        <q-btn flat label="다시 시도" @click="navigateTo('/login')" />
      </template>
    </q-banner>
  </q-page>
</template>
