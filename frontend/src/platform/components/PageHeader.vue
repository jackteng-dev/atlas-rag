<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>
<template><header class="page-header"><div><p class="eyebrow">ATLAS / WORKSPACE</p><h1>{{ title }}</h1><p v-if="description" class="muted">{{ description }}</p></div><div class="actions"><slot /></div></header></template>

