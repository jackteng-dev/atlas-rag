<script setup lang="ts">
import { computed } from 'vue'
import type { Locator, RunStep } from '../api/types'
import { json, record } from '../composables/operations'
import { locator } from '../composables/format'
import DataTable from './DataTable.vue'
const props = defineProps<{ step: RunStep }>()
const output = computed(() => record(props.step.output))
const checks = computed(() => {
  const results = output.value?.results
  if (!Array.isArray(results)) return []
  return results.flatMap(subject => {
    const s = record(subject)
    if (!s || !Array.isArray(s.checks)) return []
    return s.checks.flatMap(check => {
      const c = record(check)
      return c ? [{ subject: String(s.subject ?? '—'), field: String(c.field ?? '—'), op: String(c.op ?? '—'),
        expected: c.expected, actual: c.actual, pass: c.pass === true ? '通過' : c.pass === false ? '未通過' : '未提供' }] : []
    })
  })
})
const answer = computed(() => {
  const a = output.value?.answer
  return typeof a === 'string' ? a.split('\n') : Array.isArray(a) ? a.filter((line): line is string => typeof line === 'string') : []
})
const citations = computed(() => {
  const c = output.value?.citations
  return Array.isArray(c) ? c : []
})
function position(value: unknown) {
  const v = record(value)
  if (!v) return '未提供位置'
  const result: Locator = {}
  for (const key of ['page', 'lineStart', 'lineEnd', 'row'] as const) if (typeof v[key] === 'number') result[key] = v[key]
  for (const key of ['section', 'sheet'] as const) if (typeof v[key] === 'string') result[key] = v[key]
  return locator(result)
}
</script>
<template>
  <template v-if="step.type === 'RULE' && checks.length">
    <h3>規則比對</h3>
    <DataTable :rows="checks" :columns="[{key:'subject',label:'對象'},{key:'field',label:'欄位'},{key:'op',label:'運算'},{key:'expected',label:'預期值'},{key:'actual',label:'實際值'},{key:'pass',label:'結果'}]">
      <template #cell-pass="{row}"><span class="status-badge" :class="row.pass === '通過' ? 'positive' : 'negative'">{{ row.pass }}</span></template>
    </DataTable>
  </template>
  <template v-if="step.type === 'LLM'">
    <h3>模型回答</h3><p v-if="!answer.length" class="muted">尚無回答內容。</p>
    <p v-for="(line, index) in answer" :key="index" class="message-content">{{ line }}</p>
    <h3 v-if="citations.length">引用來源</h3>
    <article v-for="(citation, index) in citations" :key="index" class="chunk-card">
      <template v-if="record(citation)">
        <strong>{{ record(citation)?.title ?? '未提供標題' }}</strong>
        <small>版本：{{ record(citation)?.versionNo ?? '未提供' }} · {{ position(record(citation)?.locator) }}</small>
        <blockquote>{{ record(citation)?.quote ?? '未提供引文' }}</blockquote>
      </template>
      <template v-else><p class="muted">此引用僅包含步驟識別碼，未提供文件標題、版本、位置及引文。</p><pre>{{ json(citation) }}</pre></template>
    </article>
  </template>
  <details><summary>檢視輸出 JSON</summary><pre>{{ json(step.output) }}</pre></details>
</template>