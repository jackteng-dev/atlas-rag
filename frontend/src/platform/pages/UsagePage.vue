<script setup lang="ts">
import { computed, onMounted, ref, useId } from 'vue'
import { api } from '../api/client'
import type { Quota, UsageSummary } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useTask } from '../composables/useTask'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import DataTable from '../components/DataTable.vue'

const session = useSessionStore(); const org = session.currentOrgId
// Calendar dates and daily buckets use UTC, matching the usage API.
const today = new Date()
const from = ref(new Date(Date.UTC(today.getUTCFullYear(),today.getUTCMonth(),today.getUTCDate() - 29)).toISOString().slice(0,10))
const to = ref(today.toISOString().slice(0,10))
const summary = ref<UsageSummary | null>(null); const quota = ref<Quota | null>(null)
const draft = ref({monthlyTokenLimit:0,monthlyRequestLimit:0,requestsPerMinute:0})
const saved = ref(false)
const {busy,error,run} = useTask(); const action = useTask()
const titleId = useId(); const descId = useId()
const maxTokens = computed(() => Math.max(1,...(summary.value?.daily.map(d => d.tokens) ?? [])))
const bars = computed(() => (summary.value?.daily ?? []).map((day,index,all) => ({
  ...day, x: 45 + index * 600 / all.length, width: Math.max(1,600 / all.length - 2), height: 160 * day.tokens / maxTokens.value,
})))
const totals = computed(() => summary.value ? [
  {label:'請求次數',value:summary.value.totals.requests,unit:'次'}, {label:'輸入 tokens',value:summary.value.totals.inputTokens,unit:'tokens'},
  {label:'輸出 tokens',value:summary.value.totals.outputTokens,unit:'tokens'}, {label:'失敗次數',value:summary.value.totals.failed,unit:'次'},
] : [])
function adoptQuota(value: Quota) {
  quota.value = value
  draft.value = {monthlyTokenLimit:value.monthlyTokenLimit,monthlyRequestLimit:value.monthlyRequestLimit,requestsPerMinute:value.requestsPerMinute}
}
async function load() {
  if (!session.canAdmin) return
  await run(async () => {
    if (!from.value || !to.value || from.value > to.value) throw new Error('請選擇有效日期範圍，起日不得晚於迄日。')
    const start = new Date(from.value + 'T00:00:00Z')
    const end = new Date(to.value + 'T00:00:00Z'); end.setUTCDate(end.getUTCDate() + 1)
    if ((end.getTime() - start.getTime()) / 86400000 > 366) throw new Error('日期範圍最多 366 天。')
    const [s,q] = await Promise.all([api.usage(org,start.toISOString(),end.toISOString()),api.quota(org)])
    summary.value = s; adoptQuota(q)
  })
}
async function save() {
  if (session.currentRole !== 'OWNER') return
  saved.value = false
  await action.run(async () => {
    if (Object.values(draft.value).some(v => !Number.isSafeInteger(v) || v < 0)) throw new Error('配額必須是大於或等於 0 的整數。')
    adoptQuota(await api.updateQuota(org,{...draft.value})); saved.value = true
  })
}
const quotaBars = computed(() => quota.value ? [
  {label:'本月 tokens',used:quota.value.usedTokens,limit:quota.value.monthlyTokenLimit,unit:'tokens'},
  {label:'本月請求',used:quota.value.usedRequests,limit:quota.value.monthlyRequestLimit,unit:'次'},
] : [])
onMounted(load)
</script>
<template>
  <PageHeader title="用量" description="以請求、tokens 與延遲檢視使用情況。"><button :disabled="busy" @click="load">重新整理</button></PageHeader>
  <template v-if="session.canAdmin">
    <form class="filters" @submit.prevent="load">
      <label>起日（UTC，含當日）<input v-model="from" type="date" required :max="to" :disabled="busy"></label>
      <label>迄日（UTC，含當日）<input v-model="to" type="date" required :min="from" :disabled="busy"></label>
      <button class="primary" :disabled="busy">查詢</button>
    </form>
    <ErrorBanner :error="error" /><Spinner v-if="busy" />
    <template v-if="summary">
      <div class="stat-grid"><article v-for="card in totals" :key="card.label" class="stat-card"><span>{{ card.label }}</span><strong>{{ card.value.toLocaleString('zh-TW') }}</strong><small>{{ card.unit }}</small></article></div>
      <section class="panel"><h2>依模型</h2>
        <DataTable v-if="summary.byModel.length" :rows="summary.byModel" row-key="model" :columns="[{key:'model',label:'模型'},{key:'requests',label:'請求次數'},{key:'tokens',label:'tokens'},{key:'avgLatencyMs',label:'平均延遲（ms）'},{key:'p95LatencyMs',label:'p95 延遲（ms）'}]" />
        <EmptyState v-else title="此期間沒有模型用量" />
      </section>
      <section class="panel"><h2>依使用者</h2>
        <DataTable v-if="summary.byUser.length" :rows="summary.byUser" row-key="userId" :columns="[{key:'email',label:'使用者'},{key:'requests',label:'請求次數'},{key:'tokens',label:'tokens'}]" />
        <EmptyState v-else title="此期間沒有使用者用量" />
      </section>
      <section class="panel"><h2>依 Agent</h2>
        <DataTable v-if="summary.byAgent.length" :rows="summary.byAgent" row-key="agentId" :columns="[{key:'name',label:'Agent'},{key:'runs',label:'執行次數'},{key:'tokens',label:'tokens'}]" />
        <EmptyState v-else title="此期間沒有 Agent 用量" />
      </section>
      <section class="panel"><h2>每日用量（UTC）</h2>
        <template v-if="summary.daily.length">
          <svg class="usage-chart" viewBox="0 0 680 230" role="img" :aria-labelledby="titleId + ' ' + descId">
            <title :id="titleId">每日 tokens 使用量長條圖</title>
            <desc :id="descId">橫軸為 UTC 日期，縱軸為 tokens。最高值 {{ maxTokens }} tokens；完整數值列於下方每日用量表。</desc>
            <line x1="45" y1="190" x2="645" y2="190" stroke="currentColor" />
            <text x="45" y="18">tokens（最高 {{ maxTokens }}）</text>
            <g v-for="bar in bars" :key="bar.day" role="img" :aria-label="bar.day + '：' + bar.tokens + ' tokens'">
              <title>{{ bar.day }}：{{ bar.tokens }} tokens</title><rect :x="bar.x" :y="190 - bar.height" :width="bar.width" :height="bar.height" fill="var(--primary)" />
            </g>
            <text x="45" y="215">{{ summary.daily[0]?.day }}</text><text x="645" y="215" text-anchor="end">{{ summary.daily[summary.daily.length - 1]?.day }}</text>
          </svg>
          <DataTable :rows="summary.daily" row-key="day" caption="每日請求與 tokens" :columns="[{key:'day',label:'日期（UTC）'},{key:'requests',label:'請求次數'},{key:'tokens',label:'tokens'}]" />
        </template><EmptyState v-else title="此期間沒有每日用量" />
      </section>
    </template>
    <section v-if="quota" class="panel">
      <h2>本月配額</h2><p class="muted">配額獨立於上方查詢日期範圍。0 表示不設上限。</p>
      <div class="quota-grid">
        <div v-for="bar in quotaBars" :key="bar.label">
          <label>{{ bar.label }}：{{ bar.used.toLocaleString('zh-TW') }} / {{ bar.limit > 0 ? bar.limit.toLocaleString('zh-TW') : '無上限' }} {{ bar.unit }}
            <progress :value="bar.limit > 0 ? Math.min(bar.used,bar.limit) : 0" :max="bar.limit > 0 ? bar.limit : 1" :aria-label="bar.label" :aria-valuetext="bar.used + ' / ' + (bar.limit > 0 ? bar.limit : '無上限')">{{ bar.used }} / {{ bar.limit }}</progress>
          </label>
        </div>
      </div>
      <p>每分鐘請求上限：{{ quota.requestsPerMinute > 0 ? quota.requestsPerMinute + ' 次／分鐘' : '無上限' }}</p>
      <form v-if="session.currentRole === 'OWNER'" class="form-stack narrow" @submit.prevent="save">
        <h3>編輯配額</h3>
        <fieldset :disabled="action.busy.value">
          <label>每月 tokens 上限<input v-model.number="draft.monthlyTokenLimit" type="number" min="0" step="1" required></label>
          <label>每月請求次數上限<input v-model.number="draft.monthlyRequestLimit" type="number" min="0" step="1" required></label>
          <label>每分鐘請求次數<input v-model.number="draft.requestsPerMinute" type="number" min="0" step="1" required></label>
        </fieldset>
        <ErrorBanner :error="action.error.value" /><p v-if="saved" role="status">配額已更新。</p><button class="primary" :disabled="action.busy.value || busy">儲存配額</button>
      </form>
    </section>
  </template><EmptyState v-else title="權限不足" description="用量頁需要 ADMIN 或 OWNER 權限。" />
</template>