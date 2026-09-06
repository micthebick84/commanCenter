<script setup lang="ts">
// 관리 하위 3페이지 전환 (스펙 2026-09-06 §4.4) — 모바일에서 헤더 탭이 사라지므로 관리 페이지 상단에 세그먼트로 제공.
import { useQuasar } from 'quasar'
const $q = useQuasar()
</script>

<template>
  <q-tabs
    v-if="$q.screen.lt.md"
    dense
    no-caps
    align="justify"
    active-color="primary"
    indicator-color="primary"
    class="q-mb-sm admin-tabs"
    data-test="admin-section-tabs"
  >
    <q-route-tab to="/admin/workers" label="워커 헬스" />
    <q-route-tab to="/admin/mcp-catalog" label="MCP 카탈로그" />
    <q-route-tab to="/admin/repo-catalog" label="레포 카탈로그" />
  </q-tabs>
</template>

<style scoped>
.admin-tabs :deep(.q-tab) {
  min-height: 44px;
}
</style>
