<script setup lang="ts">
import type { StepDefinition } from '../api/types'
import { json, stepLabels } from '../composables/operations'
defineProps<{ steps: StepDefinition[] }>()
</script>
<template>
  <ol v-if="steps.length" class="definition-steps">
    <li v-for="(step, index) in steps" :key="index">
      <strong>{{ stepLabels[step.type] }} · {{ step.name }}</strong>
      <details><summary>步驟設定（唯讀）</summary><pre>{{ json(step.config) }}</pre></details>
    </li>
  </ol>
  <p v-else class="muted">沒有步驟。</p>
</template>