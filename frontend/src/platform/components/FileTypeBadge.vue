<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ extension?: string | null }>()
const type = computed(() => props.extension?.replace(/^\./, '').toLowerCase() || '')
</script>
<template><span class="file-type" :class="'file-type-' + type">{{ type.toUpperCase() || '—' }}</span></template>
<style scoped>
.file-type { display:inline-flex; align-items:center; width:fit-content; padding:2px 7px; border-radius:5px; font-size:10px; line-height:1.5; font-weight:700; letter-spacing:.4px; color:var(--muted); background:var(--surface-alt) }
.file-type-md { color:var(--primary); background:var(--primary-soft) }
.file-type-txt { color:var(--muted); background:color-mix(in srgb, var(--muted) 12%, var(--surface)) }
.file-type-pdf { color:var(--danger); background:color-mix(in srgb, var(--danger) 10%, var(--surface)) }
.file-type-docx { color:color-mix(in srgb, #639be5 65%, var(--text)); background:color-mix(in srgb, #639be5 14%, var(--surface)) }
.file-type-xlsx { color:var(--warning); background:var(--warning-soft) }
</style>
