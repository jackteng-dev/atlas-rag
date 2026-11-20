<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '../api/client'
import type { Approval } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useToastStore } from '../stores/toast'
import { useTask } from '../composables/useTask'
import { date, orgPath } from '../composables/format'
import { json } from '../composables/operations'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import DataTable from '../components/DataTable.vue'
import StatusBadge from '../components/StatusBadge.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

const session = useSessionStore(); const org = session.currentOrgId; const toast = useToastStore()
const tab = ref<'PENDING' | 'ALL'>('PENDING'); const rows = ref<Approval[]>([]); const selected = ref<Approval | null>(null)
const comment = ref(''); const decision = ref<'approve' | 'reject' | null>(null)
const { busy, error, run } = useTask(); const action = useTask()
async function load() {
  if (!session.canApprove) return
  await run(async () => { rows.value = await api.approvals(org, tab.value === 'PENDING' ? 'PENDING' : undefined) })
}
function inspect(row: Approval) { selected.value = row; comment.value = ''; action.error.value = null }
function confirm(kind: 'approve' | 'reject') { decision.value = kind; action.error.value = null }
async function decide() {
  if (!selected.value || !decision.value || comment.value.length > 500) return
  await action.run(async () => {
    await api[decision.value!](org, selected.value!.id, comment.value)
    selected.value = null; decision.value = null; toast.show('審核決定已儲存。'); await load()
  })
}
watch(tab, load); onMounted(load)
</script>
<template>
  <PageHeader title="審核" description="核對流程與 payload，再核准或拒絕待處理的執行。"><button :disabled="busy" @click="load">重新整理</button></PageHeader>
  <template v-if="session.canApprove">
    <div class="actions tabs" role="group" aria-label="審核狀態">
      <button :aria-pressed="tab === 'PENDING'" :class="{primary:tab === 'PENDING'}" :disabled="busy" @click="tab = 'PENDING'">PENDING（待審核）</button>
      <button :aria-pressed="tab === 'ALL'" :class="{primary:tab === 'ALL'}" :disabled="busy" @click="tab = 'ALL'">全部</button>
    </div>
    <ErrorBanner :error="error" /><Spinner v-if="busy" />
    <section v-if="rows.length" class="panel"><DataTable :rows="rows" :columns="[{key:'agentName',label:'Agent'},{key:'summary',label:'摘要'},{key:'requestedBy',label:'申請者'},{key:'createdAt',label:'建立時間'},{key:'status',label:'狀態'},{key:'actions',label:'操作'}]">
      <template #cell-createdAt="{row}">{{ date(row.createdAt) }}</template><template #cell-status="{row}"><StatusBadge :state="row.status" /></template>
      <template #cell-actions="{row}"><button @click="inspect(row)">檢視詳情</button></template>
    </DataTable></section>
    <EmptyState v-else-if="!busy && !error" :title="tab === 'PENDING' ? '沒有待審核項目' : '尚無審核紀錄'" />
  </template>
  <EmptyState v-else title="權限不足" description="審核需要 EDITOR、ADMIN 或 OWNER 權限。" />
  <Modal v-if="selected" side title="審核詳情" :busy="action.busy.value" @close="selected = null">
    <StatusBadge :state="selected.status" /><h3>{{ selected.agentName }}</h3><p>{{ selected.summary }}</p>
    <p>申請者：{{ selected.requestedBy }}</p><p>建立：{{ date(selected.createdAt) }}</p>
    <RouterLink :to="orgPath(org,'runs/' + selected.runId)">檢視執行紀錄</RouterLink>
    <h3>Payload JSON</h3><pre>{{ json(selected.payload) }}</pre>
    <template v-if="selected.status === 'PENDING'">
      <p v-if="selected.requestedBy === session.me?.user.id && !session.canAdmin" class="notice">您是此執行的申請者；伺服器會檢查是否允許自行審核。</p>
      <label>審核備註（選填，最多 500 字）<textarea v-model="comment" maxlength="500" :disabled="action.busy.value"></textarea><small>{{ comment.length }} / 500</small></label>
      <ErrorBanner v-if="!decision" :error="action.error.value" />
      <div class="actions end"><button :disabled="action.busy.value" @click="confirm('reject')">拒絕</button><button class="primary" :disabled="action.busy.value" @click="confirm('approve')">核准</button></div>
    </template>
    <template v-else><p>決定者：{{ selected.decidedBy ?? '—' }} · {{ date(selected.decidedAt) }}</p><p>備註：{{ selected.comment ?? '—' }}</p></template>
  </Modal>
  <ConfirmDialog v-if="decision" :title="decision === 'approve' ? '核准執行' : '拒絕執行'" :message="(decision === 'approve' ? '確定核准此執行？' : '確定拒絕此執行？') + (comment ? ' 備註：' + comment : '')" :confirm-label="decision === 'approve' ? '確認核准' : '確認拒絕'" :busy="action.busy.value" :error="action.error.value" @close="decision = null" @confirm="decide" />
</template>