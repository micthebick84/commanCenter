<template>
  <!-- 툴바 없는 전체 화면 레이아웃 — 로그인 화면(2026-10-11 디자인 승인 C안)과 디자인 목업 크게 보기(pages/designs/[id].vue, 2026-10-31)가 쓴다. 그 밖의 앱 화면은 default.vue를 쓴다. -->
  <q-layout view="hHh lpR fFf">
    <q-page-container>
      <slot />
    </q-page-container>
  </q-layout>
</template>
