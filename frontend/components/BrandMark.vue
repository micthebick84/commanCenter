<script setup lang="ts">
// Symphony 브랜드 마크 — 2026-10-11 디자인 검토에서 승인된 A4(악보 S).
// 오선 3줄 위에 4분음표 7개가 S 궤적을 그리고, 바깥쪽 4개(위 둘은 위로, 아래 둘은 아래로)에 스템을 단다.
// 24 그리드 인라인 SVG, 색은 currentColor — 헤더(흰 글자)·로그인(text-primary)·탭 등 쓰는 곳의 글자색을 따른다.
// 파비콘(public/favicon.svg)은 같은 패스를 primary 색으로 박은 사본 — 둘이 어긋나면 test/brand-assets.spec.ts가 잡는다.
withDefaults(defineProps<{ size?: number }>(), { size: 24 })
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    aria-hidden="true"
    data-test="brand-mark"
    class="brand-mark"
  >
    <g class="brand-mark__staff" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" opacity="0.4">
      <path d="M3.5 7h17" />
      <path d="M3.5 12h17" />
      <path d="M3.5 17h17" />
    </g>
    <g class="brand-mark__stems" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
      <path d="M13.9 4.6V1" />
      <path d="M18.5 6.8V3.2" />
      <path d="M10.1 19.4V23" />
      <path d="M5.6 17.3v3.6" />
    </g>
    <g class="brand-mark__heads" fill="currentColor">
      <ellipse cx="16.6" cy="7.1" rx="2.3" ry="1.8" transform="rotate(-20 16.6 7.1)" />
      <ellipse cx="12" cy="4.9" rx="2.3" ry="1.8" transform="rotate(-20 12 4.9)" />
      <ellipse cx="7.6" cy="8" rx="2.3" ry="1.8" transform="rotate(-20 7.6 8)" />
      <ellipse cx="12" cy="12" rx="2.3" ry="1.8" transform="rotate(-20 12 12)" />
      <ellipse cx="16.6" cy="15.7" rx="2.3" ry="1.8" transform="rotate(-20 16.6 15.7)" />
      <ellipse cx="12" cy="19.1" rx="2.3" ry="1.8" transform="rotate(-20 12 19.1)" />
      <ellipse cx="7.5" cy="17" rx="2.3" ry="1.8" transform="rotate(-20 7.5 17)" />
    </g>
  </svg>
</template>

<style scoped>
.brand-mark {
  flex: 0 0 auto;
  display: block;
}
</style>
