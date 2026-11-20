<script setup lang="ts">
import { onMounted, computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '../api/client'
import type { DocumentItem, DocumentStatus, KnowledgeBase } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useTask, usePolling } from '../composables/useTask'
import { orgPath, date } from '../composables/format'
import PageHeader from '../components/PageHeader.vue'
import DataTable from '../components/DataTable.vue'
import FileTypeBadge from '../components/FileTypeBadge.vue'
import { fileSize } from '../composables/files'
import EmptyState from '../components/EmptyState.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import StatusBadge from '../components/StatusBadge.vue'
const org = useSessionStore().currentOrgId
const docs = ref<DocumentItem[]>([]); const kbs = ref<KnowledgeBase[]>([])
const kbId = ref(''); const status = ref('')
const states: { id: DocumentStatus; label: string }[] = [{id:'PROCESSING',label:'處理中'},{id:'READY',label:'可用'},{id:'FAILED',label:'失敗'},{id:'NEEDS_OCR',label:'需要 OCR'},{id:'CANCELLED',label:'已取消'},{id:'DELETING',label:'刪除中'}]
const visible = computed(() => docs.value.filter(d => (!kbId.value || d.kbId === kbId.value) && (!status.value || d.status === status.value)))
const { busy, error, run } = useTask()
async function load() { await run(async () => { const [a,b] = await Promise.all([api.documents(org),api.knowledgeBases(org)]); docs.value = a; kbs.value = b }) }
onMounted(load)
usePolling(load, () => !error.value && docs.value.some(d => d.status === 'PROCESSING'), 3000)
</script>
<template><PageHeader title="文件" description="查看所有可讀文件、版本與處理狀態。"><button :disabled="busy" @click="load">重新整理</button></PageHeader><ErrorBanner :error="error" /><div class="filters"><label>知識庫<select v-model="kbId"><option value="">所有知識庫</option><option v-for="kb in kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option></select></label><label>文件狀態<select v-model="status"><option value="">所有狀態</option><option v-for="state in states" :key="state.id" :value="state.id">{{ state.label }}</option></select></label></div><Spinner v-if="busy" /><p v-if="docs.some(d => d.status === 'PROCESSING')" class="muted">處理中每 3 秒自動更新。</p><section v-if="visible.length" class="panel"><DataTable :rows="visible" :columns="[{key:'title',label:'文件'},{key:'extension',label:'類型'},{key:'sizeBytes',label:'大小'},{key:'kbId',label:'知識庫'},{key:'status',label:'狀態'},{key:'latestVersion',label:'版本'},{key:'updatedAt',label:'更新時間'}]"><template #cell-title="{row}"><div><RouterLink :to="orgPath(org,'documents/' + row.id)">{{ row.title }}</RouterLink><small class="muted">{{ row.filename || '—' }}</small></div></template><template #cell-extension="{row}"><FileTypeBadge :extension="row.extension" /></template><template #cell-sizeBytes="{row}">{{ fileSize(row.sizeBytes) }}</template><template #cell-kbId="{row}"><RouterLink :to="orgPath(org,'knowledge-bases/' + row.kbId)">{{ kbs.find(k => k.id === row.kbId)?.name ?? row.kbId }}</RouterLink></template><template #cell-status="{row}"><StatusBadge :state="row.status" /></template><template #cell-updatedAt="{row}">{{ date(row.updatedAt) }}</template></DataTable></section><EmptyState v-else-if="!busy && !error" :title="docs.length ? '沒有符合篩選條件的文件' : '尚無可讀文件'" description="請調整篩選條件，或至知識庫上傳文件。" /></template>

