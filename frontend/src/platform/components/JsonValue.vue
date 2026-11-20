<script setup lang="ts">
import { computed } from 'vue'
import { json, record } from '../composables/operations'
import DataTable from './DataTable.vue'
const props = defineProps<{ value: unknown }>()
const rows = computed(() => {
  const obj = record(props.value)
  return obj ? Object.entries(obj).map(([key, value]) => ({ key, value })) : null
})
</script>
<template>
  <DataTable v-if="rows?.length" :rows="rows" row-key="key" :columns="[{key:'key',label:'項目'},{key:'value',label:'內容'}]">
    <template #cell-value="{row}"><pre v-if="row.value !== null && typeof row.value === 'object'">{{ json(row.value) }}</pre><span v-else>{{ json(row.value) }}</span></template>
  </DataTable>
  <pre v-else>{{ json(value) }}</pre>
</template>