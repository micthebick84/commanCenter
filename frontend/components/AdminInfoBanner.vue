<script setup lang="ts">
// 관리 카탈로그 상단 안내 박스. 데스크톱은 늘 펼친 q-banner, 모바일(lt.md)은 한 줄 요약(summary)만 보이는 접힌 상태로
// 시작하고 탭하면 본문(기본 슬롯)이 펼쳐진다 — 긴 안내가 카드 목록 위 화면의 1/4을 차지하지 않게.
import { useQuasar } from 'quasar'

defineProps<{ summary: string }>()

const $q = useQuasar()
const open = ref(false)
</script>

<template>
  <div v-if="$q.screen.lt.md" class="info-fold q-mb-md" data-test="info-banner">
    <button
      type="button"
      class="info-fold-head"
      :aria-expanded="open"
      data-test="info-banner-toggle"
      @click="open = !open"
    >
      <q-icon name="info" color="primary" size="20px" />
      <span class="info-fold-summary">{{ summary }}</span>
      <q-icon :name="open ? 'expand_less' : 'expand_more'" color="grey-7" size="22px" />
    </button>
    <q-slide-transition>
      <div v-if="open" class="info-fold-body" data-test="info-banner-body">
        <slot />
      </div>
    </q-slide-transition>
  </div>
  <q-banner v-else class="bg-blue-1 text-grey-9 q-mb-md" data-test="info-banner">
    <template #avatar><q-icon name="info" color="primary" /></template>
    <slot />
  </q-banner>
</template>

<style scoped>
.info-fold {
  background: #e3f2fd;
  border-radius: 6px;
  color: #424242;
}
.info-fold-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 44px;
  padding: 6px 8px 6px 12px;
  border: 0;
  background: transparent;
  font: inherit;
  font-size: 13.5px;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.info-fold-summary {
  flex: 1 1 0;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.info-fold-body {
  padding: 0 12px 12px 40px;
  font-size: 13px;
  line-height: 1.5;
}
</style>
