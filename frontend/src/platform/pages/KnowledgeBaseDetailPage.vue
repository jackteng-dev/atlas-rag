<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import type { KnowledgeBase, DocumentItem } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useToastStore } from '../stores/toast'
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
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import UploadForm from '../components/UploadForm.vue'
import AccessMembers from '../components/AccessMembers.vue'
const org = useSessionStore().currentOrgId; const kbId = String(useRoute().params.kbId); const router = useRouter(); const toast = useToastStore()
const kb = ref<KnowledgeBase | null>(null); const docs = ref<DocumentItem[]>([])
const editOpen = ref(false); const deleteOpen = ref(false)
const form = ref({ name: '', description: '', visibility: 'ORG' as KnowledgeBase['visibility'] })
const { busy, error, run } = useTask(); const action = useTask()
async function load() {
  await run(async () => { const [a,b] = await Promise.all([api.knowledgeBase(org,kbId),api.documents(org,kbId)]); kb.value = a; docs.value = b })
}
function edit() { if (!kb.value) return; form.value = { name: kb.value.name, description: kb.value.description, visibility: kb.value.visibility }; action.error.value = null; editOpen.value = true }
async function save() {
  await action.run(async () => { await api.updateKnowledgeBase(org,kbId,form.value); editOpen.value = false; toast.show('知識庫已更新。'); await load() })
}
async function remove() {
  await action.run(async () => { await api.deleteKnowledgeBase(org,kbId); await router.replace(orgPath(org,'knowledge-bases')) })
}
const upload = (file: File, progress: (n: number) => void) => api.uploadDocument(org,kbId,file,progress)
onMounted(load)
usePolling(load, () => !error.value && docs.value.some(d => d.status === 'PROCESSING'), 3000)
</script>
<template><PageHeader :title="kb?.name ?? '知識庫'" :description="kb?.description"><button :disabled="busy" @click="load">重新整理</button><template v-if="kb?.access === 'MANAGE'"><button @click="edit">編輯知識庫</button><button class="danger" @click="action.error.value = null; deleteOpen = true">刪除知識庫</button></template></PageHeader><ErrorBanner :error="error" /><Spinner v-if="busy" /><template v-if="kb"><p class="muted">{{ kb.visibility === 'ORG' ? '組織可見' : '限制存取' }} · 我的權限 {{ kb.access }} · {{ kb.readyCount }} / {{ kb.documentCount }} 份文件可用</p><UploadForm v-if="kb.access !== 'READ'" :upload="upload" @uploaded="load" /><p v-else class="notice">目前為唯讀權限；上傳與編輯需要 EDIT 或 MANAGE。</p><section class="panel"><h2>知識庫文件</h2><DataTable v-if="docs.length" :rows="docs" :columns="[{key:'title',label:'文件'},{key:'extension',label:'類型'},{key:'sizeBytes',label:'大小'},{key:'status',label:'狀態'},{key:'latestVersion',label:'最新版本'},{key:'updatedAt',label:'更新時間'}]"><template #cell-title="{row}"><div><RouterLink :to="orgPath(org,'documents/' + row.id)">{{ row.title }}</RouterLink><small class="muted">{{ row.filename || '—' }}</small></div></template><template #cell-extension="{row}"><FileTypeBadge :extension="row.extension" /></template><template #cell-sizeBytes="{row}">{{ fileSize(row.sizeBytes) }}</template><template #cell-status="{row}"><StatusBadge :state="row.status" /></template><template #cell-updatedAt="{row}">{{ date(row.updatedAt) }}</template></DataTable><EmptyState v-else-if="!busy" title="尚無文件" description="上傳文件後即可建立可查證的知識來源。" /></section><section v-if="kb.access === 'MANAGE'" class="panel"><AccessMembers :scope-id="kbId" kind="kb" /></section></template>
<Modal v-if="editOpen" title="編輯知識庫" :busy="action.busy.value" @close="editOpen = false"><form class="form-stack" @submit.prevent="save"><label>名稱<input v-model="form.name" required :disabled="action.busy.value"></label><label>說明<textarea v-model="form.description" :disabled="action.busy.value"></textarea></label><label>可見性<select v-model="form.visibility" :disabled="action.busy.value"><option value="ORG">組織可見</option><option value="RESTRICTED">限制存取</option></select></label><ErrorBanner :error="action.error.value" /><button class="primary" :disabled="action.busy.value">儲存</button></form></Modal><ConfirmDialog v-if="deleteOpen" title="刪除知識庫" message="確定刪除此知識庫？請先確認其中的文件與存取權限影響。" :busy="action.busy.value" :error="action.error.value" @close="deleteOpen = false" @confirm="remove" /></template>

