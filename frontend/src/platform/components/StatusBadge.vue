<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ state: string }>()
const labels: Record<string, string> = { PROCESSING: '處理中', READY: '可用', FAILED: '失敗', NEEDS_OCR: '需要 OCR', CANCELLED: '已取消', DELETING: '刪除中', RECEIVED: '已接收', VALIDATING: '驗證中', STORED: '已儲存', PARSING: '解析中', CHUNKING: '切分中', EMBEDDING: '向量化中', INDEXING: '建立索引中', SUPERSEDED: '已有新版本', QUEUED: '排隊中', RUNNING: '執行中', SUCCEEDED: '成功', WAITING_APPROVAL: '等待審核', REJECTED: '已拒絕', PENDING: '等待中', APPROVED: '已核准', COMPLETE: '完成', REFUSED: '證據不足／拒答', CURRENT: '目前版本', UNAVAILABLE: '無法存取', ACTIVE: '啟用', DISABLED: '停用' }
const tone = computed(() => ['READY', 'SUCCEEDED', 'APPROVED', 'COMPLETE', 'CURRENT', 'ACTIVE'].includes(props.state) ? 'positive' : ['FAILED', 'REJECTED', 'UNAVAILABLE'].includes(props.state) ? 'negative' : ['CANCELLED', 'SUPERSEDED', 'DISABLED', 'DELETING'].includes(props.state) ? 'neutral' : 'pending')
</script>
<template><span class="status-badge" :class="tone" :title="state">{{ labels[state] ?? state }}</span></template>

