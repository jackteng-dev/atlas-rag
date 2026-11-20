<script setup lang="ts">
withDefaults(defineProps<{ title?: string; description?: string }>(), { title: '目前沒有資料' })
</script>
<template><section class="empty-state"><span aria-hidden="true" class="empty-mark">◇</span><h2>{{ title }}</h2><p v-if="description" class="muted">{{ description }}</p><slot /></section></template>

