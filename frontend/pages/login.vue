<script setup lang="ts">
import { useQuasar } from 'quasar'

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
        <div class="text-h5 q-mb-md">netisMaker</div>
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
