<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import type { Agent, DocumentItem, KnowledgeBase, Run, TemplateInfo } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useTask } from '../composables/useTask'
import { date, orgPath } from '../composables/format'
import { templateLabels } from '../composables/operations'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import DataTable from '../components/DataTable.vue'
import StatusBadge from '../components/StatusBadge.vue'
import DefinitionSteps from '../components/DefinitionSteps.vue'

const session = useSessionStore(); const org = session.currentOrgId
const agentId = String(useRoute().params.agentId); const router = useRouter()
const agent = ref<Agent | null>(null); const template = ref<TemplateInfo | null>(null)
const kbs = ref<KnowledgeBase[]>([]); const documents = ref<DocumentItem[]>([]); const runs = ref<Run[]>([])
const input = ref<Record<string, string | string[]>>({})
const { busy, error, run } = useTask(); const action = useTask()
const readyDocuments = computed(() => documents.value.filter(d => d.status === 'READY'))
const recent = computed(() => [...runs.value].sort((a,b) => b.createdAt.localeCompare(a.createdAt)).slice(0,20))
const validInput = computed(() => !!template.value && template.value.inputSchema.every(f => {
  const v = input.value[f.field]
  return Array.isArray(v) ? v.length > 0 && v.length <= 20 : typeof v === 'string' && !!v.trim()
}))
async function load() {
  await run(async () => {
    const [a, ts, bases, rs] = await Promise.all([api.agent(org, agentId), api.agentTemplates(org), api.knowledgeBases(org), api.runs(org, agentId)])
    agent.value = a; template.value = ts.find(t => t.template === a.template) ?? null; kbs.value = bases; runs.value = rs
    for (const f of template.value?.inputSchema ?? []) {
      if (!(f.field in input.value)) input.value[f.field] = f.type === 'documents' ? [] : f.type === 'select' ? f.options?.[0] ?? '' : ''
    }
    if (template.value?.inputSchema.some(f => f.type === 'documents')) documents.value = await api.documents(org)
  })
}
async function toggle() {
  if (!agent.value || !session.canApprove) return
  const a = agent.value
  await action.run(async () => {
    // enabled is supported by AgentController.AgentRequest on PUT.
    agent.value = await api.updateAgent(org, agentId, { name:a.name, template:a.template, description:a.description, definition:a.definition, enabled:!a.enabled })
  })
}
async function start() {
  if (!agent.value?.enabled || !validInput.value) return
  await action.run(async () => {
    const result = await api.createRun(org, agentId, { ...input.value })
    await router.push(orgPath(org, 'runs/' + result.id))
  })
}
onMounted(load)
</script>
<template>
  <PageHeader :title="agent?.name ?? 'Agent 詳細資料'" :description="agent?.description">
    <RouterLink class="button" :to="orgPath(org,'agents')">返回 Agents</RouterLink>
    <button :disabled="busy || action.busy.value" @click="load">重新整理</button>
    <button v-if="agent && session.canApprove" :disabled="action.busy.value" @click="toggle">{{ agent.enabled ? '停用' : '啟用' }}</button>
  </PageHeader>
  <ErrorBanner :error="error" /><ErrorBanner :error="action.error.value" /><Spinner v-if="busy" />
  <template v-if="agent">
    <section class="panel">
      <div class="actions"><span class="status-badge neutral">{{ templateLabels[agent.template] }}</span><StatusBadge :state="agent.enabled ? 'ACTIVE' : 'DISABLED'" /><span>v{{ agent.version }} · {{ date(agent.updatedAt) }}</span></div>
      <h2>流程定義</h2>
      <p>模型：{{ agent.definition.model }} · 每日執行上限：{{ agent.definition.maxRunsPerDay }} 次</p>
      <h3>知識庫</h3>
      <ul v-if="agent.definition.kbIds.length"><li v-for="kbId in agent.definition.kbIds" :key="kbId">{{ kbs.find(k => k.id === kbId)?.name ?? '無法存取的知識庫' }}</li></ul><p v-else class="muted">沒有指定知識庫。</p>
      <h3>工具白名單</h3><ul v-if="agent.definition.tools.length"><li v-for="tool in agent.definition.tools" :key="tool"><code>{{ tool }}</code></li></ul><p v-else class="muted">沒有允許的工具。</p>
      <details><summary>系統提示詞（唯讀）</summary><pre>{{ agent.definition.systemPrompt }}</pre></details>
      <h3>步驟</h3><DefinitionSteps :steps="agent.definition.steps" />
    </section>
    <section class="panel">
      <h2>執行</h2>
      <p v-if="!agent.enabled" class="notice">此 Agent 已停用，啟用後才能執行。</p>
      <form v-if="template" class="form-stack" @submit.prevent="start">
        <fieldset :disabled="!agent.enabled || busy || !!error || action.busy.value">
          <label v-for="field in template.inputSchema" :key="field.field">
            {{ field.label }}
            <input v-if="field.type === 'text'" v-model="input[field.field]" required>
            <select v-else-if="field.type === 'select'" v-model="input[field.field]" required><option v-for="option in field.options" :key="option" :value="option">{{ option }}</option></select>
            <select v-else v-model="input[field.field]" multiple required size="5"><option v-for="doc in readyDocuments" :key="doc.id" :value="doc.id">{{ doc.title }} · v{{ doc.currentVersion }}</option></select>
            <small v-if="field.type === 'documents'">{{ readyDocuments.length ? '可複選 1–20 份 READY 文件。' : '沒有 READY 文件，請先上傳並完成處理。' }}</small>
          </label>
        </fieldset>
        <button class="primary" :disabled="!agent.enabled || !validInput || busy || !!error || action.busy.value">{{ action.busy.value ? '處理中…' : '執行' }}</button>
      </form>
      <p v-else-if="!busy" class="notice">找不到對應範本的輸入欄位，無法建立執行。</p>
    </section>
    <section class="panel"><h2>最近執行（最多 20 筆）</h2>
      <DataTable v-if="recent.length" :rows="recent" :columns="[{key:'id',label:'執行'},{key:'status',label:'狀態'},{key:'agentVersion',label:'版本'},{key:'createdAt',label:'建立時間'},{key:'actorId',label:'執行者'}]">
        <template #cell-id="{row}"><RouterLink :to="orgPath(org,'runs/' + row.id)">{{ row.id }}</RouterLink></template>
        <template #cell-status="{row}"><StatusBadge :state="row.status" /></template>
        <template #cell-createdAt="{row}">{{ date(row.createdAt) }}</template>
      </DataTable><EmptyState v-else-if="!busy && !error" title="尚無執行紀錄" />
    </section>
  </template>
</template>