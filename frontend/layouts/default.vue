<script setup lang="ts">
const auth = useAuthStore()

function handleLogout() {
  auth.logout()
}
</script>

<template>
  <q-layout view="hHh lpR fFf">
    <q-header elevated class="bg-primary text-white">
      <q-toolbar>
        <q-toolbar-title>
          <NuxtLink to="/tasks" style="color: inherit; text-decoration: none">netisMaker</NuxtLink>
        </q-toolbar-title>
        <q-tabs v-if="auth.isAuthenticated" shrink>
          <q-route-tab to="/tasks" label="작업" />
          <q-route-tab v-if="auth.isAdmin" to="/admin/workers" label="워커 헬스" />
        </q-tabs>
        <q-space />
        <div v-if="auth.me" class="q-mr-sm">
          {{ auth.me.username || auth.me.email }}
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
