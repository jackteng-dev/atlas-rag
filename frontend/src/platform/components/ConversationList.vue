<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { Conversation } from '../api/types'
import { date, orgPath } from '../composables/format'
defineProps<{ conversations: Conversation[]; org: string; busy: boolean; canCreate: boolean }>()
const emit = defineEmits<{ create: []; selected: [] }>()
</script>
<template>
  <div class="conversation-nav">
    <header><h2>我的對話</h2><button :disabled="busy || !canCreate" :title="!canCreate ? '需先取得可讀知識庫' : ''" @click="emit('create')">＋ 新增對話</button></header>
    <nav v-if="conversations.length" class="conversation-list" aria-label="對話清單"><RouterLink v-for="item in conversations" :key="item.id" :to="orgPath(org,'chat/' + item.id)" @click="emit('selected')">{{ item.title || '未命名對話' }}<small>{{ date(item.updatedAt) }}</small></RouterLink></nav>
    <p v-else-if="!busy" class="muted">尚無對話。</p>
  </div>
</template>
<style scoped>
.conversation-nav { display:flex; flex-direction:column; min-height:0; height:100% }
header { display:flex; flex-direction:column; align-items:stretch; gap:12px; padding-bottom:18px }
header h2 { font-size:14px; margin:0 }
.conversation-list { overflow-y:auto; flex:1; max-height:none }
</style>
