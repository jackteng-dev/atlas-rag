<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { AdminJob, AdminOrg, AdminUser, Health } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useToastStore } from '../stores/toast'
import { useTask } from '../composables/useTask'
import { date } from '../composables/format'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import DataTable from '../components/DataTable.vue'
import StatusBadge from '../components/StatusBadge.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

const session = useSessionStore(); const toast = useToastStore()
const health = ref<Health | null>(null); const orgs = ref<AdminOrg[]>([]); const users = ref<AdminUser[]>([]); const jobs = ref<AdminJob[]>([])
const target = ref<AdminUser | null>(null)
const {busy,error,run} = useTask(); const action = useTask()
async function load() {
  if (!session.isSystemAdmin) return
  await run(async () => {
    const [a,b,c,d] = await Promise.all([api.adminHealth(),api.adminOrgs(),api.adminUsers(),api.adminJobs()])
    health.value = a; orgs.value = b; users.value = c; jobs.value = d
  })
}
async function toggle() {
  if (!target.value || !session.isSystemAdmin) return
  await action.run(async () => {
    await api.updateAdminUser(target.value!.id,target.value!.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
    target.value = null; toast.show('使用者狀態已更新。'); await load()
  })
}
onMounted(load)
</script>
<template>
  <PageHeader title="系統管理" description="檢視系統健康、組織、使用者及背景工作。"><button :disabled="busy || action.busy.value" @click="load">重新整理</button></PageHeader>
  <template v-if="session.isSystemAdmin">
    <ErrorBanner :error="error" /><Spinner v-if="busy" />
    <section v-if="health" class="panel">
      <div class="actions"><h2>系統健康</h2><StatusBadge :state="health.status" /></div>
      <div v-if="health.components.length" class="health-grid"><article v-for="component in health.components" :key="component.name" class="health-card">
        <h3>{{ component.name }}</h3><StatusBadge :state="component.status" /><p>{{ component.detail }}</p>
      </article></div><EmptyState v-else title="尚無健康元件資料" />
    </section>
    <section class="panel"><h2>組織</h2>
      <DataTable v-if="orgs.length" :rows="orgs" :columns="[{key:'name',label:'組織'},{key:'slug',label:'識別名稱'},{key:'status',label:'狀態'},{key:'plan',label:'方案'},{key:'members',label:'成員數'},{key:'documents',label:'文件數'},{key:'createdAt',label:'建立時間'}]">
        <template #cell-name="{row}">{{ row.name }}<small>{{ row.id }}</small></template>
        <template #cell-status="{row}"><StatusBadge :state="row.status" /></template>
        <template #cell-createdAt="{row}">{{ date(row.createdAt) }}</template>
      </DataTable><EmptyState v-else-if="!busy && !error" title="尚無組織" />
    </section>
    <section class="panel"><h2>使用者</h2>
      <DataTable v-if="users.length" :rows="users" :columns="[{key:'displayName',label:'使用者'},{key:'systemRole',label:'系統角色'},{key:'status',label:'狀態'},{key:'actions',label:'操作'}]">
        <template #cell-displayName="{row}">{{ row.displayName }}<small>{{ row.email }}</small></template>
        <template #cell-status="{row}"><StatusBadge :state="row.status" /></template>
        <template #cell-actions="{row}"><button :class="{danger:row.status === 'ACTIVE'}" :disabled="action.busy.value || busy" @click="action.error.value = null; target = row">{{ row.status === 'ACTIVE' ? '停用' : '啟用' }}</button></template>
      </DataTable><EmptyState v-else-if="!busy && !error" title="尚無使用者" />
    </section>
    <section class="panel"><h2>背景工作</h2>
      <DataTable v-if="jobs.length" :rows="jobs" :columns="[{key:'type',label:'類型'},{key:'state',label:'狀態'},{key:'attempts',label:'嘗試次數'},{key:'lastError',label:'最後錯誤'},{key:'traceId',label:'traceId'},{key:'updatedAt',label:'更新時間'}]">
        <template #cell-type="{row}">{{ row.type }}<small>工作：{{ row.id }}</small><small>組織：{{ row.orgId }}</small></template>
        <template #cell-state="{row}"><StatusBadge :state="row.state" /></template>
        <template #cell-updatedAt="{row}">{{ date(row.updatedAt) }}</template>
      </DataTable><EmptyState v-else-if="!busy && !error" title="尚無背景工作" />
    </section>
  </template><EmptyState v-else title="權限不足" description="系統管理僅限 SYSTEM_ADMIN。" />
  <ConfirmDialog v-if="target" :title="target.status === 'ACTIVE' ? '停用使用者' : '啟用使用者'" :message="'確定' + (target.status === 'ACTIVE' ? '停用' : '啟用') + ' ' + target.email + '？' + (target.status === 'ACTIVE' ? '停用會撤銷其登入工作階段。' : '')" :confirm-label="target.status === 'ACTIVE' ? '確認停用' : '確認啟用'" :busy="action.busy.value" :error="action.error.value" @close="target = null" @confirm="toggle" />
</template>