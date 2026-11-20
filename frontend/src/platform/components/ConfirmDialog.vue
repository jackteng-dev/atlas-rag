<script setup lang="ts">
import Modal from './Modal.vue'
import ErrorBanner from './ErrorBanner.vue'
withDefaults(defineProps<{ title: string; message: string; busy?: boolean; error?: unknown; confirmLabel?: string }>(), { confirmLabel: '確認刪除' })
const emit = defineEmits<{ confirm: []; close: [] }>()
</script>
<template><Modal :title="title" :busy="busy" @close="emit('close')"><p>{{ message }}</p><ErrorBanner :error="error" /><div class="actions end"><button type="button" :disabled="busy" @click="emit('close')">取消</button><button type="button" class="danger" :disabled="busy" @click="emit('confirm')">{{ busy ? '處理中…' : confirmLabel }}</button></div></Modal></template>

