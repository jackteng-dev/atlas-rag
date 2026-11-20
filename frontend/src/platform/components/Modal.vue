<script setup lang="ts">
import { ref, onMounted, onUnmounted, useId } from 'vue'
defineProps<{ title: string; busy?: boolean; side?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const headingId = useId()
const previouslyFocused = document.activeElement as HTMLElement | null
onMounted(() => dialog.value?.showModal())
onUnmounted(() => { dialog.value?.close(); previouslyFocused?.focus() })
</script>
<template><Teleport to="body"><dialog ref="dialog" class="modal" :class="{ 'side-panel': side }" :aria-labelledby="headingId" @cancel.prevent="!busy && emit('close')"><header class="modal-header"><h2 :id="headingId">{{ title }}</h2><button type="button" :disabled="busy" aria-label="關閉對話框" @click="emit('close')">×</button></header><slot /></dialog></Teleport></template>

