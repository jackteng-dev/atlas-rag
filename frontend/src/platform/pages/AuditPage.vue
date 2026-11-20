<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { AuditEvent } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useTask } from '../composables/useTask'
import { date } from '../composables/format'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import DataTable from '../components/DataTable.vue'
import StatusBadge from '../components/StatusBadge.vue'
const session = useSessionStore(); const org = session.currentOrgId
const rows = ref<AuditEvent[]>([]); const filter = ref(''); const activeFilter = ref('')
const hasMore = ref(false); const initialized = ref(false); const limit = 100
const {busy,error,run} = useTask()
async function load(more = false) {
  if (!session.canAdmin) return
  await run(async () => {
    const action = more ? activeFilter.value : filter.value.trim()
    const before = more ? rows.value[rows.value.length - 1]?.id : undefined
    const batch = await api.audit(org,{limit,action,before:before === undefined ? undefined : String(before)})
    if (more) { const ids = new Set(rows.value.map(r => r.id)); rows.value.push(...batch.filter(r => !ids.has(r.id))) }
    else { rows.value = batch; activeFilter.value = action; initialized.value = true }
    hasMore.value = batch.length === limit
  })
}
onMounted(() => load())
</script>
<template>
  <PageHeader title="稽核紀錄" description="追查組織操作、結果與 traceId。"><button :disabled="busy" @click="load()">重新整理</button></PageHeader>
  <template v-if="session.canAdmin">
    <form class="filters" @submit.prevent="load()"><label>動作前綴<input v-model="filter" placeholder="例如 agent. 或 connector." :disabled="busy"></label><button class="primary" :disabled="busy">套用篩選</button></form>
    <ErrorBanner :error="error" /><Spinner v-if="busy" />
    <section v-if="rows.length" class="panel">
      <DataTable :rows="rows" :columns="[{key:'createdAt',label:'時間'},{key:'actorEmail',label:'操作者'},{key:'action',label:'動作'},{key:'resource',label:'資源'},{key:'result',label:'結果'},{key:'traceId',label:'traceId'}]">
        <template #cell-createdAt="{row}">{{ date(row.createdAt) }}</template>
        <template #cell-actorEmail="{row}">{{ row.actorEmail ?? '系統／未知' }}</template>
        <template #cell-resource="{row}">{{ row.resourceType ?? '—' }}<small>{{ row.resourceId ?? '—' }}</small></template>
        <template #cell-result="{row}"><StatusBadge :state="row.result" /></template>
      </DataTable>
      <div v-if="hasMore" class="actions end"><button :disabled="busy" @click="load(true)">載入更多</button></div>
      <p v-else-if="initialized" class="muted">已載入此篩選的全部紀錄。</p>
    </section><EmptyState v-else-if="!busy && !error" title="沒有符合條件的稽核紀錄" />
  </template><EmptyState v-else title="權限不足" description="稽核頁需要 ADMIN 或 OWNER 權限。" />
</template>