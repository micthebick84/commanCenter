<script setup lang="ts">
definePageMeta({ layout: 'default' })

const auth = useAuthStore()

onMounted(() => {
  auth.restore()
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
