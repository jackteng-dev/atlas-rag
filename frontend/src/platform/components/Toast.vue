<script setup lang="ts">
import { useToastStore } from '../stores/toast'
const toast = useToastStore()
</script>
<template><div class="toast-host" role="status" aria-live="polite"><div v-for="item in toast.items" :key="item.id" class="toast"><span>{{ item.message }}</span><button aria-label="關閉通知" @click="toast.remove(item.id)">×</button></div></div></template>

