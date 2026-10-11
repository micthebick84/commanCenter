<template>
  <!-- 툴바 없는 전체 화면 레이아웃 — 로그인 전용 (2026-10-11 디자인 승인 C안). 앱 화면은 default.vue를 쓴다. -->
  <q-layout view="hHh lpR fFf">
    <q-page-container>
      <slot />
    </q-page-container>
  </q-layout>
</template>
