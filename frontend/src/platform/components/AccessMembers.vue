<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { KbMember, Member, WorkspaceMember } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useToastStore } from '../stores/toast'
import { useTask } from '../composables/useTask'
import ErrorBanner from './ErrorBanner.vue'
import Spinner from './Spinner.vue'
import DataTable from './DataTable.vue'
import EmptyState from './EmptyState.vue'
import ConfirmDialog from './ConfirmDialog.vue'

const props = defineProps<{ scopeId: string; kind: 'kb' | 'workspace' }>()
const org = useSessionStore().currentOrgId; const toast = useToastStore()
const members = ref<Member[]>([]); const access = ref<(KbMember | WorkspaceMember)[]>([])
const userId = ref(''); const role = ref<WorkspaceMember['role']>('VIEWER'); const removeOpen = ref(false)
const {busy,error,run} = useTask(); const action = useTask()
async function readAccess() {
  access.value = props.kind === 'kb' ? await api.kbMembers(org,props.scopeId) : await api.workspaceMembers(org,props.scopeId)
}
async function load() {
  await run(async () => {
    const [people, entries] = await Promise.all([api.members(org),
      props.kind === 'kb' ? api.kbMembers(org,props.scopeId) : api.workspaceMembers(org,props.scopeId)])
    members.value = people; access.value = entries
    if (!userId.value) userId.value = people[0]?.userId ?? ''
  })
}
async function save() {
  if (!userId.value) return
  await action.run(async () => {
    if (props.kind === 'kb') await api.putKbMember(org,props.scopeId,userId.value,role.value === 'MANAGER' ? 'EDITOR' : role.value)
    else await api.putWorkspaceMember(org,props.scopeId,userId.value,role.value)
    await readAccess(); toast.show('成員權限已儲存。')
  })
}
function selectMember(member: KbMember | WorkspaceMember) { userId.value = member.userId; role.value = member.role }
async function remove() {
  await action.run(async () => {
    if (props.kind === 'kb') await api.removeKbMember(org,props.scopeId,userId.value)
    else await api.removeWorkspaceMember(org,props.scopeId,userId.value)
    removeOpen.value = false; await readAccess(); toast.show('成員的直接權限已移除。')
  })
}
onMounted(load)
</script>
<template>
  <section class="form-stack">
    <h3>{{ kind === 'kb' ? '知識庫成員' : '工作空間成員' }}</h3>
    <ErrorBanner :error="error" /><ErrorBanner v-if="!removeOpen" :error="action.error.value" /><Spinner v-if="busy" />
    <p class="notice">選擇組織成員，設定或移除其直接權限。繼承權限由伺服器決定。</p>
    <DataTable v-if="access.length" :rows="access" row-key="userId" :columns="[{key:'displayName',label:'成員'},{key:'email',label:'電子郵件'},{key:'role',label:'角色'},{key:'actions',label:'操作'}]">
      <template #cell-actions="{row}"><button :disabled="action.busy.value" @click="selectMember(row)">選取成員</button></template>
    </DataTable><EmptyState v-else-if="!busy && !error" title="尚無直接授權的成員" />
    <form class="form-stack" @submit.prevent="save">
      <div class="field-row">
        <label>組織成員<select v-model="userId" required :disabled="busy || action.busy.value"><option v-for="member in members" :key="member.userId" :value="member.userId">{{ member.displayName }}（{{ member.email }}）</option></select></label>
        <label>授予角色<select v-model="role" :disabled="action.busy.value"><option v-if="kind === 'workspace'" value="MANAGER">MANAGER（管理）</option><option value="EDITOR">EDITOR（編輯）</option><option value="VIEWER">VIEWER（檢視）</option></select></label>
      </div>
      <div class="actions">
        <button class="primary" :disabled="!userId || busy || action.busy.value">儲存權限</button>
        <button type="button" :disabled="busy || action.busy.value" @click="load">重新讀取成員</button>
        <button type="button" class="danger" :disabled="!userId || !access.some(a => a.userId === userId) || action.busy.value" @click="action.error.value = null; removeOpen = true">移除直接權限</button>
      </div>
    </form>
    <ConfirmDialog v-if="removeOpen" title="移除成員權限" message="確定移除此成員的直接權限？其組織或工作空間繼承權限仍由伺服器決定。" :busy="action.busy.value" :error="action.error.value" confirm-label="確認移除" @close="removeOpen = false" @confirm="remove" />
  </section>
</template>