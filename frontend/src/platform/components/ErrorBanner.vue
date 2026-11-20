<script setup lang="ts">
import { computed } from 'vue'
import { ApiError } from '../api/client'
const props = defineProps<{ error: unknown }>()
const message = computed(() => props.error instanceof Error ? props.error.message : String(props.error ?? ''))
const apiError = computed(() => props.error instanceof ApiError ? props.error : null)
</script>
<template><div v-if="error" class="error-banner" role="alert"><strong v-if="apiError?.status === 403">權限不足：</strong>{{ message }}<small v-if="apiError?.code">{{ apiError.code }}</small><small v-if="apiError?.traceId">traceId：{{ apiError.traceId }}</small><slot /></div></template>

