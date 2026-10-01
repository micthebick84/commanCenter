<script setup lang="ts">
// 관리 카탈로그(레포·MCP) 모바일 카드 — lt.md에서 q-table 대신 쓴다. TaskCardCompact와 같은 카드 톤.
// 위→아래: 제목+배지 / 활성 토글(우상단) · 본문(기본 슬롯: 경로·URL·상태 등) · 액션 바(actions 슬롯, 버튼은 균등 폭 44px).
// 비활성 항목은 본문을 흐리게 하고 "비활성" 표식을 붙여, 토글 위치를 보지 않아도 상태가 읽히게 한다.
// enabled를 안 넘기면 토글·비활성 표식이 없는 읽기 전용 카드(워커 헬스), actions 슬롯이 없으면 액션 바도 없다.
// enabled 기본값을 undefined로 명시 — 생략 시 Vue의 Boolean 캐스팅이 false(=비활성 카드)로 바꾸는 것을 막는다.
withDefaults(defineProps<{ title: string; enabled?: boolean }>(), { enabled: undefined })
const emit = defineEmits<{ (e: 'toggle'): void }>()
</script>

<template>
  <div
    class="catalog-card"
    :class="{ 'catalog-card--off': enabled === false, 'catalog-card--no-actions': !$slots.actions }"
    data-test="catalog-card"
  >
    <div class="row no-wrap items-start">
      <div class="col card-head" :class="{ 'card-head--toggle': enabled !== undefined }">
        <span class="card-title">{{ title }}</span>
        <slot name="badges" />
        <span v-if="enabled === false" class="card-off" data-test="card-off">비활성</span>
      </div>
      <q-toggle
        v-if="enabled !== undefined"
        :model-value="enabled"
        color="positive"
        class="card-toggle"
        :aria-label="enabled ? '비활성화' : '활성화'"
        data-test="card-toggle"
        @update:model-value="emit('toggle')"
      />
    </div>
    <div class="card-body">
      <slot />
    </div>
    <div v-if="$slots.actions" class="card-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.catalog-card {
  border: 1px solid rgba(0, 0, 0, 0.14);
  border-radius: 6px;
  background: #fff;
  padding: 12px 12px 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.07);
  overflow: hidden; /* 액션 바의 각진 버튼이 카드 모서리 밖으로 안 나오게 */
}
.catalog-card--no-actions {
  padding-bottom: 12px;
}
.card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 6px;
  min-width: 0;
}
/* 토글(40px)과 제목 줄의 세로 중심을 맞춘다 */
.card-head--toggle {
  padding-top: 8px;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.35;
  color: #212121;
  word-break: break-all;
}
.card-off {
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
  background: #eeeeee;
  color: #757575;
}
.card-toggle {
  margin: -2px -8px 0 0;
}
.card-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12.5px;
  color: #616161;
  min-width: 0;
}
.catalog-card--off .card-body {
  opacity: 0.6;
}
.card-body :deep(code) {
  word-break: break-all;
}
/* 본문 슬롯 보조 클래스 — 항목명/값 2열 표(워커 헬스) */
.card-body :deep(.card-kv) {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 4px 12px;
  align-items: center;
}
.card-body :deep(.card-kv dt) {
  color: #9e9e9e;
}
.card-body :deep(.card-kv dd) {
  margin: 0;
  color: #424242;
  min-width: 0;
  overflow-wrap: anywhere; /* 긴 호스트명은 줄바꿈하되, 들어가는 값은 단어 중간에서 끊지 않는다 */
}
/* 본문 슬롯에서 쓰는 보조 클래스: URL은 작은 고정폭, 설명은 2줄까지 */
.card-body :deep(.card-url) {
  font-size: 11.5px;
  line-height: 1.4;
}
.card-body :deep(.card-desc) {
  color: #424242;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-actions {
  display: flex;
  margin: 2px -12px 0;
  border-top: 1px solid rgba(0, 0, 0, 0.08);
}
.card-actions :deep(.q-btn) {
  flex: 1 1 0;
  min-width: 0;
  min-height: 44px;
  padding: 0 4px;
  border-radius: 0;
  font-size: 13px;
}
/* 360px 폭에서 "헬스 테스트"처럼 긴 라벨이 아이콘 아래로 꺾이지 않게 한 줄 고정 */
.card-actions :deep(.q-btn__content) {
  flex-wrap: nowrap;
  white-space: nowrap;
}
.card-actions :deep(.q-btn .q-icon) {
  font-size: 18px;
  margin-right: 6px;
}
.card-actions :deep(.q-btn + .q-btn) {
  border-left: 1px solid rgba(0, 0, 0, 0.08);
}
</style>
