<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '../api/client'
import { useSessionStore } from '../stores/session'
import { useTask } from '../composables/useTask'
import type { DocumentItem } from '../api/types'
import { date, orgPath } from '../composables/format'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import DataTable from '../components/DataTable.vue'
import FileTypeBadge from '../components/FileTypeBadge.vue'
import { fileSize } from '../composables/files'
import StatusBadge from '../components/StatusBadge.vue'
const session = useSessionStore(); const org = session.currentOrgId
const { busy, error, run } = useTask()
const counts = ref<{ label: string; count: number | null; path: string }[]>([])
const recent = ref<DocumentItem[]>([])
async function load() {
  await run(async () => {
    const [kbs, docs, conversations, approvals, agents] = await Promise.allSettled([
      api.knowledgeBases(org), api.documents(org), api.conversations(org),
      session.canApprove ? api.approvals(org, 'PENDING') : Promise.resolve(null), api.agents(org),
    ])
    counts.value = [
      { label: '可讀知識庫', count: kbs.status === 'fulfilled' ? kbs.value.length : null, path: 'knowledge-bases' },
      { label: '可讀文件', count: docs.status === 'fulfilled' ? docs.value.length : null, path: 'documents' },
      { label: 'Agents', count: agents.status === 'fulfilled' ? agents.value.length : null, path: 'agents' },
      { label: '我的對話', count: conversations.status === 'fulfilled' ? conversations.value.length : null, path: 'chat' },
      ...(session.canApprove ? [{ label: '待處理審核', count: approvals.status === 'fulfilled' ? approvals.value?.filter(a => a.status === 'PENDING').length ?? null : null, path: 'approvals' }] : []),
    ]
    if (docs.status === 'fulfilled') recent.value = [...docs.value].sort((a,b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0,5)
    const failed = [kbs, docs, conversations, approvals, agents].find(r => r.status === 'rejected')
    if (failed?.status === 'rejected') throw failed.reason
  })
}
onMounted(load)
</script>
<template><PageHeader title="工作空間總覽" description="從團隊知識開始，讓每個答案都有依據。"><button :disabled="busy" @click="load">重新整理</button></PageHeader><ErrorBanner :error="error" /><Spinner v-if="busy" /><div v-if="counts.length" class="stat-grid"><article v-for="card in counts" :key="card.path" class="stat-card"><RouterLink :to="orgPath(org,card.path)">{{ card.label }}</RouterLink><strong>{{ card.count ?? '—' }}</strong><small>{{ card.count === null ? '讀取失敗' : '目前可存取的資料' }}</small></article></div><section class="panel"><h2>最近更新的文件</h2><DataTable v-if="recent.length" :rows="recent" :columns="[{key:'title',label:'文件'},{key:'extension',label:'類型'},{key:'sizeBytes',label:'大小'},{key:'status',label:'狀態'},{key:'updatedAt',label:'更新時間'}]"><template #cell-title="{row}"><div><RouterLink :to="orgPath(org,'documents/' + row.id)">{{ row.title }}</RouterLink><small class="muted">{{ row.filename || '—' }}</small></div></template><template #cell-extension="{row}"><FileTypeBadge :extension="row.extension" /></template><template #cell-sizeBytes="{row}">{{ fileSize(row.sizeBytes) }}</template><template #cell-status="{row}"><StatusBadge :state="row.status" /></template><template #cell-updatedAt="{row}">{{ date(row.updatedAt) }}</template></DataTable><EmptyState v-else-if="!busy && !error" title="尚無文件" description="建立知識庫並上傳文件，即可開始知識對話。" /></section></template>

