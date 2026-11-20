<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { Connector, ConnectorType, ToolInfo } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useToastStore } from '../stores/toast'
import { useTask } from '../composables/useTask'
import { date } from '../composables/format'
import { json, lines } from '../composables/operations'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import DataTable from '../components/DataTable.vue'
import StatusBadge from '../components/StatusBadge.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

const session = useSessionStore(); const org = session.currentOrgId; const toast = useToastStore()
const connectors = ref<Connector[]>([]); const tools = ref<ToolInfo[]>([])
const { busy, error, run } = useTask(); const action = useTask()
const open = ref(false); const removal = ref<Connector | null>(null)
const tests = ref<Record<string, {ok:boolean; detail:string}>>({})
const form = ref({ type:'HTTP_GET' as ConnectorType, name:'', secretRef:'', baseUrl:'', allowedPaths:'', endpoint:'', allowedTools:'', readOnly:true })
const queries = ref([{name:'', sql:''}])
async function load() {
  if (!session.canAdmin) return
  await run(async () => {
    const [a,b] = await Promise.all([api.connectors(org), api.tools(org)])
    connectors.value = a; tools.value = b
  })
}
function createDialog() {
  form.value = {type:'HTTP_GET',name:'',secretRef:'',baseUrl:'',allowedPaths:'',endpoint:'',allowedTools:'',readOnly:true}
  queries.value = [{name:'',sql:''}]; action.error.value = null; open.value = true
}
async function create() {
  await action.run(async () => {
    let config: Record<string, unknown>
    if (form.value.type === 'HTTP_GET') {
      const paths = lines(form.value.allowedPaths)
      if (!paths.length) throw new Error('請至少輸入一個允許路徑。')
      config = { baseUrl:form.value.baseUrl.trim(), allowedPaths:paths }
    } else if (form.value.type === 'SQL_READONLY') {
      const entries = queries.value.map(q => [q.name.trim(),q.sql.trim()] as const)
      if (!entries.length || entries.some(([name,sql]) => !name || !/^select\s/i.test(sql))) throw new Error('每個查詢都需要名稱及 SELECT 陳述式。')
      if (new Set(entries.map(([name]) => name)).size !== entries.length) throw new Error('查詢名稱不可重複。')
      config = {queries:Object.fromEntries(entries)}
    } else {
      const allowedTools = lines(form.value.allowedTools)
      if (!allowedTools.length) throw new Error('請至少輸入一個允許工具。')
      config = {endpoint:form.value.endpoint.trim(),allowedTools,readOnly:form.value.readOnly}
    }
    await api.createConnector(org, {type:form.value.type, name:form.value.name.trim(), config,
      ...(form.value.secretRef.trim() ? {secretRef:form.value.secretRef.trim()} : {})})
    open.value = false; toast.show('連接器已建立。'); await load()
  })
}
async function toggle(c: Connector) {
  await action.run(async () => { await api.updateConnector(org,c.id,{enabled:!c.enabled}); delete tests.value[c.id]; await load() })
}
async function test(c: Connector) {
  delete tests.value[c.id]
  await action.run(async () => { tests.value[c.id] = await api.testConnector(org,c.id) })
}
async function remove() {
  if (!removal.value) return
  await action.run(async () => { await api.deleteConnector(org,removal.value!.id); removal.value = null; toast.show('連接器已刪除。'); await load() })
}
onMounted(load)
</script>
<template>
  <PageHeader title="連接器" description="管理受控連接器與可用工具白名單。">
    <button :disabled="busy || action.busy.value" @click="load">重新整理</button><button v-if="session.canAdmin" class="primary" @click="createDialog">建立連接器</button>
  </PageHeader>
  <template v-if="session.canAdmin">
    <ErrorBanner :error="error" /><ErrorBanner v-if="!open && !removal" :error="action.error.value" /><Spinner v-if="busy" />
    <section class="panel"><h2>連接器清單</h2>
      <DataTable v-if="connectors.length" :rows="connectors" :columns="[{key:'name',label:'名稱'},{key:'type',label:'類型'},{key:'enabled',label:'狀態'},{key:'config',label:'設定'},{key:'createdAt',label:'建立時間'},{key:'actions',label:'操作'}]">
        <template #cell-enabled="{row}"><StatusBadge :state="row.enabled ? 'ACTIVE' : 'DISABLED'" /></template>
        <template #cell-config="{row}"><details><summary>檢視設定</summary><pre>{{ json(row.config) }}</pre><p>secretRef：{{ row.secretRef ?? '未設定' }}</p></details></template>
        <template #cell-createdAt="{row}">{{ date(row.createdAt) }}</template>
        <template #cell-actions="{row}"><div class="actions">
          <button :disabled="action.busy.value || busy" @click="toggle(row)">{{ row.enabled ? '停用' : '啟用' }}</button>
          <button :disabled="action.busy.value || !row.enabled" @click="test(row)">測試</button>
          <button class="danger" :disabled="action.busy.value" @click="action.error.value = null; removal = row">刪除</button>
        </div><div v-if="tests[row.id]" role="status"><span class="status-badge" :class="tests[row.id].ok ? 'positive' : 'negative'">{{ tests[row.id].ok ? '通過' : '未通過' }}</span><pre>{{ json(tests[row.id]) }}</pre></div></template>
      </DataTable><EmptyState v-else-if="!busy && !error" title="尚無連接器" />
    </section>
    <section class="panel"><h2>可用工具（唯讀）</h2>
      <DataTable v-if="tools.length" :rows="tools" :columns="[{key:'name',label:'工具'},{key:'description',label:'說明'},{key:'sideEffect',label:'副作用'},{key:'requiresApproval',label:'需審核'},{key:'connectorId',label:'連接器'}]">
        <template #cell-name="{row}">{{ row.name }}<small>{{ row.id }}</small></template>
        <template #cell-sideEffect="{row}"><span class="status-badge" :class="row.sideEffect ? 'pending' : 'neutral'">{{ row.sideEffect ? '有副作用' : '唯讀' }}</span></template>
        <template #cell-requiresApproval="{row}"><span class="status-badge" :class="row.requiresApproval ? 'pending' : 'neutral'">{{ row.requiresApproval ? '需要審核' : '不需審核' }}</span></template>
      </DataTable><EmptyState v-else-if="!busy && !error" title="沒有可用工具" />
    </section>
  </template><EmptyState v-else title="權限不足" description="連接器管理需要 ADMIN 或 OWNER 權限。" />
  <Modal v-if="open" title="建立連接器" :busy="action.busy.value" @close="open = false">
    <form class="form-stack" @submit.prevent="create">
      <fieldset :disabled="action.busy.value">
        <label>類型<select v-model="form.type"><option value="HTTP_GET">HTTP_GET</option><option value="SQL_READONLY">SQL_READONLY</option><option value="MCP">MCP</option></select></label>
        <label>名稱<input v-model="form.name" required maxlength="120"></label>
        <template v-if="form.type === 'HTTP_GET'">
          <label>基底網址（baseUrl）<input v-model="form.baseUrl" type="url" pattern="https?://.*" required></label>
          <label>允許路徑（allowedPaths，每行一個）<textarea v-model="form.allowedPaths" required placeholder="/api/catalog"></textarea></label>
        </template>
        <template v-else-if="form.type === 'SQL_READONLY'">
          <p class="notice">命名查詢只能使用單一 SELECT 查詢 demo_erp 資料；限制由伺服器驗證。</p>
          <fieldset v-for="(query,index) in queries" :key="index"><legend>查詢 {{ index + 1 }}</legend>
            <label>查詢名稱<input v-model="query.name" required></label>
            <label>SELECT 陳述式<textarea v-model="query.sql" required placeholder="SELECT * FROM demo_erp.parts"></textarea></label>
            <button type="button" :disabled="queries.length === 1" @click="queries.splice(index,1)">移除此查詢</button>
          </fieldset>
          <button type="button" @click="queries.push({name:'',sql:''})">新增查詢</button>
        </template>
        <template v-else>
          <label>端點（endpoint）<input v-model="form.endpoint" type="url" required></label>
          <label>允許工具（allowedTools，每行一個）<textarea v-model="form.allowedTools" required></textarea></label>
          <label class="check-label"><input v-model="form.readOnly" type="checkbox">readOnly（唯讀）</label>
        </template>
        <label>secretRef（選填）<input v-model="form.secretRef" pattern="ATLAS_CONNECTOR_[A-Z0-9_]{1,80}" placeholder="ATLAS_CONNECTOR_…"><small>伺服器環境變數名稱，需以 ATLAS_CONNECTOR_ 開頭；密碼不會存入資料庫</small></label>
      </fieldset>
      <ErrorBanner :error="action.error.value" /><button class="primary" :disabled="action.busy.value || !form.name.trim()">建立</button>
    </form>
  </Modal>
  <ConfirmDialog v-if="removal" title="刪除連接器" :message="'確定刪除「' + removal.name + '」？相關工具將無法再使用。'" :busy="action.busy.value" :error="action.error.value" @close="removal = null" @confirm="remove" />
</template>