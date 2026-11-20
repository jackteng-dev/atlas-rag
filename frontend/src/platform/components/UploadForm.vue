<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ApiError } from '../api/client'
import { fileExtension, fileSize } from '../composables/files'
import FileTypeBadge from './FileTypeBadge.vue'
const props = defineProps<{ upload: (file: File, progress: (n: number) => void) => Promise<unknown> }>()
const emit = defineEmits<{ uploaded: [] }>()
type State = 'QUEUED' | 'UPLOADING' | 'SUCCESS' | 'FAILED' | 'REJECTED' | 'CANCELLED'
interface Entry { id: number; file: File; state: State; progress: number; message: string; traceId: string }
const queue = ref<Entry[]>([])
const input = ref<HTMLInputElement>()
const busy = ref(false); const dragging = ref(false); const finished = ref(false)
let nextId = 0; let stopped = false; let alive = true
const queued = computed(() => queue.value.filter(item => item.state === 'QUEUED'))
const successes = computed(() => queue.value.filter(item => item.state === 'SUCCESS').length)
const failures = computed(() => queue.value.filter(item => item.state === 'FAILED').length)
function add(files: FileList | File[] | null) {
  if (!files || busy.value) return
  finished.value = false
  for (const file of Array.from(files)) {
    const reasons = []
    if (!['md','txt','pdf','docx','xlsx'].includes(fileExtension(file.name))) reasons.push('不支援的檔案類型；請使用 .md、.txt、.pdf、.docx 或 .xlsx')
    if (file.size > 50 * 1024 * 1024) reasons.push('檔案超過 50 MB 上限')
    queue.value.push({ id: ++nextId, file, state: reasons.length ? 'REJECTED' : 'QUEUED', progress: 0, message: reasons.join('；'), traceId: '' })
  }
}
function choose(e: Event) { const element = e.target as HTMLInputElement; add(element.files); element.value = '' }
function drop(e: DragEvent) { dragging.value = false; add(e.dataTransfer?.files ?? null) }
function remove(id: number) { queue.value = queue.value.filter(item => item.id !== id || item.state !== 'QUEUED') }
function cancelRemaining() {
  stopped = true
  for (const item of queue.value) if (item.state === 'QUEUED') item.state = 'CANCELLED'
}
async function submit() {
  if (busy.value || !queued.value.length) return
  busy.value = true; finished.value = false; stopped = false
  try {
    while (alive && !stopped) {
      const item = queued.value[0]
      if (!item) break
      item.state = 'UPLOADING'
      try {
        await props.upload(item.file, n => { item.progress = Math.min(100, Math.max(0, n)) })
        item.progress = 100; item.state = 'SUCCESS'
        if (alive) emit('uploaded')
      } catch (e) {
        item.state = 'FAILED'
        item.message = e instanceof Error ? e.message : '上傳失敗，請重試。'
        if (e instanceof ApiError) { item.message = e.code + '：' + item.message; item.traceId = e.traceId }
      }
    }
  } finally { busy.value = false; finished.value = true }
}
onBeforeUnmount(() => { alive = false; cancelRemaining() })
const labels: Record<State, string> = { QUEUED:'等待上傳', UPLOADING:'上傳中', SUCCESS:'成功', FAILED:'失敗', REJECTED:'未接受', CANCELLED:'已取消' }
</script>
<template>
  <form class="upload-form batch-upload" @submit.prevent="submit">
    <input ref="input" class="sr-only" tabindex="-1" type="file" multiple accept=".md,.txt,.pdf,.docx,.xlsx" :disabled="busy" aria-label="選擇上傳文件（可多選）" @change="choose">
    <button type="button" class="drop-zone" aria-label="拖曳檔案到這裡，或點擊選擇" :class="{ dragging }" :disabled="busy" @click="input?.click()" @dragover.prevent="!busy && (dragging = true)" @dragleave.prevent="dragging = false" @drop.prevent="drop">
      <strong>拖曳檔案到這裡，或點擊選擇</strong>
      <small>.md / .txt / .pdf / .docx / .xlsx · 每個檔案上限 50 MB</small>
    </button>
    <ul v-if="queue.length" class="upload-queue" aria-label="檔案上傳佇列">
      <li v-for="item in queue" :key="item.id" :aria-busy="item.state === 'UPLOADING'">
        <div class="queue-file"><FileTypeBadge :extension="fileExtension(item.file.name)" /><strong>{{ item.file.name }}</strong><small class="muted">{{ fileSize(item.file.size) }}</small><button v-if="item.state === 'QUEUED'" type="button" :aria-label="'移除 ' + item.file.name" @click="remove(item.id)">移除</button></div>
        <progress :value="item.progress" max="100" :aria-label="item.file.name + ' 上傳進度'"></progress>
        <div class="queue-result" :class="{ 'upload-error': item.state === 'FAILED' || item.state === 'REJECTED' }">
          {{ labels[item.state] }}<span v-if="item.state === 'UPLOADING'"> · {{ item.progress }}%{{ item.progress === 100 ? '，伺服器處理中…' : '' }}</span><span v-if="item.message">：{{ item.message }}</span><small v-if="item.traceId">traceId：{{ item.traceId }}</small>
        </div>
      </li>
    </ul>
    <div class="actions"><button class="primary" :disabled="busy || !queued.length">{{ busy ? '依序上傳中…' : '上傳 ' + queued.length + ' 個檔案' }}</button><button v-if="busy && queued.length" type="button" @click="cancelRemaining">取消剩餘佇列</button><button v-if="!busy && queue.length && !queued.length" type="button" @click="queue = []; finished = false">清除紀錄</button></div>
    <p v-if="busy" class="muted">每次上傳一個檔案；取消佇列後，目前檔案仍會完成。</p>
    <p v-if="finished" role="status">已上傳 {{ successes }} 個，失敗 {{ failures }} 個<small class="muted">（成功表示已接收，解析與索引狀態請查看文件清單）</small></p>
  </form>
</template>
<style scoped>
.batch-upload { display:flex; flex-direction:column; align-items:stretch }
.batch-upload .drop-zone { width:100%; min-height:110px; flex-direction:column; border:1px dashed var(--border); border-radius:var(--radius); background:var(--surface); padding:20px; text-align:center }
.batch-upload .drop-zone.dragging { border-color:var(--primary); background:var(--primary-soft) }
.drop-zone small { color:var(--muted); font-weight:400 }
.upload-queue { list-style:none; margin:0; padding:0; display:grid; gap:12px }
.upload-queue li { padding:12px; border-radius:var(--radius-sm); background:var(--surface); min-width:0 }
.queue-file { display:flex; gap:10px; flex-wrap:wrap; align-items:center }
.queue-file strong { flex:1; min-width:100px; overflow-wrap:anywhere; font-size:13px }
.queue-file button { padding:4px 8px; min-height:30px; font-size:12px }
.queue-result { font-size:12px; color:var(--muted); overflow-wrap:anywhere }
.queue-result small { display:block }
.upload-error { color:var(--danger) }
.batch-upload p { margin:0 }
.batch-upload p small { display:block }
</style>
