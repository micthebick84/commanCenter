<script setup lang="ts">
import { useQuasar } from 'quasar'
import BrandMark from '~/components/BrandMark.vue'

definePageMeta({ layout: 'default' })

const auth = useAuthStore()
const route = useRoute()
const $q = useQuasar()

onMounted(() => {
  auth.restore()
  // RP-Initiated Logout 복귀 시 ?logout=true. 토큰은 이미 비어있으니 자동 이동 없음.
  if (route.query.logout === 'true') {
    $q.notify({ type: 'positive', message: '로그아웃되었습니다.', timeout: 2500 })
    return
  }
  if (auth.isAuthenticated) navigateTo('/tasks')
})

function startLogin() {
  auth.loginRedirect()
}
</script>

<template>
  <q-page class="flex flex-center column">
    <q-card style="max-width: 400px; width: 90%" flat bordered>
      <q-card-section class="text-center">
        <!-- 세로 락업: 마크(44px, primary) 위 + "Symphony" 아래 — 2026-10-11 디자인 승인 -->
        <div class="column items-center q-mb-md login-brand" data-test="login-brand">
          <BrandMark :size="44" class="text-primary" />
          <h1 class="text-h5 q-ma-none">Symphony</h1>
        </div>
        <p>Netis 플랫폼 작업 분석 자동화 도구</p>
      </q-card-section>
      <q-card-section>
        <q-btn
          unelevated
          color="primary"
          icon="login"
          label="netis-auth로 로그인"
          class="full-width"
          @click="startLogin"
        />
      </q-card-section>
    </q-card>
  </q-page>
</template>

<style scoped>
.login-brand {
  gap: 10px;
}
</style>
