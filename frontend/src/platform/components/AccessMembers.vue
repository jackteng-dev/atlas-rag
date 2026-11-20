<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { Member } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useToastStore } from '../stores/toast'
import { useTask } from '../composables/useTask'
import ErrorBanner from './ErrorBanner.vue'
import Spinner from './Spinner.vue'
import ConfirmDialog from './ConfirmDialog.vue'
const props = defineProps<{ scopeId: string; kind: 'kb' | 'workspace' }>()
const org = useSessionStore().currentOrgId; const toast = useToastStore()
const members = ref<Member[]>([]); const userId = ref(''); const role = ref<'MANAGER' | 'EDITOR' | 'VIEWER'>('VIEWER')
const response = ref<unknown>(undefined); const received = ref(false); const removeOpen = ref(false)
const { busy, error, run } = useTask(); const action = useTask()
async function load() {
  await run(async () => {
    members.value = await api.members(org)
    if (!userId.value) userId.value = members.value[0]?.userId ?? ''
    if (props.kind === 'kb') { response.value = await api.kbMembers(org, props.scopeId); received.value = true }
  })
}
async function inspect() {
  if (!userId.value) return
  await action.run(async () => {
    try { response.value = await api.workspaceMember(org, props.scopeId, userId.value); received.value = true }
    catch (e) { if (e instanceof ApiError && e.status === 404) { response.value = '此成員尚未加入工作空間或無法存取。'; received.value = true } else throw e }
  })
}
async function save() {
  await action.run(async () => {
    if (props.kind === 'kb') await api.putKbMember(org, props.scopeId, userId.value, role.value === 'MANAGER' ? 'EDITOR' : role.value)
    else await api.putWorkspaceMember(org, props.scopeId, userId.value, role.value)
    response.value = undefined; received.value = false; toast.show('成員權限已儲存。')
    if (props.kind === 'kb') await load()
    else await inspectAfterSave()
  })
}
async function inspectAfterSave() { response.value = await api.workspaceMember(org, props.scopeId, userId.value); received.value = true }
async function remove() {
  await action.run(async () => {
    if (props.kind === 'kb') await api.removeKbMember(org, props.scopeId, userId.value)
    else await api.removeWorkspaceMember(org, props.scopeId, userId.value)
    removeOpen.value = false; response.value = undefined; received.value = false; toast.show('成員的直接權限已移除。')
    if (props.kind === 'kb') await load()
  })
}
watch(userId, () => { if (props.kind === 'workspace') { response.value = undefined; received.value = false } })
onMounted(load)
</script>
<template><section class="form-stack"><h3>{{ kind === 'kb' ? '知識庫成員' : '工作空間成員' }}</h3><ErrorBanner :error="error" /><ErrorBanner v-if="!removeOpen" :error="action.error.value" /><Spinner v-if="busy" /><p class="notice">選擇組織成員，設定或移除其直接權限。{{ kind === 'workspace' ? '目前支援逐一查詢成員。' : '' }}</p><form class="form-stack" @submit.prevent="save"><div class="field-row"><label>組織成員<select v-model="userId" required :disabled="busy || action.busy.value"><option v-for="member in members" :key="member.userId" :value="member.userId">{{ member.displayName }}（{{ member.email }}）</option></select></label><label>授予角色<select v-model="role" :disabled="action.busy.value"><option v-if="kind === 'workspace'" value="MANAGER">MANAGER</option><option value="EDITOR">EDITOR</option><option value="VIEWER">VIEWER</option></select></label></div><div class="actions"><button class="primary" :disabled="!userId || action.busy.value">儲存權限</button><button v-if="kind === 'workspace'" type="button" :disabled="!userId || action.busy.value" @click="inspect">查詢目前權限</button><button v-else type="button" :disabled="busy || action.busy.value" @click="load">重新讀取成員</button><button type="button" class="danger" :disabled="!userId || action.busy.value" @click="action.error.value = null; removeOpen = true">移除直接權限</button></div></form><details v-if="received"><summary>{{ kind === 'kb' ? '檢視目前成員資料' : '檢視此成員的目前權限' }}</summary><pre>{{ typeof response === 'string' ? response : JSON.stringify(response, null, 2) }}</pre></details><ConfirmDialog v-if="removeOpen" title="移除成員權限" message="確定移除此成員的直接權限？其組織或工作空間繼承權限仍由伺服器決定。" :busy="action.busy.value" :error="action.error.value" confirm-label="確認移除" @close="removeOpen = false" @confirm="remove" /></section></template>


