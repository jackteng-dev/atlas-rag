<script setup lang="ts" generic="T extends object">
withDefaults(defineProps<{ rows: T[]; columns: { key: string; label: string }[]; rowKey?: string; caption?: string }>(), { rowKey: 'id' })
const value = (row: T, key: string) => (row as Record<string, unknown>)[key]
</script>
<template><div class="table-wrap"><table><caption v-if="caption">{{ caption }}</caption><thead><tr><th v-for="column in columns" :key="column.key" scope="col">{{ column.label }}</th></tr></thead><tbody><tr v-for="(row, index) in rows" :key="String(value(row, rowKey) ?? index)"><td v-for="column in columns" :key="column.key" :data-label="column.label"><slot :name="'cell-' + column.key" :row="row" :value="value(row, column.key)">{{ value(row, column.key) ?? '—' }}</slot></td></tr></tbody></table></div></template>

