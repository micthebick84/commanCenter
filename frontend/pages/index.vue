<script setup lang="ts">
definePageMeta({ layout: 'default' })
onMounted(() => navigateTo('/tasks'))
</script>

<template>
  <q-page class="flex flex-center">
    <q-spinner-dots color="primary" size="3em" />
  </q-page>
</template>
