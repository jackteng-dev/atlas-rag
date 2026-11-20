<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import { useSessionStore } from '../stores/session'
import { useTask } from '../composables/useTask'
import PageHeader from '../components/PageHeader.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
const session = useSessionStore(); const router = useRouter()
const form = ref({ name: '', slug: '' }); const { busy, error, run } = useTask()
async function submit() {
  await run(async () => {
    const org = await api.createOrg(form.value)
    await session.load(true); session.switchOrg(org.id)
    await router.replace('/o/' + encodeURIComponent(org.id) + '/overview')
  })
}
</script>
<template><PageHeader title="建立組織" description="為團隊建立獨立的知識工作空間。" /><form class="panel form-stack narrow" @submit.prevent="submit"><label>組織名稱<input v-model="form.name" required :disabled="busy"></label><label>組織代稱（slug）<input v-model="form.slug" required :disabled="busy"><small>請使用可識別組織的唯一代稱。</small></label><ErrorBanner :error="error" /><button class="primary" :disabled="busy">{{ busy ? '建立中…' : '建立組織' }}</button></form></template>

