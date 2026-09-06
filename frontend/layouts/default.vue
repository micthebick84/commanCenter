<script setup lang="ts">
import { useQuasar } from 'quasar'

const auth = useAuthStore()
const $q = useQuasar()

function handleLogout() {
  $q.dialog({
    title: '로그아웃',
    message: '정말로 로그아웃 하시겠습니까?',
    ok: { label: '로그아웃', color: 'negative', unelevated: true },
    cancel: { label: '취소', flat: true },
    persistent: true,
  }).onOk(() => {
    auth.logout()
  })
}
</script>

<template>
  <q-layout view="hHh lpR fFf">
    <q-header elevated class="bg-primary text-white">
      <q-toolbar>
        <!-- 좁은 화면(lt.md)에서는 제목이 탭에 밀려 "n"까지 줄어들므로 flex-shrink:0으로 고정 — Quasar shrink(col-shrink)는
             flex: 0 1 auto라 여전히 줄어든다. 데스크톱 배치는 그대로 (2026-09-06 모바일 QA) -->
        <q-toolbar-title :class="{ 'toolbar-brand--fixed': $q.screen.lt.md }">
          <NuxtLink to="/tasks" style="color: inherit; text-decoration: none">netisMaker</NuxtLink>
        </q-toolbar-title>
        <q-tabs v-if="auth.isAuthenticated" shrink>
          <q-route-tab to="/tasks" label="작업" />
          <q-route-tab to="/questions" label="질문" />
          <q-route-tab v-if="auth.isAdmin" to="/admin/workers" label="워커 헬스" />
          <q-route-tab v-if="auth.isAdmin" to="/admin/mcp-catalog" label="MCP 카탈로그" />
          <q-route-tab v-if="auth.isAdmin" to="/admin/repo-catalog" label="레포 카탈로그" />
        </q-tabs>
        <q-space />
        <!-- xs(<600px)에서는 사용자명을 숨기고 역할 칩만 남긴다 — 사용자 블록이 두 줄로 꺾여 툴바가 깨지던 것 방지 -->
        <div v-if="auth.me" class="row items-center no-wrap q-mr-sm" data-test="toolbar-user">
          <span v-if="!$q.screen.xs" class="q-mr-xs">{{ auth.me.username || auth.me.email }}</span>
          <q-chip
            v-if="auth.isAdmin"
            color="amber"
            text-color="black"
            size="sm"
            label="ADMIN"
            dense
          />
        </div>
        <q-btn v-if="auth.isAuthenticated" flat dense icon="logout" @click="handleLogout" />
      </q-toolbar>
    </q-header>
    <q-page-container>
      <slot />
    </q-page-container>
  </q-layout>
</template>

<style scoped>
/* 좁은 화면 브랜드: 내용 폭 고정(줄어들지 않음). q-toolbar(.row)의 .col-shrink/.q-toolbar__title 규칙보다 구체적이어야 한다. */
.q-toolbar > .toolbar-brand--fixed {
  flex: 0 0 auto;
}
</style>
