<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api } from '../api/client'
import type { Run } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useTask, usePolling } from '../composables/useTask'
import { date, orgPath } from '../composables/format'
import { json, stepLabels } from '../composables/operations'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import RunStepOutput from '../components/RunStepOutput.vue'

const session = useSessionStore(); const org = session.currentOrgId
const runId = String(useRoute().params.runId)
const execution = ref<Run | null>(null)
const { busy, error, run } = useTask(); const action = useTask()
const canAct = computed(() => !!execution.value && (execution.value.actorId === session.me?.user.id || session.canAdmin))
const canCancel = computed(() => !!execution.value && ['QUEUED','RUNNING','WAITING_APPROVAL'].includes(execution.value.status))
const steps = computed(() => [...(execution.value?.steps ?? [])].sort((a,b) => a.ordinal - b.ordinal))
async function load() { await run(async () => { execution.value = await api.run(org,runId) }) }
async function act(kind: 'cancel' | 'retry') {
  if (!canAct.value) return
  await action.run(async () => {
    if (kind === 'cancel') await api.cancelRun(org,runId)
    else await api.retryRun(org,runId)
    // Re-read the same run: retries resume failed steps on the original run.
    execution.value = await api.run(org,runId)
  })
}
onMounted(load)
usePolling(load, () => !error.value && !action.busy.value && !!execution.value && ['QUEUED','RUNNING'].includes(execution.value.status), 2000)
</script>
<template>
  <PageHeader :title="execution ? execution.agentName + ' · 執行紀錄' : '執行紀錄'">
    <button :disabled="busy || action.busy.value" @click="load">重新整理</button>
    <button v-if="canAct && canCancel" class="danger" :disabled="action.busy.value || busy" @click="act('cancel')">取消執行</button>
    <button v-if="canAct && execution?.status === 'FAILED'" :disabled="action.busy.value || busy" @click="act('retry')">重試</button>
  </PageHeader>
  <ErrorBanner :error="error" /><ErrorBanner :error="action.error.value" /><Spinner v-if="busy && !execution" />
  <template v-if="execution">
    <section class="panel">
      <div class="actions"><StatusBadge :state="execution.status" /><span>Agent v{{ execution.agentVersion }}</span><RouterLink :to="orgPath(org,'agents/' + execution.agentId)">檢視 Agent</RouterLink></div>
      <p>執行 ID：{{ execution.id }}</p><p>traceId：<code>{{ execution.traceId }}</code></p>
      <p class="muted">建立：{{ date(execution.createdAt) }} · 更新：{{ date(execution.updatedAt) }}</p>
      <p v-if="execution.error" class="error-banner" role="alert">{{ execution.error }}</p>
      <p v-if="execution.status === 'WAITING_APPROVAL'" class="notice">執行正在等待人工審核。<RouterLink v-if="session.canApprove" :to="orgPath(org,'approvals')">前往審核</RouterLink><span v-else>請由 EDITOR 或管理員前往審核頁處理。</span></p>
      <h2>輸入 JSON</h2><pre>{{ json(execution.input) }}</pre>
    </section>
    <section class="panel"><h2>步驟時間軸</h2>
      <ol v-if="steps.length" class="run-timeline">
        <li v-for="step in steps" :key="step.ordinal">
          <div class="actions"><strong>{{ step.ordinal + 1 }}. {{ stepLabels[step.type] }} · {{ step.name }}</strong><StatusBadge :state="step.status" /></div>
          <p class="muted">開始：{{ date(step.startedAt) }} · 結束：{{ date(step.finishedAt) }}</p>
          <p v-if="step.error" class="error-banner" role="alert">{{ step.error }}</p>
          <RunStepOutput :step="step" />
        </li>
      </ol><EmptyState v-else title="尚無步驟紀錄" />
    </section>
    <section class="panel"><details><summary>最終輸出 JSON</summary><pre>{{ json(execution.output) }}</pre></details></section>
  </template>
</template>