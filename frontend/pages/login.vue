<script setup lang="ts">
import { useQuasar } from 'quasar'
import BrandMark from '~/components/BrandMark.vue'

// 로그인 전용 화면(툴바 없음) — 2026-10-11 디자인 승인 C안: primary 그라디언트 배경 + 악보 마크 워터마크 + 흰 카드.
// 카드에는 브랜드 락업과 "Login" 버튼만 둔다(제품 설명 문구 없음). 자격 증명은 netis-auth 로그인 화면에서 입력한다.
definePageMeta({ layout: 'blank' })

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
  <q-page class="login-page flex flex-center" data-test="login-page">
    <div class="login-watermark" aria-hidden="true" data-test="login-watermark">
      <BrandMark :size="640" />
    </div>
    <q-card class="login-card column items-center" flat data-test="login-card">
      <!-- 세로 락업: 마크(56px, primary) 위 + "Symphony" 아래 -->
      <div class="column items-center login-brand" data-test="login-brand">
        <BrandMark :size="56" class="text-primary" />
        <h1 class="login-brand__name">Symphony</h1>
      </div>
      <q-btn
        unelevated
        no-caps
        color="primary"
        label="Login"
        class="full-width login-btn"
        data-test="login-btn"
        @click="startLogin"
      />
    </q-card>
  </q-page>
</template>

<style scoped>
.login-page {
  position: relative;
  overflow: hidden;
  padding: 48px 16px;
  background: linear-gradient(160deg, var(--q-primary) 0%, #0d47a1 100%);
}
.login-watermark {
  position: absolute;
  right: -140px;
  bottom: -160px;
  color: #fff;
  opacity: 0.08;
  pointer-events: none;
}
.login-card {
  position: relative;
  width: 100%;
  max-width: 420px;
  border-radius: 16px;
  box-shadow: 0 24px 48px rgba(0, 0, 0, 0.28);
  padding: 48px 40px 40px;
  gap: 32px;
}
.login-brand {
  gap: 12px;
}
.login-brand__name {
  margin: 0;
  font-size: 28px;
  font-weight: 500;
  letter-spacing: 0.01em;
  line-height: 1.2;
}
.login-btn {
  height: 44px;
  border-radius: 6px;
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.01em;
}
@media (max-width: 599px) {
  .login-page {
    padding: 24px 16px;
  }
  .login-card {
    padding: 40px 24px 32px;
    gap: 28px;
  }
  .login-watermark {
    right: -120px;
    bottom: -110px;
  }
  .login-watermark :deep(svg) {
    width: 420px;
    height: 420px;
  }
}
</style>
