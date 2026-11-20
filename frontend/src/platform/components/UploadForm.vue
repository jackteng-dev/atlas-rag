<script setup lang="ts">
import { ref } from 'vue'
import { useTask } from '../composables/useTask'
import ErrorBanner from './ErrorBanner.vue'
const props = defineProps<{ upload: (file: File, progress: (n: number) => void) => Promise<unknown> }>()
const emit = defineEmits<{ uploaded: [] }>()
const file = ref<File | null>(null)
const input = ref<HTMLInputElement>()
const progress = ref(0)
const { busy, error, run } = useTask()
function choose(e: Event) { file.value = (e.target as HTMLInputElement).files?.[0] ?? null }
async function submit() {
  if (!file.value) return
  const selected = file.value
  await run(async () => {
    if (!/\.(md|txt|pdf|docx|xlsx)$/i.test(selected.name)) throw new Error('請選擇 .md、.txt、.pdf、.docx 或 .xlsx 檔案。')
    progress.value = 0
    await props.upload(selected, n => { progress.value = n })
    file.value = null; if (input.value) input.value.value = ''
    emit('uploaded')
  })
}
</script>
<template><form class="upload-form" @submit.prevent="submit"><label>上傳文件（.md / .txt / .pdf / .docx / .xlsx）<input ref="input" type="file" accept=".md,.txt,.pdf,.docx,.xlsx" :disabled="busy" @change="choose"></label><button class="primary" :disabled="busy || !file">{{ busy ? '上傳中…' : '上傳' }}</button><div v-if="busy" class="upload-progress" role="status"><progress :value="progress" max="100" aria-label="上傳進度"></progress>{{ progress }}%{{ progress === 100 ? '，伺服器處理中…' : '' }}</div><ErrorBanner :error="error" /></form></template>

