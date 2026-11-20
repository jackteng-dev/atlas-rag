<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api } from '../api/client'
import type { Agent, AgentTemplate, KnowledgeBase, TemplateInfo } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useStatusStore } from '../stores/status'
import { useTask } from '../composables/useTask'
import { date, orgPath } from '../composables/format'
import { templateLabels } from '../composables/operations'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import DataTable from '../components/DataTable.vue'
import StatusBadge from '../components/StatusBadge.vue'
import Modal from '../components/Modal.vue'
import DefinitionSteps from '../components/DefinitionSteps.vue'

const session = useSessionStore(); const org = session.currentOrgId
const status = useStatusStore(); const router = useRouter()
const agents = ref<Agent[]>([]); const templates = ref<TemplateInfo[]>([]); const kbs = ref<KnowledgeBase[]>([])
const { busy, error, run } = useTask(); const action = useTask(); const choices = useTask()
const open = ref(false)
const form = ref({ template: '' as AgentTemplate | '', name: '', description: '', kbIds: [] as string[], model: 'fast' as 'fast' | 'quality' })
const selected = computed(() => templates.value.find(t => t.template === form.value.template))
const qualityAvailable = computed(() => status.status?.models.some(m => m.id === 'quality' && m.available) ?? false)
async function load() { await run(async () => { agents.value = await api.agents(org) }) }
async function loadChoices() {
  await choices.run(async () => {
    const [a, b] = await Promise.all([api.agentTemplates(org), api.knowledgeBases(org), status.load()])
    templates.value = a; kbs.value = b
    if (!form.value.template) form.value.template = a[0]?.template ?? ''
  })
}
function createDialog() {
  form.value = { template: '', name: '', description: '', kbIds: [], model: 'fast' }
  action.error.value = null; open.value = true; void loadChoices()
}
watch(() => form.value.template, () => { form.value.kbIds = [] })
async function create() {
  if (!session.canApprove || !selected.value) return
  await action.run(async () => {
    const template = selected.value!
    const agent = await api.createAgent(org, {
      name: form.value.name.trim(), description: form.value.description.trim(), template: template.template,
      definition: { ...template.definition, model: form.value.model === 'quality' && qualityAvailable.value ? 'quality' : 'fast',
        kbIds: template.template === 'KNOWLEDGE_QA' ? [...form.value.kbIds] : [] },
    })
    open.value = false; await router.push(orgPath(org, 'agents/' + agent.id))
  })
}
onMounted(load)
</script>
<template>
  <PageHeader title="Agents" description="以版本化範本執行知識問答、文件比對與唯讀工具流程。">
    <button :disabled="busy" @click="load">重新整理</button>
    <button v-if="session.canApprove" class="primary" @click="createDialog">建立 Agent</button>
  </PageHeader>
  <ErrorBanner :error="error" /><Spinner v-if="busy" />
  <section v-if="agents.length" class="panel">
    <DataTable :rows="agents" :columns="[{key:'name',label:'名稱'},{key:'template',label:'範本'},{key:'enabled',label:'狀態'},{key:'version',label:'版本'},{key:'updatedAt',label:'更新時間'},{key:'actions',label:'操作'}]">
      <template #cell-name="{row}"><RouterLink :to="orgPath(org,'agents/' + row.id)">{{ row.name }}</RouterLink><small>{{ row.description }}</small></template>
      <template #cell-template="{row}"><span class="status-badge neutral">{{ templateLabels[row.template] }}</span></template>
      <template #cell-enabled="{row}"><StatusBadge :state="row.enabled ? 'ACTIVE' : 'DISABLED'" /></template>
      <template #cell-version="{row}">v{{ row.version }}</template>
      <template #cell-updatedAt="{row}">{{ date(row.updatedAt) }}</template>
      <template #cell-actions="{row}"><RouterLink class="button" :to="orgPath(org,'agents/' + row.id)">{{ row.enabled ? '檢視／執行' : '檢視' }}</RouterLink></template>
    </DataTable>
  </section>
  <EmptyState v-else-if="!busy && !error" title="尚無 Agent" description="由 EDITOR、ADMIN 或 OWNER 建立第一個 Agent。" />
  <Modal v-if="open" title="建立 Agent" :busy="action.busy.value" @close="open = false">
    <Spinner v-if="choices.busy.value" /><ErrorBanner :error="choices.error.value"><button @click="loadChoices">重試</button></ErrorBanner>
    <form v-if="templates.length && !choices.error.value" class="form-stack" @submit.prevent="create">
      <fieldset :disabled="action.busy.value || choices.busy.value">
        <label>範本<select v-model="form.template" required><option v-for="template in templates" :key="template.template" :value="template.template">{{ template.name }}</option></select></label>
        <p class="muted">{{ selected?.description }}</p>
        <label>名稱<input v-model="form.name" required maxlength="120"></label>
        <label>說明<textarea v-model="form.description"></textarea></label>
        <label>模型<select v-model="form.model"><option value="fast">fast（快速）</option><option v-if="qualityAvailable" value="quality">quality（品質）</option></select><small v-if="!qualityAvailable">quality 模型目前不可用。</small></label>
        <fieldset v-if="form.template === 'KNOWLEDGE_QA'">
          <legend>知識庫（可複選）</legend>
          <label v-for="kb in kbs" :key="kb.id" class="check-label"><input v-model="form.kbIds" type="checkbox" :value="kb.id">{{ kb.name }}</label>
          <p v-if="!kbs.length" class="muted">尚無可讀知識庫，請先建立知識庫。</p>
        </fieldset>
        <h3>範本步驟（唯讀）</h3><DefinitionSteps v-if="selected" :steps="selected.definition.steps" />
      </fieldset>
      <ErrorBanner :error="action.error.value" />
      <button class="primary" :disabled="action.busy.value || choices.busy.value || !selected || !form.name.trim() || (form.template === 'KNOWLEDGE_QA' && !form.kbIds.length)">建立</button>
    </form>
    <EmptyState v-else-if="!choices.busy.value && !choices.error.value" title="沒有可用範本" />
  </Modal>
</template>