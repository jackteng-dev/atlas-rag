<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import type { DocumentItem, KnowledgeBase, Chunk, DocumentVersion } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useToastStore } from '../stores/toast'
import { useTask, usePolling } from '../composables/useTask'
import { orgPath, date, locator } from '../composables/format'
import PageHeader from '../components/PageHeader.vue'
import DataTable from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import StatusBadge from '../components/StatusBadge.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import UploadForm from '../components/UploadForm.vue'
const org = useSessionStore().currentOrgId; const docId = String(useRoute().params.docId); const router = useRouter(); const toast = useToastStore()
const doc = ref<DocumentItem | null>(null); const kb = ref<KnowledgeBase | null>(null)
const editable = computed(() => kb.value?.access === 'MANAGE' || kb.value?.access === 'EDIT')
const deleteOpen = ref(false)
const selectedVersion = ref<number | null>(null); const chunks = ref<Chunk[]>([])
const { busy, error, run } = useTask(); const action = useTask(); const chunkTask = useTask()
const processing = (version: DocumentVersion) => ['RECEIVED','VALIDATING','STORED','PARSING','CHUNKING','EMBEDDING','INDEXING'].includes(version.pipelineState)
async function load() {
  await run(async () => {
    const item = await api.document(org,docId)
    const knowledgeBase = await api.knowledgeBase(org,item.kbId)
    doc.value = item; kb.value = knowledgeBase
  })
}
async function showChunks(n: number) {
  selectedVersion.value = n; chunks.value = []
  await chunkTask.run(async () => { chunks.value = await api.chunks(org,docId,n) })
}
async function versionAction(kind: 'retry' | 'cancel', n: number) {
  await action.run(async () => {
    if (kind === 'retry') await api.retryVersion(org,docId,n)
    else await api.cancelVersion(org,docId,n)
    toast.show(kind === 'retry' ? '已提交重試。' : '已提交取消。'); await load()
  })
}
async function reindex() {
  await action.run(async () => { await api.reindexDocument(org,docId); toast.show('已提交重新索引工作。'); await load() })
}
async function remove() {
  await action.run(async () => { await api.deleteDocument(org,docId); await router.replace(orgPath(org,'documents')) })
}
async function download(version: DocumentVersion) {
  await action.run(async () => {
    const blob = await api.downloadOriginal(org,docId,version.versionNo)
    const url = URL.createObjectURL(blob); const a = document.createElement('a')
    a.href = url; a.download = version.filename; document.body.append(a); a.click(); a.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 30000)
  })
}
const upload = (file: File, progress: (n: number) => void) => api.uploadVersion(org,docId,file,progress)
onMounted(load)
usePolling(load, () => !error.value && !!doc.value?.versions?.some(processing), 3000)
</script>
<template><PageHeader :title="doc?.title ?? '文件詳細資料'" description="檢視版本、處理流程與可核對的來源段落。"><button :disabled="busy" @click="load">重新整理</button><template v-if="editable"><button :disabled="action.busy.value || !doc?.currentVersion || doc?.status === 'PROCESSING'" :title="doc?.status === 'PROCESSING' ? '文件仍在處理中' : !doc?.currentVersion ? '尚無可用版本' : ''" @click="reindex">重新索引</button><button class="danger" :disabled="action.busy.value" @click="action.error.value = null; deleteOpen = true">刪除文件</button></template></PageHeader><ErrorBanner :error="error" /><ErrorBanner v-if="!deleteOpen" :error="action.error.value" /><Spinner v-if="busy" /><template v-if="doc"><section class="panel"><div class="actions"><StatusBadge :state="doc.status" /><RouterLink :to="orgPath(org,'knowledge-bases/' + doc.kbId)">{{ kb?.name ?? '返回知識庫' }}</RouterLink></div><p class="muted">目前可用版本 {{ doc.currentVersion ? 'v' + doc.currentVersion : '尚無' }} · 最新版本 v{{ doc.latestVersion }} · {{ date(doc.updatedAt) }}</p><p v-if="doc.tags.length" class="muted">標籤：{{ doc.tags.join('、') }}</p><p v-if="doc.expiresAt" class="muted">到期時間：{{ date(doc.expiresAt) }}</p><h3 v-if="editable">上傳新版本</h3><UploadForm v-if="editable" :upload="upload" @uploaded="load" /><p v-else class="notice">目前為唯讀權限；上傳、重試、取消與刪除需要 EDIT 或 MANAGE。</p></section>
<section class="panel"><h2>版本與處理流程</h2><DataTable v-if="doc.versions?.length" :rows="doc.versions" row-key="versionNo" :columns="[{key:'versionNo',label:'版本'},{key:'filename',label:'原始檔案'},{key:'pipelineState',label:'處理狀態'},{key:'createdAt',label:'建立時間'},{key:'actions',label:'操作'}]"><template #cell-versionNo="{row}">v{{ row.versionNo }}<small>{{ row.chunkCount }} 個段落{{ row.pageCount !== null ? ' · ' + row.pageCount + ' 頁' : '' }}</small></template><template #cell-filename="{row}">{{ row.filename }}<small>{{ row.sizeBytes.toLocaleString() }} bytes · {{ row.mimeType }}</small></template><template #cell-pipelineState="{row}"><StatusBadge :state="row.pipelineState" /><small v-if="row.errorCode">{{ row.errorCode }}：{{ row.errorMessage }}</small><small v-if="row.jobId">jobId：{{ row.jobId }}</small><small v-if="row.parserVersion">parser：{{ row.parserVersion }}</small></template><template #cell-createdAt="{row}">{{ date(row.createdAt) }}<small v-if="row.readyAt">可用：{{ date(row.readyAt) }}</small></template><template #cell-actions="{row}"><div class="actions"><button :disabled="chunkTask.busy.value || !row.chunkCount" :title="!row.chunkCount ? '尚未建立段落' : ''" @click="showChunks(row.versionNo)">瀏覽段落</button><button :disabled="action.busy.value || ['RECEIVED','VALIDATING'].includes(row.pipelineState)" :title="['RECEIVED','VALIDATING'].includes(row.pipelineState) ? '原始檔案尚未完成儲存' : ''" @click="download(row)">下載原檔</button><button v-if="editable && row.pipelineState === 'FAILED'" :disabled="action.busy.value" @click="versionAction('retry',row.versionNo)">重試</button><button v-if="editable && processing(row)" :disabled="action.busy.value" @click="versionAction('cancel',row.versionNo)">取消處理</button></div></template></DataTable><EmptyState v-else-if="!busy" title="尚無版本資料" /></section>
<section v-if="selectedVersion !== null" class="panel"><h2>來源段落 · v{{ selectedVersion }}</h2><ErrorBanner :error="chunkTask.error.value" /><Spinner v-if="chunkTask.busy.value" /><p v-if="!chunkTask.busy.value && !chunkTask.error.value && !chunks.length" class="muted">此版本尚無可讀段落。</p><article v-for="chunk in chunks" :key="chunk.chunkId" class="chunk-card"><h3>{{ chunk.ordinal }} · {{ chunk.heading || chunk.title }}</h3><small class="muted">{{ locator(chunk.locator) }} · v{{ chunk.versionNo }}</small><StatusBadge v-if="!chunk.current" state="SUPERSEDED" /><p>{{ chunk.content }}</p></article></section></template>
<ConfirmDialog v-if="deleteOpen" title="刪除文件" message="刪除後文件將立即無法讀取，相關引用也會失效。確定繼續？" :busy="action.busy.value" :error="action.error.value" @close="deleteOpen = false" @confirm="remove" /></template>

