<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '../api/client'
import type { KnowledgeBase, Workspace } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useTask } from '../composables/useTask'
import { orgPath } from '../composables/format'
import PageHeader from '../components/PageHeader.vue'
import DataTable from '../components/DataTable.vue'
import Modal from '../components/Modal.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
const org = useSessionStore().currentOrgId
const kbs = ref<KnowledgeBase[]>([]); const workspaces = ref<Workspace[]>([])
const open = ref(false)
const form = ref({ workspaceId: '', name: '', description: '', visibility: 'ORG' as KnowledgeBase['visibility'] })
const { busy, error, run } = useTask(); const action = useTask()
async function load() {
  await run(async () => { const [a,b] = await Promise.all([api.knowledgeBases(org),api.workspaces(org)]); kbs.value = a; workspaces.value = b })
}
function create() { form.value = { workspaceId: workspaces.value[0]?.id ?? '', name: '', description: '', visibility: 'ORG' }; action.error.value = null; open.value = true }
async function save() {
  await action.run(async () => { const kb = await api.createKnowledgeBase(org, form.value); kbs.value.push(kb); open.value = false })
}
onMounted(load)
</script>
<template><PageHeader title="知識庫" description="依工作空間整理團隊知識與存取權限。"><button :disabled="busy" @click="load">重新整理</button><button class="primary" :disabled="busy || !workspaces.length" :title="!workspaces.length ? '需先建立工作空間' : '需要組織 ADMIN 或工作空間 MANAGER 權限'" @click="create">建立知識庫</button></PageHeader><ErrorBanner :error="error" /><Spinner v-if="busy" /><p v-if="!busy && !error && !workspaces.length" class="notice">需先由管理員在組織設定建立工作空間，才能新增知識庫。</p><div v-if="kbs.length" class="panel"><DataTable :rows="kbs" :columns="[{key:'name',label:'知識庫'},{key:'description',label:'說明'},{key:'documentCount',label:'文件數'},{key:'readyCount',label:'可用文件'},{key:'access',label:'我的權限'}]"><template #cell-name="{row}"><RouterLink :to="orgPath(org,'knowledge-bases/' + row.id)">{{ row.name }}</RouterLink><small>{{ row.visibility === 'ORG' ? '組織可見' : '限制存取' }}</small></template></DataTable></div><EmptyState v-else-if="!busy && !error" title="尚無可讀知識庫" description="建立知識庫，或請管理員授予存取權限。" />
<Modal v-if="open" title="建立知識庫" :busy="action.busy.value" @close="open = false"><form class="form-stack" @submit.prevent="save"><p class="notice">需要組織 ADMIN 或指定工作空間的 MANAGER 權限。</p><label>工作空間<select v-model="form.workspaceId" required :disabled="action.busy.value"><option v-for="ws in workspaces" :key="ws.id" :value="ws.id">{{ ws.name }}</option></select></label><label>名稱<input v-model="form.name" required :disabled="action.busy.value"></label><label>說明<textarea v-model="form.description" :disabled="action.busy.value"></textarea></label><label>可見性<select v-model="form.visibility" :disabled="action.busy.value"><option value="ORG">組織可見</option><option value="RESTRICTED">限制存取</option></select></label><ErrorBanner :error="action.error.value" /><button class="primary" :disabled="action.busy.value">{{ action.busy.value ? '建立中…' : '建立' }}</button></form></Modal></template>

