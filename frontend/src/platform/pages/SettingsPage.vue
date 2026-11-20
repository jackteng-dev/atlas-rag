<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { Org, Member, Invitation, Workspace, OrgRole } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useToastStore } from '../stores/toast'
import { useTask } from '../composables/useTask'
import { date } from '../composables/format'
import PageHeader from '../components/PageHeader.vue'
import DataTable from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'

import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import AccessMembers from '../components/AccessMembers.vue'
const session = useSessionStore(); const orgId = session.currentOrgId; const toast = useToastStore()
const org = ref<Org | null>(null); const name = ref('')
const members = ref<Member[]>([]); const invitations = ref<Invitation[]>([]); const workspaces = ref<Workspace[]>([])
const drafts = ref<Record<string,{ role: OrgRole; status: Member['status'] }>>({})
const invitationForm = ref({ email:'', role:'VIEWER' as Invitation['role'] })
const workspaceForm = ref({ name:'', description:'' }); const selectedWorkspace = ref('')
const inviteOpen = ref(false); const workspaceOpen = ref(false); const oneTimeLink = ref(''); const copyError = ref<unknown>(null)
const removal = ref<{ kind:'member'|'invitation'; id:string; label:string } | null>(null)
const { busy, error, run } = useTask(); const action = useTask()
const roles: OrgRole[] = ['OWNER','ADMIN','EDITOR','VIEWER']
async function load() {
  await run(async () => {
    const [a,b,c,d] = await Promise.all([api.org(orgId),api.members(orgId),api.invitations(orgId),api.workspaces(orgId)])
    org.value = a; name.value = a.name; members.value = b
    invitations.value = c.map(({token: _token,...invitation}) => invitation)
    workspaces.value = d; if (!selectedWorkspace.value) selectedWorkspace.value = d[0]?.id ?? ''
    drafts.value = Object.fromEntries(b.map(m => [m.userId,{role:m.role,status:m.status}]))
  })
}
async function saveOrg() {
  await action.run(async () => { await api.updateOrg(orgId,{ name:name.value }); await session.load(true); toast.show('組織名稱已更新。'); await load() })
}
function canEdit(member: Member) { return session.currentRole === 'OWNER' || member.role !== 'OWNER' }
async function saveMember(member: Member) {
  await action.run(async () => {
    await api.updateMember(orgId,member.userId,drafts.value[member.userId])
    await session.load(true); toast.show('成員已更新。'); await load()
  })
}
function confirmRemove(kind:'member'|'invitation',id:string,label:string) { action.error.value = null; removal.value = {kind,id,label} }
async function remove() {
  if (!removal.value) return
  const target = removal.value
  await action.run(async () => {
    if (target.kind === 'member') await api.removeMember(orgId,target.id)
    else await api.revokeInvitation(orgId,target.id)
    removal.value = null; toast.show(target.kind === 'member' ? '成員已移除。' : '邀請已撤銷。')
    await session.load(true); await load()
  })
}
async function createInvite() {
  await action.run(async () => {
    const { token, ...invitation } = await api.createInvitation(orgId,invitationForm.value)
    invitations.value.unshift(invitation); inviteOpen.value = false
    if (token) { oneTimeLink.value = location.origin + '/invite/' + encodeURIComponent(token); copyError.value = null }
    else toast.show('邀請已建立，但伺服器未提供一次性連結。')
  })
}
function dismissLink() { oneTimeLink.value = ''; copyError.value = null }
async function copyLink() {
  copyError.value = null
  try { await navigator.clipboard.writeText(oneTimeLink.value); toast.show('邀請連結已複製。') }
  catch { copyError.value = new Error('無法存取剪貼簿，請選取連結後手動複製。') }
}
async function createWorkspace() {
  await action.run(async () => {
    const ws = await api.createWorkspace(orgId,workspaceForm.value)
    workspaces.value.push(ws); selectedWorkspace.value = ws.id; workspaceOpen.value = false; toast.show('工作空間已建立。')
  })
}
function openInvite() { invitationForm.value = { email:'',role:'VIEWER' }; action.error.value = null; inviteOpen.value = true }
function openWorkspace() { workspaceForm.value = { name:'',description:'' }; action.error.value = null; workspaceOpen.value = true }
onMounted(load)
</script>
<template><PageHeader title="組織設定" description="管理組織、團隊成員、邀請與工作空間。"><button :disabled="busy || action.busy.value" @click="load">重新整理</button></PageHeader><template v-if="session.canAdmin"><ErrorBanner :error="error" /><ErrorBanner v-if="!inviteOpen && !workspaceOpen && !removal" :error="action.error.value" /><Spinner v-if="busy" /><form v-if="org" class="panel form-stack narrow" @submit.prevent="saveOrg"><h2>組織資料</h2><label>組織名稱<input v-model="name" required :disabled="action.busy.value"></label><p class="muted">{{ org.slug }} · {{ org.plan }} · {{ org.status }}</p><button class="primary" :disabled="action.busy.value || name === org.name">儲存名稱</button></form>
<section class="panel"><h2>組織成員</h2><p class="muted">只有 OWNER 可授予或移除 OWNER；最後一位 OWNER 由伺服器保護。</p><DataTable v-if="members.length" :rows="members" row-key="userId" :columns="[{key:'displayName',label:'成員'},{key:'role',label:'角色'},{key:'status',label:'狀態'},{key:'joinedAt',label:'加入時間'},{key:'actions',label:'操作'}]"><template #cell-displayName="{row}">{{ row.displayName }}<small>{{ row.email }}</small></template><template #cell-role="{row}"><select v-if="drafts[row.userId]" v-model="drafts[row.userId].role" :disabled="action.busy.value || !canEdit(row)" :aria-label="row.displayName + ' 的角色'"><option v-for="role in roles" :key="role" :value="role" :disabled="role === 'OWNER' && session.currentRole !== 'OWNER'">{{ role }}</option></select></template><template #cell-status="{row}"><select v-if="drafts[row.userId]" v-model="drafts[row.userId].status" :disabled="action.busy.value || !canEdit(row)" :aria-label="row.displayName + ' 的狀態'"><option value="ACTIVE">啟用</option><option value="DISABLED">停用</option></select></template><template #cell-joinedAt="{row}">{{ date(row.joinedAt) }}</template><template #cell-actions="{row}"><div v-if="canEdit(row)" class="actions"><button :disabled="action.busy.value || (drafts[row.userId]?.role === row.role && drafts[row.userId]?.status === row.status)" @click="saveMember(row)">儲存</button><button class="danger" :disabled="action.busy.value" @click="confirmRemove('member',row.userId,row.email)">移除</button></div><p v-else class="muted">需 OWNER 權限</p></template></DataTable><EmptyState v-else-if="!busy && !error" title="尚無成員資料" /></section>
<section class="panel"><div class="page-header"><h2>成員邀請</h2><button class="primary" :disabled="action.busy.value" @click="openInvite">邀請成員</button></div><DataTable v-if="invitations.length" :rows="invitations" :columns="[{key:'email',label:'電子郵件'},{key:'role',label:'角色'},{key:'expiresAt',label:'到期時間'},{key:'state',label:'狀態'},{key:'actions',label:'操作'}]"><template #cell-expiresAt="{row}">{{ date(row.expiresAt) }}</template><template #cell-state="{row}"><span>{{ row.acceptedAt ? '已接受' : row.revokedAt ? '已撤銷' : Date.parse(row.expiresAt) <= Date.now() ? '已到期' : '待接受' }}</span></template><template #cell-actions="{row}"><button v-if="!row.acceptedAt && !row.revokedAt && Date.parse(row.expiresAt) > Date.now()" :disabled="action.busy.value" @click="confirmRemove('invitation',row.id,row.email)">撤銷邀請</button><span v-else class="muted">—</span></template></DataTable><EmptyState v-else-if="!busy && !error" title="尚無邀請" /></section>
<section class="panel"><div class="page-header"><h2>工作空間</h2><button class="primary" :disabled="action.busy.value" @click="openWorkspace">建立工作空間</button></div><DataTable v-if="workspaces.length" :rows="workspaces" :columns="[{key:'name',label:'名稱'},{key:'description',label:'說明'},{key:'createdAt',label:'建立時間'}]"><template #cell-createdAt="{row}">{{ date(row.createdAt) }}</template></DataTable><EmptyState v-else-if="!busy && !error" title="尚無工作空間" /><label v-if="workspaces.length">管理工作空間成員<select v-model="selectedWorkspace"><option v-for="ws in workspaces" :key="ws.id" :value="ws.id">{{ ws.name }}</option></select></label><AccessMembers v-if="selectedWorkspace" :key="selectedWorkspace" :scope-id="selectedWorkspace" kind="workspace" /></section></template><EmptyState v-else title="權限不足" description="組織設定需要 OWNER 或 ADMIN 權限。" />
<Modal v-if="inviteOpen" title="邀請成員" :busy="action.busy.value" @close="inviteOpen = false"><form class="form-stack" @submit.prevent="createInvite"><label>電子郵件<input v-model="invitationForm.email" type="email" required :disabled="action.busy.value"></label><label>組織角色<select v-model="invitationForm.role" :disabled="action.busy.value"><option value="ADMIN">ADMIN</option><option value="EDITOR">EDITOR</option><option value="VIEWER">VIEWER</option></select></label><ErrorBanner :error="action.error.value" /><button class="primary" :disabled="action.busy.value">建立邀請</button></form></Modal>
<Modal v-if="oneTimeLink" title="請保存邀請連結" @close="dismissLink"><p class="notice">此連結只顯示這一次。關閉後無法再次取得，請先複製並交給受邀者。</p><label>邀請連結<input class="invite-link" :value="oneTimeLink" readonly @focus="($event.target as HTMLInputElement).select()"></label><ErrorBanner :error="copyError" /><div class="actions end"><button @click="copyLink">複製連結</button><button class="primary" @click="dismissLink">已保存，關閉</button></div></Modal>
<Modal v-if="workspaceOpen" title="建立工作空間" :busy="action.busy.value" @close="workspaceOpen = false"><form class="form-stack" @submit.prevent="createWorkspace"><label>名稱<input v-model="workspaceForm.name" required :disabled="action.busy.value"></label><label>說明<textarea v-model="workspaceForm.description" :disabled="action.busy.value"></textarea></label><ErrorBanner :error="action.error.value" /><button class="primary" :disabled="action.busy.value">建立</button></form></Modal>
<ConfirmDialog v-if="removal" :title="removal.kind === 'member' ? '移除組織成員' : '撤銷邀請'" :message="'確定' + (removal.kind === 'member' ? '移除 ' : '撤銷 ') + removal.label + '？'" confirm-label="確認" :busy="action.busy.value" :error="action.error.value" @close="removal = null" @confirm="remove" /></template>


