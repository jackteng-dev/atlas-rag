<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { EvaluationSummary } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useTask } from '../composables/useTask'
import { date } from '../composables/format'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import EmptyState from '../components/EmptyState.vue'
import JsonValue from '../components/JsonValue.vue'
const org = useSessionStore().currentOrgId
const result = ref<EvaluationSummary | null>(null)
const {busy,error,run} = useTask()
const sections = [{key:'summary',label:'摘要'},{key:'metrics',label:'指標'},{key:'conditions',label:'評估條件'},{key:'configuration',label:'設定'},{key:'totals',label:'總計'}] as const
async function load() { await run(async () => { result.value = await api.evaluation(org) }) }
onMounted(load)
</script>
<template>
  <PageHeader title="評估報告" description="唯讀呈現已保存的評估結果與實驗條件。"><button :disabled="busy" @click="load">重新整理</button></PageHeader>
  <p class="notice">引用有效性 ≠ 語意準確率。引用核對通過，只代表來源與版本可核對，不能直接視為回答語意正確。</p>
  <ErrorBanner :error="error" /><Spinner v-if="busy" />
  <template v-if="result">
    <p class="notice message-content">{{ result.note }}</p>
    <article v-for="(report,index) in result.reports" :key="report.file + index" class="panel">
      <h2>{{ report.file }}</h2><span v-if="report.frozen" class="status-badge pending">凍結基準，不可覆寫</span>
      <p class="muted">產生時間：{{ date(report.generatedAt) }}</p>
      <p v-if="report.error" class="error-banner" role="alert">{{ report.error }}</p>
      <template v-for="section in sections" :key="section.key">
        <section v-if="report[section.key] !== undefined" class="report-section"><h3>{{ section.label }}</h3><JsonValue :value="report[section.key]" /></section>
      </template>
    </article>
    <EmptyState v-if="!result.reports.length && !busy" title="尚無評估報告" description="報告由伺服器提供，此頁不會執行或覆寫評估。" />
  </template>
</template>